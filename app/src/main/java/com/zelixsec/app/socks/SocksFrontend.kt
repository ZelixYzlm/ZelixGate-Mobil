package com.zelixsec.app.socks

import com.zelixsec.app.desync.DesyncProxy
import com.zelixsec.app.desync.IpCheck
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZelixGate - SOCKS5 Ön Yüzü (Android VPN köprüsü)
 * ----------------------------------------------
 * VPN açıldığında hev-socks5-tunnel tüm cihaz trafiğini bu yerel SOCKS5
 * sunucusuna aktarır; sunucu da ağır işi DesyncProxy motora yaptırır.
 * Böylece masaüstündeki parçalama (split) + DoH + reklam engelleme mantığının
 * BÜTÜNÜ VPN trafiğinde de aynı biçimde çalışır.
 *
 * Desteklenen:
 *   - CONNECT (CMD 1):   motora openSocks() ile gider (parçalama dahil)
 *   - UDP ASSOCIATE (CMD 3): yerel UDP aktarıcı devreye girer; hedef port 53
 *     olan DNS paketleri motordaki DoH ile yanıtlanır (hev mapped-DNS yalnız
 *     A sorgularını kendisi cevaplar; AAAA ve doğrudan sağlayıcı sorguları
 *     buradan DoH ile döner).
 *   - Kimlik doğrulama: anonim (hev kullanıcısı zaten yerel).
 */

class SocksFrontend(
    private val engine: DesyncProxy,
    private val logSink: ((level: String, message: String) -> Unit)? = null,
) {
    companion object {
        const val PREFERRED_TCP_PORT = 7171

        /* SOCKS5 sabitleri */
        private const val SOCKS_VERSION = 5
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4
        private const val REP_SUCCESS = 0x00
        private const val REP_GENERAL_FAIL = 0x01
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CMD_NOT_SUPPORTED = 0x07
        private const val REP_ATYP_NOT_SUPPORTED = 0x08

        private const val RELAY_IDLE_MS = 60_000L
        private const val SESSION_CLEAN_INTERVAL_MS = 1000L

        /**
         * Android'de InetAddress.getLoopbackAddress() ::1 (IPv6) döndürür!
         * hev, öz-test ve tüm istemciler 127.0.0.1 kullandığı için dinleyici
         * açıkça IPv4 loopback'e bağlanmalı; aksi halde her connect
         * ECONNREFUSED alır (sunucu [::1]'de, istemci 127.0.0.1'de).
         */
        private val LOOPBACK_V4: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }

    private val running = AtomicBoolean(false)

    private var tcpServer: ServerSocket? = null
    private var tcpPort = 0

    private var relayChannel: DatagramChannel? = null
    private var selector: Selector? = null
    private var udpPort = 0

    private val tcpPool = Executors.newCachedThreadPool { r -> Thread(r, "zelix-socks-tcp").apply { isDaemon = true } }
    private val synthPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "zelix-dns-synth").apply { isDaemon = true } }

    /** key = "istemci|dst" */
    private class RelaySession(
        val key: String,
        val framePrefix: ByteArray,
        val clientAddr: InetAddress,
        val clientPort: Int,
        val dst: InetSocketAddress,
        val dstKey: String,
        val channel: DatagramChannel,
    ) {
        @Volatile
        var lastActive = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, RelaySession>()

    private val buffer = ThreadLocal.withInitial { ByteBuffer.allocateDirect(65552) }

    /** Teşhis günlükleri: ilk 5 + her 50'de bir (log kutusunu boğmasın). */
    private val rlCount = java.util.concurrent.atomic.AtomicLong()

    private fun rlLog(level: String, message: String) {
        val n = rlCount.incrementAndGet()
        if (n <= 5L || n % 50L == 0L) logSink?.invoke(level, message)
    }

    /* ------------------------------------------------------------- yaşam */

    /** SOCKS5 TCP dinleyicisini + UDP aktarıcıyı başlatır; TCP portunu döner. */
    fun start(): Int {
        if (running.get()) return tcpPort
        running.set(true)

        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(LOOPBACK_V4, PREFERRED_TCP_PORT), 512)
        } catch (_: IOException) {
            logSink?.invoke("warn", "$PREFERRED_TCP_PORT dolu, SOCKS için boş port aranıyor...")
            server.bind(InetSocketAddress(LOOPBACK_V4, 0), 512)
        }
        tcpServer = server
        tcpPort = server.localPort
        logSink?.invoke("info", "SOCKS5 ön yüzü başladı: 127.0.0.1:$tcpPort")

        startRelay()

        Thread({ acceptLoop(server) }, "zelix-socks-accept").apply { isDaemon = true }.start()
        logSink?.invoke(
            "info",
            "SOCKS5 UDP aktarıcı: 127.0.0.1:$udpPort (motor portu ${engine.enginePort()})",
        )
        return tcpPort
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        sessions.values.forEach { session ->
            try {
                session.channel.close()
            } catch (_: Exception) {
            }
        }
        sessions.clear()

        try {
            selector?.wakeup()
        } catch (_: Exception) {
        }
        try {
            tcpServer?.close()
        } catch (_: Exception) {
        }
        tcpServer = null
        syntheticShutdown()
        logSink?.invoke("info", "SOCKS5 ön yüzü durduruldu")
    }

    private fun syntheticShutdown() {
        try {
            selector?.close()
        } catch (_: Exception) {
        }
        try {
            relayChannel?.close()
        } catch (_: Exception) {
        }
        selector = null
        relayChannel = null
    }

    /* --------------------------------------------------------- TCP tarafı */

    private fun acceptLoop(server: ServerSocket) {
        var accepted = 0L
        var acceptErr = 0L
        try {
            logSink?.invoke(
                "info",
                "[socks] accept döngüsü devrede: 127.0.0.1:${server.localPort}",
            )
            // accept() boştayken 10 sn'de bir timeout atar; canlılık nabzı için.
            server.soTimeout = 10_000
            while (running.get()) {
                val client: Socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    // Bağlantı gelmedi; accept thread'inin yaşadığını göster.
                    if (accepted == 0L) {
                        logSink?.invoke(
                            "info",
                            "[socks] accept canlı ama hiç bağlantı gelmedi (port ${server.localPort})",
                        )
                    }
                    continue
                } catch (err: IOException) {
                    if (!running.get()) break
                    if (server.isClosed) {
                        logSink?.invoke("error", "[socks] accept soketi kapandı; döngü sonlandı")
                        break
                    }
                    acceptErr++
                    if (acceptErr <= 3L || acceptErr % 50L == 0L) {
                        logSink?.invoke(
                            "warn",
                            "[socks] accept hatası: ${err.message ?: "bilinmeyen"}",
                        )
                    }
                    continue
                }
                accepted++
                if (accepted <= 5L || accepted % 50L == 0L) {
                    logSink?.invoke(
                        "info",
                        "[socks] kabul #$accepted (${client.inetAddress?.hostAddress ?: "?"}:${client.port})",
                    )
                }
                tcpPool.execute { handleClient(client) }
            }
        } catch (err: Throwable) {
            if (running.get()) {
                logSink?.invoke(
                    "error",
                    "[socks] accept döngüsü çöktü: ${err.javaClass.simpleName}: ${err.message ?: "bilinmeyen"}",
                )
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!readVersion(input) || !negotiateMethod(input, output)) {
                rlLog("warn", "[socks] sürüm/yöntem el sıkışması reddedildi")
                closeQuiet(client)
                return
            }

            val header = ByteArray(4)
            if (!readFully(input, header, 0, 4)) {
                closeQuiet(client)
                return
            }
            if ((header[0].toInt() and 0xff) != SOCKS_VERSION) {
                closeQuiet(client)
                return
            }
            val cmd = header[1].toInt() and 0xff
            val atyp = header[3].toInt() and 0xff

            val target = when (atyp) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    if (!readFully(input, addr, 0, 4)) return closeQuiet(client)
                    val portB = ByteArray(2)
                    if (!readFully(input, portB, 0, 2)) return closeQuiet(client)
                    Target(
                        host = addr.joinToString(".") { (it.toInt() and 0xff).toString() },
                        port = ((portB[0].toInt() and 0xff) shl 8) or (portB[1].toInt() and 0xff),
                    )
                }
                ATYP_DOMAIN -> {
                    val lenByte = ByteArray(1)
                    if (!readFully(input, lenByte, 0, 1)) return closeQuiet(client)
                    val len = lenByte[0].toInt() and 0xff
                    if (len == 0 || len > 255) return closeQuiet(client)
                    val nameBytes = ByteArray(len)
                    if (!readFully(input, nameBytes, 0, len)) return closeQuiet(client)
                    val portB = ByteArray(2)
                    if (!readFully(input, portB, 0, 2)) return closeQuiet(client)
                    Target(
                        host = String(nameBytes, Charsets.ISO_8859_1).trim(),
                        port = ((portB[0].toInt() and 0xff) shl 8) or (portB[1].toInt() and 0xff),
                    )
                }
                ATYP_IPV6 -> {
                    val addr = ByteArray(16)
                    if (!readFully(input, addr, 0, 16)) return closeQuiet(client)
                    val portB = ByteArray(2)
                    if (!readFully(input, portB, 0, 2)) return closeQuiet(client)
                    Target(
                        host = InetAddress.getByAddress(addr).hostAddress ?: "",
                        port = ((portB[0].toInt() and 0xff) shl 8) or (portB[1].toInt() and 0xff),
                    )
                }
                else -> {
                    replyFailure(output, REP_ATYP_NOT_SUPPORTED)
                    closeQuiet(client)
                    return
                }
            }

            if (cmd == CMD_CONNECT) {
                rlLog("info", "[socks] CONNECT → ${target.host}:${target.port}")
            } else {
                rlLog("info", "[socks] CMD=$cmd → ${target.host}:${target.port}")
            }
            when (cmd) {
                CMD_CONNECT -> handleConnect(client, output, target)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, target)
                else -> {
                    replyFailure(output, REP_CMD_NOT_SUPPORTED)
                    closeQuiet(client)
                }
            }
        } catch (err: Exception) {
            logSink?.invoke("warn", "SOCKS istemci hatası: ${err.message ?: "bilinmeyen"}")
            closeQuiet(client)
        }
    }

    private class Target(val host: String, val port: Int)

    /** DNS-over-TCP (RFC 1035 4.2.2): 2 bayt uzunluk + sorgu → 2 bayt uzunluk + cevap. */
    private fun handleDnsTcp(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 4000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val lenBuf = ByteArray(2)
            while (readFully(input, lenBuf, 0, 2)) {
                val n = ((lenBuf[0].toInt() and 0xff) shl 8) or (lenBuf[1].toInt() and 0xff)
                if (n == 0 || n > 512) return
                val query = ByteArray(n)
                if (!readFully(input, query, 0, n)) return
                rlLog("info", "[socks] DNS-over-TCP sorgusu: $n bayt")
                val answer = buildDnsAnswer(query) ?: return
                output.write((answer.size shr 8) and 0xff)
                output.write(answer.size and 0xff)
                output.write(answer)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            closeQuiet(client)
        }
    }

    private fun handleConnect(client: Socket, output: OutputStream, target: Target) {
        val host = target.host
        val port = target.port
        // IPv6 hedefleri bu ortamda çalışmıyor (BlueStacks/QEMU IPv6'da
        // 10 sn sonra ECONNREFUSED veriyor). Hemen başarısız dönelim ki
        // istemci (Chrome Happy Eyeballs) IPv4 A kaydına hızla düşsün.
        // DNS tarafında AAAA artık NODATA dönüyor; bu, literal IPv6
        // istekleri için ikinci güvenlik katmanı.
        if (host.contains(':')) {
            rlLog("info", "[socks] IPv6 hedef reddedildi (A kaydına düşülüyor): $host:$port")
            replyFailure(output, REP_HOST_UNREACHABLE)
            closeQuiet(client)
            return
        }
        // Bazı istemciler DNS'i TCP üzerinden sorar (UDP düşerse);
        // motora 198.18.0.2:53 gibi ic adrese CONNECT acmak anlamsizdir,
        // sorguyu doğrudan DoH sentezinden yanıtlıyoruz.
        if (port == 53) {
            handleDnsTcp(client)
            return
        }
        try {
            // Motor: DoH + parçalama + yukarı akış zinciri. SOCKS başarı cevabını
            // upstream açıldıktan hemen sonra motora ait beforeReply kancası yazar
            // (parçalama pompası başlamadan önce). Reklam engellenen hostlarda
            // AdblockedException fırlatılır ve cevap hiç yazılmadan okunur.
            engine.openSocks(client, host, port) { replySuccess(output) }
        } catch (err: DesyncProxy.AdblockedException) {
            logSink?.invoke("info", "Reklam engellendi: $host:$port")
            replyFailure(output, REP_GENERAL_FAIL)
            closeQuiet(client)
        } catch (err: Exception) {
            logSink?.invoke(
                "info",
                "SOCKS CONNECT başarısız: $host:$port (${err.message ?: "bilinmeyen"})",
            )
            replyFailure(output, REP_GENERAL_FAIL)
            closeQuiet(client)
        }
    }

    private fun handleUdpAssociate(client: Socket, target: Target) {
        val relay = relayChannel
        if (relay == null || !running.get()) {
            try {
                replyFailure(client.getOutputStream(), REP_GENERAL_FAIL)
            } catch (_: Exception) {
            }
            closeQuiet(client)
            return
        }

        try {
            // BND.ADDR=127.0.0.1 BND.PORT=udpRelay portu (RFC 1928)
            val reply = ByteArray(10)
            reply[0] = 0x05
            reply[1] = REP_SUCCESS.toByte()
            reply[2] = 0x00
            reply[3] = ATYP_IPV4.toByte()
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val bytes = loopback.address
            reply[4] = bytes[0]
            reply[5] = bytes[1]
            reply[6] = bytes[2]
            reply[7] = bytes[3]
            reply[8] = ((udpPort shr 8) and 0xff).toByte()
            reply[9] = (udpPort and 0xff).toByte()
            client.getOutputStream().apply {
                write(reply)
                flush()
            }
        } catch (_: Exception) {
            closeQuiet(client)
            return
        }

        // Kontrol bağlantısı kapanınca bu istemcinin UDP oturumlarını temizle.
        rlLog("info", "[socks] UDP ASSOCIATE (relay 127.0.0.1:$udpPort)")
        val clientKey = "${client.inetAddress?.hostAddress ?: "?"}:${client.port}"
        val input = client.getInputStream()
        try {
            client.soTimeout = 1000
            val probe = ByteArray(1)
            while (running.get()) {
                val n: Int
                try {
                    n = input.read(probe)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) break
            }
        } finally {
            closeQuiet(client)
            cleanupClientSessions(clientKey)
        }
    }

    private fun cleanupClientSessions(clientKey: String) {
        sessions.values.removeIf { session -> session.key.startsWith("$clientKey|") }
    }

    private fun readVersion(input: InputStream): Boolean {
        val ver = ByteArray(1)
        if (!readFully(input, ver, 0, 1)) return false
        return (ver[0].toInt() and 0xff) == SOCKS_VERSION
    }

    private fun negotiateMethod(input: InputStream, output: OutputStream): Boolean {
        val count = ByteArray(1)
        if (!readFully(input, count, 0, 1)) return false
        val n = count[0].toInt() and 0xff
        if (n == 0 || n > 32) return false
        val methods = ByteArray(n)
        if (!readFully(input, methods, 0, n)) return false
        val supportsNoAuth = methods.any { m -> (m.toInt() and 0xff) == 0x00 }
        // Yalnız NO AUTH (0x00) sunulur; hev tüneli bunu kullanır.
        output.write(if (supportsNoAuth) byteArrayOf(0x05, 0x00) else byteArrayOf(0x05, 0xFF.toByte()))
        output.flush()
        return supportsNoAuth
    }

    private fun replySuccess(output: OutputStream) {
        val reply = byteArrayOf(0x05, REP_SUCCESS.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
        output.write(reply)
        output.flush()
    }

    private fun replyFailure(output: OutputStream, replyCode: Int) {
        try {
            val reply = byteArrayOf(0x05, replyCode.toByte(), 0x00, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)
            output.write(reply)
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun readFully(input: InputStream, data: ByteArray, off: Int, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val n = try {
                input.read(data, off + got, len - got)
            } catch (_: IOException) {
                return false
            }
            if (n < 0) return false
            got += n
        }
        return true
    }

    private fun closeQuiet(socket: Socket) {
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: Exception) {
        }
    }

    /* --------------------------------------------------------- UDP tarafı */

    private fun startRelay() {
        val selector0 = Selector.open()
        val relay = DatagramChannel.open().apply {
            configureBlocking(false)
            bind(InetSocketAddress(LOOPBACK_V4, 0))
        }
        selector = selector0
        relayChannel = relay
        udpPort = relay.socket().localPort
        relay.register(selector0, SelectionKey.OP_READ)

        Thread({ relayLoop(selector0, relay) }, "zelix-socks-udp").apply { isDaemon = true }.start()
    }

    private fun relayLoop(sel: Selector, relay: DatagramChannel) {
        val buf = ByteBuffer.allocate(65552)
        while (running.get()) {
            try {
                sel.select(SESSION_CLEAN_INTERVAL_MS)
                val keys = sel.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid()) continue
                    if (key.channel() === relay) {
                        while (handleRelayDatagram(relay)) {
                            // sıra bitene kadar
                        }
                    } else {
                        val session = key.attachment() as? RelaySession
                        if (session != null) {
                            while (handleSessionDatagram(key, session)) {
                                // sıra bitene kadar
                            }
                        }
                    }
                }
                cleanupSessions()
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
        // kapanış
    }

    /** İstemciden gelen (tünelden) kapsülü çöz ve ilet/yanıtla; tekrar varsa true. */
    private fun handleRelayDatagram(relay: DatagramChannel): Boolean {
        val buf = buffer.get()
        buf.clear()
        val raw = try {
            relay.receive(buf)
        } catch (_: Exception) {
            return false
        } ?: return false
        val src = raw as? InetSocketAddress ?: return true

        if (src.address == null || src.port <= 0) return true
        buf.flip()
        val frame = ByteArray(buf.remaining()).also { buf.get(it) }
        if (frame.size < 10) return true

        if ((frame[2].toInt() and 0xff) != 0) return true // RSV bağımlı kısım 0 olmalı (FRAG desteklenmez)
        val frag = frame[1].toInt() and 0xff
        if (frag != 0) return true

        val atyp = frame[3].toInt() and 0xff
        var pos = 4
        var dstHost = ""
        when (atyp) {
            ATYP_IPV4 -> {
                if (frame.size < pos + 6) return true
                dstHost = frame.sliceArray(pos until pos + 4).joinToString(".") { b -> (b.toInt() and 0xff).toString() }
                pos += 4
            }
            ATYP_DOMAIN -> {
                if (frame.size <= pos) return true
                val len = frame[pos].toInt() and 0xff
                pos += 1
                if (len == 0 || frame.size < pos + len) return true
                dstHost = String(frame, pos, len, Charsets.ISO_8859_1)
                pos += len
            }
            ATYP_IPV6 -> {
                if (frame.size < pos + 18) return true
                val addr = frame.sliceArray(pos until pos + 16)
                dstHost = try {
                    InetAddress.getByAddress(addr).hostAddress ?: return true
                } catch (_: Exception) {
                    return true
                }
                pos += 16
            }
            else -> return true
        }
        if (frame.size < pos + 3) return true
        val dstPort = ((frame[pos].toInt() and 0xff) shl 8) or (frame[pos + 1].toInt() and 0xff)
        pos += 2
        if (dstPort <= 0) return true
        // IPv6 UDP hedefleri kapalı (DNS'te AAAA verilmiyor; literal istekler atılır).
        if (dstHost.contains(':')) return true
        val payload = if (frame.size > pos) frame.copyOfRange(pos, frame.size) else ByteArray(0)
        if (payload.isEmpty()) return true

        val clientKey = "${src.address.hostAddress}:${src.port}"
        val dstKey = "$dstHost:$dstPort"
        val sessionKey = "$clientKey|$dstKey"
        val framePrefix = frame.copyOfRange(0, pos)
        val relay0 = relayChannel ?: return true

        if (dstPort == 53) {
            // UDP DNS -> motor DoH ile sentezle (hev mapped-DNS'ten gelen AAAA ve
            // doğrudan sağlayıcı sorguları da yakalanır). Engeli doldukça sessizce at.
            val query = payload
            val srcAddr = src.address
            val srcPort = src.port
            synthPool.execute {
                val answer = buildDnsAnswer(query)
                if (answer != null) {
                    try {
                        val out = ByteBuffer.wrap(framePrefix + answer)
                        relay0.send(out, InetSocketAddress(srcAddr, srcPort))
                    } catch (_: Exception) {
                    }
                }
            }
            return true
        }

        val session = sessions.compute(sessionKey) { _key, existing ->
            if (existing != null && existing.dst.address != null) {
                existing.lastActive = System.currentTimeMillis()
                existing
            } else {
                try {
                    val dstAddress = resolveTarget(dstHost, dstPort) ?: return@compute null
                    // DİKKAT: Çıkış soketi 127.0.0.1'e bağlanmamalı! Loopback'e
                    // bağlı bir UDP soketi dış hedeflere paket gönderemez
                    // (EINVAL/ENETUNREACH) — QUIC ve tüm UDP trafiği ölür.
                    // Joker adresten bağlanır; kaynak adresi OS seçer.
                    val channel = DatagramChannel.open().apply {
                        configureBlocking(false)
                    }
                    val newSession = RelaySession(
                        key = sessionKey,
                        framePrefix = framePrefix,
                        clientAddr = src.address,
                        clientPort = src.port,
                        dst = dstAddress,
                        dstKey = dstKey,
                        channel = channel,
                    )
                    val sel = selector ?: return@compute null
                    channel.register(sel, SelectionKey.OP_READ, newSession)
                    logSink?.invoke("debug", "UDP oturumu: $clientKey -> $dstKey")
                    newSession
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return true

        try {
            val sessbuf = ByteBuffer.wrap(payload)
            session.channel.send(sessbuf, session.dst)
            session.lastActive = System.currentTimeMillis()
        } catch (_: Exception) {
            sessions.remove(sessionKey)
            try {
                session.channel.close()
            } catch (_: Exception) {
            }
        }
        return true
    }

    /** Oturumun dışarıdan gelen datagramı: SOCKS kuşatıyla istemciye sar. */
    private fun handleSessionDatagram(key: SelectionKey, session: RelaySession): Boolean {
        val buf = buffer.get()
        buf.clear()
        val src = try {
            session.channel.receive(buf)
        } catch (_: Exception) {
            return false
        } ?: return false
        buf.flip()
        if (buf.remaining() <= 0) return true
        val payload = ByteArray(buf.remaining()).also { buf.get(it) }

        try {
            val relay = relayChannel
            if (relay != null) {
                val out = ByteBuffer.wrap(session.framePrefix + payload)
                relay.send(out, InetSocketAddress(session.clientAddr, session.clientPort))
            }
        } catch (_: Exception) {
        }
        session.lastActive = System.currentTimeMillis()
        return true
    }

    private fun cleanupSessions() {
        val now = System.currentTimeMillis()
        sessions.values.removeIf { session ->
            if (now - session.lastActive > RELAY_IDLE_MS) {
                try {
                    session.channel.close()
                } catch (_: Exception) {
                }
                true
            } else {
                false
            }
        }
    }

    private fun resolveTarget(host: String, port: Int): InetSocketAddress? {
        val clean = host.trim()
        if (clean.isEmpty()) return null
        if (IpCheck.isIp(clean)) {
            return try {
                InetSocketAddress(InetAddress.getByName(clean), port)
            } catch (_: Exception) {
                null
            }
        }
        // alan adı -> motorun DoH'u (önbellekli), başarısızsa sistem DNS
        val ips = try {
            engine.resolveDns(clean, "A").ifEmpty { emptyList() }
        } catch (_: Exception) {
            emptyList()
        }
        if (ips.isNotEmpty()) {
            for (ip in ips) {
                try {
                    return InetSocketAddress(InetAddress.getByName(ip), port)
                } catch (_: Exception) {
                }
            }
        }
        return try {
            InetSocketAddress(InetAddress.getByName(clean), port)
        } catch (_: Exception) {
            null
        }
    }

    /* --------------------------------------------------- UDP DNS sentezi */

    /**
     * DNS sorgusunu motordaki DoH ile yanıtla.
     * QTYPE 1 (A) -> IPv4.
     * QTYPE 28 (AAAA) -> NODATA (NOERROR, 0 yanıt): IPv6 kapalı; istemci
     * A kaydına düşer. (Bu ortamlarda IPv6 bağlantıları ECONNREFUSED.)
     * Diğer tipler -> NODATA. Çözümleme başarısızsa SERVFAIL; biçim bozuksa null.
     */
    private fun buildDnsAnswer(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val qd = u16(query, 4)
        val flags = u16(query, 2)
        if ((flags and 0x8000) != 0) return null // cevabı değil sorguyu bekleriz
        if (qd != 1) return null // tek soruluk standard sorgu

        // question parse: sonda qtype/qclass
        var pos = 12
        val nameStart = pos
        var qend = -1
        while (pos < query.size - 4) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) {
                pos += 1
                if (pos + 4 > query.size) return null
                qend = pos + 4
                break
            }
            if (len > 63) return null // sıkıştırma beklenmez
            pos += 1 + len
        }
        if (qend < 0) return null
        val qtype = u16(query, qend - 4)
        val qclass = u16(query, qend - 2)
        if (qclass != 1) return null // internet sınıfı değil -> general relay

        val name = decodeDnsName(query, nameStart, qend - 4)
        if (name.isEmpty()) return null

        var answers: List<ByteArray>
        var rcode = 0
        try {
            val ips = when (qtype) {
                1 -> engine.resolveDns(name, "A")
                // AAAA -> NODATA (boş). IPv6 bu ortamda ölü; A kaydına düşülür.
                28 -> emptyList()
                else -> emptyList()
            }
            answers = ips.mapNotNull { ipStringToBytes(it) }.take(4)
            // Yalnızca A sorgusunda çözüm yoksa SERVFAIL anlamlıdır.
            // AAAA'nın boş dönmesi NODATA'dır (rcode 0) — istemci bekletilmez.
            if (answers.isEmpty() && qtype == 1) rcode = 2
        } catch (_: Exception) {
            answers = emptyList()
            rcode = 2
        }

        val out = java.io.ByteArrayOutputStream()
        val head = ByteArray(12)
        System.arraycopy(query, 0, head, 0, 6) // idflags+qd = id(2)+flags(2)+qd(2)
        head[6] = 0
        head[7] = answers.size.toByte() // ancount
        head[8] = 0
        head[9] = 0
        head[10] = 0
        head[11] = 0
        out.write(head)

        // orijinal soru bölümünü aynen geri yazdır
        out.write(query, 12, qend - 12)

        for (answer in answers) {
            // güvenli gösterici: nameStart her zaman 12 -> 0xC00C
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            out.write(byteArrayOf((qtype shr 8).toByte(), qtype.toByte()))
            out.write(byteArrayOf(0, 1)) // QCLASS == IN
            out.write(byteArrayOf(0, 0, 0, 30)) // TTL 30 sn
            out.write(byteArrayOf((answer.size shr 8).toByte(), answer.size.toByte()))
            out.write(answer)
        }

        val result = out.toByteArray()
        // Bayraklar: istemcinin RD biti korunur; QR + RA işaretlenir, RCODE yazılır.
        val outFlags = (flags and 0x0100) or 0x8000 or 0x0080 or rcode
        result[2] = ((outFlags shr 8) and 0xff).toByte()
        result[3] = (outFlags and 0xff).toByte()
        return result
    }

    private fun decodeDnsName(query: ByteArray, start: Int, end: Int): String {
        val sb = StringBuilder()
        var pos = start
        while (pos < end) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) break
            if (len > 63 || pos + 1 + len > end) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, pos + 1, len, Charsets.ISO_8859_1))
            pos += 1 + len
        }
        return sb.toString().lowercase()
    }

    private fun ipStringToBytes(ip: String): ByteArray? {
        return try {
            InetAddress.getByName(ip).address
        } catch (_: Exception) {
            null
        }
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
}
