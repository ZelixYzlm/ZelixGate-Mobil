package com.zelixsec.app.desync

/**
 * ZelixGate - TLS/HTTP parçalama çekirdeği (Android portu)
 * ------------------------------------------------------
 * Amaç: DPI (Deep Packet Inspection) cihazları SNI alanını tek pakette
 * gördüğünde bağlantıyı bloklar / sıfırlar. ClientHello'yu iki ayrı TCP
 * segmentine bölerek SNI'ı iki pakete dağıtırız ve DPI'ın imzası bozulur.
 *
 * Bu dosya ağ G/Ç yapmaz; sadece "nereden bölünecek" kararını verir.
 * (engine/tls-split.js ile bayt düzeyinde aynı davranış)
 */

const val RECORD_HANDSHAKE = 0x16
const val HANDSHAKE_CLIENT_HELLO = 0x01
const val EXT_SERVER_NAME = 0x0000

class SniInfo(val sni: String, val offset: Int, val length: Int)

class SplitPlan(
    val parts: Array<ByteArray>,
    val offset: Int,
    val sni: String?,
    val mode: String,
)

object TlsSplit {
    val MODES = listOf(
        "off", "split2", "split-sni", "split-record", "split-pos", "split-middle",
    )

    private fun be16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

    /** Bir tamponun TLS ClientHello kaydı ile başlayıp başlamadığını söyler. */
    fun looksLikeClientHello(buf: ByteArray): Boolean {
        if (buf.size < 6) return false
        if ((buf[0].toInt() and 0xff) != RECORD_HANDSHAKE) return false
        if ((buf[1].toInt() and 0xff) != 0x03) return false // TLS 1.x
        return (buf[5].toInt() and 0xff) == HANDSHAKE_CLIENT_HELLO
    }

    /** TLS kayıt başlığındaki uzunluğa göre kaydın tamamının elimizde olup olmadığı. */
    fun clientHelloComplete(buf: ByteArray): Boolean {
        if (!looksLikeClientHello(buf) || buf.size < 5) return false
        val recordLen = be16(buf, 3)
        return buf.size >= 5 + recordLen
    }

    /**
     * ClientHello içindeki SNI (server_name) alanını bulur.
     * offset: tampon içindeki hostname'in ilk baytının mutlak indeksi
     */
    fun readServerName(buf: ByteArray): SniInfo? {
        if (!looksLikeClientHello(buf)) return null

        val recordLen = be16(buf, 3)
        val recordEnd = minOf(buf.size, 5 + recordLen)

        var p = 5 // TLS kayıt başlığı
        p += 4 // handshake type (1) + length (3)
        p += 2 + 32 // client_version + random
        if (p + 1 > recordEnd) return null

        val sessionIdLen = buf[p].toInt() and 0xff
        p += 1 + sessionIdLen
        if (p + 2 > recordEnd) return null

        val cipherLen = be16(buf, p)
        p += 2 + cipherLen
        if (p + 1 > recordEnd) return null

        val compLen = buf[p].toInt() and 0xff
        p += 1 + compLen
        if (p + 2 > recordEnd) return null

        val extTotal = be16(buf, p)
        p += 2
        val extEnd = minOf(recordEnd, p + extTotal)

        while (p + 4 <= extEnd) {
            val type = be16(buf, p)
            val len = be16(buf, p + 2)
            val dataStart = p + 4
            if (dataStart + len > extEnd) break

            if (type == EXT_SERVER_NAME) {
                // server_name_list: list_len(2) | type(1) | name_len(2) | name
                val nameStart = dataStart + 5
                if (nameStart > dataStart + len) break
                val nameLen = be16(buf, dataStart + 3)
                if (nameStart + nameLen > buf.size) break
                val sni = String(buf, nameStart, nameLen, Charsets.ISO_8859_1)
                return SniInfo(sni, nameStart, nameLen)
            }
            p = dataStart + len
        }
        return null
    }

    /**
     * Bölme modları:
     *  - 'off'          : hiç bölme (referans / yönlendirici modu)
     *  - 'split2'       : SNI'ın ilk baytı 1. segmentte, geri kalanı 2. segmentte
     *  - 'split-sni'    : hostname'in TAMAMI 1. segmentte, sonrası 2. segmentte
     *  - 'split-record' : TLS kayıt başlığından hemen sonra (5. bayt) böl
     *  - 'split-pos'    : kullanıcının verdiği sabit ofsetten böl
     *  - 'split-middle' : paketi ortadan ikiye böl (agresif, HTTP için de çalışır)
     */
    fun planSplit(buf: ByteArray, mode: String = "split2", splitPosition: Int = 1): SplitPlan {
        val safeMode = if (MODES.contains(mode)) mode else "split2"
        val total = buf.size

        if (total < 2 || safeMode == "off") {
            return SplitPlan(arrayOf(buf), 0, null, "off")
        }

        val info = readServerName(buf)
        val sni = info?.sni
        var offset = 0

        if (safeMode == "split2") {
            offset = info?.let { it.offset + 1 } ?: minOf(5, total - 1)
        } else if (safeMode == "split-sni") {
            offset = info?.let { it.offset + it.length } ?: minOf(5, total - 1)
        } else if (safeMode == "split-record") {
            offset = minOf(5, total - 1)
        } else if (safeMode == "split-pos") {
            offset = minOf(maxOf(1, splitPosition), total - 1)
        } else if (safeMode == "split-middle") {
            offset = maxOf(1, total / 2)
        }

        if (offset <= 0) offset = 1
        if (offset >= total) offset = total - 1

        return SplitPlan(
            arrayOf(buf.copyOfRange(0, offset), buf.copyOfRange(offset, total)),
            offset,
            sni,
            safeMode,
        )
    }

    /**
     * Düz HTTP isteği (proxy üzerinden gelen mutlak URL'li istek) için bölme.
     * İstek satırı + başlıkların ortasından bölmek DPI'ın host imzasını bozar.
     */
    fun planHttpSplit(head: ByteArray): SplitPlan {
        val text = String(head, Charsets.ISO_8859_1)
        val hostIdx = text.lowercase().indexOf("\r\nhost:")
        if (hostIdx >= 0) {
            val offset = minOf(head.size - 1, hostIdx + 1)
            if (offset > 0) {
                return SplitPlan(
                    arrayOf(head.copyOfRange(0, offset), head.copyOfRange(offset, head.size)),
                    offset,
                    null,
                    "http",
                )
            }
        }
        val mid = maxOf(1, head.size / 2)
        return SplitPlan(
            arrayOf(head.copyOfRange(0, mid), head.copyOfRange(mid, head.size)),
            mid,
            null,
            "http",
        )
    }
}
