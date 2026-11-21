package com.zelixsec.app.desync

import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * ZelixGate - DNS-over-HTTPS çözümleyici (Android portu)
 * ----------------------------------------------------
 * Operatörlerin çoğu blokajı bozuk DNS cevabı ile uygular (discord.com ->
 * 127.0.0.1 / 10.x gibi). Bu modül A/AAAA kayıtlarını HTTPS üzerinden
 * (Cloudflare / Google / Quad9) çözer, böylece zehirli yerel DNS devre dışı
 * kalır. Sonuçlar TTL'e göre önbelleğe alınır (gecikme eklemez).
 */
object DohProviders {
    val PROVIDERS = mapOf(
        "cloudflare" to mapOf(
            "label" to "Cloudflare (1.1.1.1)",
            "hostname" to "cloudflare-dns.com",
            "ip" to "1.1.1.1",
            "path" to "/dns-query",
            "json" to true,
        ),
        "google" to mapOf(
            "label" to "Google (8.8.8.8)",
            "hostname" to "dns.google",
            "ip" to "8.8.8.8",
            "path" to "/resolve",
            "json" to true,
        ),
        "quad9" to mapOf(
            "label" to "Quad9 (9.9.9.9)",
            "hostname" to "dns.quad9.net",
            "ip" to "9.9.9.9",
            "path" to "/dns-query",
            "json" to true,
        ),
        "system" to mapOf(
            "label" to "Sistem DNS (DoH kapalı)",
            "system" to true,
        ),
    )

    fun normalize(provider: String?): String = if (PROVIDERS.containsKey(provider)) provider!! else "cloudflare"
}

object IpCheck {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpv4(value: String): Boolean = IPV4.matches(value)

    fun isIpv6(value: String): Boolean {
        if (!value.contains(':')) return false
        return try {
            InetAddress.getByName(value).hostAddress != null
        } catch (_: Exception) {
            false
        }
    }

    fun isIp(value: String): Boolean = isIpv4(value) || isIpv6(value)
}

/** IP'ye bağlanıp SNI'ı sabit hostname ile gönderen küçük DoH GET istemcisi. */
object DohTls {
    /**
     * Tek bir HTTPS isteği ile JSON DNS cevabı alır.
     * SNI doğru gönderilsin diye hostname yerine sabit IP'ye bağlanıp
     * servername ile sertifika doğrulaması yapılır (DNS zehirlenmesine bağışık).
     */
    fun queryJson(providerHostname: String, providerIp: String, providerPath: String, name: String, type: String, timeoutMs: Int): JSONObject {
        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.tcpNoDelay = true
            val params: SSLParameters = socket.sslParameters
            // IP'ye bağlandığımız için sertifikayı hostname'e göre doğrula:
            // SNI'ı provider hostname'e sabitle.
            try {
                params.serverNames = listOf<SNIServerName>(SNIHostName(providerHostname))
            } catch (_: Exception) {
                // SNIHostName desteği yoksa varsayılan davranış
            }
            socket.sslParameters = params
            // providerIp sabit IP (DNS araması yapılmaz); zehirli DNS'e rağmen bağlanır
            socket.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(providerIp), 443), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            verifyServerName(socket, providerHostname)

            val path = "$providerPath?name=${urlEncode(name)}&type=$type"
            val req = ("GET $path HTTP/1.1\r\n" +
                "Host: $providerHostname\r\n" +
                "accept: application/dns-json\r\n" +
                "user-agent: ZelixGate/1.0\r\n" +
                "connection: close\r\n" +
                "\r\n")
            val out = socket.getOutputStream()
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val resp = readHttpResponse(socket.getInputStream())
            if (resp.statusCode != 200) throw Exception("DoH HTTP ${resp.statusCode}")
            return JSONObject(String(resp.body, Charsets.UTF_8))
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    class HttpResp(val statusCode: Int, val headers: Map<String, String>, val body: ByteArray)

    /* --- yardımcılar --- */

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Doğrulama: zinciri JSSE zaten kontrol etti; hostname SAN'da aranır. */
    private fun verifyServerName(socket: SSLSocket, hostname: String) {
        val chain = socket.session.peerCertificates
        if (chain.isEmpty()) throw IllegalStateException("sertifika yok")
        val leaf = chain[0] as java.security.cert.X509Certificate
        val names = leaf.subjectAlternativeNames ?: throw IllegalStateException("SAN yok")
        val target = hostname.lowercase()
        var found = false
        for (entry in names) {
            if (entry.size >= 2) {
                val type = entry[0]
                val value = entry[1]?.toString()?.lowercase() ?: ""
                if (type == 2) {
                    if (value == target) {
                        found = true
                        break
                    }
                    if (value.startsWith("*.") && target.endsWith(value.substring(1))) {
                        found = true
                        break
                    }
                }
            }
        }
        if (!found) {
            throw javax.net.ssl.SSLHandshakeException("yazım hatası: $hostname sertifikada yok")
        }
    }

    /** Basit HTTP/1.1 cevap okuyucu (gövde = content-length veya connection:close). */
    private fun readHttpResponse(input: InputStream): HttpResp {
        var headerBytes = ByteArray(0)
        var bodyStart = -1
        val buf = ByteArray(4096)
        var total = 0
        while (total < 512 * 1024) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            headerBytes = headerBytes + buf.copyOf(n)
            val idx = findDoubleCrlf(headerBytes)
            if (idx >= 0) {
                bodyStart = idx
                break
            }
        }
        if (bodyStart < 0) throw Exception("DoH cevabı çok büyük")
        val headPart = String(headerBytes, 0, bodyStart, Charsets.ISO_8859_1)
        val bodyPart = headerBytes.copyOfRange(bodyStart, headerBytes.size)
        val lines = headPart.split("\r\n")
        val statusLine = lines.firstOrNull() ?: ""
        val m = Regex("""^HTTP/1\.[01] (\d{3})""").find(statusLine)
        val code = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        var body = bodyPart
        val cl = headers["content-length"]?.toIntOrNull()
        if (cl != null) {
            if (body.size > cl) body = body.copyOf(cl)
        } else if (headers["transfer-encoding"] == "chunked") {
            body = deChunk(bodyPart)
        } else if (code == 200 && headers["connection"] != "close") {
            // non-close, no-length: belirsiz; olduğu bırakılır (body kısmen)
        }
        return HttpResp(code, headers, body)
    }

    private fun findDoubleCrlf(buffer: ByteArray): Int {
        for (i in 0 until buffer.size - 3) {
            if (buffer[i] == 13.toByte() && buffer[i + 1] == 10.toByte() &&
                buffer[i + 2] == 13.toByte() && buffer[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    private fun deChunk(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.size) {
            var lineEnd = i
            while (lineEnd + 1 < input.size && !(input[lineEnd] == 13.toByte() && input[lineEnd + 1] == 10.toByte())) lineEnd++
            val sizeText = String(input, i, lineEnd - i, Charsets.ISO_8859_1)
            val sep = sizeText.indexOf(';')
            val hex = (if (sep >= 0) sizeText.substring(0, sep) else sizeText).trim()
            val size = hex.toInt(16)
            i = lineEnd + 2
            if (size == 0) break
            if (i + size > input.size) break
            out.write(input, i, size)
            i += size + 2
        }
        return out.toByteArray()
    }
}

class DohResolver(opts: Map<String, Any> = emptyMap()) {
    private var provider: String = DohProviders.normalize(opts["provider"] as? String ?: "cloudflare")
    var enabled: Boolean = (opts["enabled"] as? Boolean) !== false
        private set
    private var allowSystemFallback: Boolean = (opts["allowSystemFallback"] as? Boolean) !== false
    private var timeoutMs: Int = (opts["timeoutMs"] as? Number)?.toInt() ?: 4000
    private val cache = HashMap<String, CacheEntry>()
    private val statsHits = AtomicLong(0)
    private val statsMisses = AtomicLong(0)
    private val statsErrors = AtomicLong(0)

    private class CacheEntry(val ips: List<String>, val expires: Long)

    fun configure(next: Map<String, Any> = emptyMap()) {
        (next["provider"] as? String)?.let { provider = DohProviders.normalize(it) }
        (next["enabled"] as? Boolean)?.let { enabled = it }
        (next["allowSystemFallback"] as? Boolean)?.let { allowSystemFallback = it }
        (next["timeoutMs"] as? Number)?.let { timeoutMs = it.toInt() }
        synchronized(cache) { cache.clear() }
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    fun stats(): JSONObject = JSONObject().put("hits", statsHits.get()).put("misses", statsMisses.get()).put("errors", statsErrors.get())

    companion object {
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000
        const val MIN_TTL_MS: Long = 30 * 1000
    }

    /** Önbellekten taze kayıt döndürür veya null. */
    private fun fromCache(name: String, type: String): List<String>? {
        val key = "$name|$type"
        val entry: CacheEntry?
        synchronized(cache) { entry = cache[key] }
        if (entry == null) return null
        if (entry.expires < System.currentTimeMillis()) {
            synchronized(cache) { cache.remove(key) }
            return null
        }
        statsHits.incrementAndGet()
        return entry.ips
    }

    private fun store(name: String, type: String, ips: List<String>, ttlSeconds: Int) {
        // ttlSeconds * 1000 || DEFAULT_TTL_MS (JS) -> 0 ise varsayılan TTL
        val raw = ttlSeconds * 1000L
        val ttlRaw = if (raw > 0) raw else DEFAULT_TTL_MS
        val ttl = maxOf(MIN_TTL_MS, minOf(ttlRaw, 30 * 60 * 1000L))
        val entry = CacheEntry(ips, System.currentTimeMillis() + ttl)
        synchronized(cache) { cache["$name|$type"] = entry }
    }

    private fun queryType(name: String, type: String): List<String> {
        val key = "$name|$type"
        fromCache(name, type)?.let {
            return it
        }
        statsMisses.incrementAndGet()

        val entry = DohProviders.PROVIDERS[provider]
        if (!(enabled) || entry?.get("system") == true) {
            return systemResolve(name, type)
        }

        val json: JSONObject
        try {
            json = DohTls.queryJson(
                entry?.get("hostname") as String,
                entry?.get("ip") as String,
                entry?.get("path") as String,
                name,
                type,
                timeoutMs,
            )
        } catch (err: Exception) {
            statsErrors.incrementAndGet()
            if (allowSystemFallback) return systemResolve(name, type)
            throw err
        }

        val answers = json.optJSONArray("Answer") ?: org.json.JSONArray()
        // CNAME zincirini atlayıp istediğimiz tipteki kayıtları topla
        val wantType = if (type == "A") 1 else 28
        val ips = mutableListOf<String>()
        var minTtl = 600
        for (i in 0 until answers.length()) {
            val a = answers.optJSONObject(i) ?: continue
            if ((a.optInt("type", -1)) == wantType) {
                val data = a.optString("data")
                if (IpCheck.isIp(data)) ips.add(data)
            }
            minTtl = minOf(minTtl, if (a.has("TTL")) a.optInt("TTL", 60) else 60)
        }

        if (ips.isEmpty()) {
            if (allowSystemFallback) return systemResolve(name, type)
            throw Exception("$name için $type kaydı bulunamadı")
        }

        store(name, type, ips, minTtl)
        return ips
    }

    private fun systemResolve(name: String, type: String): List<String> {
        val records = mutableListOf<String>()
        for (addr in InetAddress.getAllByName(name)) {
            if (type == "A" && addr is Inet4Address) records.add(addr.hostAddress)
            if (type == "AAAA" && addr is Inet6Address) records.add(addr.hostAddress)
        }
        if (records.isEmpty()) throw Exception("$name çözümlenemedi")
        store(name, type, records, 60)
        return records
    }

    /**
     * Host adını IPv4 + IPv6 listesine çevirir. Host zaten IP ise dokunmaz.
     * IPv4 önceliklidir; IPv6 sadece yoksa kullanılır.
     */
    fun resolve(host: String): List<String> {
        if (IpCheck.isIp(host)) return listOf(host)
        val v4 = runCatching { queryType(host, "A") }.getOrDefault(emptyList())
        if (v4.isNotEmpty()) return v4
        return queryType(host, "AAAA")
    }

    /** İstenen kayıt tipini (A/AAAA) çözümler; UDP DNS sentezi için kullanılır. */
    fun resolveType(host: String, type: String): List<String> {
        if (IpCheck.isIp(host)) return listOf(host)
        return queryType(host, type)
    }
}

/** `node:url` muadili URL yardımcıları. */
object UrlUtil {
    fun parse(rawUrl: String): URL? = try {
        URL(rawUrl)
    } catch (_: Exception) {
        null
    }

    fun pathname(search: String): String =
        search.substringBefore('?') + if (search.contains('?')) "?" + search.substringAfter('?') else ""
}
