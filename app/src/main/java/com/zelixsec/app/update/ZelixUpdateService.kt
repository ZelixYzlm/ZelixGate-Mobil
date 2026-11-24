package com.zelixsec.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * ZelixGate - Güncelleme servisi (Android, GitHub Releases)
 * -------------------------------------------------------
 * ZelixYzlm/ZelixGate-Mobil deposundaki en son GitHub Release'i okur:
 * yeni sürüm var mı diye bakar; kullanıcı "Güncelle" butonuna bastığında
 * APK'yı indirir ve paket kurucuya verir (kurulum HER ZAMAN manuel).
 *
 * Release biçimi:
 *   tag_name:      "v1.0.3"        -> sürüm numarası (v öneki yok edilir)
 *   name:          başlık
 *   body:          sürüm notları
 *   assets[]:      name ".apk" ile biten ilk dosya indirilir
 *   published_at:  tarih
 *
 * Kullanıcı ayarlarda manifestUrl'i düz bir update.json'a çevirirse
 * o biçim desteklenir:
 *   { "latest": "1.0.3", "notes": "...", "url": "...apk", "sha256": "...", "publishedAt": "..." }
 *
 * Fazlar (phases) masaüstüyle aynıdır:
 *   idle | checking | up-to-date | available | error | downloading | ready | installing
 */
class ZelixUpdateService(
    private val context: Context,
    currentVersion: String,
    manifestUrl: String = RELEASES_URL,
) {
    interface Listener {
        fun onUpdateStatus(status: JSONObject)
        fun onUpdateProgress(progress: JSONObject)
        fun onUpdateLog(level: String, message: String)
    }

    interface Installer {
        /** İndirilmiş APK'yı paket kurucuyla açar; kurulum ekranı açıldıysa true döner. */
        fun installApk(file: File, downloadUrl: String): Boolean
    }

    var installer: Installer? = null
    var listener: Listener? = null

    /**
     * Motor çalışırken yerel SOCKS5 bağlantı noktasını döner. Doğrudan indirme
     * başarısız olursa (bazı ağlarda release-assets.githubusercontent.com
     * engellenebiliyor) indirme bu tünel üzerinden yeniden denenir.
     */
    var proxyPortProvider: (() -> Int?)? = null

    private var currentVersion: String = currentVersion
    var manifestUrl: String = manifestUrl.trim().ifEmpty { RELEASES_URL }
        private set

    private var phase = "idle"
    private var error: String? = null
    private var latest: String? = null
    private var notes = ""
    private var downloadUrl: String? = null
    private var expectedSha256: String? = null
    private var expectedTotal: Long = 0L
    private var releaseUrl: String? = null
    private var publishedAt: String? = null
    private var checkedAt: String? = null
    private var progress = JSONObject().put("received", 0).put("total", 0).put("percent", 0)
    private var file: File? = null

    fun configure(configured: JSONObject?) {
        val url = configured?.optString("manifestUrl", "") ?: ""
        manifestUrl = url.trim().ifEmpty { RELEASES_URL }
    }

    @Synchronized
    fun getStatus(): JSONObject {
        return JSONObject()
            .put("phase", phase)
            .put("currentVersion", currentVersion)
            .put("latest", latest ?: JSONObject.NULL)
            .put("notes", notes)
            .put("downloadUrl", downloadUrl ?: JSONObject.NULL)
            .put("releaseUrl", releaseUrl ?: JSONObject.NULL)
            .put("manifestUrl", manifestUrl)
            .put("checkedAt", checkedAt ?: JSONObject.NULL)
            .put("publishedAt", publishedAt ?: JSONObject.NULL)
            .put("progress", JSONObject(progress.toString()))
            .put("file", file?.absolutePath ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }

    private fun publish() {
        listener?.onUpdateStatus(getStatus())
    }

    private fun say(message: String, level: String = "info") {
        listener?.onUpdateLog(level, message)
    }

    /**
     * Sunucudaki release/manifest'e bakar; manual ise hata fırlatır,
     * otomatik çekim sessizdir (electron/updates.js check() ile aynı davranış).
     */
    @Synchronized
    fun check(manual: Boolean = false): JSONObject {
        phase = "checking"
        error = null
        publish()
        try {
            val manifest = fetchSource(manifestUrl)
            latest = manifest.latest
            notes = manifest.notes
            downloadUrl = manifest.downloadUrl
            expectedSha256 = manifest.sha256?.takeIf { it.isNotEmpty() }
            expectedTotal = manifest.assetSize
            releaseUrl = manifest.releaseUrl
            publishedAt = manifest.publishedAt?.takeIf { it.isNotEmpty() }
            checkedAt = isoNow()

            val candidate = latest ?: throw Exception("manifest: sürüm alanı (latest) eksik")
            if (isNewer(candidate, currentVersion)) {
                phase = "available"
                say("Yeni sürüm tespit edildi: $candidate (kurulu: $currentVersion)")
            } else {
                phase = "up-to-date"
                say("Sürüm güncel: $currentVersion")
            }
        } catch (err: Exception) {
            val msg = err.message ?: "bilinmeyen hata"
            val noRelease = msg.contains("HTTP 404") && manifestUrl.contains("api.github.com")
            if (noRelease) {
                // GitHub /releases/latest: yayımlanmış release yokken 404 döner.
                // Bu bir hata değil, "henüz sürüm yayınlanmamış" durumudur;
                // masaüstü UX'iyle uyumlu şekilde güncel kabul edilir.
                checkedAt = isoNow()
                latest = null
                notes = ""
                downloadUrl = null
                expectedTotal = 0L
                releaseUrl = null
                phase = "up-to-date"
                say("GitHub'da yayınlanmış sürüm yok; sürüm güncel kabul edildi", "debug")
            } else {
                phase = "error"
                error = msg
                say("Manifest okunamadı: $error", "warn")
                if (manual) throw err
            }
        }
        val status = getStatus()
        publish()
        return status
    }

    /** Kurulum dosyasını indirip "ready" fazına geçer. */
    @Synchronized
    fun downloadAndPrepare(): JSONObject {
        if (phase != "available") throw Exception("indirilecek güncelleme yok")
        val url = downloadUrl ?: throw Exception("release'de APK dosyası yok")
        phase = "downloading"
        error = null
        progress = JSONObject()
            .put("received", 0)
            .put("total", expectedTotal)
            .put("percent", 0)
        file = null
        publish()

        // APK'yı uygulamanın iç deposuna indir. Dış depodaki
        // Android/data/... yolu bazı cihazlarda paket kurucu tarafından
        // okunamıyor; iç depo + FileProvider en güvenilir yoldur.
        val updateDir = File(context.filesDir, "update")
        updateDir.mkdirs()
        val dest = File(updateDir, "ZelixGate-Setup-${sanitizeName(latest ?: "guncelleme")}.apk")
        try {
            if (dest.exists()) dest.delete()
        } catch (_: Exception) {
        }
        try {
            val bytes = downloadFile(url, dest, expectedSha256)
            verifyApk(dest)
            file = dest
            phase = "ready"
            say("Kurulum indirildi (${String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0)} MB)")
        } catch (err: Exception) {
            phase = "error"
            error = err.message ?: "bilinmeyen hata"
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            say("İndirme başarısız: $error", "error")
        }
        val status = getStatus()
        publish()
        if (phase == "error") throw Exception(error)
        return status
    }

    /**
     * İndirilen APK'yı paket kurucuya verir. Android'de NSIS yoktur;
     * kurulum kullanıcı onayını paket kurucu üzerinden yapar.
     */
    @Synchronized
    fun install(): JSONObject {
        if (phase == "available") downloadAndPrepare()
        val apk = file ?: throw Exception("indirilmiş kurulum dosyası yok")
        if (phase != "ready") throw Exception("indirilmiş kurulum dosyası yok")
        if (!apk.isFile || apk.length() <= 0L) {
            phase = "error"
            error = "kurulum dosyası bulunamadı veya boş"
            publish()
            throw Exception(error)
        }

        say("Kurulum ekranı açılıyor")
        val opened = installer?.installApk(apk, downloadUrl ?: "") ?: false
        if (!opened) {
            // Örn. Android 8+ "Bilinmeyen uygulamalar" izni yok. Fazı ready
            // bırak ki kullanıcı izni verdikten sonra tekrar deneyebilsin.
            phase = "ready"
            error = "Kurulum ekranı açılamadı. 'Bilinmeyen uygulamalar' iznini verip tekrar deneyin."
            publish()
            throw Exception(error)
        }
        phase = "installing"
        error = null
        publish()
        return getStatus().put("file", apk.absolutePath)
    }

    /**
     * Kurulum ekranı açıldıktan sonra kullanıcı iptal edip uygulamaya
     * dönerse faz "installing"de takılı kalmasın; tekrar denenebilsin.
     */
    @Synchronized
    fun resetInstallPhase() {
        if (phase != "installing") return
        phase = "ready"
        error = null
        publish()
    }

    /* ------------------------------------------------------------ kaynak seçim */

    private class Source(
        val latest: String?,
        val notes: String,
        val downloadUrl: String?,
        val sha256: String?,
        val publishedAt: String?,
        val assetSize: Long,
        val releaseUrl: String?,
    )

    /** GitHub Releases API ya da klasik update.json'dan normalize edilmiş bilgi. */
    private fun fetchSource(urlString: String): Source {
        val root = JSONObject(String(readJson(urlString), Charsets.UTF_8))

        val isRelease = urlString.contains("api.github.com") || urlString.endsWith("/releases")
        if (isRelease) {
            val tag = root.optString("tag_name", "").ifEmpty { root.optString("name", "") }.trim()
            val assets = root.optJSONArray("assets") ?: JSONArray()
            var assetUrl: String? = null
            var assetSize = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    assetUrl = asset.optString("browser_download_url", "")
                    assetSize = asset.optLong("size", 0)
                    break
                }
            }
            if (tag.isNotEmpty() && assetUrl == null) {
                say("Release bulundu ama APK dosyası yok (tag: $tag)", "warn")
            }
            return Source(
                latest = tag.ifEmpty { null },
                notes = root.optString("body", "").ifEmpty { root.optString("notes", "") },
                downloadUrl = assetUrl?.takeIf { it.isNotEmpty() },
                sha256 = null,
                publishedAt = root.optString("published_at", "").trim(),
                assetSize = assetSize,
                releaseUrl = root.optString("html_url", "").trim().ifEmpty { null },
            )
        }

        // Klasik update.json biçimi
        val latest = root.optString("latest", "")
            .ifEmpty { root.optString("version", "").ifEmpty { root.optString("tag", "") } }
            .trim()
        val download = root.optString("url", "")
            .ifEmpty { root.optString("download", "").ifEmpty { root.optString("installer", "") } }
            .takeIf { it.isNotEmpty() }
        return Source(
            latest = latest.ifEmpty { null },
            notes = root.optString("notes", "").ifEmpty { root.optString("description", "") },
            downloadUrl = download,
            sha256 = root.optString("sha256", "").ifEmpty { null },
            publishedAt = root.optString("publishedAt", "").ifEmpty { root.optString("date", "") },
            assetSize = root.optLong("size", 0L),
            releaseUrl = root.optString("releaseUrl", "")
                .ifEmpty { root.optString("page", "") }
                .ifEmpty { null },
        )
    }

    /* ------------------------------------------------------------ http yardımcı */

    /** Çoklu yönlendirmeyi elle takip eder (en fazla 5); JSON gövdesi döner. */
    private fun readJson(urlString: String): ByteArray {
        val san = urlString.ifEmpty { RELEASES_URL }
        val bytes = openStreamBounded(san, MAX_JSON_BYTES)
        if (bytes.isEmpty()) throw Exception("manifest JSON değil")
        return bytes
    }

    private fun openStreamBounded(urlString: String, limit: Int): ByteArray {
        var current = urlString
        var count = 0
        while (true) {
            if (count > MAX_REDIRECTS) throw Exception("çok fazla yönlendirme")
            val u = URL(current)
            if (u.protocol != "https") throw Exception("yalnızca https bağlantısı desteklenir")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            // GitHub API zorunlu tutar (Unauthorized) -> uygulama adıyla
            conn.setRequestProperty("user-agent", "ZelixGate-Update/1.0")
            if (current.contains("api.github.com")) {
                conn.setRequestProperty("accept", "application/vnd.github+json")
            } else {
                conn.setRequestProperty("accept", "*/*")
            }
            val code = try {
                conn.connect()
                conn.responseCode
            } catch (err: IOException) {
                disconnectQuiet(conn)
                throw Exception("bağlantı hatası: ${err.message ?: "ağ"}")
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("location")
                disconnectQuiet(conn)
                if (location.isNullOrEmpty()) throw Exception("yönlendirme adresi yok")
                current = URL(u, location).toString()
                count++
                continue
            }
            if (code != 200) {
                disconnectQuiet(conn)
                throw Exception("manifest HTTP $code")
            }
            try {
                return readBodyBounded(conn.inputStream, limit)
            } catch (err: IOException) {
                throw Exception("manifest okunamadı: ${err.message ?: "ağ"}")
            } finally {
                disconnectQuiet(conn)
            }
        }
    }

    /** İndirilen APK'nın paket adı ve imzası kurulu uygulamayla uyumlu mu? */
    private fun verifyApk(apk: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw Exception("indirilen dosya geçerli bir APK değil")
        if (archive.packageName != context.packageName) {
            throw Exception("APK paket adı uyuşmuyor: ${archive.packageName}")
        }
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSig = signatureBytes(archive)
        val installedSig = signatureBytes(installed)
        if (archiveSig != null && installedSig != null && !archiveSig.contentEquals(installedSig)) {
            throw Exception(
                "APK imzası kurulu uygulamayla uyuşmuyor. Eski sürümü kaldırıp " +
                    "bu APK'yı elle kurun (ayarlarınız sıfırlanır).",
            )
        }
    }

    private fun signatureBytes(info: PackageInfo): ByteArray? {
        return if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return null
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            signers.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
    }

    /**
     * 150 ms aralıklarla ilerleme yayınlayan, SHA-256 doğrulamalı indirme.
     * Doğrudan bağlantı başarısız olursa (bazı ağlarda GitHub asset sunucusu
     * engellenir) motorun yerel SOCKS5 tüneli üzerinden yeniden dener.
     */
    private fun downloadFile(urlString: String, dest: File, expectedSha256: String?): Long {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            if (part.exists()) part.delete()
        } catch (_: Exception) {
        }

        val attempts = ArrayList<DownloadAttempt>()
        attempts.add(DownloadAttempt("doğrudan", null))
        val proxyPort = try {
            proxyPortProvider?.invoke()
        } catch (_: Exception) {
            null
        }
        if (proxyPort != null && proxyPort > 0) {
            attempts.add(
                DownloadAttempt(
                    "VPN/SOCKS",
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)),
                ),
            )
        }

        var lastError: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            try {
                if (index > 0) say("İndirme yeniden deneniyor (${attempt.label})...", "warn")
                val bytes = downloadOnce(urlString, part, expectedSha256, attempt.proxy)
                try {
                    if (dest.exists()) dest.delete()
                } catch (_: Exception) {
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                progress = JSONObject()
                    .put("received", bytes)
                    .put("total", bytes)
                    .put("percent", 100)
                listener?.onUpdateProgress(progress)
                return bytes
            } catch (err: Exception) {
                lastError = err
                try {
                    part.delete()
                } catch (_: Exception) {
                }
                say(
                    "İndirme denemesi başarısız (${attempt.label}): ${err.message ?: "ağ"}",
                    "warn",
                )
            }
        }
        throw lastError ?: Exception("indirme başarısız")
    }

    private class DownloadAttempt(val label: String, val proxy: Proxy?)

    private fun downloadOnce(
        urlString: String,
        dest: File,
        expectedSha256: String?,
        proxy: Proxy?,
    ): Long {
        var current = urlString
        var count = 0
        val conn: HttpURLConnection
        while (true) {
            if (count > MAX_REDIRECTS) throw Exception("çok fazla yönlendirme")
            val u = URL(current)
            if (u.protocol != "https") throw Exception("yalnızca https bağlantısı desteklenir")
            val c = if (proxy == null) {
                u.openConnection() as HttpURLConnection
            } else {
                u.openConnection(proxy) as HttpURLConnection
            }
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.setRequestProperty("user-agent", "ZelixGate-Update/1.0")
            c.setRequestProperty("accept", "*/*")
            // Sıkıştırma kapalı: Content-Length gerçek dosya boyutu olsun,
            // yüzde hesabı ve boyut doğrulaması şaşmasın.
            c.setRequestProperty("accept-encoding", "identity")
            val code = try {
                c.connect()
                c.responseCode
            } catch (err: IOException) {
                disconnectQuiet(c)
                throw Exception("bağlantı hatası: ${err.message ?: "ağ"}")
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = c.getHeaderField("location")
                disconnectQuiet(c)
                if (location.isNullOrEmpty()) throw Exception("yönlendirme adresi yok")
                current = URL(u, location).toString()
                count++
                continue
            }
            if (code != 200) {
                disconnectQuiet(c)
                throw Exception("indirme HTTP $code")
            }
            conn = c
            break
        }

        // Content-Length yoksa release'teki asset boyutuyla yüzde hesapla.
        val declared = conn.contentLengthLong.takeIf { it > 0 } ?: expectedTotal
        var got = 0L
        try {
            val input = conn.inputStream
            var lastEmit = 0L
            dest.outputStream().use { out ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        got += n
                        out.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 150) {
                            lastEmit = now
                            val total = if (declared > 0) declared else 0L
                            val percent = if (total > 0) {
                                ((got * 100) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            progress = JSONObject()
                                .put("received", got)
                                .put("total", total)
                                .put("percent", percent)
                            listener?.onUpdateProgress(progress)
                        }
                    }
                }
            }
        } catch (err: IOException) {
            throw Exception("indirme hatası: ${err.message ?: "ağ"}")
        } finally {
            disconnectQuiet(conn)
        }

        if (declared > 0 && got != declared) {
            throw Exception("eksik indirme ($got / $declared bayt)")
        }
        val digest = hashBytes(dest, MessageDigest.getInstance("SHA-256"))
        val hex = digest.joinToString("") { String.format("%02x", it) }
        if (expectedSha256 != null && hex.lowercase() != expectedSha256.trim().lowercase()) {
            throw Exception("SHA-256 doğrulaması başarısız (dosya bozuk olabilir)")
        }
        return got
    }

    companion object {
        /** Kendi deposundaki en son Release (update.json yerine bu sistem). */
        const val RELEASES_URL = "https://api.github.com/repos/ZelixYzlm/ZelixGate-Mobil/releases/latest"
        const val MAX_REDIRECTS = 5
        const val MAX_JSON_BYTES = 512 * 1024

        fun isoNow(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        /** JS: parseInt(part, 10) || 0 -> "3a" => 3, "beta" => 0 */
        fun versionParts(value: String): List<String> {
            if (value.isEmpty()) return emptyList()
            val stripped = value.trim().replace(Regex("^v", RegexOption.IGNORE_CASE), "")
            if (!stripped.contains('.')) return listOf(stripped)
            return stripped.split('.').map { part -> Regex("""^\d+""").find(part.trim())?.value ?: "0" }
        }

        fun parseIntSafe(value: String): Int = value.trim().toIntOrNull() ?: 0

        fun isNewer(candidate: String, current: String): Boolean {
            val a = versionParts(candidate)
            val b = versionParts(current)
            if (a.isEmpty()) return false
            val len = maxOf(a.size, b.size)
            for (i in 0 until len) {
                val x = parseIntSafe(if (i < a.size) a[i] else "0")
                val y = parseIntSafe(if (i < b.size) b[i] else "0")
                if (x != y) return x > y
            }
            return false
        }

        fun sanitizeName(value: String): String =
            value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64).ifEmpty { "guncelleme" }

        private fun hashBytes(file: File, digest: MessageDigest): ByteArray {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest()
        }

        private fun readBodyBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw Exception("manifest çok büyük")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        private fun disconnectQuiet(conn: HttpURLConnection) {
            try {
                conn.inputStream.close()
            } catch (_: Exception) {
            }
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
