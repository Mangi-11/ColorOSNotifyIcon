package com.fankes.coloros.notify.framework

import android.content.Context
import android.content.Intent
import com.fankes.coloros.notify.core.ModuleInfo
import com.fankes.coloros.notify.core.SystemPackages

object SystemUiRefreshSignal {

    fun request(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(ModuleInfo.ACTION_REFRESH_SYSTEM_UI_CONFIG)
                    .setPackage(SystemPackages.SYSTEM_UI)
            )
        }
    }
}
