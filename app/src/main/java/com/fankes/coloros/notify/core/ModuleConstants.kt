package com.fankes.coloros.notify.core

object SystemPackages {
    const val SYSTEM_SCOPE = "system"
    const val SYSTEM_UI = "com.android.systemui"
}

object ModuleInfo {
    const val LOG_TAG = "ColorOSNotifyIcon"
    const val ACTION_REFRESH_SYSTEM_UI_CONFIG = "com.fankes.coloros.notify.action.REFRESH_SYSTEM_UI_CONFIG"
    const val RULES_BASE_URL = "https://raw.githubusercontent.com/fankes/AndroidNotifyIconAdapt/main"
    const val RULES_OS_URL = "$RULES_BASE_URL/OS/ColorOS/NotifyIconsSupportConfig.json"
    const val RULES_APP_URL = "$RULES_BASE_URL/APP/NotifyIconsSupportConfig.json"
}
