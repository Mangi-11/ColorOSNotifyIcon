package com.fankes.coloros.notify.hook.systemui

internal enum class StatusBarColorabilityAction {
    KeepHostResult,
    MarkColorable,
    MarkNotColorable,
    ClassifyNotificationMask,
}

/**
 * Selects the owner of the status-bar colorability decision.
 *
 * A claim transferred from the exact descriptor to the exact rendering view owns the result,
 * because it describes the Drawable now on screen. Without a module claim, a per-notification
 * preservation decision keeps the platform predicate. The app-icon marker is
 * delegated only while the configured source preserves ColorOS app-icon behavior; otherwise the
 * actual Drawable is checked against the complete notification-mask contract.
 */
internal object StatusBarColorabilityPolicy {

    fun select(
        moduleReplacementColorable: Boolean?,
        usesAppIcon: Boolean,
        keepHostAppIconBehavior: Boolean,
        keepNotificationHostDefault: Boolean,
    ): StatusBarColorabilityAction = when {
        moduleReplacementColorable == true -> StatusBarColorabilityAction.MarkColorable
        moduleReplacementColorable == false -> StatusBarColorabilityAction.MarkNotColorable
        keepNotificationHostDefault -> StatusBarColorabilityAction.KeepHostResult
        usesAppIcon && keepHostAppIconBehavior -> StatusBarColorabilityAction.KeepHostResult
        else -> StatusBarColorabilityAction.ClassifyNotificationMask
    }
}
