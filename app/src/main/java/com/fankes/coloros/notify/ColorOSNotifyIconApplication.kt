package com.fankes.coloros.notify

import android.app.Application
import com.fankes.coloros.notify.framework.RemoteConfigCoordinator
import com.fankes.coloros.notify.framework.XposedServiceBridge
import com.fankes.coloros.notify.rules.RuleStore
import io.github.libxposed.service.XposedService

class ColorOSNotifyIconApplication : Application() {

    private val configPublisher = object : XposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            service?.let { RemoteConfigCoordinator.publish(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RuleStore.initialize(this)
        XposedServiceBridge.initialize()
        XposedServiceBridge.addListener(configPublisher)
    }
}
