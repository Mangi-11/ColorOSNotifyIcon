package com.fankes.coloros.notify.hook.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageView
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.runtimeFailure
import java.util.Collections
import java.util.WeakHashMap
import kotlin.jvm.functions.Function1

/**
 * ColorOS 16 lock-screen notification capsule (bottom pill between shortcuts).
 */
internal class LockScreenCapsuleHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: LockScreenCapsuleMembers,
) {
    private val replacementDrawables =
        Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())

    private fun lockScreenCapsuleEnabled(): Boolean =
        configuration.snapshot.config.lockScreenCapsuleIconReplacementEnabled

    fun install() {
        installNotificationIconDataCtorHooks()
        installGetRoundedIconHook()
        installWrapWithWhiteBgHook()
        installCapsuleCardBindHooks()
        installCapsuleGroupIconHooks()
        installCapsuleEntryIconDrawableHook()
    }

    private fun installNotificationIconDataCtorHooks() {
        for (ctor in members.notificationIconDataCtors) {
            hooks.install(ctor, "systemui.lockscreen.capsule.notificationIconData") { chain ->
                if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
                if (isMediaPlayerArg(chain.args.getOrNull(7))) return@install chain.proceed()
                val context = appContext() ?: return@install chain.proceed()
                // Chain.getArgs() is immutable; collect the replacements and hand them to proceed().
                var patchedArgs: Array<Any?>? = null
                patchNotificationIconData(
                    context = context,
                    iconData = null,
                    key = chain.args.getOrNull(0) as? String,
                    packageName = chain.args.getOrNull(1) as? String,
                    entry = chain.args.getOrNull(6),
                    onPatched = { drawable, icon ->
                        patchedArgs = chain.args.toTypedArray().also {
                            it[2] = drawable
                            it[3] = icon
                            it[5] = null
                        }
                    },
                )
                // Do not use `?.let { } ?: `: proceed() legitimately returns null (constructors,
                // void methods), which would fall through and invoke the target a second time.
                // Copy to a local val — a captured var cannot be smart-cast to non-null.
                val finalArgs = patchedArgs
                if (finalArgs != null) chain.proceed(finalArgs) else chain.proceed()
            }
        }
    }

    private fun installGetRoundedIconHook() {
        val method = members.capsuleGetRoundedIcon ?: return
        hooks.install(method, "systemui.lockscreen.capsule.getRoundedIcon") { chain ->
            if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
            val iconData = chain.args.getOrNull(0) ?: return@install chain.proceed()
            val context = (chain.thisObject as? View)?.context ?: return@install chain.proceed()
            try {
                patchNotificationIconData(
                    context = context,
                    iconData = iconData,
                    key = members.notificationIconDataGetKey?.invoke(iconData) as? String,
                    packageName = members.notificationIconDataGetPackageName?.invoke(iconData) as? String,
                    entry = members.notificationIconDataGetEntry?.invoke(iconData),
                    onPatched = { drawable, icon ->
                        members.notificationIconDataIconField?.set(iconData, drawable)
                        members.notificationIconDataSmallIconField?.set(iconData, icon)
                        members.notificationIconDataIconColorField?.set(iconData, null)
                    },
                )
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "lockscreen:capsule:rounded_icon",
                    message = "锁屏胶囊圆角图标改写失败，交回 ColorOS 原实现",
                    cause = exception,
                    revision = configuration.snapshot.revision,
                )
            }
            chain.proceed()
        }
    }

    private fun installWrapWithWhiteBgHook() {
        val method = members.wrapWithWhiteBg ?: return
        hooks.install(method, "systemui.lockscreen.capsule.wrapWithWhiteBg") { chain ->
            if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
            val drawable = chain.args.getOrNull(0) as? Drawable
            if (drawable != null && replacementDrawables.contains(drawable)) {
                drawable
            } else {
                chain.proceed()
            }
        }
    }

    private fun installCapsuleCardBindHooks() {
        val cardMethods = listOfNotNull(
            members.capsuleSetupIconAndBadges,
            members.capsuleBind,
        )
        for (method in cardMethods) {
            hooks.install(method, "systemui.lockscreen.capsule.card_bind") { chain ->
                if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
                val innerData = chain.args.getOrNull(0) ?: return@install chain.proceed()
                val card = chain.thisObject ?: return@install chain.proceed()
                chain.proceed()
                scheduleAggregateGlyphRefresh(card, innerData)
            }
        }
    }

    private fun installCapsuleGroupIconHooks() {
        // Only the capsule-named coloring entry. Do not hook initIconViewColor /
        // initPillBgAndNumberColor — those are shared with shade and lock-screen stacks.
        val colorHooks = listOfNotNull(
            members.groupIconInitCapsuleIconColor,
            members.groupIconAccessInitCapsuleIconColor,
        )
        for (method in colorHooks) {
            hooks.install(method, "systemui.lockscreen.capsule.group_icon_color") { chain ->
                if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
                val entryIndex = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) 3 else 2
                val iconIndex = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) 2 else 1
                val entry = chain.args.getOrNull(entryIndex) ?: return@install chain.proceed()
                val iconView = chain.args.getOrNull(iconIndex) as? ImageView
                    ?: return@install chain.proceed()
                if (!isLockScreenIslandIconView(iconView)) return@install chain.proceed()
                if (overrideGroupIconColor(iconView, entry)) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun installCapsuleEntryIconDrawableHook() {
        val method = members.groupIconInitEntryIconDrawable ?: return
        hooks.install(method, "systemui.lockscreen.capsule.initEntryIconDrawable") { chain ->
            if (!lockScreenCapsuleEnabled()) return@install chain.proceed()
            if (chain.args.getOrNull(4) as? Boolean != true) return@install chain.proceed()
            val entry = chain.args.getOrNull(0) ?: return@install chain.proceed()
            val iconView = chain.args.getOrNull(1) as? ImageView
                ?: return@install chain.proceed()
            if (!isLockScreenIslandIconView(iconView)) return@install chain.proceed()
            val originalCallback = chain.args.getOrNull(5) as? Function1<Any?, Unit>
            val result = if (originalCallback != null) {
                // Chain.getArgs() is immutable; wrap the callback in a fresh argument array.
                val newArgs = chain.args.toTypedArray()
                newArgs[5] = object : Function1<Any?, Unit> {
                    override fun invoke(result: Any?) {
                        originalCallback.invoke(result)
                        iconView.post { overrideGroupIconColor(iconView, entry) }
                    }
                }
                chain.proceed(newArgs)
            } else {
                chain.proceed()
            }
            iconView.post { overrideGroupIconColor(iconView, entry) }
            result
        }
    }

    private fun scheduleAggregateGlyphRefresh(card: Any, innerData: Any) {
        if (!isAggregateCard(innerData)) return
        val iconView = members.capsuleCardGetIcon?.invoke(card) as? ImageView ?: return
        val apply = Runnable { applyAggregateGlyph(innerData, iconView) }
        iconView.post(apply)
        iconView.postDelayed(apply, 32)
        iconView.postDelayed(apply, 128)
    }

    private fun applyAggregateGlyph(innerData: Any, iconView: ImageView) {
        val entry = resolveEntryFromInnerData(innerData) ?: return
        if (!overrideGroupIconColor(iconView, entry)) return
        clearInnerDataHostTint(innerData)
    }

    private fun overrideGroupIconColor(iconView: ImageView, entry: Any): Boolean {
        if (!isLockScreenIslandIconView(iconView)) return false
        val sbn = try {
            members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "lockscreen:capsule:group_icon_color",
                message = "锁屏胶囊聚合 entry 读取失败",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            null
        } ?: return false
        val plan = resolveReplacementPlan(iconView.context, sbn.key, sbn.packageName, sbn)
            ?: return false
        if (!configuration.isCurrent(plan.snapshot)) return false
        applyLockScreenGlyph(iconView, plan.drawable, plan.isColorable)
        return true
    }

    private fun clearInnerDataHostTint(innerData: Any) {
        members.innerDataIconColorField?.set(innerData, null)
        val iconData = members.innerDataGetIconData?.invoke(innerData) ?: return
        members.notificationIconDataIconColorField?.set(iconData, null)
        val drawable = members.notificationIconDataIconField?.get(iconData) as? Drawable
        if (drawable != null) {
            replacementDrawables.add(drawable)
        }
    }

    private fun isAggregateCard(innerData: Any): Boolean {
        val cardType = members.innerDataGetCardType?.invoke(innerData)?.toString().orEmpty()
        return cardType == "AGGREGATE" || cardType == "GROUP"
    }

    private fun resolveEntryFromInnerData(innerData: Any): Any? {
        val iconData = members.innerDataGetIconData?.invoke(innerData) ?: return null
        members.notificationIconDataGetEntry?.invoke(iconData)?.let { return it }
        val reportData = members.innerDataGetReportDataList?.invoke(innerData) as? List<*>
        val latestEntry = reportData?.asSequence()
            ?.mapNotNull { item ->
                item?.javaClass?.methods
                    ?.firstOrNull { it.name == "getEntry" && it.parameterTypes.isEmpty() }
                    ?.invoke(item)
            }
            ?.lastOrNull()
        if (latestEntry != null) return latestEntry
        return null
    }

    private fun patchNotificationIconData(
        context: Context,
        iconData: Any?,
        key: String?,
        packageName: String?,
        entry: Any?,
        onPatched: (drawable: Drawable, icon: Icon) -> Unit,
    ) {
        val sbn = entry?.let {
            try {
                members.notificationEntryGetSbn.invoke(it) as? StatusBarNotification
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "lockscreen:capsule:entry",
                    message = "读取锁屏胶囊通知 entry 失败",
                    cause = exception,
                    revision = configuration.snapshot.revision,
                )
                null
            }
        }
        val plan = resolveReplacementPlan(context, key, packageName, sbn) ?: return
        if (!configuration.isCurrent(plan.snapshot)) return
        onPatched(plan.drawable, plan.icon)
        if (iconData != null) {
            members.notificationIconDataIconColorField?.set(iconData, null)
        }
    }

    private data class ReplacementPlan(
        val snapshot: RuntimeSnapshot,
        val icon: Icon,
        val drawable: Drawable,
        val isColorable: Boolean,
    )

    private fun resolveReplacementPlan(
        context: Context,
        key: String?,
        packageName: String?,
        sbn: StatusBarNotification?,
    ): ReplacementPlan? {
        if (!lockScreenCapsuleEnabled()) return null
        StatusBarIconReplacementCache.lookup(key, packageName)?.let { cached ->
            val drawable = try {
                cached.icon.loadDrawable(context)?.mutate()
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "lockscreen:capsule:load_cached",
                    message = "锁屏胶囊缓存图标解码失败",
                    cause = exception,
                    revision = configuration.snapshot.revision,
                )
                null
            } ?: return null
            replacementDrawables.add(drawable)
            return ReplacementPlan(
                snapshot = configuration.snapshot,
                icon = cached.icon,
                drawable = drawable,
                isColorable = cached.isColorable,
            )
        }

        val notification = sbn ?: return null
        val snapshot = configuration.snapshot
        return try {
            snapshot.resolver.resolveStatusBarIconPlan(
                context = context,
                sbn = notification,
                originalSmallIcon = notification.originalSmallIcon(diagnostics, snapshot.revision),
            )?.let { plan ->
                val drawable = plan.icon.loadDrawable(context)?.mutate() ?: return null
                replacementDrawables.add(drawable)
                ReplacementPlan(
                    snapshot = snapshot,
                    icon = plan.icon,
                    drawable = drawable,
                    isColorable = plan.isColorable,
                ).also {
                    if (configuration.isCurrent(snapshot)) {
                        StatusBarIconReplacementCache.put(
                            notificationKey = notification.key,
                            packageName = notification.packageName,
                            icon = plan.icon,
                            isColorable = plan.isColorable,
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "lockscreen:capsule:resolve",
                message = "锁屏胶囊图标解析失败",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            null
        }
    }

    private fun applyLockScreenGlyph(iconView: ImageView, drawable: Drawable, isColorable: Boolean) {
        replacementDrawables.add(drawable)
        iconView.clearColorFilter()
        iconView.imageTintList = null
        iconView.setImageDrawable(drawable)
        if (LockScreenCapsuleColorPolicy.shouldApplyWhiteGlyphTint(isColorable)) {
            iconView.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun isLockScreenIslandIconView(view: View?): Boolean {
        var current: View? = view
        while (current != null) {
            val name = current.javaClass.name
            if (
                name.contains("CapsuleNotificationCardView") ||
                name.contains("CapsuleNotificationIconView") ||
                name.contains("CapsuleNotificationContainerView")
            ) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun isMediaPlayerArg(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Int -> value != 0
        else -> false
    }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
