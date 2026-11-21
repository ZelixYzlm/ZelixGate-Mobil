package com.zelixsec.app.desync

import org.json.JSONArray

/**
 * ZelixGate - Reklam ve izleyici engelleyici (Android portu)
 * -------------------------------------------------------
 * Sistem vekili/VPN açıkken uygulamaların (tarayıcı, oyun istemcisi) reklam
 * ağlarına açtığı CONNECT/HTTP isteklerini yerelde reddeder. Liste
 * tamamıyla yereldir: dış sunucu sorgusu yapılmaz.
 *
 * Eşleşme "sonek" temellidir: "doubleclick.net" kuralı geçerlidir ve
 * "pagead2.doubleclick.net" gibi alt alan adlarını da kapsar. Ayrıca
 * "ads.x.com", "adserver.x.com", "adservice.x.com" biçimli öneki de engellenir.
 */
object AdblockLists {
    /** BUILTIN_BLOCKED (engine/adblock.js ile aynı liste) */
    val BUILTIN_BLOCKED = listOf(
        /* Google reklam ailesi */
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "analytics.google.com",
        "imasdk.googleapis.com",
        "admob.com",
        "app-measurement.com",

        /* Sosyal medya reklam/izleyici uçları */
        "connect.facebook.net",
        "an.facebook.com",
        "ads.tiktok.com",
        "analytics.tiktok.com",
        "static.ads-twitter.com",
        "ads.pinterest.com",
        "ads.snapchat.com",

        /* RTB / reklam borsaları (SSP-DSP) */
        "adnxs.com",
        "adnxs-simple.com",
        "adsrvr.org",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "indexww.com",
        "casalemedia.com",
        "adform.net",
        "smartadserver.com",
        "bidswitch.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "mgid.com",
        "teads.tv",
        "yieldmo.com",
        "media.net",
        "33across.com",
        "sharethrough.com",
        "sonobi.com",
        "bidr.io",
        "amazon-adsystem.com",
        "2mdn.net",
        "adsafeprotected.com",
        "moatads.com",
        "doubleverify.com",
        "gidgted.com",

        /* Pop/push/başdöndüren reklam ağları */
        "propellerads.com",
        "popads.net",
        "popcash.net",
        "adcash.com",
        "clicksor.com",
        "exoclick.com",
        "revcontent.com",
        "zedo.com",
        "zeropark.com",
        "adroll.com",
        "clickadu.com",

        /* Rusça/CIS reklam ve sayaçlar */
        "an.yandex.ru",
        "adfox.ru",
        "mc.yandex.ru",
        "top-fwz1.mail.ru",
        "st.top100.ru",

        /* Türkiye yerel reklam ağları */
        "mediaad.org",
        "admatic.com.tr",

        /* Mobil reklam SDK'ları */
        "applovin.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "inmobi.com",
        "mopub.com",
        "chartboost.com",
        "vungle.com",
        "fyber.com",
        "heyzap.com",
        "tapjoy.com",
        "ironsrc.com",

        /* İzlemek/attribution/'analitik reklam' zincirleri */
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "onesignal.com",
        "mixpanel.com",
        "amplitude.com",
        "segment.io",
        "segment.com",
        "hotjar.com",
        "fullstory.com",
        "statcounter.com",
        "histats.com",
        "addthis.com",
        "sharethis.com",
        "quantserve.com",
        "scorecardresearch.com",
    )

    /** Öneki engellenen evrensel kalıplar (alt alan adına göre eşleşir). */
    val PREFIX_RULES = listOf("ads.", "adserver.", "adservice.")
}

object Adblock {
    val IPV4_RE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun normalizeDomain(host: String): String {
        var value = host.lowercase().trim()
        // Yapıştırılan tam URL'leri de kabul et: https://reklam.ornek.com/x -> reklam.ornek.com
        if (Regex("""^https?://""").containsMatchIn(value)) {
            try {
                val url = java.net.URL(value)
                value = url.host
            } catch (_: Exception) {
                value = value.replaceFirst(Regex("""^https?://"""), "")
            }
        }
        value = value.replace(Regex("""/.*$"""), "").replace(Regex("""^\[|\]$"""), "").replace(Regex("""\.$"""), "")
        return value.trim()
    }

    fun cleanRules(list: List<String>?): Set<String> {
        val out = mutableSetOf<String>()
        for (item in list ?: emptyList()) {
            var value = normalizeDomain(item)
            if (value.isEmpty()) continue
            value = value.replace(Regex("""^\*\."""), "") // *.ornek.com -> ornek.com
            if (value.isNotEmpty() && value.contains('.')) out.add(value)
        }
        return out
    }

    fun builtinRules(): Set<String> = cleanRules(AdblockLists.BUILTIN_BLOCKED)
}

class AdBlocker(opts: Map<String, Any> = emptyMap()) {
    var enabled: Boolean = (opts["enabled"] as? Boolean) !== false
        private set
    private var custom: List<String> =
        (opts["customBlocked"] as? List<*>)?.map { it.toString() } ?: emptyList()
    private var rules: Set<String> = emptySet()
    private var customRules: Set<String> = emptySet()

    init {
        @Suppress("LeakingThis")
        rebuild()
    }

    fun configure(next: Map<String, Any> = emptyMap()) {
        (next["enabled"] as? Boolean)?.let { enabled = it }
        (next["customBlocked"] as? List<*>)?.let { custom = it.map { c -> c.toString() } }
        rebuild()
    }

    private fun rebuild() {
        rules = Adblock.builtinRules()
        customRules = Adblock.cleanRules(custom)
    }

    fun counts(): org.json.JSONObject = org.json.JSONObject().apply {
        put("builtin", rules.size)
        put("custom", customRules.size)
    }

    /**
     * Host reklam/izleyici mi?
     * @param host ham hostname ("ads.ornek.com", IP, "ornek.com" ...)
     */
    fun blocks(rawHost: String): Boolean {
        if (!enabled) return false
        val h = Adblock.normalizeDomain(rawHost).removePrefix("www.")
        if (h.isEmpty() || h.contains("localhost") || Adblock.IPV4_RE.matches(h) || Regex("""^:+""").containsMatchIn(h)) {
            return false
        }

        for (prefix in AdblockLists.PREFIX_RULES) {
            if (h == prefix.dropLast(1) || h.startsWith(prefix) || h.contains(".$prefix")) {
                return true
            }
        }

        for (item in customRules) {
            if (h == item || h.endsWith(".$item")) return true
        }
        for (item in rules) {
            if (h == item || h.endsWith(".$item")) return true
        }
        return false
    }
}
