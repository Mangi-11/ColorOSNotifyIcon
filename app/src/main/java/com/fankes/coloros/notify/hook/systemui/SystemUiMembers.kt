package com.fankes.coloros.notify.hook.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.widget.ImageView
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.reflect.Reflection
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class StatusBarMembers(
    val updateGrayScale: Method,
    val setIsIconColorable: Method,
    val getIconDescriptor: Method,
    val iconManagerIconBuilder: Field,
    val iconBuilderContext: Field,
    val notificationEntryGetSbn: Method,
    val statusBarIcon: Field,
    val statusBarPreloadedIcon: Field,
)

internal data class OplusHeaderMembers(
    val getBase: Method,
    val updateIconColor: Method?,
    val updateIconRoundness: Method?,
)

internal data class PanelMembers(
    val notificationEntryGetSbn: Method,
    val notificationViewWrapperRow: Field,
    val expandableRowGetEntry: Method,
    val headerOnContentUpdated: Method?,
    val headerResolveHeaderViews: Method?,
    val headerSetIsChildInGroup: Method?,
    val headerGetIcon: Method?,
    val oplusHeader: OplusHeaderMembers?,
    val oplusGroupWrapper: Class<*>?,
    val oplusGroupInitIcon: Method?,
    val oplusGroupResolveHeaderViews: Method?,
)

internal data class RefreshMembers(
    val attach: Method?,
    val refreshNotifications: Method?,
)

internal object SystemUiMembers {

    fun resolveStatusBar(
        classLoader: ClassLoader,
        diagnostics: Diagnostics,
    ): StatusBarMembers? {
        val classes = ClassResolver(classLoader, diagnostics)
        val statusBarIconView = classes.required(STATUS_BAR_ICON_VIEW) ?: return null
        val statusBarIconController = classes.required(STATUS_BAR_ICON_CONTROLLER) ?: return null
        val iconManager = classes.required(ICON_MANAGER) ?: return null
        val iconBuilder = classes.required(ICON_BUILDER) ?: return null
        val notificationEntry = classes.required(NOTIFICATION_ENTRY) ?: return null
        val statusBarIcon = classes.required(STATUS_BAR_ICON) ?: return null

        fun missing(member: String, signature: String): StatusBarMembers? {
            diagnostics.memberMissing(
                scope = "systemui:statusbar:$member",
                message = "未找到精确成员：$signature",
            )
            return null
        }

        val updateGrayScale = Reflection.findMethodReturning(
            statusBarIconController,
            "updateStatusBarIconGrayScale",
            Void.TYPE,
            Drawable::class.java,
            statusBarIconView,
            StatusBarNotification::class.java,
        ) ?: return missing(
            "update_gray_scale",
            "StatusBarIconControllerExImpl.updateStatusBarIconGrayScale(Drawable, StatusBarIconView, StatusBarNotification): void",
        )
        val setIsIconColorable = Reflection.findMethodReturning(
            statusBarIconView,
            "setIsIconColorable",
            Void.TYPE,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return missing("set_colorable", "StatusBarIconView.setIsIconColorable(boolean): void")
        val getIconDescriptor = Reflection.findMethodReturning(
            iconManager,
            "getIconDescriptor",
            statusBarIcon,
            notificationEntry,
            Boolean::class.javaPrimitiveType!!,
        ) ?: return missing(
            "get_descriptor",
            "IconManager.getIconDescriptor(NotificationEntry, boolean): StatusBarIcon",
        )
        val iconManagerIconBuilder = Reflection.findField(iconManager, "iconBuilder", iconBuilder)
            ?: return missing("icon_builder", "IconManager.iconBuilder: IconBuilder")
        val iconBuilderContext = Reflection.findField(iconBuilder, "context", Context::class.java)
            ?: return missing("context", "IconBuilder.context: Context")
        val notificationEntryGetSbn = Reflection.findMethodReturning(
            notificationEntry,
            "getSbn",
            StatusBarNotification::class.java,
        ) ?: return missing("get_sbn", "NotificationEntry.getSbn(): StatusBarNotification")
        val statusBarIconField = Reflection.findField(statusBarIcon, "icon", Icon::class.java)
            ?: return missing("icon", "StatusBarIcon.icon: Icon")
        val statusBarPreloadedIcon = Reflection.findField(statusBarIcon, "preloadedIcon", Drawable::class.java)
            ?: return missing("preloaded_icon", "StatusBarIcon.preloadedIcon: Drawable")

        return StatusBarMembers(
            updateGrayScale = updateGrayScale,
            setIsIconColorable = setIsIconColorable,
            getIconDescriptor = getIconDescriptor,
            iconManagerIconBuilder = iconManagerIconBuilder,
            iconBuilderContext = iconBuilderContext,
            notificationEntryGetSbn = notificationEntryGetSbn,
            statusBarIcon = statusBarIconField,
            statusBarPreloadedIcon = statusBarPreloadedIcon,
        )
    }

    fun resolvePanel(
        classLoader: ClassLoader,
        diagnostics: Diagnostics,
    ): PanelMembers? {
        val classes = ClassResolver(classLoader, diagnostics)
        val notificationEntry = classes.optional(NOTIFICATION_ENTRY)
        val expandableRow = classes.optional(EXPANDABLE_ROW)
        val notificationViewWrapper = classes.optional(NOTIFICATION_VIEW_WRAPPER)
        if (notificationEntry == null || expandableRow == null || notificationViewWrapper == null) {
            diagnostics.memberMissing(
                scope = "systemui:panel:core_classes",
                message = "通知面板核心类不完整，跳过面板图标功能",
            )
            return null
        }

        val notificationEntryGetSbn = Reflection.findMethodReturning(
            notificationEntry,
            "getSbn",
            StatusBarNotification::class.java,
        )
        val notificationViewWrapperRow = Reflection.findField(
            notificationViewWrapper,
            "mRow",
            expandableRow,
        )
        val expandableRowGetEntry = Reflection.findMethodReturning(
            expandableRow,
            "getEntry",
            notificationEntry,
        )
        if (
            notificationEntryGetSbn == null ||
            notificationViewWrapperRow == null ||
            expandableRowGetEntry == null
        ) {
            diagnostics.memberMissing(
                scope = "systemui:panel:row_access",
                message = "通知面板行访问成员签名不匹配，跳过面板图标功能",
            )
            return null
        }

        val headerWrapper = classes.optional(NOTIFICATION_HEADER_WRAPPER)
        val headerOnContentUpdated = headerWrapper?.let {
            Reflection.findMethodReturning(it, "onContentUpdated", Void.TYPE, expandableRow)
        }
        val headerResolveHeaderViews = headerWrapper?.let {
            Reflection.findMethodReturning(it, "resolveHeaderViews", Void.TYPE)
        }
        val headerSetIsChildInGroup = headerWrapper?.let {
            Reflection.findMethodReturning(
                it,
                "setIsChildInGroup",
                Void.TYPE,
                Boolean::class.javaPrimitiveType!!,
            )
        }
        val headerGetIcon = headerWrapper?.let {
            Reflection.findMethodReturning(it, "getIcon", ImageView::class.java)
        }
        if (
            headerWrapper == null ||
            headerGetIcon == null ||
            (headerOnContentUpdated == null && headerResolveHeaderViews == null)
        ) {
            diagnostics.memberMissing(
                scope = "systemui:panel:header",
                message = "通知 Header 成员签名不完整，相关面板路径将独立跳过",
            )
        }
        if (headerWrapper != null && headerSetIsChildInGroup == null) {
            diagnostics.memberMissing(
                scope = "systemui:panel:header_group_state",
                message = "未找到 setIsChildInGroup(boolean)，跳过退出分组后的图标恢复",
            )
        }

        val oplusGroupWrapper = classes.optional(OPLUS_GROUP_WRAPPER)
        val oplusGroupInitIcon = oplusGroupWrapper?.let {
            Reflection.findMethodReturning(it, "initIcon", Void.TYPE)
        }
        val oplusGroupResolveHeaderViews = oplusGroupWrapper?.let {
            Reflection.findMethodReturning(it, "resolveHeaderViews", Void.TYPE)
        }
        if (
            oplusGroupWrapper == null ||
            (oplusGroupInitIcon == null && oplusGroupResolveHeaderViews == null)
        ) {
            diagnostics.memberMissing(
                scope = "systemui:panel:oplus_group",
                message = "Oplus 聚合摘要成员签名不完整，跳过聚合摘要图标路径",
            )
        }

        val oplusHeaderExtension = classes.optional(OPLUS_HEADER_EXTENSION)
        if (oplusHeaderExtension == null) {
            diagnostics.memberMissing(
                scope = "systemui:panel:oplus_header_class",
                message = "未找到 Oplus Header 扩展，跳过着色与圆角覆盖保护",
            )
        }
        val oplusHeader = oplusHeaderExtension?.let { extension ->
            val getBase = Reflection.findMethodReturning(
                extension,
                "getBase",
                notificationViewWrapper,
            )
            val updateIconColor = Reflection.findMethodReturning(
                extension,
                "updateIconColor",
                Boolean::class.javaPrimitiveType!!,
            )
            val updateIconRoundness = Reflection.findMethodReturning(
                extension,
                "updateIconRoundness",
                Void.TYPE,
                Boolean::class.javaPrimitiveType!!,
            )
            if (getBase == null) {
                diagnostics.memberMissing(
                    scope = "systemui:panel:oplus_header",
                    message = "Oplus Header 基类访问签名不匹配，跳过着色与圆角覆盖保护",
                )
                null
            } else {
                if (updateIconColor == null) {
                    diagnostics.memberMissing(
                        scope = "systemui:panel:oplus_header_color",
                        message = "未找到 updateIconColor()，跳过二次着色保护",
                    )
                }
                if (updateIconRoundness == null) {
                    diagnostics.memberMissing(
                        scope = "systemui:panel:oplus_header_roundness",
                        message = "未找到 updateIconRoundness(boolean)，跳过异步圆角覆盖修复",
                    )
                }
                OplusHeaderMembers(getBase, updateIconColor, updateIconRoundness)
            }
        }

        return PanelMembers(
            notificationEntryGetSbn = notificationEntryGetSbn,
            notificationViewWrapperRow = notificationViewWrapperRow,
            expandableRowGetEntry = expandableRowGetEntry,
            headerOnContentUpdated = headerOnContentUpdated,
            headerResolveHeaderViews = headerResolveHeaderViews,
            headerSetIsChildInGroup = headerSetIsChildInGroup,
            headerGetIcon = headerGetIcon,
            oplusHeader = oplusHeader,
            oplusGroupWrapper = oplusGroupWrapper,
            oplusGroupInitIcon = oplusGroupInitIcon,
            oplusGroupResolveHeaderViews = oplusGroupResolveHeaderViews,
        )
    }

    fun resolveRefresh(classLoader: ClassLoader): RefreshMembers? {
        val coordinator = loadOptional(VIEW_CONFIG_COORDINATOR, classLoader) ?: return null
        val pipeline = loadOptional(NOTIF_PIPELINE, classLoader)
        val attach = pipeline?.let {
            Reflection.findMethodReturning(coordinator, "attach", Void.TYPE, it)
        }
        val refresh = Reflection.findMethodReturning(
            coordinator,
            "updateNotificationsOnDensityOrFontScaleChanged",
            Void.TYPE,
        )
        return RefreshMembers(
            attach = attach,
            refreshNotifications = refresh,
        )
    }

    fun resolveUseAppIcon(classLoader: ClassLoader): Method? {
        val clazz = loadOptional(OPLUS_SMALL_ICON_UTIL, classLoader) ?: return null
        return Reflection.findMethodReturning(
            clazz,
            "useAppIconForSmallIcon",
            Boolean::class.javaPrimitiveType!!,
            android.app.Notification::class.java,
        )
    }

    private class ClassResolver(
        private val classLoader: ClassLoader,
        private val diagnostics: Diagnostics,
    ) {
        fun required(name: String): Class<*>? = Reflection.loadClassOrNull(name, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "systemui:class:$name",
                message = "未找到 SystemUI 类：$name",
                cause = cause,
            )
        }

        fun optional(name: String): Class<*>? = loadOptional(name, classLoader)
    }

    private fun loadOptional(name: String, classLoader: ClassLoader): Class<*>? = try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    }

    private const val STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
    private const val STATUS_BAR_ICON_CONTROLLER =
        "com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl"
    private const val ICON_MANAGER = "com.android.systemui.statusbar.notification.icon.IconManager"
    private const val ICON_BUILDER = "com.android.systemui.statusbar.notification.icon.IconBuilder"
    private const val NOTIFICATION_ENTRY =
        "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
    private const val EXPANDABLE_ROW =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val NOTIFICATION_VIEW_WRAPPER =
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper"
    private const val NOTIFICATION_HEADER_WRAPPER =
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper"
    private const val OPLUS_GROUP_WRAPPER =
        "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupTemplateWrapper"
    private const val OPLUS_HEADER_EXTENSION =
        "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp"
    private const val VIEW_CONFIG_COORDINATOR =
        "com.android.systemui.statusbar.notification.collection.coordinator.ViewConfigCoordinator"
    private const val NOTIF_PIPELINE =
        "com.android.systemui.statusbar.notification.collection.NotifPipeline"
    private const val OPLUS_SMALL_ICON_UTIL =
        "com.oplus.systemui.statusbar.notification.util.OplusNotificationSmallIconUtil"
}
