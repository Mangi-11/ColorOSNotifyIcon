package com.fankes.coloros.notify.hook.systemui

import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.runtimeFailure

internal class NotificationRefreshBridge(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val members: RefreshMembers?,
) {
    @Volatile
    private var coordinator: Any? = null

    fun install() {
        val resolved = members ?: run {
            diagnostics.memberMissing(
                scope = "systemui:refresh:coordinator_class",
                message = "未找到 ViewConfigCoordinator，配置变更将等待通知自然刷新",
            )
            return
        }
        val attach = resolved.attach
        if (attach != null) {
            hooks.install(attach, "systemui.refresh.coordinator.attach") { chain ->
                val result = chain.proceed()
                coordinator = chain.thisObject ?: coordinator
                result
            }
        } else {
            diagnostics.memberMissing(
                scope = "systemui:refresh:attach",
                message = "ViewConfigCoordinator.attach 精确签名不匹配，配置变更将等待通知自然刷新",
            )
            return
        }
        if (resolved.refreshNotifications == null) {
            diagnostics.memberMissing(
                scope = "systemui:refresh:method",
                message = "未找到 ViewConfigCoordinator 刷新方法，配置变更将等待通知自然刷新",
            )
        }
    }

    /** Called on SystemUI's main thread after an immutable snapshot has been published. */
    fun refresh(revision: Long) {
        val method = members?.refreshNotifications ?: return
        val target = coordinator ?: return
        try {
            method.invoke(target)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "systemui:refresh:invoke",
                message = "刷新 SystemUI 通知视图失败，将等待通知自然刷新",
                cause = exception,
                revision = revision,
            )
        }
    }
}
