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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class NotificationPanelHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: PanelMembers,
) {
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()
    private val groupReapplyTasks = Collections.synchronizedMap(WeakHashMap<ImageView, Runnable>())

    fun install() {
        installOplusHeaderColorGuard()
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

    /** Called on the main thread before notifications are refreshed for a new snapshot. */
    fun onSnapshotPublished() {
        headerIconClaims.clear()
        val pending = synchronized(groupReapplyTasks) {
            groupReapplyTasks.entries.map { it.key to it.value }
                .also { groupReapplyTasks.clear() }
        }
        pending.forEach { (view, action) -> view.removeCallbacks(action) }
    }

    private fun installOplusHeaderColorGuard() {
        val oplusHeader = members.oplusHeader ?: return
        val getIcon = members.headerGetIcon ?: return
        hooks.install(oplusHeader.updateIconColor, "systemui.panel.header.updateIconColor") { chain ->
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
                PanelIconTarget.OplusGroupSummary -> rowView?.findOplusGroupSummaryIcon()
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
                currentDrawable = icon.drawable,
            ) ?: return

            if (target == PanelIconTarget.OplusGroupSummary) {
                icon.clearOplusGroupSummaryDecoration()
                icon.applyRenderPlan(plan, target)
                icon.reapplyOplusGroupSummaryIcon(plan, snapshot) {
                    statusBarNotificationFromRow(row)?.key == sbn.key
                }
            } else {
                icon.applyRenderPlan(plan, target)
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

    private fun statusBarNotificationFromRow(row: Any): StatusBarNotification? {
        val entry = members.expandableRowGetEntry.invoke(row) ?: return null
        return members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
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
        clearColorFilter()
        imageTintList = null
        background = null
        foreground = null
        clipToOutline = false
        if (target == PanelIconTarget.OplusGroupSummary) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun ImageView.clearOplusGroupSummaryDecoration() {
        background = null
        foreground = null
        clipToOutline = false
        imageTintList = null
        clearColorFilter()

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

    private fun ImageView.reapplyOplusGroupSummaryIcon(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        snapshot: RuntimeSnapshot,
        isStillSameNotification: () -> Boolean,
    ) {
        groupReapplyTasks.remove(this)?.let { removeCallbacks(it) }
        val remainingRuns = AtomicInteger(OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS.size)
        lateinit var action: Runnable
        action = Runnable {
            try {
                if (!isAttachedToWindow || !configuration.isCurrent(snapshot)) return@Runnable
                if (!isStillSameNotification()) return@Runnable
                clearOplusGroupSummaryDecoration()
                applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:group_reapply",
                    message = "Oplus 聚合摘要图标延迟重绘失败",
                    cause = exception,
                    revision = snapshot.revision,
                )
            } finally {
                if (remainingRuns.decrementAndGet() <= 0) {
                    synchronized(groupReapplyTasks) {
                        if (groupReapplyTasks[this] === action) groupReapplyTasks.remove(this)
                    }
                }
            }
        }
        groupReapplyTasks[this] = action
        OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS.forEach { delay -> postDelayed(action, delay) }
    }

    private enum class PanelIconTarget(val diagnosticName: String) {
        Header("header"),
        OplusGroupSummary("oplus_group_summary"),
    }

    private companion object {
        val OPLUS_GROUP_ICON_REAPPLY_DELAYS_MS = longArrayOf(64L, 180L, 360L)
    }
}
