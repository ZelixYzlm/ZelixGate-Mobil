package com.zelixsec.app.store

import android.content.Context
import com.zelixsec.app.update.ZelixUpdateService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ZelixGate - Ayar deposu (Android portu)
 * -------------------------------------
 * Ayarlar uygulama dosya alanında JSON olarak tutulur (ŞEKİL: electron/store.js
 * ile aynı; yukarı akış şifresi yalnızca yerelde saklanır).
 */

data class Profile(val id: String, val name: String, val emoji: String, val hosts: List<String>) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("name", name).put("emoji", emoji).put("hosts", JSONArray(hosts))
}

object Profiles {
    /** PROFILES (electron/store.js ile aynı liste) */
    val LIST = listOf(
        Profile(
            "discord",
            "Discord",
            "💬",
            listOf(
                "discord.com", "discordapp.com", "discordapp.net", "discord.gg",
                "discord.media", "discordstatus.com",
            ),
        ),
        Profile(
            "roblox",
            "Roblox",
            "🧱",
            listOf(
                "roblox.com", "rbxcdn.com", "rbx.com", "robloxlabs.com", "roblox.cn", "roblox.qq.com",
            ),
        ),
        Profile(
            "social",
            "Sosyal + Yayın",
            "📡",
            listOf(
                "twitter.com", "x.com", "instagram.com", "facebook.com", "youtube.com",
                "twitch.tv", "tiktok.com", "wikipedia.org", "pastebin.com",
            ),
        ),
        Profile("all", "Tüm Trafik", "🌐", emptyList()),
    )

    fun byId(id: String?): Profile = LIST.firstOrNull { it.id == id } ?: LIST[0]

    fun asJsonArray(): JSONArray = JSONArray().apply { LIST.forEach { put(it.toJson()) } }
}

object ZelixDefaults {
    /** Varsayılan güncelleme kaynağı: GitHub Releases API (update.json sistemi kaldırıldı). */
    val DEFAULT_UPDATE_URL = ZelixUpdateService.RELEASES_URL

    fun defaults(): JSONObject = JSONObject().apply {
        put("activeProfile", "discord")
        put("customHosts", JSONArray())
        put(
            "engine",
            JSONObject()
                .put("port", 7717)
                .put("mode", "split2")
                .put("splitPosition", 1)
                .put("splitDelayMs", 0),
        )
        put(
            "doh",
            JSONObject()
                .put("enabled", true)
                .put("provider", "cloudflare")
                .put("allowSystemFallback", true),
        )
        put(
            "upstream",
            JSONObject()
                .put("enabled", false)
                .put("type", "socks5")
                .put("host", "127.0.0.1")
                .put("port", 1080)
                .put("username", "")
                .put("password", ""),
        )
        put("systemProxy", JSONObject().put("enabled", false).put("lastApplied", JSONObject.NULL))
        put("adblock", JSONObject().put("enabled", true).put("customBlocked", JSONArray()))
        put(
            "appearance",
            JSONObject()
                .put("theme", "neon")
                .put("opacity", 78)
                .put("aurora", true)
                .put("powerStyle", "power")
                .put("powerAnimation", "ring"),
        )
        put(
            "update",
            JSONObject()
                .put("manifestUrl", DEFAULT_UPDATE_URL)
                .put("autoCheck", true)
                .put("lastCheck", JSONObject.NULL),
        )
        put(
            "app",
            JSONObject()
                .put("autoStart", false)
                .put("minimizeToTray", true)
                .put("autoConnect", true)
                .put("autoSystemProxy", true)
                .put("language", "tr"),
        )
        put("diagnosticsTarget", "discord.com")
    }

    /**
     * deepMerge (electron/store.js ile aynı semantik):
     * her iki tarafın da obje olduğu yerde derin birleştirme; dizi ve
     * skaler değerlerde yama kazanır.
     */
    fun deepMerge(base: JSONObject, patch: JSONObject?): JSONObject {
        val out = JSONObject(base.toString())
        if (patch == null) return out
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = patch.opt(key) ?: continue
            val baseVal = out.opt(key)
            if (value is JSONObject && baseVal is JSONObject) {
                out.put(key, deepMerge(baseVal, value))
            } else {
                out.put(key, value)
            }
        }
        return out
    }
}

class ZelixStore(context: Context) {
    private val file: File = File(context.filesDir, "zelixgate-settings.json")
    private val legacyFile: File = File(context.filesDir, "zelixsec-settings.json")

    var data: JSONObject = load()
        private set

    private fun load(): JSONObject {
        return try {
            val source = when {
                file.exists() -> file
                legacyFile.exists() -> legacyFile
                else -> null
            }
            if (source == null) {
                ZelixDefaults.defaults()
            } else {
                val merged = ZelixDefaults.deepMerge(
                    ZelixDefaults.defaults(),
                    JSONObject(source.readText()),
                )
                // 1.0.9 ve öncesindeki ayarlar eski GitHub deposunu işaret
                // ediyor olabilir; güncelleme kaynağını yeni depoya taşı.
                val update = merged.optJSONObject("update")
                val manifest = update?.optString("manifestUrl", "") ?: ""
                if (
                    update != null && (
                        manifest.isBlank() ||
                            manifest.contains("ZelixSec-app") ||
                            manifest.contains("ZelixGate-app")
                        )
                ) {
                    update.put("manifestUrl", ZelixDefaults.DEFAULT_UPDATE_URL)
                }
                if (source == legacyFile) {
                    try {
                        file.writeText(merged.toString(2))
                    } catch (_: Exception) {
                    }
                }
                merged
            }
        } catch (_: Exception) {
            ZelixDefaults.defaults()
        }
    }

    fun save(): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(data.toString(2))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun get(): JSONObject = data

    fun patch(partial: JSONObject?): JSONObject {
        data = ZelixDefaults.deepMerge(data, partial ?: JSONObject())
        save()
        return data
    }

    fun reset(): JSONObject {
        data = ZelixDefaults.defaults()
        save()
        return data
    }

    /**
     * Profil + özel host listesinden motor için allowlist üretir.
     * 'all' profili boş liste döner = her hostta bölme uygulanır.
     */
    fun allowlistFor(profileId: String? = null, customHosts: Any? = null): List<String> {
        val data = this.data
        val id = profileId ?: data.optString("activeProfile", "discord")
        val custom: Any? = if (customHosts != null || data.isNull("customHosts")) customHosts
        else data.opt("customHosts")
        val parts = when (custom) {
            is JSONArray -> (0 until custom.length()).map { custom.optString(it) }
            is String -> custom.split(Regex("[\\s,;]+"))
            else -> emptyList<String>()
        }
        val cleaned = parts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        if (id == "all") return emptyList()
        if (id == "custom") return LinkedHashSet(cleaned).toList()

        val profile = Profiles.byId(id)
        return LinkedHashSet(profile.hosts + cleaned).toList()
    }
}
