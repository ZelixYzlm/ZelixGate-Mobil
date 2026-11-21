package com.zelixsec.app.desync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * ZelixGate - Teşhis (diagnostics, Android portu)
 * ----------------------------------------------
 * Aynı hedefe üç yol dener ve sonucu karşılaştırır:
 *   1) DoH ile DNS çözümü  -> DNS zehirlemesi var mı?
 *   2) Doğrudan TLS        -> DPI bloğu gerçekten var mı?
 *   3) Proxy üzerinden TLS -> ZelixGate bloğu aşabiliyor mu?
 * Bu, "bağlandım ama çalışmıyor" durumunu tahminle değil ölçümle ayırır.
 */

object Diagnostics {

    data class TlsResult(val ms: Double, val protocol: String?)

    private fun elapsedMs(startedNs: Long): Double {
        val raw = (System.nanoTime() - startedNs) / 1e6
        return kotlin.math.round(raw * 10) / 10.0
    }

    /** TCP bağlantı süresi (ms); başarısızsa hata fırlatır. */
    fun tcpConnect(host: String, port: Int, timeoutMs: Int = 5000): Double {
        val started = System.nanoTime()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return elapsedMs(started)
        } catch (_: SocketTimeoutException) {
            throw Exception("TCP zaman aşımı")
        } catch (_: IOException) {
            throw Exception("TCP bağlanamadı")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** IP'ye bağlanıp hedef hostname ile SNI gönderen TLS el sıkışması. */
    fun tlsHandshake(host: String, port: Int, target: String, timeoutMs: Int = 6000): TlsResult {
        val started = System.nanoTime()
        val plain = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), timeoutMs)
            }
        } catch (_: SocketTimeoutException) {
            throw Exception("TLS zaman aşımı")
        } catch (_: IOException) {
            throw Exception("TCP bağlanamadı")
        }

        val ssl = try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(plain, target, port, true) as SSLSocket
        } catch (err: Exception) {
            try {
                plain.close()
            } catch (_: Exception) {
            }
            throw Exception("TLS kurulamadı: ${err.message ?: "hata"}")
        }

        try {
            try {
                setAlpn(ssl)
            } catch (_: Exception) {
            }
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            SslVerifiers.verifyHostname(ssl, target)
            val ms = elapsedMs(started)
            val protocol = ssl.session?.protocol
            return TlsResult(ms, protocol)
        } catch (_: SocketTimeoutException) {
            throw Exception("TLS zaman aşımı")
        } catch (err: Exception) {
            throw Exception(err.message ?: "TLS hatası")
        } finally {
            try {
                ssl.close()
            } catch (_: Exception) {
            }
            try {
                plain.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Yerel proxy üzerinden CONNECT + TLS el sıkışması. */
    fun tlsThroughProxy(proxyPort: Int, target: String, timeoutMs: Int = 8000): TlsResult {
        val started = System.nanoTime()
        val plain = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
            }
        } catch (_: SocketTimeoutException) {
            throw Exception("Proxy TLS zaman aşımı")
        } catch (_: IOException) {
            throw Exception("Proxy TLS: bağlanamadı")
        }

        try {
            plain.soTimeout = timeoutMs
            plain.getOutputStream().apply {
                write(("CONNECT $target:443 HTTP/1.1\r\nHost: $target:443\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                flush()
            }

            // CONNECT cevabının başlığını oku
            val buffer = mutableListOf<Byte>()
            val chunk = ByteArray(4096)
            var findEnd = -1
            while (findEnd < 0) {
                val n = plain.getInputStream().read(chunk)
                if (n < 0) throw Exception("Proxy TLS: bağlantı kesildi")
                for (i in 0 until n) buffer.add(chunk[i])
                val arr = buffer.toByteArray()
                findEnd = indexOfDoubleCrlf(arr)
                if (findEnd < 0 && arr.size > 16 * 1024) throw Exception("Proxy TLS: cevap çok büyük")
            }
            val head = String(buffer.toByteArray(), 0, findEnd, Charsets.ISO_8859_1)
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) {
                throw Exception("proxy CONNECT reddetti: ${head.split("\r\n").firstOrNull() ?: "boş"}")
            }

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, target, 443, true) as SSLSocket
            try {
                try {
                    setAlpn(ssl)
                } catch (_: Exception) {
                }
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                SslVerifiers.verifyHostname(ssl, target)
                val ms = elapsedMs(started)
                return TlsResult(ms, ssl.session?.protocol)
            } catch (_: SocketTimeoutException) {
                throw Exception("Proxy TLS zaman aşımı")
            } catch (err: Exception) {
                throw Exception(err.message ?: "Proxy TLS hatası")
            } finally {
                try {
                    ssl.close()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                plain.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun indexOfDoubleCrlf(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    /** ALPN (http/1.1) — API 29+; eski sürümlerde sessizce atlanır. */
    private fun setAlpn(ssl: SSLSocket) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val params: SSLParameters = ssl.sslParameters
        params.applicationProtocols = listOf("http/1.1").toTypedArray()
        ssl.sslParameters = params
    }

    /**
     * Tam teşhis raporu: engine/diagnostics.js runDiagnostics() ile aynı
     * alan adları ve aynı karar (verdict) metinleri.
     */
    fun runDiagnostics(opts: JSONObject): JSONObject {
        val host = opts.optString("host").ifEmpty { "discord.com" }
        val proxyPort = opts.optInt("proxyPort", 7717)
        val dohJson = opts.optJSONObject("doh") ?: JSONObject()
        val dohOpts = mutableMapOf<String, Any>(
            "provider" to dohJson.optString("provider", "cloudflare"),
            "enabled" to dohJson.optBoolean("enabled", true),
            "allowSystemFallback" to dohJson.optBoolean("allowSystemFallback", true),
        )
        if (dohJson.has("timeoutMs")) dohOpts["timeoutMs"] = dohJson.optInt("timeoutMs", 4000)
        val resolver = DohResolver(dohOpts)

        val report = JSONObject()
            .put("host", host)
            .put("at", System.currentTimeMillis())
            .put("dns", JSONObject().put("ok", false).put("ips", JSONArray()).put("ms", 0).put("error", JSONObject.NULL))
            .put(
                "direct",
                JSONObject().put("ok", false).put("ms", 0).put("ip", JSONObject.NULL)
                    .put("protocol", JSONObject.NULL).put("error", JSONObject.NULL),
            )
            .put("secured", JSONObject().put("ok", false).put("ms", 0).put("protocol", JSONObject.NULL).put("error", JSONObject.NULL))
            .put("verdict", "unknown")
            .put("notes", JSONArray())

        val notes = report.optJSONArray("notes")

        val dnsStarted = System.currentTimeMillis()
        try {
            val ips = resolver.resolve(host)
            report.put(
                "dns",
                JSONObject()
                    .put("ok", ips.isNotEmpty())
                    .put("ips", JSONArray(ips))
                    .put("ms", System.currentTimeMillis() - dnsStarted)
                    .put("error", JSONObject.NULL),
            )
        } catch (err: Exception) {
            report.put(
                "dns",
                JSONObject()
                    .put("ok", false)
                    .put("ips", JSONArray())
                    .put("ms", System.currentTimeMillis() - dnsStarted)
                    .put("error", err.message ?: "bilinmeyen"),
            )
        }

        if (!report.optJSONObject("dns")!!.optBoolean("ok", false)) {
            report.put("verdict", "dns-failed")
            notes.put("Alan adı çözümlenemedi. DoH sağlayıcısını değiştirmeyi deneyin.")
            return report
        }

        val dns = report.optJSONObject("dns")!!
        val ipsArr = dns.optJSONArray("ips")!!
        var ip = ""
        for (i in 0 until ipsArr.length()) {
            val candidate = ipsArr.optString(i)
            if (candidate.isNotBlank()) {
                val isV4 = SslVerifiers.isIpv4(candidate)
                if (isV4) {
                    ip = candidate
                    break
                }
                if (ip.isEmpty()) ip = candidate
            }
        }
        if (ip.isEmpty()) ip = ""
        val direct = report.optJSONObject("direct")!!

        try {
            val ms = tcpConnect(ip, 443)
            direct.put("ms", ms)
        } catch (err: Exception) {
            direct.put("error", "TCP: ${err.message}")
        }

        if (!direct.has("error") || direct.optString("error").isEmpty()) {
            try {
                val handshake = tlsHandshake(ip, 443, host)
                direct.put("ok", true)
                direct.put("ms", handshake.ms)
                direct.put("protocol", handshake.protocol ?: JSONObject.NULL)
                direct.put("ip", ip)
            } catch (err: Exception) {
                direct.put("error", err.message ?: "TLS hatası")
                direct.put("ip", ip)
            }
        } else {
            direct.put("ip", ip)
        }

        val secured = report.optJSONObject("secured")!!
        try {
            val r = tlsThroughProxy(proxyPort, host)
            secured.put("ok", true)
            secured.put("ms", r.ms)
            secured.put("protocol", r.protocol ?: JSONObject.NULL)
        } catch (err: Exception) {
            secured.put("error", err.message ?: "bilinmeyen")
        }

        val directOk = direct.optBoolean("ok", false)
        val securedOk = secured.optBoolean("ok", false)
        when {
            directOk && securedOk -> {
                report.put("verdict", "open")
                notes.put("Ağda bu alan adına DPI bloğu görünmüyor; ZelixGate yine de parçalama uyguluyor.")
            }
            !directOk && securedOk -> {
                report.put("verdict", "bypassed")
                notes.put("Doğrudan bağlantı engellendi, ZelixGate bağlantıyı kurdu. Blok aşılıyor.")
            }
            directOk && !securedOk -> {
                report.put("verdict", "proxy-error")
                notes.put("Doğrudan bağlantı çalışıyor ama proxy başarısız. Parçalama modunu veya portu değiştirin.")
            }
            else -> {
                report.put("verdict", "blocked-hard")
                notes.put("İki yol da başarısız: IP seviyesinde blok olabilir. Yukarı akış (SOCKS5/WARP) gerekir.")
            }
        }

        return report
    }
}

/** TLS sertifika hostname doğrulaması (DoH ve teşhis paylaşımlı). */
object SslVerifiers {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpv4(value: String): Boolean = IPV4.matches(value)

    /**
     * Zincir JSSE tarafından doğrulanmıştır; hostname SAN'da aranır
     * (Node https.request(checkServerIdentity) muadili).
     */
    fun verifyHostname(socket: SSLSocket, hostname: String) {
        if (IPV4.matches(hostname)) return
        val chain = socket.session?.peerCertificates ?: throw IllegalStateException("sertifika yok")
        if (chain.isEmpty()) throw IllegalStateException("sertifika yok")
        val leaf = chain[0] as java.security.cert.X509Certificate
        val names = leaf.subjectAlternativeNames ?: throw IllegalStateException("SAN yok")
        val target = hostname.lowercase()
        var found = false
        for (entry in names) {
            if (entry.size < 2) continue
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
        if (!found) throw javax.net.ssl.SSLPeerUnverifiedException("hostname doğrulanamadı: $hostname")
    }
}
