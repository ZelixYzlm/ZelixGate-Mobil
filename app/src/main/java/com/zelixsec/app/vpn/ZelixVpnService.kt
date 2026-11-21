package com.zelixsec.app.vpn

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.zelixsec.app.runtime.ZelixRuntime
import hev.htproxy.TProxyService
import java.io.File

/**
 * ZelixGate - VPN tünel servisi (Android)
 * ======================================
 * Masaüstündeki "Sistem vekili" (uygulama) katmanının Android karşılığı burasıdır.
 * Kullanıcı VPN izni verdiğinde (MainActivity.prepareVpn), bu hizmet:
 *
 *   1) Parçalama motorunu (DesyncProxy) ayağa kaldırır,
 *   2) SOCKS5 ön yüzünü (SocksFrontend, 127.0.0.1:7171) açar,
 *   3) hev-socks5-tunnel için YAML yapılandırmasını yazar,
 *   4) tun arayüzünü kurar (198.18.0.1/24 + fc00::1/126, DNS 198.18.0.2)
 *      ve TProxyStartService(yaml, fd) ile cihaz trafiğini SOCKS5 üzerinden
 *      motora aktarır.
 *
 * Böylece parçalama (split) + DoH + reklam engelleme zinciri telefonun
 * HTTP(S) trafiğinde de aynı biçimde çalışır.
 */
class ZelixVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zelixsec.app.START"
        const val ACTION_STOP = "com.zelixsec.app.STOP"
        const val CHANNEL_ID = "zelixgate_engine"
        private const val NOTIF_ID = 41

        /* tun arayüz değerleri (hev conf/main.yml ile aynı) */
        const val TUN_MTU = 8500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val TUN_DNS = "198.18.0.2"
    }

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var tunnelUp = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ZelixRuntime.pushLog("info", "Tünel: durdurma komutu alındı")
                stopTunnel(clearVpnState = true)
                stopSelf()
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    /* --------------------------------------------------------------- tünel */

    private fun startTunnel() {
        if (tunnelUp) return
        try {
            startForegroundCompat()

            // 1) Motor + SOCKS5 ön yüzü (sıra önemli: hev SOCKS'a bağlanır)
            ZelixRuntime.ensureEngine()
            val socksPort = ZelixRuntime.ensureSocks()

            // 2) hev yapılandırmasını diske yaz (teşhis için günlüğe de yansıt)
            val config = writeHevConfig(socksPort)
            ZelixRuntime.pushLog(
                "debug",
                "[hevconf] " + config.readText().replace("\n", " | "),
            )

            // Öz-test: sunucuya (A) düz IPv4 ve (B) hev'in kullandığı
            // v4-mapped IPv6 yoluyla bağlanmayı dener; hangi yolun
            // ECONNREFUSED verdiğini doğrudan günlüğe yazar.
            socksSelfTest(socksPort)

            // 3) tun arayüzünü kur
            val builder = Builder()
                .addAddress(TUN_IPV4, 24)
                .addAddress(TUN_IPV6, 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(TUN_DNS)
                .setMtu(TUN_MTU)
                .setSession("ZelixGate")
            builder.addDisallowedApplication(packageName)

            val fd = builder.establish()
            if (fd == null) {
                ZelixRuntime.pushLog("warn", "VPN izni verilmedi; tünel kurulamadı")
                ZelixRuntime.onVpnDown()
                stopSelf()
                return
            }
            tun = fd

            // 4) hev motorunu fd üzerinden devreye sok
            val started = TProxyService.TProxyStartService(config.absolutePath, fd.fd)
            if (!started) throw Exception("hev tünel başlatılamadı (yapılandırma hatası)")

            tunnelUp = true
            ZelixRuntime.onVpnUp()
            startHevMonitor()
            ZelixRuntime.pushLog(
                "info",
                "Tünel kurulu: $TUN_IPV4 (MTU $TUN_MTU) → SOCKS 127.0.0.1:$socksPort",
            )
        } catch (err: Exception) {
            ZelixRuntime.pushLog("error", "Tünel başlatılamadı: ${err.message ?: "bilinmeyen hata"}")
            stopTunnel(clearVpnState = false)
            stopSelf()
        }
    }

    /** TProxyStopService + tun kapanışı + SOCKS kapanışı; zaten kapalıysa sessiz. */
    private fun stopTunnel(clearVpnState: Boolean) {
        stopHevMonitor()
        try {
            if (tunnelUp) TProxyService.TProxyStopService()
        } catch (_: Exception) {
        }
        tunnelUp = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        ZelixRuntime.stopSocks()
        stopForegroundCompat()
        if (clearVpnState) {
            ZelixRuntime.onVpnDown()
            ZelixRuntime.pushLog("info", "Tünel kapatıldı")
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        // Sistem (ayarlar) veya başka bir VPN uygulaması bizi düşürdü.
        ZelixRuntime.pushLog("warn", "VPN izni iptal edildi; tünel kapatıldı")
        stopTunnel(clearVpnState = true)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel(clearVpnState = false)
        super.onDestroy()
    }

    /* ---------------------------------------------------------- bildirimler */

    private fun startForegroundCompat() {
        val notice = ZelixRuntime.buildNotification(this)
        // Android 14 (API 34) itibarıyla ön plan servisleri tür bildirmek
        // zorunda. VPN için ayrı bir tür olmadığından specialUse kullanılır;
        // tür ve subtype AndroidManifest.xml içinde de tanımlıdır.
        // Eski cihazlarda iki parametreli sürüm kullanılmaya devam eder.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notice,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notice)
            }
        } catch (err: Exception) {
            ZelixRuntime.pushLog(
                "error",
                "Ön plan servisi başlatılamadı: ${err.javaClass.simpleName}: ${err.message ?: "bilinmeyen"}",
            )
            throw err
        }
    }

    /**
     * SOCKS sunucusuna iki farklı soket ailesiyle bağlanmayı dener:
     * A) düz IPv4 (127.0.0.1) — çekirdek yerel tablosu,
     * B) v4-mapped IPv6 (::ffff:127.0.0.1) — hev'in kullandığı yol.
     * B başarısız olup A başarılıysa sorun doğrudan mapped yoldadır.
     * Ağ işlemi ana thread'de yasak olduğundan ayrı thread'de çalışır.
     */
    private fun socksSelfTest(port: Int) {
        Thread({
            val cases = arrayOf(
                "A (düz IPv4)" to java.net.InetAddress.getByName("127.0.0.1"),
                "B (v4-mapped IPv6)" to java.net.InetAddress.getByAddress(
                    byteArrayOf(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0xff.toByte(), 0xff.toByte(), 127, 0, 0, 1,
                    ),
                ),
            )
            for ((label, addr) in cases) {
                try {
                    val s = java.net.Socket()
                    s.soTimeout = 2000
                    s.connect(java.net.InetSocketAddress(addr, port), 2000)
                    s.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                    val r = ByteArray(2)
                    val n = s.getInputStream().read(r)
                    val ok = n >= 2 && r[0] == 0x05.toByte() && r[1] == 0x00.toByte()
                    ZelixRuntime.pushLog(
                        "info",
                        "[socks] öz-test $label: " + if (ok) "başarılı" else "yanıt yok/yanlış",
                    )
                    s.close()
                } catch (err: Throwable) {
                    ZelixRuntime.pushLog(
                        "error",
                        "[socks] öz-test $label: BAŞARISIZ — ${err.message ?: err.javaClass.simpleName}",
                    )
                }
            }
        }, "zelix-socks-selftest").apply { isDaemon = true }.start()
    }

    /* ------------------------------------------------------------ hev YAML */
    /** conf/main.yml şeması (hev-config.c) ile uyumlu; mapdns YOK —
     *  DNS sorguları SOCKS üzerinden bu uygulamanın DoH sentezine gider ve
     *  GERÇEK IP'lerle yanıtlanır (fake-IP/bootstrap döngüsü gerçekleşmez). */
    private fun writeHevConfig(socksPort: Int): File {
        val logFile = File(filesDir, "hev-tun.log")
        val yaml = StringBuilder()
        yaml.append("tunnel:\n")
        yaml.append("  name: tun0\n")
        yaml.append("  mtu: $TUN_MTU\n")
        yaml.append("  ipv4: $TUN_IPV4\n")
        yaml.append("  ipv6: '$TUN_IPV6'\n")
        yaml.append("socks5:\n")
        yaml.append("  port: $socksPort\n")
        yaml.append("  address: 127.0.0.1\n")
        yaml.append("  udp: 'udp'\n")
        yaml.append("misc:\n")
        yaml.append("  log-file: ${logFile.absolutePath}\n")
        // 'info': oturum açılışları ve offload taraması app LOG sekmesinde
        // görünür; telefonda paket akışının hangi katmanda öldüğünü ayırt
        // etmemizi sağlar (warn'da hev sağlıklıyken hiçbir şey yazmaz).
        yaml.append("  log-level: info\n")

        val out = File(filesDir, "hev-tun.yml")
        out.writeText(yaml.toString())
        return out
    }

    /* -------------------------------------------------------- hev izleyici */

    private val monitorHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var hevMonitor: Runnable? = null

    /**
     * Tünel çalışırken hev'in tun sayaçlarını izler (TProxyGetStats):
     * - giden (tx): tun'den okunan paketler — 0 kalırsa paketler tun'e hiç
     *   ulaşmıyor demektir,
     * - dönen (rx): tun'e yazılan cevap paketleri — 0 kalırsa cevap yolu
     *   kopuk demektir.
     * hev döngüsü beklenmedik çıkarsa (sessiz ölüm) hatayı görünür kılar.
     */
    private fun startHevMonitor() {
        if (hevMonitor != null) return
        var last = ""
        val task = object : Runnable {
            override fun run() {
                try {
                    if (!TProxyService.TProxyIsRunning()) {
                        ZelixRuntime.pushLog(
                            "error",
                            "[hev-s] tünel döngüsü beklenmedik şekilde çıktı (yapılandırma/kernel hatası olabilir)",
                        )
                        hevMonitor = null
                        return
                    }
                    val st = TProxyService.TProxyGetStats()
                    if (st != null && st.size >= 4) {
                        val cur = "${st[0]}/${st[1]}/${st[2]}/${st[3]}"
                        if (cur != last) {
                            last = cur
                            ZelixRuntime.pushLog(
                                "debug",
                                "[hev-s] tun giden=${st[0]} pkt/${st[1]} B • dönen=${st[2]} pkt/${st[3]} B",
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
                monitorHandler.postDelayed(this, 4000)
            }
        }
        hevMonitor = task
        monitorHandler.postDelayed(task, 2500)
    }

    private fun stopHevMonitor() {
        hevMonitor?.let { monitorHandler.removeCallbacks(it) }
        hevMonitor = null
    }
}
