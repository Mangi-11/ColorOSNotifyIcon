package com.fankes.coloros.notify.hook.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.IconBitmapClassifier
import com.fankes.coloros.notify.hook.runtimeFailure

internal class StatusBarHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
) {
    fun install(members: StatusBarMembers) {
        hooks.install(members.updateGrayScale, "systemui.statusbar.updateGrayScale") { chain ->
            val snapshot = configuration.snapshot
            val drawable = chain.args.getOrNull(0) as? Drawable ?: return@install chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) ?: return@install chain.proceed()
            chain.args.getOrNull(2) as? StatusBarNotification ?: return@install chain.proceed()
            try {
                members.setIsIconColorable.invoke(
                    statusBarIconView,
                    IconBitmapClassifier.isMonochromeDrawable(drawable),
                )
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "status_bar:monochrome",
                    message = "状态栏单色判定替换失败，交回 ColorOS 原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                return@install chain.proceed()
            }
            null
        }

        hooks.install(members.getIconDescriptor, "systemui.statusbar.getIconDescriptor") { chain ->
            val statusBarIcon = chain.proceed() ?: return@install null
            val snapshot = configuration.snapshot
            val notificationEntry = chain.args.getOrNull(0) ?: return@install statusBarIcon
            val iconManager = chain.thisObject ?: return@install statusBarIcon
            try {
                val iconBuilder = members.iconManagerIconBuilder.get(iconManager)
                val context = members.iconBuilderContext.get(iconBuilder) as? Context
                    ?: return@install statusBarIcon
                val sbn = members.notificationEntryGetSbn.invoke(notificationEntry) as? StatusBarNotification
                    ?: return@install statusBarIcon
                val currentIcon = members.statusBarIcon.get(statusBarIcon) as? Icon
                val replacement = snapshot.resolver.resolveStatusBarIcon(
                    context = context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                    currentStatusBarIcon = currentIcon,
                ) ?: return@install statusBarIcon

                members.statusBarPreloadedIcon.set(statusBarIcon, null)
                members.statusBarIcon.set(statusBarIcon, replacement)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "status_bar:replace_icon",
                    message = "状态栏规则图标注入失败，保留 ColorOS 原结果",
                    cause = exception,
                    revision = snapshot.revision,
                )
            }
            statusBarIcon
        }
    }
}
