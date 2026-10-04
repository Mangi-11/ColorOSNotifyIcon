package com.fankes.coloros.notify.hook.systemui

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.fankes.coloros.notify.core.SystemPackages
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.NotificationIconResolver
import com.fankes.coloros.notify.hook.runtimeFailure
import java.util.concurrent.ConcurrentHashMap

internal class NotificationPanelHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: PanelMembers,
) {
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()

    fun install() {
        installOplusHeaderHooks()
        installGroupIconManagerHooks()
        members.headerOnContentUpdated?.let { method ->
            hooks.install(method, "systemui.panel.header.onContentUpdated") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, rowCandidate = chain.args.firstOrNull())
                }
                result
            }
        }
        members.headerResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.header.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper -> applyPanelIcon(wrapper) }
                result
            }
        }
        members.headerSetIsChildInGroup?.let { method ->
            hooks.install(method, "systemui.panel.header.setIsChildInGroup") { chain ->
                val result = chain.proceed()
                if (chain.args.firstOrNull() == false) {
                    chain.thisObject?.let(::applyPanelIcon)
                }
                result
            }
        }
        members.oplusGroupInitIcon?.let { method ->
            hooks.install(method, "systemui.panel.group.initIcon") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        members.oplusGroupResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.group.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
    }

    /**
     * ColorOS collapsed-group UI extracts WallpaperColors from the app/small icon and paints a
     * solid capsule ("色块"). When we already have a rule/theme replacement, take over icon + pill.
     */
    private fun installGroupIconManagerHooks() {
        members.groupIconInitIconViewColor?.let { method ->
            hooks.install(method, "systemui.panel.group.initIconViewColor") { chain ->
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val entry = chain.args.getOrNull(2) ?: return@install chain.proceed()
                val iconView = chain.args.getOrNull(1) as? ImageView
                    ?: return@install chain.proceed()
                try {
                    val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
                        ?: return@install chain.proceed()
                    val plan = snapshot.resolver.resolvePanelIconPlan(
                        context = iconView.context,
                        sbn = sbn,
                        originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                    ) ?: return@install chain.proceed()
                    if (!configuration.isCurrent(snapshot)) return@install chain.proceed()

                    iconView.clearColorFilter()
                    iconView.imageTintList = null
                    iconView.setImageDrawable(plan.drawable)
                    plan.tintColor?.let { tint ->
                        iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    }
                    null
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:group_icon_color",
                        message = "折叠分组图标着色覆盖失败，交回 ColorOS 原实现",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                    chain.proceed()
                }
            }
        }

        // ColorOS 17: the summary glyph lives in OplusGroupNotificationRowIconView; the host only
        // colors the pill here, then its callback paints a round background onto the icon view.
        members.groupIconInitEntryIconDrawable?.let { method ->
            hooks.install(method, "systemui.panel.group.initEntryIconDrawable") { chain ->
                if (chain.args.getOrNull(4) as? Boolean != false) return@install chain.proceed()
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val entry = chain.args.getOrNull(0) ?: return@install chain.proceed()
                val iconView = chain.args.getOrNull(1) as? ImageView
                    ?: return@install chain.proceed()
                chain.args.getOrNull(5)?.let { originalCallback ->
                    chain.args[5] = wrapHostCallback(method.parameterTypes[5], originalCallback) {
                        applyGroupSummaryIcon(iconView, entry, snapshot)
                    }
                }
                val result = chain.proceed()
                applyGroupSummaryIcon(iconView, entry, snapshot)
                result
            }
        }

        members.groupIconInitPillBgAndNumberColor?.let { method ->
            hooks.install(method, "systemui.panel.group.initPillBgAndNumberColor") { chain ->
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val iconInfo = chain.args.getOrNull(1) ?: return@install chain.proceed()
                val frame = chain.args.getOrNull(0) as? android.widget.FrameLayout
                    ?: return@install chain.proceed()
                try {
                    val isGrays = iconInfo.javaClass.methods
                        .firstOrNull { it.name == "isGraysIcon" && it.parameterTypes.isEmpty() }
                        ?.invoke(iconInfo) as? Boolean
                    // Colorful app-icon path paints a vibrant capsule; neutralize it.
                    if (isGrays == true) return@install chain.proceed()
                    val result = chain.proceed()
                    val night = (frame.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val muted = if (night) 0x33FFFFFF.toInt() else 0x33000000
                    (frame.background as? android.graphics.drawable.GradientDrawable)?.setColor(muted)
                    result
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:group_pill_color",
                        message = "折叠分组胶囊去色失败，交回 ColorOS 原实现",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                    chain.proceed()
                }
            }
        }
    }

    private fun installOplusHeaderHooks() {
        val oplusHeader = members.oplusHeader ?: return
        installOplusHeaderColorGuard(oplusHeader)
        installOplusHeaderRoundnessReapply(oplusHeader)
    }

    private fun installOplusHeaderColorGuard(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconColor ?: return
        val getIcon = members.headerGetIcon ?: return
        hooks.install(method, "systemui.panel.header.updateIconColor") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val extension = chain.thisObject ?: return@install chain.proceed()
            val shouldBlockColorUpdate = try {
                val wrapper = oplusHeader.getBase.invoke(extension)
                val icon = wrapper?.let { getIcon.invoke(it) as? ImageView }
                val row = wrapper?.let { members.notificationViewWrapperRow.get(it) }
                val sbn = row?.let(::statusBarNotificationFromRow)
                val drawable = icon?.drawable
                icon != null && sbn != null && drawable != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                false
            }
            if (shouldBlockColorUpdate) false else chain.proceed()
        }
    }

    private fun installOplusHeaderRoundnessReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconRoundness ?: return
        hooks.install(method, "systemui.panel.header.updateIconRoundness") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper, target = wrapper.iconTarget)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_roundness_reapply",
                        message = "Oplus Header 圆角处理后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun applyPanelIcon(
        wrapper: Any,
        rowCandidate: Any? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
        expectedSnapshot: RuntimeSnapshot? = null,
        allowDeferredLookup: Boolean = true,
    ) {
        val snapshot = expectedSnapshot ?: configuration.snapshot
        if (expectedSnapshot != null && !configuration.isCurrent(snapshot)) return
        if (!snapshot.config.panelIconReplacementEnabled) return
        try {
            val row = rowCandidate ?: members.notificationViewWrapperRow.get(wrapper) ?: return
            val rowView = row as? View
            val icon = when (target) {
                PanelIconTarget.Header -> members.headerGetIcon?.invoke(wrapper) as? ImageView
                // GroupIconManager colors NotificationHeaderViewWrapper.mIcon (CachingIconView).
                PanelIconTarget.OplusGroupSummary ->
                    (members.headerGetIcon?.invoke(wrapper) as? ImageView)
                        ?: rowView?.findOplusGroupSummaryIcon()
            } ?: run {
                if (target == PanelIconTarget.OplusGroupSummary && allowDeferredLookup && rowView != null) {
                    rowView.post {
                        if (configuration.isCurrent(snapshot)) {
                            applyPanelIcon(
                                wrapper = wrapper,
                                rowCandidate = row,
                                target = target,
                                expectedSnapshot = snapshot,
                                allowDeferredLookup = false,
                            )
                        }
                    }
                }
                return
            }
            if (target == PanelIconTarget.Header) headerIconClaims.release(icon)
            val sbn = statusBarNotificationFromRow(row) ?: return
            val plan = snapshot.resolver.resolvePanelIconPlan(
                context = icon.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            ) ?: return

            icon.applyRenderPlan(plan, target)
            if (target == PanelIconTarget.Header) {
                icon.drawable?.let { drawable -> headerIconClaims.claim(icon, sbn.key, drawable) }
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${target.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    private fun applyGroupSummaryIcon(iconView: ImageView, entry: Any, snapshot: RuntimeSnapshot) {
        if (!configuration.isCurrent(snapshot)) return
        try {
            val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification ?: return
            val plan = snapshot.resolver.resolvePanelIconPlan(
                context = iconView.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            ) ?: return
            iconView.applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${PanelIconTarget.OplusGroupSummary.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    private fun statusBarNotificationFromRow(row: Any): StatusBarNotification? {
        val entry = members.expandableRowGetEntry.invoke(row) ?: return null
        return members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
    }

    private val Any.iconTarget: PanelIconTarget
        get() = if (members.oplusGroupWrapper?.isInstance(this) == true) {
            PanelIconTarget.OplusGroupSummary
        } else {
            PanelIconTarget.Header
        }

    private fun View.findOplusGroupSummaryIcon(): ImageView? {
        val headerId = systemUiId("oplus_notification_collapsed_group_header")
        val containerId = systemUiId("icon_container")
        val iconId = systemUiId("icon")
        if (headerId == 0 || containerId == 0 || iconId == 0) return null
        val header = findViewById<View>(headerId) as? ViewGroup ?: return null
        val container = header.findViewById<View>(containerId) as? ViewGroup ?: header
        return container.findViewById<View>(iconId) as? ImageView
    }

    @SuppressLint("DiscouragedApi") // These resources belong to the hooked SystemUI APK, not this module.
    private fun View.systemUiId(name: String): Int =
        systemUiIdCache[name] ?: resources.getIdentifier(name, "id", SystemPackages.SYSTEM_UI)
            .also { systemUiIdCache[name] = it }

    private fun ImageView.applyRenderPlan(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        target: PanelIconTarget,
    ) {
        clearHostDecoration(target)
        if (target == PanelIconTarget.OplusGroupSummary) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun ImageView.clearHostDecoration(target: PanelIconTarget) {
        background = null
        foreground = null
        clipToOutline = false
        imageTintList = null
        clearColorFilter()

        if (target != PanelIconTarget.OplusGroupSummary) return
        val container = parent as? View
        if (container?.id == systemUiId("icon_container")) {
            container.background = null
            container.foreground = null
            container.clipToOutline = false
            if (container is ViewGroup) {
                container.clipChildren = false
                container.clipToPadding = false
            }
        }
    }

    private enum class PanelIconTarget(val diagnosticName: String) {
        Header("header"),
        OplusGroupSummary("oplus_group_summary"),
    }
}
