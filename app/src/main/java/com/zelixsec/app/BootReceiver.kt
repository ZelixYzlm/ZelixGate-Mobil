package com.zelixsec.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zelixsec.app.runtime.ZelixRuntime
import com.zelixsec.app.vpn.ZelixVpnService

/**
 * ZelixGate - Pil/önyükleme alıcısı
 * Cihaz açıldığında masaüstündeki "Bilgisayar açılışında başlat" (app.autoStart)
 * karşılığı olarak: ayar açıkken motor ve tünel sistemde geri yüklenir.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            ZelixRuntime.init(context)
            val app = ZelixRuntime.snapshot().optJSONObject("settings")
                ?.optJSONObject("app") ?: return
            val autoStart = app.optBoolean("autoStart", false)
            val systemProxyEnabled = ZelixRuntime.snapshot().optJSONObject("settings")
                ?.optJSONObject("systemProxy")?.optBoolean("enabled", false) ?: false

            if (autoStart || systemProxyEnabled) {
                val vpn = Intent(context, ZelixVpnService::class.java)
                vpn.action = ZelixVpnService.ACTION_START
                context.startForegroundService(vpn)
            }
        } catch (_: Exception) {
        }
    }
}
