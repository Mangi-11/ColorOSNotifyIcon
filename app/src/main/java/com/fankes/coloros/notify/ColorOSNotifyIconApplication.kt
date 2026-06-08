package com.fankes.coloros.notify

import android.app.Application
import com.fankes.coloros.notify.framework.LsposedServiceBridge
import com.fankes.coloros.notify.rules.RuleStore

class ColorOSNotifyIconApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        RuleStore.initialize(this)
        LsposedServiceBridge.initialize()
    }
}
