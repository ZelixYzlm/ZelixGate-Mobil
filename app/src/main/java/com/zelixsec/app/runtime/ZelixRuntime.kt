package com.zelixsec.app.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zelixsec.app.R
import com.zelixsec.app.desync.DesyncProxy
import com.zelixsec.app.desync.Diagnostics
import com.zelixsec.app.socks.SocksFrontend
import com.zelixsec.app.store.Profiles
import com.zelixsec.app.store.ZelixStore
import com.zelixsec.app.update.ZelixUpdateService
import com.zelixsec.app.vpn.ZelixVpnService
import hev.htproxy.TProxyService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZelixGate - Çalışma zamanı (electron/main.js'in Android portu)
 * ============================================================
 * Electron'da ana süreç sktrolleri yapıyordu:
 *   - ayar deposu (store.js)  -> ZelixStore
 *   - parçalama motoru        -> DesyncProxy (engine/proxy.js portu)
 *   - güncelleme servisi      -> ZelixUpdateService (GitHub Releases API)
 *   - sistem vekili           -> Android'de VPN tüneli (ZelixVpnService)
 * Ara yüz IPC yerine WebView köprüsüyle konuşur (ZelixBridge).
 */

object ZelixRuntime {

    private const val MAX_LOGS = 600

    // ------------------------------------------------------------------ durum

    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var store0: ZelixStore? = null

    @Volatile
    private var proxy0: DesyncProxy? = null

    @Volatile
    private var socks0: SocksFrontend? = null

    @Volatile
    private var updater0: ZelixUpdateService? = null

    private val logs = ConcurrentLinkedQueue<JSONObject>()

    @Volatile
    private var lastStatus: JSONObject? = null

    /** WebView hazır olana kadar yayınlanan olaylar burada bekletilir. */
    private val pendingBroadcasts = ConcurrentLinkedQueue<Pair<String, String>>()

    private val webReady = AtomicBoolean(false)

    /// MainActivity bağları (kullanıcı arayüzü işlemleri)
    @Volatile
    var resultSink: ((id: String, name: String, json: String) -> Unit)? = null

    @Volatile
    var uiTask: ((action: String) -> Unit)? = null

    @Volatile
    var webSink: ((channel: String, payloadJson: String) -> Unit)? = null

    @Volatile
    var vpnUp = false
        private set

    private val ipcPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "zelix-ipc").apply { isDaemon = true }
    }

    private val updatePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zelix-update").apply { isDaemon = true }
    }

    /* ------------------------------------------------------------------ init */

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        buildChannels(context)

        val versionName = appVersion(context)

        val st = ZelixStore(context)
        store0 = st

        val engine = buildProxy()
        proxy0 = engine

        val updater = ZelixUpdateService(context, versionName)
        updater0 = updater
        updater.listener = object : ZelixUpdateService.Listener {
            override fun onUpdateStatus(status: JSONObject) {
                broadcast("update:status", status)
            }

            override fun onUpdateProgress(progress: JSONObject) {
                broadcast("update:progress", progress)
            }

            override fun onUpdateLog(level: String, message: String) {
                pushLog(level, "[güncelleme] $message")
            }
        }
        updater.installer = object : ZelixUpdateService.Installer {
            override fun installApk(file: File, downloadUrl: String) {
                main.post { openInstallIntent(context, file) }
            }
        }
        updater.configure(JSONObject().put("manifestUrl", st.get().optJSONObject("update")?.optString("manifestUrl", "") ?: ""))
        // electron main.js: store.get().update?.autoCheck !== false ise 5 sn sonra sessiz kontrol
        val autoCheck = st.get().optJSONObject("update")?.optBoolean("autoCheck", true) ?: true
        if (autoCheck) {
            main.postDelayed({
                updatePool.submit {
                    try {
                        updater.check(false)
                    } catch (_: Exception) {
                        // otomatik çekim sessizdir (electron updates.js ile aynı)
                    }
                }
            }, 5000)
        }
        pushLog("info", "ZelixGate $versionName başladı (android)")
    }

    private fun buildProxy(): DesyncProxy {
        val instance = DesyncProxy(buildEngineConfig())
        instance.onLog = { level, message, extra ->
            pushLog(level, message, extra)
        }
        instance.onSplit = { info ->
            broadcast("engine:split", info)
            val sni = info.optString("sni")
            if (sni.isNotEmpty()) {
                pushLog(
                    "debug",
                    "Bölündü: $sni @${info.optInt("offset")} (${info.optInt("first")}+${info.optInt("second")} bayt)",
                )
            }
        }
        return instance
    }

    /* ------------------------------------------------------- yardımcı erişim */

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("runtime başlatılmadı")

    private fun store(): ZelixStore = store0 ?: throw IllegalStateException("runtime başlatılmadı")

    private fun proxy(): DesyncProxy = proxy0 ?: throw IllegalStateException("runtime başlatılmadı")

    private fun updater(): ZelixUpdateService =
        updater0 ?: throw IllegalStateException("runtime başlatılmadı")

    fun appVersion(context: Context): String {
        // Öncelik: derleme anındaki sabit (PackageManager'a bağlı değil; cihazda
        // "0.0.0" gösterme ihtimalini tamamen ortadan kaldırır).
        try {
            val v = com.zelixsec.app.BuildConfig.VERSION_NAME
            if (!v.isNullOrBlank()) return v
        } catch (_: Throwable) {
        }
        // Yedek: kurulu paket bilgisinden oku.
        return try {
            var name = ""
            if (Build.VERSION.SDK_INT >= 33) {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                name = info.versionName ?: "0.0.0"
            } else {
                @Suppress("DEPRECATION")
                name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            }
            if (name.isEmpty()) "0.0.0" else name
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    /* ------------------------------------------------------------------- log */

    fun pushLog(
        level: String,
        message: String,
        extra: JSONObject? = null,
    ): JSONObject {
        val entry = extra ?: JSONObject()
        entry.put("level", level)
        entry.put("message", message)
        entry.put("at", System.currentTimeMillis())
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
        broadcast("engine:log", entry)
        return entry
    }

    /* ------------------------------------------------------------ yayınlar */

    fun broadcast(channel: String, payload: JSONObject) {
        val json = payload.toString()
        if (webReady.get()) {
            main.post {
                webSink?.invoke(channel, json)
            }
        } else {
            pendingBroadcasts.add(channel to json)
        }
    }

    fun markWebReady() {
        if (!webReady.compareAndSet(false, true)) return
        main.post {
            val sink = webSink ?: return@post
            while (true) {
                val item = pendingBroadcasts.poll() ?: break
                sink.invoke(item.first, item.second)
            }
        }
    }

    /* ------------------------------------------------------------- motor */

    fun currentAllowlist(): List<String> {
        val data = store().get()
        return store().allowlistFor(data.optString("activeProfile"), data.opt("customHosts"))
    }

    private fun buildEngineConfig(): JSONObject {
        val data = store().get()
        return JSONObject()
            .put("port", data.optJSONObject("engine")?.optInt("port", 7717) ?: 7717)
            .put("mode", data.optJSONObject("engine")?.optString("mode") ?: "split2")
            .put(
                "splitPosition",
                data.optJSONObject("engine")?.optInt("splitPosition", 1) ?: 1,
            )
            .put(
                "splitDelayMs",
                data.optJSONObject("engine")?.optInt("splitDelayMs", 0) ?: 0,
            )
            .put("allowlist", JSONArray(currentAllowlist()))
            .put("adblock", data.optJSONObject("adblock") ?: JSONObject())
            .put("doh", data.optJSONObject("doh") ?: JSONObject())
            .put("upstream", data.optJSONObject("upstream") ?: JSONObject())
    }

    /** electron main.js startEngine() birebir. */
    fun startEngine(): JSONObject = synchronized(engineLock) {
        proxy().configure(buildEngineConfig())
        val status = proxy().start()
        lastStatus = status
        pushLog("info", "Motor hazır → 127.0.0.1:${status.optInt("port")} (${proxy().mode()})")
        vpnEnable(false)
        startStatusTicker()
        broadcast("engine:status", status)
        broadcast("settings:changed", store().get())
        return status
    }

    /** electron main.js stopEngine() birebir. */
    fun stopEngine(): JSONObject = synchronized(engineLock) {
        stopStatusTicker()
        if (vpnUp) {
            stopVpn()
        }
        val status = proxy().stop()
        lastStatus = status
        broadcast("engine:status", status)
        broadcast("settings:changed", store().get())
        return status
    }

    private val engineLock = Any()

    /** Tünel kurulumundan motoru garanti eder; hata durumunda istisna fırlatır. */
    fun ensureEngine() = synchronized(engineLock) {
        if (!proxy().running) {
            vpnEnableQuietlyIfNeeded()
            proxy().configure(buildEngineConfig())
            val status = proxy().start()
            lastStatus = status
            pushLog("info", "Motor hazır → 127.0.0.1:${status.optInt("port")} (${proxy().mode()})")
            startStatusTicker()
        }
    }

    private fun vpnEnableQuietlyIfNeeded() {
        // Masaüstü: startEngine() -> autoSystemProxy açıkken sistem vekili açılır.
        try {
            vpnEnable(true)
        } catch (_: Exception) {
        }
    }

    private fun vpnEnable(invokeIfAlready: Boolean) {
        val app = store().get().optJSONObject("app") ?: JSONObject()
        val auto = app.optBoolean("autoSystemProxy", true)
        if (!auto && !invokeIfAlready) return
        requestVpn()
    }

    private fun requestVpn() {
        if (vpnUp) return
        main.post {
            uiTask?.invoke("vpn-consent")
        }
    }

    /* ------------------------------------------------------------ SOCKS5 */

    fun ensureSocks(): Int {
        val engine = proxy()
        var frontend = socks0
        if (frontend == null) {
            frontend = SocksFrontend(engine) { level, message ->
                pushLog(level, message)
            }
            socks0 = frontend
        }
        return frontend.start()
    }

    fun stopSocks() {
        socks0?.stop()
        socks0 = null
    }

    /* ------------------------------------------- canlı durum + hev izleyici */

    private var statusTicker: Runnable? = null
    private var hevLogTailer: Runnable? = null

    /** Motor çalışırken istatistikleri ~1.5 sn'de bir tazeler (masaüstü gibi). */
    fun startStatusTicker() {
        if (statusTicker != null) return
        val tick = object : Runnable {
            override fun run() {
                val proxy = proxy0
                if (proxy == null || !proxy.running) {
                    statusTicker = null
                    return
                }
                broadcast("engine:status", proxy.getStatus())
                main.postDelayed(this, 1500)
            }
        }
        statusTicker = tick
        main.postDelayed(tick, 1250)
    }

    private fun stopStatusTicker() {
        statusTicker?.let { main.removeCallbacks(it) }
        statusTicker = null
    }

    /**
     * hev tünelinin (filesDir/hev-tun.log) yeni satırlarını uygulama günlüğüne
     * aktarır; telefonda hata teşhisini doğrudan kullanıcıya iletir.
     */
    fun startHevLogTailer(context: Context) {
        if (hevLogTailer != null) return
        val logFile = File(context.filesDir, "hev-tun.log")
        var offset = if (logFile.exists()) logFile.length() else 0L
        val tail = object : Runnable {
            override fun run() {
                if (!vpnUp) {
                    hevLogTailer = null
                    return
                }
                try {
                    if (logFile.exists()) {
                        val len = logFile.length()
                        if (len < offset) offset = 0L // dosya yeniden yazılmış
                        if (len > offset) {
                            java.io.RandomAccessFile(logFile, "r").use { raf ->
                                raf.seek(offset)
                                val data = ByteArray((len - offset).toInt())
                                raf.readFully(data)
                                offset = len
                                String(data, Charsets.UTF_8)
                                    .split('\n')
                                    .filter { it.isNotBlank() }
                                    .forEach { pushLog("debug", "[hev] $it") }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                main.postDelayed(this, 2000)
            }
        }
        hevLogTailer = tail
        main.postDelayed(tail, 2000)
    }

    private fun stopHevLogTailer() {
        hevLogTailer?.let { main.removeCallbacks(it) }
        hevLogTailer = null
    }

    /* ------------------------------------------------------------ VPN bakı */

    fun onVpnUp() {
        vpnUp = true
        startStatusTicker()
        startHevLogTailer(ctx())
        store().patch(
            JSONObject().put(
                "systemProxy",
                JSONObject()
                    .put("enabled", true)
                    .put("lastApplied", ZelixUpdateService.isoNow()),
            ),
        )
        pushLog("info", "Sistem vekili açıldı (tek tuş modu)")
        broadcast("settings:changed", store().get())
    }

    fun onVpnDown() {
        vpnUp = false
        stopStatusTicker()
        stopHevLogTailer()
        store().patch(
            JSONObject().put(
                "systemProxy",
                JSONObject()
                    .put("enabled", false)
                    .put("lastApplied", ZelixUpdateService.isoNow()),
            ),
        )
        broadcast("settings:changed", store().get())
    }

    /**
     * Wi-Fi ↔ mobil veri geçişinde çağrılır (ZelixVpnService ağ izleyicisi).
     * Eski ağa ait DNS cevapları yeni ağda ölü IP'ler döndürebildiği için
     * önbellek temizlenir; DoH geri çekilmesi de sıfırlanır.
     */
    fun onUnderlyingNetworkChanged() {
        try {
            proxy0?.onNetworkChanged()
        } catch (_: Exception) {
        }
        pushLog("info", "Ağ değişti; DNS önbelleği temizlendi")
    }

    /** MainActivity onActivityResult sonucu. */
    fun onVpnConsent(granted: Boolean) {
        if (granted) return
        if (!vpnUp) {
            pushLog("warn", "Sistem vekili açılamadı: kullanıcı VPN izni vermedi")
            store().patch(JSONObject().put("systemProxy", JSONObject().put("enabled", false)))
            broadcast("settings:changed", store().get())
        }
    }

    fun vpnStatus(): JSONObject = JSONObject()
        .put("applied", vpnUp)
        .put("platform", "android")
        .put("backup", JSONObject.NULL)

    /* --------------------------------------------------------- pil durumu */

    /**
     * Pil optimizasyonu açıkken Android uygulamayı arka planda uyutabilir;
     * VPN tüneli bu durumda kopabilir. Arayüz bu bilgiye göre uyarı kutusu
     * gösterir ve kullanıcıyı doğrudan muafiyet ekranına yönlendirir.
     */
    fun batteryStatus(): JSONObject {
        return try {
            val power = ctx().getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = power.isIgnoringBatteryOptimizations(ctx().packageName)
            JSONObject()
                .put("ignoring", ignoring)
                .put("optimized", !ignoring)
                .put("manufacturer", Build.MANUFACTURER ?: "")
        } catch (err: Exception) {
            // Durum okunamazsa kullanıcıyı gereksiz uyarıyla rahatsız etme.
            JSONObject()
                .put("ignoring", true)
                .put("optimized", false)
                .put("error", err.message ?: "bilinmeyen")
        }
    }

    @SuppressLint("BatteryLife")
    private fun invokeBatterySettings(): JSONObject {
        val packageUri = Uri.parse("package:${ctx().packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx().startActivity(intent)
                return JSONObject().put("ok", true)
            } catch (_: Exception) {
                // Bu cihazda desteklenmiyorsa sıradaki ekranı dene.
            }
        }
        pushLog("warn", "Pil optimizasyonu ayarları açılamadı")
        return JSONObject().put("ok", false).put("error", "Pil ayarları açılamadı")
    }

    /* --------------------------------------------------------- bildirimler */

    fun buildNotification(context: Context): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, ZelixVpnService::class.java).setAction(ZelixVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action(0, "Durdur", stop)
        return Notification.Builder(context, ZelixVpnService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zelix)
            .setContentTitle("ZelixGate aktif")
            .setContentText("Güvenli tünelleme çalışıyor — trafik parçalanarak iletiliyor")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action)
            .build()
    }

    private fun buildChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ZelixVpnService.CHANNEL_ID,
            "ZelixGate tünelleme servisi",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /* ------------------------------------------------------------- güncelleme */

    private fun openInstallIntent(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "com.zelixsec.app.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.startActivity(intent)
        } catch (err: Exception) {
            pushLog("error", "Kurulum dosyası açılamadı: ${err.message ?: "bilinmeyen"}")
        }
    }

    /* ---------------------------------------------------------- anlık görünür */

    fun snapshot(): JSONObject {
        val data = store().get()
        return JSONObject()
            .put("settings", data)
            .put("profiles", Profiles.asJsonArray())
            .put("allowlist", JSONArray(currentAllowlist()))
            .put("status", lastStatus ?: proxy().getStatus())
            .put("systemProxy", vpnStatus())
            .put("battery", batteryStatus())
            .put("platform", "android")
            .put("version", appVersion(ctx()))
            .put("update", updater().getStatus())
            .put("logs", JSONArray(logs.toList()))
    }

    /* ----------------------------------------------------------- VPN kapatma */

    fun stopVpn() {
        vpnUp = false
        try {
            val intent = Intent(ctx(), ZelixVpnService::class.java).setAction(ZelixVpnService.ACTION_STOP)
            ctx().startService(intent)
        } catch (_: Exception) {
            // Hizmet arka planda başlatılamazsa doğrudan JNI düzeyinde kapat.
            try {
                TProxyService.TProxyStopService()
            } catch (_: Exception) {
            }
        }
    }

    /** app:quit — her şey kapatılır (electron before-quit karşılığı). */
    fun shutdownAll() {
        runWithPool {
            try {
                stopVpn()
            } catch (_: Exception) {
            }
            try {
                stopSocks()
            } catch (_: Exception) {
            }
            try {
                proxy().stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun runWithPool(block: () -> Unit) {
        ipcPool.submit(block)
    }

    /* ------------------------------------------------------------------ IPC */

    fun dispatch(id: String, name: String, argsJson: String?) {
        val args: JSONObject = try {
            if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val pool = if (name == "update:check" || name == "update:install") updatePool else ipcPool
        pool.submit {
            val result = try {
                invoke(name, args)
            } catch (err: Exception) {
                JSONObject()
                    .put("ok", false)
                    .put("error", err.message ?: "bilinmeyen hata")
            }
            main.post {
                val sink = resultSink
                if (sink != null) {
                    sink(id, name, result.toString())
                } else {
                    pushLog("warn", "Köprü sonucu iletilemedi (arayüz kapalı): $name")
                }
                when (name) {
                    "app:quit" -> uiTask?.invoke("app:quit")
                    "window:hide" -> uiTask?.invoke("window:hide")
                }
            }
        }
    }

    /** IPC işleyicileri (isimler electron/main.js registerIpc ile aynı). */
    fun invoke(name: String, args: JSONObject): JSONObject {
        // web:ready, köprü kurulduğunda renderer'ın bildirimini alır.
        if (name == "web:ready") {
            markWebReady()
            return JSONObject().put("ok", true)
        }
        return when (name) {
            "state:get" -> snapshot()
            "engine:start" -> invokeEngineStart()
            "engine:stop" -> invokeEngineStop()
            "engine:toggle" -> invokeEngineToggle(args)
            "settings:patch" -> invokeSettingsPatch(args)
            "profile:set" -> invokeProfileSet(args)
            "system-proxy:set" -> invokeSystemProxySet(args)
            "diagnostics:run" -> invokeDiagnostics(args)
            "logs:clear" -> invokeLogsClear()
            "logs:export" -> invokeLogsExport()
            "dns:flush" -> invokeDnsFlush()
            "clipboard:copy" -> invokeClipboard(args)
            "open:external" -> invokeOpenExternal(args)
            "battery:settings" -> invokeBatterySettings()
            "update:check" -> invokeUpdateCheck()
            "update:install" -> invokeUpdateInstall()
            "adblock:clear" -> invokeAdblockClear()
            "adblock:test" -> invokeAdblockTest()
            "app:quit" -> invokeAppQuit()
            "window:hide" -> JSONObject().put("ok", true)
            else ->
                throw Exception("bilinmeyen komut: $name")
        }
    }

    /* -------------------------------------------------------- manipülasyonlar */

    private fun invokeEngineStart(): JSONObject {
        return try {
            JSONObject().put("ok", true).put("status", startEngine())
        } catch (err: Exception) {
            pushLog("error", "Başlatma hatası: ${err.message ?: "bilinmeyen"}")
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeEngineStop(): JSONObject {
        return try {
            JSONObject().put("ok", true).put("status", stopEngine())
        } catch (err: Exception) {
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeEngineToggle(args: JSONObject): JSONObject {
        val running = args.optBoolean("running", true)
        return try {
            val status = if (running) startEngine() else stopEngine()
            JSONObject().put("ok", true).put("status", status)
        } catch (err: Exception) {
            pushLog("error", err.message ?: "bilinmeyen")
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeSettingsPatch(args: JSONObject): JSONObject {
        val partial = args.optJSONObject("partial") ?: JSONObject()
        val before = store().get()
        val data = store().patch(partial)

        // Motor ayarları canlı uygulanır; port değiştiyse yeniden başlatılır.
        if (proxy().running) {
            val partialPort = partial.optJSONObject("engine")?.optLong("port", 0) ?: 0
            val beforePort = before.optJSONObject("engine")?.optLong("port", 0) ?: 0
            val portChanged = partialPort != 0L && partialPort != beforePort
            proxy().configure(buildEngineConfig())
            if (portChanged) {
                stopEngine()
                startEngine()
            } else if (vpnUp) {
                pushLog("info", "Ayarlar güncellendi; tünel sürüyor")
            }
            broadcast("engine:status", proxy().getStatus())
        }

        // startWithLogin (Android karşılığı): yeniden başlatmada önyükleme alıcısına bağlı.
        if (partial.has("update") && partial.optJSONObject("update")?.has("manifestUrl") == true) {
            updater().configure(partial.optJSONObject("update"))
        }

        broadcast("settings:changed", data)
        return JSONObject()
            .put("ok", true)
            .put("settings", data)
            .put("allowlist", JSONArray(currentAllowlist()))
    }

    private fun invokeProfileSet(args: JSONObject): JSONObject {
        val data = store().patch(JSONObject().put("activeProfile", args.optString("profileId")))
        proxy().configure(JSONObject().put("allowlist", JSONArray(currentAllowlist())))
        broadcast("engine:status", proxy().getStatus())
        broadcast("settings:changed", data)
        return JSONObject().put("ok", true).put("allowlist", JSONArray(currentAllowlist()))
    }

    private fun invokeSystemProxySet(args: JSONObject): JSONObject {
        val enabled = args.optBoolean("enabled", false)
        return try {
            if (enabled) {
                if (!proxy().running) {
                    startEngineQuiet()
                }
                if (!vpnUp) {
                    // VPN izni (consent) MainActivity üzerinden istenir;
                    // izin gelirse ZelixVpnService tüneli kurar, onVpnUp() tetiklenir.
                    requestVpn()
                    pushLog("info", "VPN izni bekleniyor...")
                }
            } else {
                store().patch(
                    JSONObject().put(
                        "systemProxy",
                        JSONObject()
                            .put("enabled", false)
                            .put("lastApplied", ZelixUpdateService.isoNow()),
                    ),
                )
                stopVpn()
                pushLog("info", "Sistem vekili kapatıldı")
            }
            JSONObject().put("ok", true).put("systemProxy", vpnStatus())
        } catch (err: Exception) {
            pushLog("error", "Sistem vekili: ${err.message ?: "bilinmeyen"}")
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun startEngineQuiet(): JSONObject {
        return synchronized(engineLock) {
            proxy().configure(buildEngineConfig())
            val status = proxy().start()
            lastStatus = status
            pushLog("info", "Motor hazır → 127.0.0.1:${status.optInt("port")} (${proxy().mode()})")
            if (vpnUp) broadcast("engine:status", status)
            startStatusTicker()
            broadcast("settings:changed", store().get())
            status
        }
    }

    private fun invokeDiagnostics(args: JSONObject): JSONObject {
        val host = args.optString("host")
        return try {
            if (!proxy().running) {
                synchronized(engineLock) {
                    if (!proxy().running) {
                        startEngineQuiet()
                    }
                }
            }
            val port = proxy().getStatus().optInt("port")
            val doh = store().get().optJSONObject("doh") ?: JSONObject()
            pushLog("info", "Teşhis başladı: $host")
            val report = Diagnostics.runDiagnostics(
                JSONObject()
                    .put("host", host)
                    .put("proxyPort", port)
                    .put("doh", doh),
            )
            pushLog(
                if (report.optString("verdict") == "bypassed") "info" else "warn",
                "Teşhis sonucu: ${report.optString("verdict")} (doğrudan ${
                    report.optJSONObject("direct")?.optBoolean("ok", false) ?: false
                }, ZelixGate ${report.optJSONObject("secured")?.optBoolean("ok", false) ?: false})",
            )
            JSONObject().put("ok", true).put("report", report)
        } catch (err: Exception) {
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeLogsClear(): JSONObject {
        logs.clear()
        return JSONObject().put("ok", true)
    }

    private fun invokeLogsExport(): JSONObject {
        return try {
            val iso = ZelixUpdateService.isoNow().take(19).replace(Regex("[:T]"), "-")
            val dir =
                ctx().getExternalFilesDir("exports")
                    ?: File(ctx().filesDir, "exports")
            dir.mkdirs()
            val out = File(dir, "zelixgate-log-$iso.txt")
            val text = logs.joinToString("\n") { entry ->
                val at = isoAt(entry.optLong("at"))
                "[${at}] ${entry.optString("level").uppercase()} ${entry.optString("message")}"
            }
            out.writeText(text)
            JSONObject().put("ok", true).put("path", out.absolutePath)
        } catch (_: Exception) {
            JSONObject().put("ok", false)
        }
    }

    private fun invokeDnsFlush(): JSONObject {
        return try {
            proxy().flushDnsCache()
            pushLog("info", "DNS önbelleği temizlendi")
            JSONObject().put("ok", true)
        } catch (_: Exception) {
            pushLog("warn", "DNS temizliği başarısız")
            JSONObject().put("ok", false)
        }
    }

    private fun invokeClipboard(args: JSONObject): JSONObject {
        val text = args.optString("text")
        main.post {
            try {
                val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ZelixGate", text))
            } catch (_: Exception) {
            }
        }
        return JSONObject().put("ok", true)
    }

    private fun invokeOpenExternal(args: JSONObject): JSONObject {
        val url = args.optString("url")
        if (Regex("^https?://").containsMatchIn(url)) {
            main.post {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx().startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
        return JSONObject().put("ok", true)
    }

    private fun invokeUpdateCheck(): JSONObject {
        return try {
            val status = updater().check(true)
            store().patch(
                JSONObject().put("update", JSONObject().put("lastCheck", ZelixUpdateService.isoNow())),
            )
            JSONObject().put("ok", true).put("status", status)
        } catch (err: Exception) {
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    /**
     * electron main.js update:install birebir: up-to-date -> hata,
     * 'error' + sürüm yoksa taze kontrol, sonra (gerekirse) indir -> paket kurucu.
     */
    private fun invokeUpdateInstall(): JSONObject {
        return try {
            val updater = updater()
            val status = updater.getStatus()
            val phaseNow = status.optString("phase")
            val latestNow = status.optString("latest", "")

            if (phaseNow == "up-to-date") {
                return JSONObject().put("ok", false).put("error", "Zaten en güncel sürümdesiniz")
            }
            if (phaseNow == "error" && latestNow.isEmpty()) {
                val fresh = updater.check(true)
                if (fresh.optString("phase") == "up-to-date") {
                    return JSONObject().put("ok", false).put("error", "Zaten en güncel sürümdesiniz")
                }
            }
            if (updater.getStatus().optString("phase") != "ready") {
                updater.downloadAndPrepare()
            }
            broadcast("update:status", updater.getStatus())
            pushLog(
                "info",
                "Güncelleme kurulumu başlatıldı (${updater.getStatus().optString("latest")})",
            )
            updater.install()
            JSONObject().put("ok", true).put("status", updater.getStatus())
        } catch (err: Exception) {
            pushLog("error", "Güncelleme hatası: ${err.message ?: "bilinmeyen"}")
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeAdblockClear(): JSONObject {
        proxy().clearAdBlockStats()
        broadcast("engine:status", proxy().getStatus())
        return JSONObject().put("ok", true)
    }

    private fun invokeAdblockTest(): JSONObject {
        return try {
            synchronized(engineLock) {
                proxy().configure(buildEngineConfig())
                if (!proxy().running) {
                    startEngineQuiet()
                }
            }
            val port = proxy().getStatus().optInt("port")
            var blocked = false
            var detail = ""
            try {
                Diagnostics.tlsThroughProxy(port, "doubleclick.net", 6000)
                detail = "CONNECT onaylandı"
            } catch (err: Exception) {
                blocked = true
                detail = err.message ?: "bilinmeyen"
            }
            pushLog(
                if (blocked) "info" else "warn",
                if (blocked) "Reklam engelleyici testi: OK ($detail)" else "Reklam engelleyici testi: ENGELLEMEYOR ($detail)",
            )
            JSONObject().put("ok", true).put("blocked", blocked).put("detail", detail)
        } catch (err: Exception) {
            JSONObject().put("ok", false).put("error", err.message ?: "bilinmeyen")
        }
    }

    private fun invokeAppQuit(): JSONObject {
        shutdownAll()
        return JSONObject().put("ok", true)
    }

    /* ---------------------------------------------------------- yardımcılar */

    private fun isoAt(time: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(time))
    }
}
