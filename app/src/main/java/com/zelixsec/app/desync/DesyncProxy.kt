package com.zelixsec.app.desync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * ZelixGate - Paket Parçalama Motoru (yerel ağ geçidi, Android portu)
 * ==================================================================
 * Tek bir yerel HTTP proxy dinler. Uygulamalar (Discord, Roblox, tarayıcı)
 * sisteme vekil sunucu olarak 127.0.0.1:<port> verildiğinde tüm trafik buradan
 * geçer ve ilk TCP segmenti (TLS ClientHello) ikiye bölünerek DPI atlanır.
 *
 * Android tarafında ayrıca SOCKS5 ön yüzü (SocksFrontend) aynı motora bağlanır;
 * VPN açıldığında telefonun tüm trafiği bu motorun üzerinden akar.
 */

const val MAX_HELLO_BYTES = 32 * 1024
const val HELLO_WAIT_MS = 120L
const val CONNECT_TIMEOUT_MS = 10_000

class HostStat {
    var count = 0
    var lastAt = 0L
    var resets = 0
}

class BlockedStat {
    var count = 0
    var lastAt = 0L
}

/** Node'un parseAuthority'si: "host:port" | "[::1]:443" | "host" */
object NetUtil {
    fun parseAuthority(authority: String?, fallbackPort: Int): HostPort? {
        val value = authority?.trim() ?: return null
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end == -1) return null
            val host = value.substring(1, end)
            val rest = value.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: fallbackPort else fallbackPort
            return HostPort(host, if (port > 0) port else fallbackPort)
        }
        val idx = value.lastIndexOf(':')
        if (idx > 0 && value.indexOf(':') == idx) {
            val host = value.substring(0, idx)
            val port = value.substring(idx + 1).toIntOrNull() ?: fallbackPort
            return HostPort(host, if (port > 0) port else fallbackPort)
        }
        return HostPort(value, fallbackPort)
    }

    fun normalizeHost(host: String): String =
        host.lowercase(Locale.ROOT).replace(Regex("""^\[|\]$"""), "").replace(Regex("""\.$"""), "")
}

class HostPort(val host: String, val port: Int)

class DesyncProxy(options: JSONObject = JSONObject()) {
    var onLog: ((level: String, message: String, extra: JSONObject?) -> Unit)? = null
    var onSplit: ((info: JSONObject) -> Unit)? = null

    private val cfg = JSONObject()
    private val doh = DohResolver()
    private val adblock = AdBlocker()
    private val hosts = LinkedHashMap<String, HostStat>()
    private val blockedHosts = LinkedHashMap<String, BlockedStat>()
    private val sockets = ConcurrentLinkedQueue<Socket>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zelix-split-sched").apply { isDaemon = true } }
    private val ioPool = Executors.newCachedThreadPool { r -> Thread(r, "zelix-io").apply { isDaemon = true } }

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    var running = false
        private set

    private val mConnections = AtomicLong(0)
    private val mActive = AtomicLong(0)
    private val mFailures = AtomicLong(0)
    private val mSuspiciousResets = AtomicLong(0)
    private val mBytesUp = AtomicLong(0)
    private val mBytesDown = AtomicLong(0)
    private val mAdsBlocked = AtomicLong(0)
    private var mStartedAt: Long = 0
    private var mLastError: String? = null

    init {
        // Varsayılanlar (engine/proxy.js)
        cfg.put("host", "127.0.0.1")
        cfg.put("port", 7717)
        cfg.put("mode", "split2")
        cfg.put("splitPosition", 1)
        cfg.put("splitDelayMs", 0)
        cfg.put("allowlist", JSONArray())
        cfg.put(
            "doh",
            JSONObject().put("enabled", true).put("provider", "cloudflare").put("allowSystemFallback", true),
        )
        cfg.put(
            "adblock",
            JSONObject().put("enabled", false).put("customBlocked", JSONArray()),
        )
        cfg.put(
            "upstream",
            JSONObject().put("enabled", false).put("type", "socks5").put("host", "127.0.0.1")
                .put("port", 1080).put("username", "").put("password", ""),
        )
        configure(options)
    }

    /* ------------------------------------------------------------------ log */

    fun log(level: String, message: String, extra: JSONObject? = null) {
        val entry = (extra ?: JSONObject())
        entry.put("level", level)
        entry.put("message", message)
        entry.put("at", System.currentTimeMillis())
        onLog?.invoke(level, message, entry)
    }

    /* ---------------------------------------------------------------- cfg */

    fun configure(next: JSONObject) {
        // merged = { ...this.config, ...next }
        val keys = next.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "doh" && key != "adblock" && key != "upstream" && key != "allowlist") {
                cfg.put(key, next.get(key))
            }
        }
        // merged.doh = { ...this.config.doh, ...(next.doh || {}) }
        mergeSub("doh", next.optJSONObject("doh"))
        mergeSub("adblock", next.optJSONObject("adblock"))
        mergeSub("upstream", next.optJSONObject("upstream"))
        val list = next.optJSONArray("allowlist")
        if (list != null) {
            val out = JSONArray()
            for (i in 0 until list.length()) {
                val h = NetUtil.normalizeHost(list.optString(i))
                if (h.isNotEmpty()) out.put(h)
            }
            cfg.put("allowlist", out)
        }
        doh.configure(mapFromCfg("doh"))
        adblock.configure(mapFromCfg("adblock"))
        log(
            "info",
            "Ayarlar güncellendi",
            JSONObject().put("config", publicConfig()),
        )
    }

    private fun mergeSub(key: String, next: JSONObject?) {
        val cur = cfg.optJSONObject(key) ?: JSONObject()
        if (next != null) {
            val k = next.keys()
            while (k.hasNext()) {
                val sub = k.next()
                cur.put(sub, next.get(sub))
            }
        }
        cfg.put(key, cur)
    }

    private fun mapFromCfg(key: String): Map<String, Any> {
        val obj = cfg.optJSONObject(key) ?: JSONObject()
        val out = mutableMapOf<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            if (v is JSONArray) {
                out[k] = List(v.length()) { i -> v.optString(i) }
            } else {
                v?.let { out[k] = it }
            }
        }
        return out
    }

    fun publicConfig(): JSONObject = JSONObject(cfg.toString()).apply {
        val up = optJSONObject("upstream") ?: JSONObject()
        up.put("password", if ((up.optString("password")).isNotEmpty()) "***" else "")
        put("upstream", up)
    }

    private fun allowlistArray(): List<String> {
        val arr = cfg.optJSONArray("allowlist") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /** Host, parçalama listesinde mi? Liste boşsa her yer dahildir. */
    fun isTarget(host: String): Boolean {
        val h = NetUtil.normalizeHost(host)
        val list = allowlistArray()
        if (list.isEmpty()) return true
        for (entry in list) {
            if (h == entry || h.endsWith(".$entry")) return true
        }
        return false
    }

    fun mode(): String = cfg.optString("mode", "split2")

    fun enginePort(): Int = cfg.optInt("port", 7717)

    /** Not: çağrı kendi iş parçacığında (blocking) yapılmalı. */
    fun resolveDns(host: String, type: String): List<String> = doh.resolveType(host, type)

    /** "DNS önbelleğini temizle" (Android'de OS DNS yok; DoH önbelleği temizlenir). */
    fun flushDnsCache() {
        doh.clearCache()
    }

    /* --------------------------------------------------------------- start */

    fun start(): JSONObject {
        if (running) return getStatus()

        var boundPort = cfg.optInt("port", 7717)
        val host = InetAddress.getByName(cfg.optString("host", "127.0.0.1"))
        val srv = ServerSocket()
        srv.reuseAddress = true
        try {
            srv.bind(InetSocketAddress(host, boundPort), 128)
        } catch (_: IOException) {
            log("warn", "$boundPort dolu, boş port aranıyor...")
            srv.bind(InetSocketAddress(host, 0), 128)
        }
        boundPort = srv.localPort
        cfg.put("port", boundPort)

        server = srv
        running = true
        mStartedAt = System.currentTimeMillis()
        mConnections.set(0)
        mActive.set(0)
        mFailures.set(0)
        mSuspiciousResets.set(0)
        mBytesUp.set(0)
        mBytesDown.set(0)
        mAdsBlocked.set(0)
        mLastError = null
        synchronized(hosts) { hosts.clear() }
        synchronized(blockedHosts) { blockedHosts.clear() }

        val t = Thread({ acceptLoop(srv) }, "zelix-accept")
        t.isDaemon = true
        acceptThread = t
        t.start()

        val dohProvider = if (cfg.optJSONObject("doh")?.optBoolean("enabled", false) == true) {
            cfg.optJSONObject("doh")?.optString("provider") ?: "kapalı"
        } else {
            "kapalı"
        }
        log(
            "info",
            "Dinleniyor: ${cfg.optString("host")}:$boundPort",
            JSONObject().put("mode", mode()).put("doh", dohProvider),
        )
        return getStatus()
    }

    fun stop(): JSONObject {
        if (!running) return getStatus()
        for (socket in sockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        sockets.clear()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        running = false
        doh.clearCache()
        log("info", "Motor durduruldu")
        return getStatus()
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running) {
            val client = try {
                srv.accept()
            } catch (_: IOException) {
                if (running) continue else break
            }
            ioPool.execute { handleClient(client) }
        }
    }

    /* ------------------------------------------------------------- status */

    fun getStatus(): JSONObject {
        val status = JSONObject()
        status.put("running", running)
        status.put("host", cfg.optString("host", "127.0.0.1"))
        status.put("port", cfg.optInt("port", 7717))
        status.put("mode", mode())
        status.put("doh", JSONObject(cfg.optJSONObject("doh")?.toString() ?: "{}"))
        status.put(
            "upstream",
            JSONObject()
                .put("enabled", cfg.optJSONObject("upstream")?.optBoolean("enabled", false) == true)
                .put("type", cfg.optJSONObject("upstream")?.optString("type") ?: "socks5")
                .put("host", cfg.optJSONObject("upstream")?.optString("host") ?: "127.0.0.1")
                .put("port", cfg.optJSONObject("upstream")?.optInt("port", 1080) ?: 1080),
        )
        val metrics = JSONObject()
            .put("connections", mConnections.get())
            .put("active", mActive.get())
            .put("failures", mFailures.get())
            .put("suspiciousResets", mSuspiciousResets.get())
            .put("bytesUp", mBytesUp.get())
            .put("bytesDown", mBytesDown.get())
            .put("adsBlocked", mAdsBlocked.get())
            .put("startedAt", if (mStartedAt == 0L) JSONObject.NULL else mStartedAt)
            .put("lastError", mLastError ?: JSONObject.NULL)
        status.put("metrics", metrics)

        val hostsArr = JSONArray()
        synchronized(hosts) {
            hosts.entries
                .sortedByDescending { it.value.lastAt }
                .take(25)
                .forEach { (host, info) ->
                    hostsArr.put(
                        JSONObject()
                            .put("host", host)
                            .put("count", info.count)
                            .put("lastAt", info.lastAt)
                            .put("resets", info.resets),
                    )
                }
        }
        status.put("hosts", hostsArr)

        val adblockStatus = JSONObject()
            .put("enabled", adblock.enabled)
            .put("blocked", mAdsBlocked.get())
            .put("counts", adblock.counts())
        val blockedArr = JSONArray()
        synchronized(blockedHosts) {
            blockedHosts.entries
                .sortedByDescending { it.value.lastAt }
                .take(15)
                .forEach { (host, info) ->
                    blockedArr.put(
                        JSONObject().put("host", host).put("count", info.count).put("lastAt", info.lastAt),
                    )
                }
        }
        adblockStatus.put("hosts", blockedArr)
        status.put("adblock", adblockStatus)
        status.put("config", publicConfig())
        return status
    }

    fun track(host: String) {
        val key = NetUtil.normalizeHost(host)
        val now = System.currentTimeMillis()
        synchronized(hosts) {
            val info = hosts.getOrPut(key) { HostStat() }
            info.count += 1
            info.lastAt = now
        }
    }

    private fun trackBlocked(host: String) {
        val key = NetUtil.normalizeHost(host)
        val now = System.currentTimeMillis()
        synchronized(blockedHosts) {
            val info = blockedHosts.getOrPut(key) { BlockedStat() }
            info.count += 1
            info.lastAt = now
        }
    }

    fun clearAdBlockStats() {
        mAdsBlocked.set(0)
        synchronized(blockedHosts) { blockedHosts.clear() }
    }

    /* ------------------------------------------------------------- client */

    private fun addSocket(socket: Socket) {
        sockets.add(socket)
    }

    private fun dropSocket(socket: Socket) {
        sockets.remove(socket)
        mActive.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
    }

    private fun closeQuiet(socket: Socket) {
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: Exception) {
        }
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
        }
    }

    /** Belirli bir süre kafa okur; kalan baytları da döner. */
    private class RawHead(val lines: List<String>, val headers: Map<String, String>, val remainder: ByteArray)

    private fun readHead(client: Socket): RawHead? {
        val input = client.getInputStream()
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        client.soTimeout = 4000
        var headerEnd = -1
        while (buffer.size() <= 64 * 1024 && headerEnd < 0) {
            val n = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: IOException) {
                return null
            }
            if (n < 0) break
            buffer.write(chunk, 0, n)
            headerEnd = doubleCrlfIndex(buffer.toByteArray())
        }
        val bytes = buffer.toByteArray()
        if (headerEnd < 0) return null
        val headText = String(bytes, 0, headerEnd, Charsets.ISO_8859_1)
        val remainder = bytes.copyOfRange(headerEnd, bytes.size)
        val parts = headText.split("\r\n")
        val headers = HashMap<String, String>()
        for (line in parts.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        return RawHead(parts, headers, remainder)
    }

    private fun doubleCrlfIndex(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val head = readHead(client) ?: return closeQuiet(client)
            val requestLine = head.lines.firstOrNull()?.trim() ?: return closeQuiet(client)
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 2) return closeQuiet(client)
            val method = parts[0].uppercase(Locale.ROOT)
            val url = parts[1]

            if (method == "CONNECT") {
                val target = NetUtil.parseAuthority(url, 443)
                if (target == null) {
                    client.getOutputStream().apply {
                        write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                        flush()
                    }
                    return closeQuiet(client)
                }
                establishTunnel(client, target.host, target.port, head.remainder, "tls", null)
                return
            }

            // Düz HTTP / WebSocket
            val parsed = try {
                java.net.URL(url)
            } catch (_: Exception) {
                null
            }
            if (parsed == null || parsed.host.isEmpty()) {
                client.getOutputStream().apply {
                    write(
                        "HTTP/1.1 400 Bad Request\r\ncontent-type: text/plain; charset=utf-8\r\n\r\nZelixGate: geçersiz istek".toByteArray(),
                    )
                    flush()
                }
                return closeQuiet(client)
            }
            val port = if (parsed.port != -1) parsed.port else 80
            val isUpgrade = (head.headers["upgrade"] ?: "").isNotEmpty()
            val req = JSONObject()
                .put("method", method)
                .put("url", url)
                .put("headers", JSONObject(head.headers))
            if (isUpgrade) {
                establishTunnel(client, parsed.host, port, head.remainder, "ws", req)
            } else {
                establishTunnel(client, parsed.host, port, head.remainder, "http", req)
            }
        } catch (err: Exception) {
            log("warn", "İstemci hatası: ${err.message ?: "bilinmeyen"}")
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    /* ------------------------------------------------------------- tünel */

    fun establishTunnel(
        clientSocket: Socket,
        host: String,
        port: Int,
        head: ByteArray?,
        kind: String,
        req: JSONObject?,
    ) {
        handleTunnel(clientSocket, host, port, head, kind, req, socksMode = false, beforeReply = null)
    }

    /**
     * SOCKS5 ön yüzünden gelen akış (HTTP 200/502 cevapları yazılmaz).
     * SOCKS5 başarı cevabını beforeReply yazar: upstream açıldıktan hemen
     * sonra, parçalama pompası başlamadan önce çağrılır. Reklam engellenen
     * hostlarda AdblockedException, upstream kurulamazsa kendi hatası fırlatılır.
     */
    fun openSocks(
        clientSocket: Socket,
        host: String,
        port: Int,
        beforeReply: (() -> Unit)?,
    ) {
        handleTunnel(clientSocket, host, port, null, "tls", null, socksMode = true, beforeReply = beforeReply)
    }

    private fun handleTunnel(
        clientSocket: Socket,
        host: String,
        port: Int,
        head: ByteArray?,
        kind: String,
        req: JSONObject?,
        socksMode: Boolean,
        beforeReply: (() -> Unit)?,
    ): Socket? {
        mConnections.incrementAndGet()
        mActive.incrementAndGet()
        addSocket(clientSocket)
        clientSocket.tcpNoDelay = true

        if (socksMode) {
            // Reklam engelleyici: SOCKS cevabı FRONTEND tarafında verilir.
            if (adblock.enabled && adblock.blocks(host)) {
                mAdsBlocked.incrementAndGet()
                trackBlocked(host)
                log("info", "Reklam engellendi: $host")
                dropSocket(clientSocket)
                throw AdblockedException()
            }
        } else if (adblock.enabled && adblock.blocks(host)) {
            mAdsBlocked.incrementAndGet()
            trackBlocked(host)
            log("info", "Reklam engellendi: $host")
            if (kind == "http") {
                if (!clientSocket.isClosed) {
                    clientSocket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 403 Blocked by ZelixGate\r\ncontent-type: text/plain; charset=utf-8\r\nconnection: close\r\n\r\nReklam engellendi (ZelixGate)\n").toByteArray(),
                        )
                        flush()
                    }
                }
            } else if (!clientSocket.isClosed) {
                clientSocket.getOutputStream().apply {
                    write("HTTP/1.1 403 Blocked by ZelixGate\r\n\r\n".toByteArray())
                    flush()
                }
                scheduler.execute {
                    try {
                        Thread.sleep(100)
                    } catch (_: Exception) {
                    }
                    closeQuiet(clientSocket)
                }
            } else {
                closeQuiet(clientSocket)
            }
            return null
        }

        val startedAt = System.currentTimeMillis()
        val upstreamSocket: Socket
        try {
            upstreamSocket = openUpstream(host, port)
        } catch (err: Exception) {
            mFailures.incrementAndGet()
            mLastError = "$host:$port -> ${err.message}"
            log("error", "Bağlantı kurulamadı: $host:$port (${err.message})")
            if (!socksMode) {
                if (kind == "http" && !clientSocket.isClosed) {
                    clientSocket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 502 Bad Gateway\r\ncontent-type: text/plain; charset=utf-8\r\nconnection: close\r\n\r\nZelixGate: hedefe ulaşılamadı").toByteArray(),
                        )
                        flush()
                    }
                } else if (!clientSocket.isClosed) {
                    clientSocket.getOutputStream().apply {
                        write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                        flush()
                    }
                }
                dropSocket(clientSocket)
                closeQuiet(clientSocket)
            } else {
                // SOCKS: hata cevabını frontend yazar, soketi orada kapatırız
                dropSocket(clientSocket)
            }
            throw err
        }

        // İstemciye tünelin açıldığını bildir (tarayıcı/uygulama bunu bekler).
        // WebSocket'te 101 cevabını hedef sunucu verir, bu yüzden atlanır.
        if (kind == "tls" && !socksMode && !clientSocket.isClosed) {
            clientSocket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: ZelixGate\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }

        track(host)
        addSocket(upstreamSocket)
        upstreamSocket.tcpNoDelay = true

        if (socksMode) {
            // SOCKS5 başarı cevabı: parçalama pompası başlamadan önce yazılır
            try {
                beforeReply?.invoke()
            } catch (_: Exception) {
            }
        }

        ioPool.execute {
            // Sunucu tarafı bağlantıyı hemen sıfırlarsa bunu "DPI şüphesi" olarak işaretle
            runUpstreamPump(upstreamSocket, clientSocket, startedAt, host)
        }

        val useSplit = isTarget(host)
        log(
            "info",
            "${if (kind == "http") "HTTP" else "TLS"} tünel: $host:$port" +
                (if (useSplit) " [${mode()}]" else " [bölmesiz]"),
        )

        // WebSocket: başlıkları olduğu gibi koruyup (Connection: Upgrade dahil) aktar
        if (kind == "ws" && req != null) {
            val url = java.net.URL(req.optString("url"))
            val headerLines = StringBuilder()
            val hdr = req.optJSONObject("headers") ?: JSONObject()
            val k = hdr.keys()
            while (k.hasNext()) {
                val key = k.next()
                if (key.equals("proxy-connection", ignoreCase = true)) continue
                headerLines.append("$key: ${hdr.optString(key)}\r\n")
            }
            val pathWithQuery =
                url.path + (if (url.query != null) "?${url.query}" else "")
            val headBuffer =
                "${req.optString("method")} $pathWithQuery HTTP/1.1\r\n$headerLines\r\n".toByteArray(Charsets.ISO_8859_1)
            mBytesUp.addAndGet(headBuffer.size.toLong())
            writeSplit(upstreamSocket, headBuffer, useSplit)
            if (head != null && head.isNotEmpty()) {
                upstreamSocket.getOutputStream().write(head)
                mBytesUp.addAndGet(head.size.toLong())
            }
            runClientToUpstreamAfterFlush(clientSocket, upstreamSocket, null, useSplit, finalFlushOnly = false, plainRelayOnly = true)
            return null
        }

        // Düz HTTP: başlığı kendimiz yeniden yazıp bölerek gönderiyoruz
        if (kind == "http" && req != null) {
            val url = java.net.URL(req.optString("url"))
            val headerLines = StringBuilder()
            val hdr = req.optJSONObject("headers") ?: JSONObject()
            val skip = setOf("proxy-connection", "connection", "keep-alive", "transfer-encoding")
            val kk = hdr.keys()
            loop@ while (kk.hasNext()) {
                val key0 = kk.next()
                for (s in skip) {
                    if (key0.equals(s, ignoreCase = true)) continue@loop
                }
                headerLines.append("$key0: ${hdr.optString(key0)}\r\n")
            }
            val pathWithQuery = url.path + (if (url.query != null) "?${url.query}" else "")
            val rebuilt =
                "${req.optString("method")} $pathWithQuery HTTP/1.1\r\n$headerLines\r\nConnection: close\r\n\r\n".toByteArray(
                    Charsets.ISO_8859_1,
                )
            mBytesUp.addAndGet(rebuilt.size.toLong())
            writeSplit(upstreamSocket, rebuilt, useSplit)

            // ÖNEMLİ: gövde bittiğinde soketi KAPATMIYORUZ. Normal istemciler gibi
            // yanıtı bekliyoruz; erken FIN gönderen bağlantıları bazı sunucular
            // (ör. Cloudflare kenarı) iptal sayıp sıfırlıyor.
            runClientToUpstreamAfterFlush(clientSocket, upstreamSocket, head, useSplit, finalFlushOnly = false, plainRelayOnly = false)
            return null
        }

        // TLS / WebSocket: ilk segment bölünür, sonrası doğrudan akar
        pipeWithSplit(clientSocket, upstreamSocket, useSplit, head)
        return null
    }

    class AdblockedException : Exception("blocked")

    /** Upstream → istemci pompası + DPI şüpheli reset sayacı. */
    private fun runUpstreamPump(upstream: Socket, client: Socket, startedAt: Long, host: String): Boolean {
        var result = false
        try {
            val input = upstream.getInputStream()
            val out = client.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    mBytesDown.addAndGet(n.toLong())
                    out.write(buffer, 0, n)
                    out.flush()
                }
            }
            // Sunucu FIN: istemciye de yazı tarafı FIN
            result = true
            try {
                client.shutdownOutput()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            try {
                if (!client.isClosed) client.close()
            } catch (_: Exception) {
            }
        } finally {
            try {
                if (!upstream.isClosed) upstream.close()
            } catch (_: Exception) {
            }
            dropSocket(upstream)
            if (System.currentTimeMillis() - startedAt < 1500) {
                val key = NetUtil.normalizeHost(host)
                synchronized(hosts) {
                    hosts[key]?.let { info -> info.resets += 1 }
                }
                mSuspiciousResets.incrementAndGet()
            }
        }
        return result
    }

    /**
     * İstemciden gelen ilk veriyi (ClientHello) biriktirir, bölme planını
     * uygular ve iki ayrı TCP segmenti olarak sunucuya yazar.
     */
    private fun pipeWithSplit(clientSocket: Socket, upstreamSocket: Socket, useSplit: Boolean, head: ByteArray?) {
        val pending = java.io.ByteArrayOutputStream()
        if (head != null) pending.writeBytes(head)
        var flushed = false
        var waitStartedAt = 0L

        /** Bekleyen başlığı parçalayarak boşalt (tamamlanmasa bile). */
        fun flushPending() {
            val data = pending.toByteArray()
            if (data.isNotEmpty()) writeSplit(upstreamSocket, data, useSplit)
            pending.reset()
            flushed = true
        }

        try {
            val input: InputStream = clientSocket.getInputStream()
            val out: OutputStream = upstreamSocket.getOutputStream()

            if (pending.size() > 0) {
                val isTls = TlsSplit.looksLikeClientHello(pending.toByteArray())
                if (!isTls || TlsSplit.clientHelloComplete(pending.toByteArray())) {
                    writeSplit(upstreamSocket, pending.toByteArray(), useSplit)
                    pending.reset()
                    flushed = true
                } else {
                    waitStartedAt = System.currentTimeMillis()
                }
            }

            clientSocket.soTimeout = 20
            val chunk = ByteArray(4096)
            val buffer = java.io.ByteArrayOutputStream()
            while (true) {
                val n: Int = try {
                    input.read(chunk)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) {
                    // İstemci FIN
                    if (!flushed) {
                        flushPending()
                    }
                    try {
                        upstreamSocket.shutdownOutput()
                    } catch (_: Exception) {
                    }
                    dropSocket(clientSocket)
                    dropSocket(upstreamSocket)
                    return
                }
                if (n == 0) continue
                mBytesUp.addAndGet(n.toLong())
                if (flushed) {
                    out.write(chunk, 0, n)
                    out.flush()
                    continue
                }
                pending.write(chunk, 0, n)
                val buf = pending.toByteArray()
                val isTls = TlsSplit.looksLikeClientHello(buf)
                if (isTls && !TlsSplit.clientHelloComplete(buf) && buf.size < MAX_HELLO_BYTES) {
                    // ClientHello birden fazla segmentte gelmiş olabilir; tamamlanmasını bekle
                    if (waitStartedAt == 0L) {
                        waitStartedAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - waitStartedAt >= HELLO_WAIT_MS) {
                        flushPending()
                    }
                    continue
                }
                flushPending()
            }
        } catch (_: Exception) {
            try {
                if (!upstreamSocket.isClosed) upstreamSocket.close()
            } catch (_: Exception) {
            }
            try {
                if (!clientSocket.isClosed) clientSocket.close()
            } catch (_: Exception) {
            }
            dropSocket(clientSocket)
            dropSocket(upstreamSocket)
            return
        } finally {
            clientSocket.soTimeout = 0
        }
    }

    private fun runClientToUpstreamAfterFlush(
        clientSocket: Socket,
        upstreamSocket: Socket,
        head: ByteArray?,
        useSplit: Boolean,
        finalFlushOnly: Boolean,
        plainRelayOnly: Boolean,
    ) {
        // 'http' / 'ws' türü: başlık yazıldıktan sonraki istemci→sunucu pompası
        var headLeft = head
        if (headLeft != null && headLeft.isNotEmpty() && plainRelayOnly) {
            try {
                upstreamSocket.getOutputStream().write(headLeft)
                mBytesUp.addAndGet(headLeft.size.toLong())
            } catch (_: Exception) {
            }
            headLeft = null
        }
        try {
            val input: InputStream = clientSocket.getInputStream()
            val out: OutputStream = upstreamSocket.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    // req.readableEnded: gövde bitti; soket kapatılmaz
                    try {
                        upstreamSocket.shutdownOutput()
                    } catch (_: Exception) {
                    }
                    continue
                }
                if (n > 0) {
                    mBytesUp.addAndGet(n.toLong())
                    out.write(buffer, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
            try {
                if (!upstreamSocket.isClosed) upstreamSocket.close()
            } catch (_: Exception) {
            }
        } finally {
            try {
                if (!clientSocket.isClosed) clientSocket.close()
            } catch (_: Exception) {
            }
            dropSocket(clientSocket)
            dropSocket(upstreamSocket)
        }
    }

    /** Bölme planını uygulayıp iki ayrı yazma ile gönderir. */
    fun writeSplit(socket: Socket, buffer: ByteArray, useSplit: Boolean) {
        if (socket.isClosed) return
        val out = socket.getOutputStream()

        if (!useSplit || buffer.size < 2) {
            out.write(buffer)
            return
        }

        val isTls = TlsSplit.looksLikeClientHello(buffer)
        val plan = if (isTls) {
            TlsSplit.planSplit(buffer, mode(), cfg.optInt("splitPosition", 1))
        } else {
            TlsSplit.planHttpSplit(buffer)
        }

        if (plan.parts.size < 2) {
            out.write(buffer)
            return
        }

        val first = plan.parts[0]
        val second = plan.parts[1]
        out.write(first)
        out.flush()
        val delay = maxOf(0, cfg.optInt("splitDelayMs", 0))
        if (delay > 0) {
            scheduler.schedule({
                try {
                    if (!socket.isClosed) {
                        socket.getOutputStream().apply {
                            write(second)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }, delay.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } else {
            out.write(second)
            out.flush()
        }

        onSplit?.invoke(
            JSONObject()
                .put("mode", plan.mode)
                .put("offset", plan.offset)
                .put("sni", plan.sni ?: JSONObject.NULL)
                .put("first", first.size)
                .put("second", second.size)
                .put("at", System.currentTimeMillis()),
        )
    }

    /* ------------------------------------------------------------ upstream */

    /** Hedefe giden soketi açar: doğrudan (DoH ile) veya yukarı akış üzerinden. */
    fun openUpstream(host: String, port: Int): Socket {
        val upstream = cfg.optJSONObject("upstream") ?: JSONObject()

        if (upstream.optBoolean("enabled", false)) {
            val type = upstream.optString("type", "socks5")
            val proxyHost = upstream.optString("host", "127.0.0.1")
            val proxyPort = (upstream.optInt("port", if (type == "socks5") 1080 else 8080))
            val username = upstream.optString("username")
            val password = upstream.optString("password")
            return if (type == "socks5") {
                connectSocks5(host, port, proxyHost, proxyPort, username, password, CONNECT_TIMEOUT_MS)
            } else {
                connectHttpProxy(host, port, proxyHost, proxyPort, username, password, CONNECT_TIMEOUT_MS)
            }
        }

        val addresses = doh.resolve(host)
        var lastError: Exception? = null
        for (address in addresses) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByName(address), port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (err: Exception) {
                lastError = err
            }
        }
        throw lastError ?: Exception("çözümlenemedi")
    }

    /** SOCKS5 tüneli açar (kimlik doğrulamalı veya anonim). */
    private fun connectSocks5(targetHost: String, targetPort: Int, proxyHost: String, proxyPort: Int, username: String, password: String, timeout: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        val started = System.currentTimeMillis()
        socket.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
        socket.soTimeout = maxOf(0, timeout - (System.currentTimeMillis() - started).toInt())
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val withAuth = username.isNotEmpty() || password.isNotEmpty()
        out.write(if (withAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))

        var buffer = ByteArray(0)
        var stage = "greeting"

        tailrec fun drain(min: Int) {
            while (buffer.size < min) {
                val chunk = ByteArray(1024)
                val n = input.read(chunk)
                if (n < 0) {
                    socket.close()
                    throw Exception("SOCKS5 sunucusu bağlantıyı kapattı")
                }
                buffer += chunk.copyOf(n)
            }
        }

        while (true) {
            when (stage) {
                "greeting" -> {
                    drain(2)
                    val method = buffer[1].toInt() and 0xff
                    buffer = buffer.copyOfRange(2, buffer.size)
                    if (method == 0x02) {
                        val u = username.toByteArray()
                        val p = password.toByteArray()
                        out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                        stage = "auth"
                    } else if (method == 0x00) {
                        stage = "connect"
                    } else {
                        socket.close()
                        throw Exception("SOCKS5: desteklenen kimlik doğrulama yok")
                    }
                }
                "auth" -> {
                    drain(2)
                    if ((buffer[1].toInt() and 0xff) != 0x00) {
                        socket.close()
                        throw Exception("SOCKS5: kimlik doğrulama başarısız")
                    }
                    buffer = buffer.copyOfRange(2, buffer.size)
                    stage = "connect"
                }
                "connect" -> {
                    val hostBuf = targetHost.toByteArray(Charsets.UTF_8)
                    out.write(
                        byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBuf.size.toByte()) + hostBuf +
                            byteArrayOf(((targetPort shr 8) and 0xff).toByte(), (targetPort and 0xff).toByte()),
                    )
                    stage = "reply"
                }
                "reply" -> {
                    drain(5)
                    if ((buffer[0].toInt() and 0xff) != 0x05) {
                        socket.close()
                        throw Exception("SOCKS5: geçersiz cevap")
                    }
                    if ((buffer[1].toInt() and 0xff) != 0x00) {
                        socket.close()
                        throw Exception("SOCKS5 hata kodu ${buffer[1].toInt() and 0xff}")
                    }
                    val atyp = buffer[3].toInt() and 0xff
                    val addrLen = when (atyp) {
                        0x01 -> 4
                        0x04 -> 16
                        else -> (buffer[4].toInt() and 0xff) + 1
                    }
                    while (buffer.size < 4 + addrLen + 2) {
                        val chunk = ByteArray(1024)
                        val n = input.read(chunk)
                        if (n < 0) {
                            socket.close()
                            throw Exception("SOCKS5 sunucusu bağlantıyı kapattı")
                        }
                        buffer += chunk.copyOf(n)
                    }
                    return socket
                }
            }
        }
    }

    /** Yukarı akış HTTP proxy üzerinden CONNECT tüneli açar. */
    private fun connectHttpProxy(targetHost: String, targetPort: Int, proxyHost: String, proxyPort: Int, username: String, password: String, timeout: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        val started = System.currentTimeMillis()
        socket.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
        socket.soTimeout = maxOf(0, timeout - (System.currentTimeMillis() - started).toInt())
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val auth = if (username.isNotEmpty() || password.isNotEmpty()) {
            val raw = "$username:$password"
            val basic = android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
            "Proxy-Authorization: Basic $basic\r\n"
        } else {
            ""
        }
        out.write(
            ("CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n$auth" +
                "Proxy-Connection: keep-alive\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )

        var buffer = ByteArray(0)
        while (true) {
            val chunk = ByteArray(1024)
            val n = input.read(chunk)
            if (n < 0) {
                socket.close()
                throw Exception("Vekil sunucu bağlantıyı kapattı")
            }
            buffer += chunk.copyOf(n)
            val end = findString(buffer, "\r\n\r\n")
            if (end == -1) {
                if (buffer.size > 16 * 1024) {
                    socket.close()
                    throw Exception("Vekil cevabı çok büyük")
                }
                continue
            }
            val head = String(buffer, 0, end, Charsets.ISO_8859_1)
            val status = Regex("""^HTTP/1\.[01] (\d{3})""").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (status == 0) {
                socket.close()
                throw Exception("Vekil cevabı ayrıştırılamadı")
            }
            if (status != 200) {
                socket.close()
                throw Exception("Vekil CONNECT reddetti (HTTP $status)")
            }
            socket.soTimeout = 0
            return socket
        }
    }
}

private fun findString(buffer: ByteArray, needle: String): Int {
    val mask = needle.toByteArray(Charsets.ISO_8859_1)
    outer@ for (i in 0..buffer.size - mask.size) {
        for (j in mask.indices) {
            if (buffer[i + j] != mask[j]) continue@outer
        }
        return i
    }
    return -1
}
