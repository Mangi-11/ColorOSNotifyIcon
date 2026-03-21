package com.fankes.coloros.notify.application

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.fankes.coloros.notify.data.ConfigData
import com.fankes.coloros.notify.utils.tool.FrameworkServiceBridge

class CNNApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        ConfigData.initialize(this)
        FrameworkServiceBridge.initialize()
    }
}
