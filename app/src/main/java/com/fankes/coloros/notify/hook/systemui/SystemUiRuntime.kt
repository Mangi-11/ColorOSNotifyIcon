package com.fankes.coloros.notify.hook.systemui

import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.ThemeIconProvider
import com.fankes.coloros.notify.hook.installationFailure
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.runtimeFailure
import io.github.libxposed.api.XposedInterface

internal class SystemUiRuntime(
    private val xposed: XposedInterface,
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val processName: String,
) {
    fun install(classLoader: ClassLoader) {
        val themeIcons = ThemeIconProvider(diagnostics)
        val configuration = SystemUiConfiguration(
            xposed = xposed,
            diagnostics = diagnostics,
            processName = processName,
            themeIcons = themeIcons,
        )
        var refreshBridge: NotificationRefreshBridge? = null
        var panelHooks: NotificationPanelHooks? = null

        // Register before reflection discovery so no committed revision can be missed during setup.
        configuration.start { snapshot ->
            panelHooks?.let { panel ->
                runPublishedTask(snapshot, "panel_cleanup", panel::onSnapshotPublished)
            }
            refreshBridge?.let { refresh ->
                runPublishedTask(snapshot, "notification_refresh") {
                    refresh.refresh(snapshot.revision)
                }
            }
        }

        installFeature("refresh") {
            refreshBridge = NotificationRefreshBridge(
                hooks = hooks,
                diagnostics = diagnostics,
                members = SystemUiMembers.resolveRefresh(classLoader),
            ).also(NotificationRefreshBridge::install)
        }

        installFeature("use_app_icon") {
            SystemUiMembers.resolveUseAppIcon(classLoader)?.let { method ->
                hooks.install(method, "systemui.useAppIconForSmallIcon") { false }
            } ?: diagnostics.memberMissing(
                scope = "systemui:use_app_icon",
                message = "未找到 useAppIconForSmallIcon(Notification): boolean，跳过 ColorOS 应用图标抑制",
            )
        }

        installFeature("status_bar") {
            SystemUiMembers.resolveStatusBar(classLoader, diagnostics)?.let { members ->
                StatusBarHooks(
                    hooks = hooks,
                    diagnostics = diagnostics,
                    configuration = configuration,
                ).install(members)
            }
        }

        installFeature("panel") {
            SystemUiMembers.resolvePanel(classLoader, diagnostics)?.let { members ->
                panelHooks = NotificationPanelHooks(
                    hooks = hooks,
                    diagnostics = diagnostics,
                    configuration = configuration,
                    members = members,
                ).also(NotificationPanelHooks::install)
            }
        }
    }

    private inline fun runPublishedTask(
        snapshot: RuntimeSnapshot,
        feature: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "config:on_published:$feature",
                message = "配置快照已发布，但关联功能刷新失败：$feature",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    private inline fun installFeature(feature: String, block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "systemui:feature:$feature",
                message = "SystemUI 功能初始化失败，已独立降级：$feature",
                cause = exception,
            )
        }
    }
}
