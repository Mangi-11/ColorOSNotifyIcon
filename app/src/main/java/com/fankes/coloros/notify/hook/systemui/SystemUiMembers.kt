package com.fankes.coloros.notify.hook.systemui

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout
import android.widget.ImageView
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.reflect.Reflection
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class StatusBarMembers(
    val updateGrayScale: Method,
    val setIsIconColorable: Method,
    val setStatusBarIcon: Method,
    val getIconDescriptor: Method,
    val iconManagerIconBuilder: Field,
    val iconBuilderContext: Field,
    val notificationEntryGetSbn: Method,
    val cloneStatusBarIcon: Method,
    val statusBarIcon: Field,
    val statusBarPreloadedIcon: Field,
    val statusBarIconType: Field,
    val peopleAvatarIconType: Any,
    val notificationSmallIcon: Field?,
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
    val oplusGroupGetIconView: Method?,
    val groupIconInitIconViewColor: Method?,
    val groupIconInitPillBgAndNumberColor: Method?,
    val groupIconInitEntryIconDrawable: Method?,
    val notificationEntryGetKey: Method?,
)

internal data class RefreshMembers(
    val attach: Method?,
    val refreshNotifications: Method?,
)

internal data class EntryUseAppIconMembers(
    val predicate: Method,
    val getBase: Method,
    val notificationEntryGetSbn: Method,
)

internal data class LockScreenCapsuleMembers(
    val notificationIconDataCtors: List<java.lang.reflect.Constructor<*>>,
    val notificationEntryGetSbn: Method,
    val capsuleGetRoundedIcon: Method?,
    val capsuleSetupIconAndBadges: Method?,
    val capsuleBind: Method?,
    val capsuleCardGetIcon: Method?,
    val notificationIconDataGetEntry: Method?,
    val notificationIconDataGetKey: Method?,
    val notificationIconDataGetPackageName: Method?,
    val notificationIconDataIconField: Field?,
    val notificationIconDataSmallIconField: Field?,
    val notificationIconDataIconColorField: Field?,
    val innerDataGetIconData: Method?,
    val innerDataGetCardType: Method?,
    val innerDataGetReportDataList: Method?,
    val innerDataIconColorField: Field?,
    val groupIconInitCapsuleIconColor: Method?,
    val groupIconAccessInitCapsuleIconColor: Method?,
    val groupIconAccessInitIconViewColor: Method?,
    val groupIconInitPillBgAndNumberColor: Method?,
    val groupIconInitEntryIconDrawable: Method?,
    val notificationSmallIcon: Field?,
    val wrapWithWhiteBg: Method?,
)

internal data class AodMembers(
    val updateNotificationView: Method?,
    val layoutContext: Field?,
    val notificationSmallIcon: Field?,
    val lockScreenIconDataCtor: java.lang.reflect.Constructor<*>?,
    val lockScreenIconDataSetIcon: Method?,
    val lockScreenIconDataGetKey: Method?,
    val lockScreenIconDataGetPackageName: Method?,
    val updateNotificationIconData: Method?,
    val dependencyExClass: Class<*>?,
    val dependencyExInstance: Field?,
    val dependencyExGetDependency: Method?,
    val lockScreenDispatcherClass: Class<*>?,
    val getLockScreenNotificationIconData: Method?,
)

internal data class FullScreenBannerMembers(
    val notificationEntryGetSbn: Method,
    val showBanner: Method?,
    val buildNotification: Method?,
    val updateBanner: Method?,
    val bannerSetIcon: Method?,
    val builderIcon: Field?,
    val builderAvatarIcon: Field?,
    val builderLargeIconWithBadge: Field?,
    val lastNotificationEntry: Field?,
    val helperContext: Field?,
    val helperFullScreenBanner: Field?,
    val bannerIconView: Field?,
    val bannerHelper: Field?,
    val bannerPackageName: Field?,
    val backgroundSetIcon: Method?,
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
        val statusBarIconType = classes.required(STATUS_BAR_ICON_TYPE) ?: return null

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
        val setStatusBarIcon = Reflection.findMethodReturning(
            statusBarIconView,
            "set",
            Boolean::class.javaPrimitiveType!!,
            statusBarIcon,
        ) ?: return missing("set_icon", "StatusBarIconView.set(StatusBarIcon): boolean")
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
        val cloneStatusBarIcon = Reflection.findMethodReturning(
            statusBarIcon,
            "clone",
            statusBarIcon,
        ) ?: return missing("clone_icon", "StatusBarIcon.clone(): StatusBarIcon")
        val statusBarIconField = Reflection.findField(statusBarIcon, "icon", Icon::class.java)
            ?: return missing("icon", "StatusBarIcon.icon: Icon")
        val statusBarPreloadedIcon = Reflection.findField(statusBarIcon, "preloadedIcon", Drawable::class.java)
            ?: return missing("preloaded_icon", "StatusBarIcon.preloadedIcon: Drawable")
        val statusBarIconTypeField = Reflection.findField(statusBarIcon, "type", statusBarIconType)
            ?: return missing("icon_type", "StatusBarIcon.type: StatusBarIcon.Type")
        val peopleAvatarIconType = statusBarIconType.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == PEOPLE_AVATAR_ICON_TYPE }
            ?: return missing("people_avatar_type", "StatusBarIcon.Type.PeopleAvatar")
        val notificationSmallIcon = Reflection.findField(
            Notification::class.java,
            "mSmallIcon",
            Icon::class.java,
        )

        return StatusBarMembers(
            updateGrayScale = updateGrayScale,
            setIsIconColorable = setIsIconColorable,
            setStatusBarIcon = setStatusBarIcon,
            getIconDescriptor = getIconDescriptor,
            iconManagerIconBuilder = iconManagerIconBuilder,
            iconBuilderContext = iconBuilderContext,
            notificationEntryGetSbn = notificationEntryGetSbn,
            cloneStatusBarIcon = cloneStatusBarIcon,
            statusBarIcon = statusBarIconField,
            statusBarPreloadedIcon = statusBarPreloadedIcon,
            statusBarIconType = statusBarIconTypeField,
            peopleAvatarIconType = peopleAvatarIconType,
            notificationSmallIcon = notificationSmallIcon,
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
        val oplusGroupGetIconView = oplusGroupWrapper?.let {
            Reflection.findMethodReturning(it, "getIconView", ImageView::class.java)
        }

        val groupIconManager = classes.optional(GROUP_ICON_MANAGER)
        val iconInfoClass = classes.optional(GROUP_ICON_INFO)
        val cachingIconView = classes.optional(CACHING_ICON_VIEW)
        val groupIconInitIconViewColor = if (
            groupIconManager != null && iconInfoClass != null && cachingIconView != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initIconViewColor",
                Void.TYPE,
                iconInfoClass,
                cachingIconView,
                notificationEntry,
            )
        } else {
            null
        }
        val groupIconInitPillBgAndNumberColor = if (
            groupIconManager != null && iconInfoClass != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initPillBgAndNumberColor",
                Void.TYPE,
                android.widget.FrameLayout::class.java,
                iconInfoClass,
                android.widget.TextView::class.java,
            )
        } else {
            null
        }
        // ColorOS 17 dropped OplusNotificationGroupTemplateWrapper and initIconViewColor; the
        // collapsed summary icon is OplusGroupNotificationRowIconView, fed through this entry.
        val hostFunction1 = classes.optional(KOTLIN_FUNCTION1)
        val groupIconInitEntryIconDrawable = if (
            groupIconManager != null && cachingIconView != null && hostFunction1 != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initEntryIconDrawable",
                Void.TYPE,
                notificationEntry,
                cachingIconView,
                android.widget.TextView::class.java,
                FrameLayout::class.java,
                Boolean::class.javaPrimitiveType!!,
                hostFunction1,
            )
        } else {
            null
        }
        val notificationEntryGetKey = Reflection.findMethodReturning(
            notificationEntry,
            "getKey",
            String::class.java,
        )
        val hasLegacyGroupPath = oplusGroupWrapper != null &&
            (oplusGroupInitIcon != null || oplusGroupResolveHeaderViews != null)
        if (!hasLegacyGroupPath && groupIconInitEntryIconDrawable == null) {
            diagnostics.memberMissing(
                scope = "systemui:panel:oplus_group",
                message = "Oplus 聚合摘要成员签名不完整，跳过聚合摘要图标路径",
            )
        }
        if (
            (groupIconInitIconViewColor == null && groupIconInitEntryIconDrawable == null) ||
            groupIconInitPillBgAndNumberColor == null
        ) {
            diagnostics.memberMissing(
                scope = "systemui:panel:group_icon_manager",
                message = "GroupIconManager 着色成员不完整，折叠分组可能仍按宿主取色绘制色块",
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
            oplusGroupGetIconView = oplusGroupGetIconView,
            groupIconInitIconViewColor = groupIconInitIconViewColor,
            groupIconInitPillBgAndNumberColor = groupIconInitPillBgAndNumberColor,
            groupIconInitEntryIconDrawable = groupIconInitEntryIconDrawable,
            notificationEntryGetKey = notificationEntryGetKey,
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

    fun resolveLockScreenCapsule(
        classLoader: ClassLoader,
        diagnostics: Diagnostics,
    ): LockScreenCapsuleMembers? {
        val notificationEntry = loadOptional(NOTIFICATION_ENTRY, classLoader) ?: return null
        val notificationEntryGetSbn = Reflection.findMethodReturning(
            notificationEntry,
            "getSbn",
            StatusBarNotification::class.java,
        ) ?: run {
            diagnostics.memberMissing(
                scope = "systemui:lockscreen:capsule:entry",
                message = "未找到 NotificationEntry.getSbn()，跳过锁屏胶囊图标替换",
            )
            return null
        }

        val iconDataClass = loadOptional(CAPSULE_NOTIFICATION_ICON_DATA, classLoader)
        val ctors = iconDataClass?.declaredConstructors.orEmpty().filter { ctor ->
            val params = ctor.parameterTypes
            params.size == 8 &&
                params[0] == String::class.java &&
                params[1] == String::class.java &&
                Drawable::class.java.isAssignableFrom(params[2]) &&
                params[3] == Icon::class.java &&
                (params[4] == Boolean::class.javaPrimitiveType || params[4] == java.lang.Boolean.TYPE) &&
                params[5] == Integer::class.java &&
                notificationEntry.isAssignableFrom(params[6])
        }.onEach { it.isAccessible = true }

        if (ctors.isEmpty()) {
            diagnostics.memberMissing(
                scope = "systemui:lockscreen:capsule:icon_data",
                message = "未找到 CapsuleNotificationDataController.NotificationIconData 构造函数（ColorOS 16 锁屏胶囊）",
            )
            return null
        }

        val capsuleCardView = loadOptional(CAPSULE_NOTIFICATION_CARD_VIEW, classLoader)
        val innerDataClass = loadOptional(CAPSULE_NOTIFICATION_INNER_DATA, classLoader)
        val capsuleGetRoundedIcon = if (capsuleCardView != null && iconDataClass != null) {
            Reflection.findMethodReturning(
                capsuleCardView,
                "getRoundedIcon",
                Drawable::class.java,
                iconDataClass,
            )
        } else {
            null
        }
        val capsuleSetupIconAndBadges = if (capsuleCardView != null && innerDataClass != null) {
            Reflection.findMethodReturning(
                capsuleCardView,
                "setupIconAndBadges",
                Void.TYPE,
                innerDataClass,
            )
        } else {
            null
        }
        val capsuleBind = if (capsuleCardView != null && innerDataClass != null) {
            Reflection.findMethodReturning(
                capsuleCardView,
                "bind",
                Void.TYPE,
                innerDataClass,
            )
        } else {
            null
        }
        val capsuleCardGetIcon = capsuleCardView?.let {
            Reflection.findMethodReturning(it, "getIcon", ImageView::class.java)
        }
        val notificationIconDataGetEntry = iconDataClass?.let {
            Reflection.findMethodReturning(it, "getEntry", notificationEntry)
        }
        val notificationIconDataGetKey = iconDataClass?.let {
            Reflection.findMethodReturning(it, "getKey", String::class.java)
        }
        val notificationIconDataGetPackageName = iconDataClass?.let {
            Reflection.findMethodReturning(it, "getPackageName", String::class.java)
        }
        val notificationIconDataIconField = iconDataClass?.let {
            Reflection.findField(it, "icon", Drawable::class.java)
        }
        val notificationIconDataSmallIconField = iconDataClass?.let {
            Reflection.findField(it, "smallIcon", Icon::class.java)
        }
        val notificationIconDataIconColorField = iconDataClass?.let {
            Reflection.findField(it, "iconColor", Integer::class.java)
        }
        val innerDataGetIconData = innerDataClass?.let {
            Reflection.findMethodReturning(it, "getIconData", iconDataClass)
        }
        val innerDataGetCardType = innerDataClass?.let { clazz ->
            loadOptional(CAPSULE_NOTIFICATION_CARD_TYPE, classLoader)?.let { cardType ->
                Reflection.findMethodReturning(clazz, "getCardType", cardType)
            }
        }
        val innerDataGetReportDataList = innerDataClass?.let {
            Reflection.findMethod(it, "getReportDataList")
        }
        val innerDataIconColorField = innerDataClass?.let {
            Reflection.findField(it, "iconColor", Integer::class.java)
        }

        val groupIconManager = loadOptional(GROUP_ICON_MANAGER, classLoader)
        val iconInfoClass = loadOptional(GROUP_ICON_INFO, classLoader)
        val cachingIconView = loadOptional(CACHING_ICON_VIEW, classLoader)
        val groupIconInitCapsuleIconColor = if (
            groupIconManager != null && iconInfoClass != null && cachingIconView != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initCapsuleIconColor",
                Void.TYPE,
                iconInfoClass,
                cachingIconView,
                notificationEntry,
            )
        } else {
            null
        }
        val groupIconAccessInitCapsuleIconColor = if (
            groupIconManager != null && iconInfoClass != null && cachingIconView != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "access\$initCapsuleIconColor",
                Void.TYPE,
                groupIconManager,
                iconInfoClass,
                cachingIconView,
                notificationEntry,
            )
        } else {
            null
        }
        val groupIconAccessInitIconViewColor = if (
            groupIconManager != null && iconInfoClass != null && cachingIconView != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "access\$initIconViewColor",
                Void.TYPE,
                groupIconManager,
                iconInfoClass,
                cachingIconView,
                notificationEntry,
            )
        } else {
            null
        }
        val groupIconInitPillBgAndNumberColor = if (
            groupIconManager != null && iconInfoClass != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initPillBgAndNumberColor",
                Void.TYPE,
                FrameLayout::class.java,
                iconInfoClass,
                android.widget.TextView::class.java,
            )
        } else {
            null
        }
        // SystemUI bundles its own Kotlin stdlib; the module's Function1 is a different class.
        val hostFunction1 = loadOptional(KOTLIN_FUNCTION1, classLoader)
        val groupIconInitEntryIconDrawable = if (
            groupIconManager != null &&
            cachingIconView != null &&
            hostFunction1 != null
        ) {
            Reflection.findMethodReturning(
                groupIconManager,
                "initEntryIconDrawable",
                Void.TYPE,
                notificationEntry,
                cachingIconView,
                android.widget.TextView::class.java,
                FrameLayout::class.java,
                Boolean::class.javaPrimitiveType!!,
                hostFunction1,
            )
        } else {
            null
        }
        val notificationSmallIcon = Reflection.findField(
            Notification::class.java,
            "mSmallIcon",
            Icon::class.java,
        )
        val wrapWithWhiteBg = loadOptional(CAPSULE_NOTIFICATION_UTILS, classLoader)?.let {
            Reflection.findMethodReturning(
                it,
                "wrapWithWhiteBg",
                Drawable::class.java,
                Drawable::class.java,
                Context::class.java,
                Float::class.javaPrimitiveType!!,
            )
        }

        if (capsuleGetRoundedIcon == null) {
            diagnostics.memberMissing(
                scope = "systemui:lockscreen:capsule:rounded_icon",
                message = "未找到 CapsuleNotificationCardView.getRoundedIcon，单条锁屏胶囊可能仍显示默认图标",
            )
        }
        if (
            groupIconInitCapsuleIconColor == null &&
            groupIconAccessInitCapsuleIconColor == null &&
            groupIconInitEntryIconDrawable == null
        ) {
            diagnostics.memberMissing(
                scope = "systemui:lockscreen:capsule:group_icon",
                message = "未找到 GroupIconManager 锁屏胶囊着色入口，聚合锁屏胶囊可能仍显示灰色",
            )
        }

        return LockScreenCapsuleMembers(
            notificationIconDataCtors = ctors,
            notificationEntryGetSbn = notificationEntryGetSbn,
            capsuleGetRoundedIcon = capsuleGetRoundedIcon,
            capsuleSetupIconAndBadges = capsuleSetupIconAndBadges,
            capsuleBind = capsuleBind,
            capsuleCardGetIcon = capsuleCardGetIcon,
            notificationIconDataGetEntry = notificationIconDataGetEntry,
            notificationIconDataGetKey = notificationIconDataGetKey,
            notificationIconDataGetPackageName = notificationIconDataGetPackageName,
            notificationIconDataIconField = notificationIconDataIconField,
            notificationIconDataSmallIconField = notificationIconDataSmallIconField,
            notificationIconDataIconColorField = notificationIconDataIconColorField,
            innerDataGetIconData = innerDataGetIconData,
            innerDataGetCardType = innerDataGetCardType,
            innerDataGetReportDataList = innerDataGetReportDataList,
            innerDataIconColorField = innerDataIconColorField,
            groupIconInitCapsuleIconColor = groupIconInitCapsuleIconColor,
            groupIconAccessInitCapsuleIconColor = groupIconAccessInitCapsuleIconColor,
            groupIconAccessInitIconViewColor = groupIconAccessInitIconViewColor,
            groupIconInitPillBgAndNumberColor = groupIconInitPillBgAndNumberColor,
            groupIconInitEntryIconDrawable = groupIconInitEntryIconDrawable,
            notificationSmallIcon = notificationSmallIcon,
            wrapWithWhiteBg = wrapWithWhiteBg,
        )
    }

    fun resolveAod(
        classLoader: ClassLoader,
        diagnostics: Diagnostics,
    ): AodMembers? {
        val layout = loadOptional(AOD_NOTIFICATION_LAYOUT, classLoader)
        val updateNotificationView = layout?.let {
            Reflection.findMethodReturning(
                it,
                "updateNotificationView",
                Void.TYPE,
                StatusBarNotification::class.java,
            )
        }
        val layoutContext = layout?.let {
            Reflection.findField(it, "mContext", Context::class.java)
        }
        val notificationSmallIcon = Reflection.findField(
            Notification::class.java,
            "mSmallIcon",
            Icon::class.java,
        )

        val lockScreenIconData = loadOptional(LOCK_SCREEN_NOTIFICATION_ICON_DATA, classLoader)
        val lockScreenIconDataCtor = lockScreenIconData?.declaredConstructors?.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 4 &&
                params[0] == String::class.java &&
                params[1] == String::class.java &&
                Drawable::class.java.isAssignableFrom(params[2]) &&
                (params[3] == Integer.TYPE || params[3] == Int::class.javaPrimitiveType)
        }?.also { it.isAccessible = true }
        val lockScreenIconDataSetIcon = lockScreenIconData?.let {
            Reflection.findMethodReturning(it, "setIcon", Void.TYPE, Drawable::class.java)
        }
        val lockScreenIconDataGetKey = lockScreenIconData?.let {
            Reflection.findMethodReturning(it, "getKey", String::class.java)
        }
        val lockScreenIconDataGetPackageName = lockScreenIconData?.let {
            Reflection.findMethodReturning(it, "getPackageName", String::class.java)
        }

        val aodPlugin = loadOptional(AOD_PLUGIN_CALL_IMPL, classLoader)
        val updateNotificationIconData = aodPlugin?.let {
            Reflection.findMethodReturning(it, "updateNotificationIconData", Void.TYPE)
        }

        val dependencyExClass = loadOptional(DEPENDENCY_EX, classLoader)
        val dependencyExInstance = dependencyExClass?.let {
            Reflection.findField(it, "sDependency", it)
        }
        val dependencyExGetDependency = dependencyExClass?.let {
            Reflection.findMethodReturning(it, "getDependency", Any::class.java, Class::class.java)
        }
        val lockScreenDispatcherClass = loadOptional(LOCK_SCREEN_NOTIFICATION_DISPATCHER, classLoader)
        val getLockScreenNotificationIconData = lockScreenDispatcherClass?.let {
            Reflection.findMethod(it, "getLockScreenNotificationIconData")
        }

        val hasPluginPath =
            updateNotificationIconData != null &&
                lockScreenIconDataSetIcon != null &&
                lockScreenIconDataGetKey != null &&
                lockScreenIconDataGetPackageName != null &&
                dependencyExInstance != null &&
                dependencyExGetDependency != null &&
                lockScreenDispatcherClass != null &&
                getLockScreenNotificationIconData != null
        val hasCtorPath = lockScreenIconDataCtor != null
        val hasLayoutPath =
            updateNotificationView != null &&
                layoutContext != null &&
                notificationSmallIcon != null

        if (!hasPluginPath && !hasCtorPath && !hasLayoutPath) {
            diagnostics.memberMissing(
                scope = "systemui:aod:paths",
                message = "未找到经典时钟息屏图标路径（AodPlugin / LockScreenNotificationIconData / NotificationLayout）",
            )
            return null
        }
        if (!hasPluginPath) {
            diagnostics.memberMissing(
                scope = "systemui:aod:plugin_path",
                message = "AodPlugin.updateNotificationIconData 路径不完整，依赖构造函数/Layout 兜底",
            )
        }

        return AodMembers(
            updateNotificationView = updateNotificationView.takeIf { hasLayoutPath },
            layoutContext = layoutContext,
            notificationSmallIcon = notificationSmallIcon,
            lockScreenIconDataCtor = lockScreenIconDataCtor,
            lockScreenIconDataSetIcon = lockScreenIconDataSetIcon,
            lockScreenIconDataGetKey = lockScreenIconDataGetKey,
            lockScreenIconDataGetPackageName = lockScreenIconDataGetPackageName,
            updateNotificationIconData = updateNotificationIconData.takeIf { hasPluginPath },
            dependencyExClass = dependencyExClass,
            dependencyExInstance = dependencyExInstance,
            dependencyExGetDependency = dependencyExGetDependency,
            lockScreenDispatcherClass = lockScreenDispatcherClass,
            getLockScreenNotificationIconData = getLockScreenNotificationIconData,
        )
    }

    fun resolveFullScreenBanner(
        classLoader: ClassLoader,
        diagnostics: Diagnostics,
    ): FullScreenBannerMembers? {
        val helper = loadOptional(FULL_SCREEN_BANNER_HELPER, classLoader)
        val banner = loadOptional(FULL_SCREEN_BANNER, classLoader)
        val builder = loadOptional(FULL_SCREEN_BANNER_BUILDER, classLoader)
        val notificationEntry = loadOptional(NOTIFICATION_ENTRY, classLoader)
        if (helper == null || banner == null || notificationEntry == null) {
            diagnostics.memberMissing(
                scope = "systemui:fullscreen_banner:classes",
                message = "未找到 FullScreenBanner 相关类，跳过全屏轻反馈图标替换",
            )
            return null
        }

        val notificationEntryGetSbn = Reflection.findMethodReturning(
            notificationEntry,
            "getSbn",
            StatusBarNotification::class.java,
        ) ?: run {
            diagnostics.memberMissing(
                scope = "systemui:fullscreen_banner:entry",
                message = "未找到 NotificationEntry.getSbn()，跳过全屏轻反馈图标替换",
            )
            return null
        }

        val buildNotification = builder?.let {
            Reflection.findMethodReturning(
                helper,
                "buildNotification",
                it,
                Context::class.java,
                notificationEntry,
            )
        }
        val showBanner = Reflection.findMethodReturning(
            helper,
            "showBanner",
            Void.TYPE,
            notificationEntry,
        )
        val updateBanner = builder?.let {
            Reflection.findMethodReturning(banner, "updateBanner", Void.TYPE, it)
        }
        // ColorOS 17 appends largeIconWithBadge: setIcon(Drawable, boolean, Drawable, boolean, Drawable).
        val bannerSetIcon = Reflection.findMethodReturning(
            banner,
            "setIcon",
            Void.TYPE,
            Drawable::class.java,
            Boolean::class.javaPrimitiveType!!,
            Drawable::class.java,
            Boolean::class.javaPrimitiveType!!,
            Drawable::class.java,
        ) ?: Reflection.findMethodReturning(
            banner,
            "setIcon",
            Void.TYPE,
            Drawable::class.java,
            Boolean::class.javaPrimitiveType!!,
            Drawable::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        val builderIcon = builder?.let {
            Reflection.findField(it, "mIcon", Drawable::class.java)
        }
        val builderAvatarIcon = builder?.let {
            Reflection.findField(it, "mAvatarIcon", Drawable::class.java)
        }
        val builderLargeIconWithBadge = builder?.let {
            Reflection.findField(it, "mLargeIconWithBadge", Drawable::class.java)
        }
        val lastNotificationEntry = Reflection.findField(
            helper,
            "mLastNotificationEntry",
            notificationEntry,
        )
        val helperContext = Reflection.findField(helper, "mContext", Context::class.java)
        val helperFullScreenBanner = Reflection.findField(helper, "mFullScreenBanner", banner)
        val bannerIconView = Reflection.findField(banner, "mIvAppIcon", ImageView::class.java)
        val bannerHelper = Reflection.findField(banner, "mBannerHelper", helper)
        val bannerPackageName = Reflection.findField(banner, "mPackageName", String::class.java)
        val backgroundView = loadOptional(FULL_SCREEN_BACKGROUND_VIEW, classLoader)
        val backgroundSetIcon = backgroundView?.let {
            Reflection.findMethodReturning(it, "setIcon", Void.TYPE, Drawable::class.java)
        }

        val hasBuilderPath = buildNotification != null && builderIcon != null
        val hasUpdatePath = updateBanner != null && builderIcon != null
        val hasSetIconPath = bannerSetIcon != null
        val hasShowPath = showBanner != null
        if (!hasBuilderPath && !hasUpdatePath && !hasSetIconPath && !hasShowPath) {
            diagnostics.memberMissing(
                scope = "systemui:fullscreen_banner:paths",
                message = "未找到 FullScreenBanner 图标入口（buildNotification / updateBanner / setIcon）",
            )
            return null
        }
        if (buildNotification == null) {
            diagnostics.memberMissing(
                scope = "systemui:fullscreen_banner:build",
                message = "未找到 FullScreenBannerHelper.buildNotification，依赖 updateBanner/setIcon 兜底",
            )
        }
        if (bannerSetIcon == null) {
            diagnostics.memberMissing(
                scope = "systemui:fullscreen_banner:set_icon",
                message = "未找到 FullScreenBanner.setIcon(Drawable, boolean, Drawable, boolean[, Drawable])",
            )
        }

        return FullScreenBannerMembers(
            notificationEntryGetSbn = notificationEntryGetSbn,
            showBanner = showBanner,
            buildNotification = buildNotification,
            updateBanner = updateBanner,
            bannerSetIcon = bannerSetIcon,
            builderIcon = builderIcon,
            builderAvatarIcon = builderAvatarIcon,
            builderLargeIconWithBadge = builderLargeIconWithBadge,
            lastNotificationEntry = lastNotificationEntry,
            helperContext = helperContext,
            helperFullScreenBanner = helperFullScreenBanner,
            bannerIconView = bannerIconView,
            bannerHelper = bannerHelper,
            bannerPackageName = bannerPackageName,
            backgroundSetIcon = backgroundSetIcon,
        )
    }

    fun resolveEntryUseAppIcon(classLoader: ClassLoader): EntryUseAppIconMembers? {
        val entryExtension = loadOptional(OPLUS_NOTIFICATION_ENTRY_EXTENSION, classLoader) ?: return null
        val pipelineEntry = loadOptional(PIPELINE_ENTRY, classLoader) ?: return null
        val notificationEntry = loadOptional(NOTIFICATION_ENTRY, classLoader) ?: return null
        val predicate = Reflection.findMethodReturning(
            entryExtension,
            "useAppIconForSmallIcon",
            Boolean::class.javaPrimitiveType!!,
        ) ?: return null
        val getBase = Reflection.findMethodReturning(
            entryExtension,
            "getBase",
            pipelineEntry,
        ) ?: return null
        val notificationEntryGetSbn = Reflection.findMethodReturning(
            notificationEntry,
            "getSbn",
            StatusBarNotification::class.java,
        ) ?: return null
        return EntryUseAppIconMembers(
            predicate = predicate,
            getBase = getBase,
            notificationEntryGetSbn = notificationEntryGetSbn,
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
    private const val PIPELINE_ENTRY =
        "com.android.systemui.statusbar.notification.collection.PipelineEntry"
    private const val OPLUS_NOTIFICATION_ENTRY_EXTENSION =
        "com.oplus.systemui.statusbar.notification.collection.OplusNotificationEntryExImpl"
    private const val STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon"
    private const val STATUS_BAR_ICON_TYPE = "com.android.internal.statusbar.StatusBarIcon\$Type"
    private const val PEOPLE_AVATAR_ICON_TYPE = "PeopleAvatar"
    private const val EXPANDABLE_ROW =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val NOTIFICATION_VIEW_WRAPPER =
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper"
    private const val NOTIFICATION_HEADER_WRAPPER =
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper"
    private const val OPLUS_GROUP_WRAPPER =
        "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupTemplateWrapper"
    private const val GROUP_ICON_MANAGER =
        "com.oplus.systemui.notification.row.oplusgroup.GroupIconManager"
    private const val GROUP_ICON_INFO =
        "com.oplus.systemui.notification.row.oplusgroup.GroupIconManager\$IconInfo"
    private const val CACHING_ICON_VIEW = "com.android.internal.widget.CachingIconView"
    private const val KOTLIN_FUNCTION1 = "kotlin.jvm.functions.Function1"
    private const val OPLUS_HEADER_EXTENSION =
        "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp"
    private const val VIEW_CONFIG_COORDINATOR =
        "com.android.systemui.statusbar.notification.collection.coordinator.ViewConfigCoordinator"
    private const val NOTIF_PIPELINE =
        "com.android.systemui.statusbar.notification.collection.NotifPipeline"
    private const val OPLUS_SMALL_ICON_UTIL =
        "com.oplus.systemui.statusbar.notification.util.OplusNotificationSmallIconUtil"
    private const val AOD_NOTIFICATION_LAYOUT =
        "com.oplus.systemui.aod.aodclock.off.notification.NotificationLayout"
    private const val LOCK_SCREEN_NOTIFICATION_ICON_DATA =
        "com.android.systemui.util.LockScreenNotificationIconData"
    private const val AOD_PLUGIN_CALL_IMPL =
        "com.oplus.systemui.aod.plugin.AodPluginCallImpl"
    private const val DEPENDENCY_EX = "com.android.systemui.DependencyEx"
    private const val LOCK_SCREEN_NOTIFICATION_DISPATCHER =
        "com.android.systemui.statusbar.LockScreenNotificationDispatcher"
    private const val CAPSULE_NOTIFICATION_ICON_DATA =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationDataController\$NotificationIconData"
    private const val CAPSULE_NOTIFICATION_INNER_DATA =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationDataController\$CapsuleNotificationInnerData"
    private const val CAPSULE_NOTIFICATION_CARD_TYPE =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationDataController\$CardType"
    private const val CAPSULE_NOTIFICATION_CARD_VIEW =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationCardView"
    private const val CAPSULE_NOTIFICATION_UTILS =
        "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationUtils"
    private const val FULL_SCREEN_BANNER_HELPER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.FullScreenBannerHelper"
    private const val FULL_SCREEN_BANNER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner"
    private const val FULL_SCREEN_BANNER_BUILDER =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner\$Builder"
    private const val FULL_SCREEN_BACKGROUND_VIEW =
        "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBackgroundView"
}
