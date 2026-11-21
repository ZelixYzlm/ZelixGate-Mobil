package com.zelixsec.app

import android.app.Application
import com.zelixsec.app.runtime.ZelixRuntime

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ZelixRuntime.init(this)
    }
}
