package com.fankes.coloros.notify.hook.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.NotificationIconMaskCompatibility
import com.fankes.coloros.notify.hook.icon.NotificationIconMaskClassifier
import com.fankes.coloros.notify.hook.runtimeFailure

internal class StatusBarHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
) {
    @Volatile
    private var iconIsGrayscaleTagId = 0
    private val iconClaims = StatusBarIconClaimRegistry<Any, View>()

    fun install(members: StatusBarMembers) {
        hooks.install(members.setStatusBarIcon, "systemui.statusbar.setIcon") { chain ->
            val statusBarIconView = chain.thisObject as? View
                ?: return@install chain.proceed()
            val descriptor = chain.args.getOrNull(0)
                ?: return@install chain.proceed()
            val boundClaim = iconClaims.bind(statusBarIconView, descriptor)
            try {
                chain.proceed().also { result ->
                    if (result != true) iconClaims.release(statusBarIconView, boundClaim)
                }
            } catch (throwable: Throwable) {
                iconClaims.release(statusBarIconView, boundClaim)
                throw throwable
            }
        }

        hooks.install(members.updateGrayScale, "systemui.statusbar.updateGrayScale") { chain ->
            val snapshot = configuration.snapshot
            val sbn = chain.args.getOrNull(2) as? StatusBarNotification
                ?: return@install chain.proceed()
            val statusBarIconView = chain.args.getOrNull(1) as? View
                ?: return@install chain.proceed()
            val applied = try {
                val action = StatusBarColorabilityPolicy.select(
                    moduleReplacementColorable = iconClaims.colorability(statusBarIconView, sbn.key),
                    usesAppIcon = sbn.notification.extras
                        ?.getBoolean(EXTRA_USE_APP_ICON_FOR_SMALL_ICON, false) == true,
                    keepHostAppIconBehavior =
                        snapshot.resolver.shouldKeepHostAppIconBehavior(),
                    keepNotificationHostDefault = snapshot.resolver.shouldKeepHostDefault(sbn),
                )
                val isColorable = when (action) {
                    StatusBarColorabilityAction.KeepHostResult -> null
                    StatusBarColorabilityAction.MarkColorable -> true
                    StatusBarColorabilityAction.MarkNotColorable -> false
                    StatusBarColorabilityAction.ClassifyNotificationMask -> {
                        val drawable = chain.args.getOrNull(0) as? Drawable
                        when (drawable?.let(NotificationIconMaskClassifier::classify)) {
                            NotificationIconMaskCompatibility.Compatible -> true
                            NotificationIconMaskCompatibility.Incompatible -> false
                            NotificationIconMaskCompatibility.Unknown,
                            null -> null
                        }
                    }
                }
                if (isColorable == null) {
                    false
                } else {
                    val grayscaleTagId = statusBarIconView.iconIsGrayscaleTagId()
                    statusBarIconView.setTag(grayscaleTagId, isColorable)
                    members.setIsIconColorable.invoke(
                        statusBarIconView,
                        isColorable,
                    )
                    true
                }
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "status_bar:colorability",
                    message = "状态栏着色判定失败，交回 ColorOS 原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                false
            }
            if (applied) null else chain.proceed()
        }

        hooks.install(members.getIconDescriptor, "systemui.statusbar.getIconDescriptor") { chain ->
            val statusBarIcon = chain.proceed() ?: return@install null
            val snapshot = configuration.snapshot
            val notificationEntry = chain.args.getOrNull(0) ?: return@install statusBarIcon
            val iconManager = chain.thisObject ?: return@install statusBarIcon
            try {
                if (
                    members.statusBarIconType.get(statusBarIcon) ===
                    members.peopleAvatarIconType
                ) return@install statusBarIcon
                val sbn = members.notificationEntryGetSbn.invoke(notificationEntry) as? StatusBarNotification
                    ?: return@install statusBarIcon
                val iconBuilder = members.iconManagerIconBuilder.get(iconManager)
                val context = members.iconBuilderContext.get(iconBuilder) as? Context
                    ?: return@install statusBarIcon
                val plan = snapshot.resolver.resolveStatusBarIconPlan(
                    context = context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                ) ?: return@install statusBarIcon
                if (!configuration.isCurrent(snapshot)) return@install statusBarIcon

                val replacement = members.cloneStatusBarIcon.invoke(statusBarIcon)
                    ?: return@install statusBarIcon
                members.statusBarPreloadedIcon.set(replacement, null)
                members.statusBarIcon.set(replacement, plan.icon)
                iconClaims.claimDescriptor(replacement, sbn.key, plan.isColorable)
                return@install replacement
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

    private fun View.iconIsGrayscaleTagId(): Int {
        iconIsGrayscaleTagId.takeIf { it != 0 }?.let { return it }
        return resources.getIdentifier(
            ICON_IS_GRAYSCALE_RESOURCE,
            "id",
            SystemPackages.SYSTEM_UI,
        ).takeIf { it != 0 }
            ?.also { iconIsGrayscaleTagId = it }
            ?: error("SystemUI icon_is_grayscale resource is unavailable")
    }

    private companion object {
        const val EXTRA_USE_APP_ICON_FOR_SMALL_ICON = "oplus_smallicon_use_app_icon"
        const val ICON_IS_GRAYSCALE_RESOURCE = "icon_is_grayscale"
    }
}
