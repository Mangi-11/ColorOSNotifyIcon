package com.fankes.coloros.notify.hook.systemui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageView
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.icon.NotificationIconResolver
import com.fankes.coloros.notify.hook.runtimeFailure

/**
 * ColorOS "notification light feedback while fullscreen" uses FullScreenBanner, not IconManager.
 * Icon replacement follows the notification-panel plan (same drawable + tint rules).
 */
internal class FullScreenBannerHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: FullScreenBannerMembers,
) {
    @Volatile
    private var lastEntry: Any? = null

    fun install() {
        installShowBannerHook()
        installBuildNotificationHook()
        installUpdateBannerHook()
        installSetIconHook()
        installBackgroundIconHook()
    }

    /**
     * Capture the entry before ColorOS builds the first banner frame. Without this, the initial
     * setIcon call can run before buildNotification and keep the default mono-white icon.
     */
    private fun installShowBannerHook() {
        val method = members.showBanner ?: return
        hooks.install(method, "systemui.fullscreen_banner.showBanner") { chain ->
            val entry = chain.args.getOrNull(0)
            if (entry != null) lastEntry = entry
            val result = chain.proceed()
            val helper = chain.thisObject
            val context = members.helperContext?.get(helper) as? Context
            val banner = members.helperFullScreenBanner?.get(helper)
            if (context != null && entry != null && banner != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installBuildNotificationHook() {
        val method = members.buildNotification ?: return
        hooks.install(method, "systemui.fullscreen_banner.buildNotification") { chain ->
            val entry = chain.args.getOrNull(1)
            if (entry != null) lastEntry = entry
            val builder = chain.proceed()
            val context = chain.args.getOrNull(0) as? Context
                ?: members.helperContext?.get(chain.thisObject) as? Context
            if (builder != null && context != null && entry != null) {
                patchBuilderIcon(context, builder, entry)
            }
            builder
        }
    }

    private fun installUpdateBannerHook() {
        val method = members.updateBanner ?: return
        hooks.install(method, "systemui.fullscreen_banner.updateBanner") { chain ->
            val banner = chain.thisObject ?: return@install chain.proceed()
            val builder = chain.args.getOrNull(0)
            val context = bannerContext(banner)
            val entry = resolveEntry(banner)
            val plan = if (builder != null && context != null && entry != null) {
                patchBuilderIcon(context, builder, entry)
            } else {
                null
            }
            val result = chain.proceed()
            if (plan != null) {
                schedulePanelRenderPlan(banner, plan)
            } else if (context != null && entry != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installSetIconHook() {
        val method = members.bannerSetIcon ?: return
        hooks.install(method, "systemui.fullscreen_banner.setIcon") { chain ->
            val banner = chain.thisObject ?: return@install chain.proceed()
            val context = bannerContext(banner)
            val entry = resolveEntry(banner)
            val plan = if (context != null && entry != null) {
                resolvePanelPlan(context, entry)
            } else {
                null
            }
            if (plan != null) {
                chain.args[0] = plan.drawable
                val avatar = chain.args.getOrNull(2) as? Drawable
                if (avatar != null) {
                    chain.args[2] = plan.drawable.constantState?.newDrawable()?.mutate() ?: plan.drawable
                }
                // ColorOS 17: largeIconWithBadge takes precedence over the icon argument.
                if (chain.args.size > 4) chain.args[4] = null
            }
            val result = chain.proceed()
            if (plan != null) {
                schedulePanelRenderPlan(banner, plan)
            } else if (context != null && entry != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installBackgroundIconHook() {
        val method = members.backgroundSetIcon ?: return
        hooks.install(method, "systemui.fullscreen_banner.background.setIcon") { chain ->
            val context = (chain.thisObject as? View)?.context
            val entry = lastEntry ?: return@install chain.proceed()
            val plan = context?.let { resolvePanelPlan(it, entry) }
            if (plan != null) {
                chain.args[0] = backgroundDrawable(plan.drawable)
            }
            chain.proceed()
        }
    }

    private fun patchBuilderIcon(
        context: Context,
        builder: Any,
        entry: Any,
    ): NotificationIconResolver.PanelIconRenderPlan? {
        val plan = resolvePanelPlan(context, entry) ?: return null
        members.builderIcon?.set(builder, plan.drawable)
        members.builderAvatarIcon?.set(
            builder,
            plan.drawable.constantState?.newDrawable()?.mutate() ?: plan.drawable,
        )
        members.builderLargeIconWithBadge?.set(builder, null)
        return plan
    }

    private fun refreshBannerIcon(context: Context, banner: Any, entry: Any) {
        resolvePanelPlan(context, entry)?.let { plan ->
            schedulePanelRenderPlan(banner, plan)
        }
    }

    private fun bannerContext(banner: Any): Context? =
        (banner as? View)?.context ?: members.helperContext?.let { field ->
            members.bannerHelper?.get(banner)?.let { helper ->
                field.get(helper) as? Context
            }
        }

    private fun resolveEntry(banner: Any): Any? {
        members.lastNotificationEntry?.let { field ->
            val helper = members.bannerHelper?.get(banner) ?: return@let
            (field.get(helper))?.let { return it }
        }
        return lastEntry
    }

    private fun schedulePanelRenderPlan(
        banner: Any,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        applyPanelRenderPlan(banner, plan)
        val iconView = members.bannerIconView?.get(banner) as? View ?: return
        iconView.post { applyPanelRenderPlan(banner, plan) }
    }

    private fun applyPanelRenderPlan(
        banner: Any,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        val iconView = members.bannerIconView?.get(banner) as? ImageView ?: return
        iconView.clearColorFilter()
        iconView.imageTintList = null
        iconView.setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun backgroundDrawable(source: Drawable): Drawable =
        source.constantState?.newDrawable()?.mutate() ?: source

    private fun resolvePanelPlan(
        context: Context,
        entry: Any,
    ): NotificationIconResolver.PanelIconRenderPlan? {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return null
        val sbn = statusBarNotificationFrom(entry) ?: return null
        return try {
            snapshot.resolver.resolvePanelIconPlan(
                context = context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            )?.takeIf { configuration.isCurrent(snapshot) }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fullscreen_banner:resolve",
                message = "全屏轻反馈图标解析失败",
                cause = exception,
                revision = snapshot.revision,
            )
            null
        }
    }

    private fun statusBarNotificationFrom(entry: Any?): StatusBarNotification? {
        if (entry == null) return null
        return try {
            members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fullscreen_banner:entry",
                message = "读取全屏轻反馈通知 entry 失败",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            null
        }
    }
}
