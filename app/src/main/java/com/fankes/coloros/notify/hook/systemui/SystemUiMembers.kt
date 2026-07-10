package com.fankes.coloros.notify.hook.systemui

import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.fankes.coloros.notify.hook.reflect.Reflection
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class OplusHeaderMembers(
    val getBase: Method,
    val updateIconColor: Method,
)

internal data class SystemUiMembers(
    val statusBarUpdateGrayScale: Method,
    val statusBarSetIsIconColorable: Method,
    val iconManagerGetIconDescriptor: Method,
    val iconManagerIconBuilderField: Field,
    val iconBuilderContextField: Field,
    val notificationEntryGetSbn: Method,
    val statusBarIconField: Field,
    val statusBarPreloadedIconField: Field,
    val notificationHeaderOnContentUpdated: Method?,
    val notificationHeaderResolveHeaderViews: Method?,
    val notificationHeaderGetIcon: Method?,
    val notificationViewWrapperRowField: Field?,
    val expandableRowGetEntry: Method?,
    val oplusHeader: OplusHeaderMembers?,
    val oplusGroupInitIcon: Method?,
    val oplusGroupResolveHeaderViews: Method?,
    val viewConfigCoordinatorConstructors: List<Constructor<*>>,
    val viewConfigCoordinatorAttach: Method?,
    val viewConfigCoordinatorRefreshNotifications: Method?,
) {
    companion object {
        fun resolve(
            classLoader: ClassLoader,
            warn: (String, String, Throwable?) -> Unit,
        ): SystemUiMembers? {
            fun warnMissing(key: String, message: String): SystemUiMembers? {
                warn(key, message, null)
                return null
            }

            fun load(name: String): Class<*>? = Reflection.loadClassOrNull(name, classLoader) {
                warn("class:$name", "未找到类：$name", it)
            }

            fun loadOptional(name: String): Class<*>? = runCatching {
                Class.forName(name, false, classLoader)
            }.getOrNull()

            val statusBarIconViewClass = load("com.android.systemui.statusbar.StatusBarIconView")
                ?: return null
            val statusBarIconControllerClass = load("com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl")
                ?: return null
            val iconManagerClass = load("com.android.systemui.statusbar.notification.icon.IconManager")
                ?: return null
            val iconBuilderClass = load("com.android.systemui.statusbar.notification.icon.IconBuilder")
                ?: return null
            val notificationEntryClass = load("com.android.systemui.statusbar.notification.collection.NotificationEntry")
                ?: return null
            val statusBarIconClass = load("com.android.internal.statusbar.StatusBarIcon")
                ?: return null
            val expandableNotificationRowClass =
                loadOptional("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
            val notificationViewWrapperClass =
                loadOptional("com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper")
            val notificationHeaderViewWrapperClass =
                loadOptional("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper")
            val oplusGroupTemplateWrapperClass =
                loadOptional("com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupTemplateWrapper")
            val oplusHeaderViewWrapperExImpClass =
                loadOptional("com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp")
            val viewConfigCoordinatorClass =
                loadOptional("com.android.systemui.statusbar.notification.collection.coordinator.ViewConfigCoordinator")
            val notifPipelineClass =
                loadOptional("com.android.systemui.statusbar.notification.collection.NotifPipeline")

            val statusBarUpdateGrayScale = Reflection.findMethod(
                statusBarIconControllerClass,
                "updateStatusBarIconGrayScale",
                Drawable::class.java,
                statusBarIconViewClass,
                StatusBarNotification::class.java,
            ) ?: return warnMissing(
                "member:statusbar.gray",
                "未找到 StatusBarIconControllerExImpl.updateStatusBarIconGrayScale"
            )
            val statusBarSetIsIconColorable = Reflection.findMethod(
                statusBarIconViewClass,
                "setIsIconColorable",
                Boolean::class.javaPrimitiveType!!,
            ) ?: return warnMissing("member:statusbar.colorable", "未找到 StatusBarIconView.setIsIconColorable(boolean)")
            val iconManagerGetIconDescriptor = Reflection.findMethod(
                iconManagerClass,
                "getIconDescriptor",
                notificationEntryClass,
                Boolean::class.javaPrimitiveType!!,
            ) ?: return warnMissing(
                "member:iconmanager.getIconDescriptor",
                "未找到 IconManager.getIconDescriptor(NotificationEntry, boolean)"
            )
            val iconManagerIconBuilderField = Reflection.findField(iconManagerClass, "iconBuilder")
                ?: return warnMissing("member:iconmanager.iconBuilder", "未找到 IconManager.iconBuilder")
            val iconBuilderContextField = Reflection.findField(iconBuilderClass, "context")
                ?: return warnMissing("member:iconbuilder.context", "未找到 IconBuilder.context")
            val notificationEntryGetSbn = Reflection.findMethod(notificationEntryClass, "getSbn")
                ?: return warnMissing("member:entry.getSbn", "未找到 NotificationEntry.getSbn()")
            val statusBarIconField = Reflection.findField(statusBarIconClass, "icon")
                ?: return warnMissing("member:statusbar.icon", "未找到 StatusBarIcon.icon")
            val statusBarPreloadedIconField = Reflection.findField(statusBarIconClass, "preloadedIcon")
                ?: return warnMissing("member:statusbar.preloaded", "未找到 StatusBarIcon.preloadedIcon")
            val notificationHeaderOnContentUpdated = notificationHeaderViewWrapperClass
                ?.takeIf { expandableNotificationRowClass != null }
                ?.let { Reflection.findMethod(it, "onContentUpdated", expandableNotificationRowClass!!) }
            val notificationHeaderResolveHeaderViews = notificationHeaderViewWrapperClass
                ?.let { Reflection.findMethod(it, "resolveHeaderViews") }
            val notificationHeaderGetIcon = notificationHeaderViewWrapperClass
                ?.let { Reflection.findMethod(it, "getIcon") }
            val notificationViewWrapperRowField = notificationViewWrapperClass
                ?.let { Reflection.findField(it, "mRow") }
            val expandableRowGetEntry = expandableNotificationRowClass
                ?.let { Reflection.findMethod(it, "getEntry") }
            val oplusHeader = oplusHeaderViewWrapperExImpClass?.let { wrapperClass ->
                val getBase = Reflection.findMethod(wrapperClass, "getBase")
                val updateIconColor = Reflection.findMethod(wrapperClass, "updateIconColor")
                    ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
                if (getBase == null || updateIconColor == null) {
                    warn(
                        "member:oplus.header.color",
                        "Oplus Header 已存在，但 getBase() 或 updateIconColor(): boolean 签名不匹配",
                        null,
                    )
                    null
                } else {
                    OplusHeaderMembers(getBase, updateIconColor)
                }
            }
            val oplusGroupInitIcon = oplusGroupTemplateWrapperClass
                ?.let { Reflection.findMethod(it, "initIcon") }
            val oplusGroupResolveHeaderViews = oplusGroupTemplateWrapperClass
                ?.let { Reflection.findMethod(it, "resolveHeaderViews") }
            val viewConfigCoordinatorAttach = viewConfigCoordinatorClass
                ?.takeIf { notifPipelineClass != null }
                ?.let { Reflection.findMethod(it, "attach", notifPipelineClass!!) }
            val viewConfigCoordinatorRefreshNotifications = viewConfigCoordinatorClass
                ?.let { Reflection.findMethod(it, "updateNotificationsOnDensityOrFontScaleChanged") }
            val viewConfigCoordinatorConstructors = viewConfigCoordinatorClass
                ?.declaredConstructors
                ?.onEach { it.isAccessible = true }
                ?.toList()
                .orEmpty()

            return SystemUiMembers(
                statusBarUpdateGrayScale = statusBarUpdateGrayScale,
                statusBarSetIsIconColorable = statusBarSetIsIconColorable,
                iconManagerGetIconDescriptor = iconManagerGetIconDescriptor,
                iconManagerIconBuilderField = iconManagerIconBuilderField,
                iconBuilderContextField = iconBuilderContextField,
                notificationEntryGetSbn = notificationEntryGetSbn,
                statusBarIconField = statusBarIconField,
                statusBarPreloadedIconField = statusBarPreloadedIconField,
                notificationHeaderOnContentUpdated = notificationHeaderOnContentUpdated,
                notificationHeaderResolveHeaderViews = notificationHeaderResolveHeaderViews,
                notificationHeaderGetIcon = notificationHeaderGetIcon,
                notificationViewWrapperRowField = notificationViewWrapperRowField,
                expandableRowGetEntry = expandableRowGetEntry,
                oplusHeader = oplusHeader,
                oplusGroupInitIcon = oplusGroupInitIcon,
                oplusGroupResolveHeaderViews = oplusGroupResolveHeaderViews,
                viewConfigCoordinatorConstructors = viewConfigCoordinatorConstructors,
                viewConfigCoordinatorAttach = viewConfigCoordinatorAttach,
                viewConfigCoordinatorRefreshNotifications = viewConfigCoordinatorRefreshNotifications,
            )
        }
    }
}
