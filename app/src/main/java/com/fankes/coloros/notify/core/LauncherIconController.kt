package com.fankes.coloros.notify.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object LauncherIconController {

    fun isHidden(context: Context): Boolean = when (componentState(context)) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true

        else -> false
    }

    fun setHidden(context: Context, hidden: Boolean) {
        val desiredState = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        if (componentState(context) == desiredState) return

        context.packageManager.setComponentEnabledSetting(
            launcherComponent(context),
            desiredState,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun componentState(context: Context): Int =
        context.packageManager.getComponentEnabledSetting(launcherComponent(context))

    private fun launcherComponent(context: Context) = ComponentName(
        context.packageName,
        "${context.packageName}.Home",
    )
}
