package com.fankes.coloros.notify.hook.systemui

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.HookRegistrar
import com.fankes.coloros.notify.hook.runtimeFailure

/**
 * Classic AOD clock icons are pushed by AodPluginCallImpl.updateNotificationIconData from
 * LockScreenNotificationIconData (built from Notification.smallIcon / app icons), not IconManager.
 */
internal class AodNotificationHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: AodMembers,
) {
    fun install() {
        installPluginDispatchHook()
        installLockScreenIconDataCtorHook()
        installNotificationLayoutHook()
    }

    /**
     * Last mile before AOD plugin receives package names + drawables.
     * Mutate in-place using the same icons already applied to the status bar.
     */
    private fun installPluginDispatchHook() {
        val method = members.updateNotificationIconData ?: return
        hooks.install(method, "systemui.aod.updateNotificationIconData") { chain ->
            try {
                rewriteLockScreenIconData()
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "aod:plugin_dispatch",
                    message = "下发前改写息屏图标失败，继续原实现",
                    cause = exception,
                    revision = configuration.snapshot.revision,
                )
            }
            chain.proceed()
        }
    }

    private fun rewriteLockScreenIconData() {
        val setIcon = members.lockScreenIconDataSetIcon ?: return
        val getKey = members.lockScreenIconDataGetKey ?: return
        val getPackageName = members.lockScreenIconDataGetPackageName ?: return
        val dependencyField = members.dependencyExInstance ?: return
        val getDependency = members.dependencyExGetDependency ?: return
        val dispatcherClass = members.lockScreenDispatcherClass ?: return
        val getIconData = members.getLockScreenNotificationIconData ?: return
        val context = appContext() ?: return

        val dependency = dependencyField.get(null) ?: return
        val dispatcher = getDependency.invoke(dependency, dispatcherClass) ?: return
        val flow = getIconData.invoke(dispatcher) ?: return
        val value = flow.javaClass.methods
            .firstOrNull { it.name == "getValue" && it.parameterTypes.isEmpty() }
            ?.invoke(flow)
            as? List<*>
            ?: return

        for (item in value) {
            if (item == null) continue
            val key = getKey.invoke(item) as? String
            val packageName = getPackageName.invoke(item) as? String
            val drawable = resolveReplacementDrawable(context, key, packageName) ?: continue
            setIcon.invoke(item, drawable)
        }
    }

    private fun installLockScreenIconDataCtorHook() {
        val ctor = members.lockScreenIconDataCtor ?: return
        hooks.install(ctor, "systemui.aod.lockScreenIconData") { chain ->
            val key = chain.args.getOrNull(0) as? String
            val packageName = chain.args.getOrNull(1) as? String
            val context = appContext()
            // Chain.getArgs() is immutable; only build a new array when a replacement exists.
            val replacement = context?.let { resolveReplacementDrawable(it, key, packageName) }
            if (replacement != null) {
                chain.proceed(chain.args.toTypedArray().also { it[2] = replacement })
            } else {
                chain.proceed()
            }
        }
    }

    private fun installNotificationLayoutHook() {
        val method = members.updateNotificationView ?: return
        val layoutContext = members.layoutContext ?: return
        val notificationSmallIcon = members.notificationSmallIcon ?: return
        hooks.install(method, "systemui.aod.updateNotificationView") { chain ->
            val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                ?: return@install chain.proceed()
            val layout = chain.thisObject ?: return@install chain.proceed()
            val context = layoutContext.get(layout) as? Context
                ?: return@install chain.proceed()
            val replacement = resolveReplacementIcon(context, sbn.key, sbn.packageName, sbn)
                ?: return@install chain.proceed()
            val previous = notificationSmallIcon.get(sbn.notification) as? Icon
            notificationSmallIcon.set(sbn.notification, replacement)
            try {
                chain.proceed()
            } finally {
                notificationSmallIcon.set(sbn.notification, previous)
            }
        }
    }

    private fun resolveReplacementDrawable(
        context: Context,
        key: String?,
        packageName: String?,
    ): Drawable? {
        val icon = resolveReplacementIcon(context, key, packageName, sbn = null) ?: return null
        return try {
            icon.loadDrawable(context)?.mutate()
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "aod:load_drawable",
                message = "息屏替换图标解码失败",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            null
        }
    }

    private fun resolveReplacementIcon(
        context: Context,
        key: String?,
        packageName: String?,
        sbn: StatusBarNotification?,
    ): Icon? {
        StatusBarIconReplacementCache.iconFor(key, packageName)?.let { return it }
        val notification = sbn ?: return null
        val snapshot = configuration.snapshot
        return try {
            snapshot.resolver.resolveStatusBarIconPlan(
                context = context,
                sbn = notification,
                originalSmallIcon = notification.originalSmallIcon(diagnostics, snapshot.revision),
            )?.also { plan ->
                if (configuration.isCurrent(snapshot)) {
                    StatusBarIconReplacementCache.put(
                        notificationKey = notification.key,
                        packageName = notification.packageName,
                        icon = plan.icon,
                        isColorable = plan.isColorable,
                    )
                }
            }?.icon
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "aod:resolve_icon",
                message = "息屏图标解析失败",
                cause = exception,
                revision = snapshot.revision,
            )
            null
        }
    }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
