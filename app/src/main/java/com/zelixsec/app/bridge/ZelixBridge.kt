package com.zelixsec.app.bridge

import android.webkit.JavascriptInterface
import com.zelixsec.app.runtime.ZelixRuntime

/**
 * ZelixGate - WebView köprüsü
 * Kenan tek @JavascriptInterface: invoke(id, name, argsJson).
 * Sonuçlar main thread'den window.__zelixResult("$id", json) ile geri gelir;
 * yayınlar window.__zelixEvent(channel, json) ile.
 */
class ZelixBridge {

    @JavascriptInterface
    fun invoke(id: String, name: String, argsJson: String?) {
        ZelixRuntime.dispatch(id, name ?: "", argsJson)
    }

    /** Arayüz açıldığını bildirerek bekleyen yayınları boşaltır. */
    @JavascriptInterface
    fun webReady() {
        ZelixRuntime.dispatch("__request__", "web:ready", null)
    }
}
