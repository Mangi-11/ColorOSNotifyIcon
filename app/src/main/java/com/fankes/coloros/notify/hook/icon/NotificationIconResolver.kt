package com.fankes.coloros.notify.hook.icon

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toDrawable
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.runtimeFailure
import com.fankes.coloros.notify.rules.IconRule
import com.fankes.coloros.notify.rules.RuleStore

internal class NotificationIconResolver(
    config: RuleStore.ModuleConfig,
    private val rules: Map<String, IconRule>,
    private val themeIcons: ThemeIconProvider,
    private val diagnostics: Diagnostics,
    private val revision: Long,
) {
    private val policyConfig = config.toPolicyConfig()

    data class PanelIconRenderPlan(
        val drawable: Drawable,
        val tintColor: Int?,
    )

    fun resolveStatusBarIcon(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
        currentStatusBarIcon: Icon?,
    ): Icon? = resolveOrFallback("status_bar", "状态栏图标解析失败") {
        if (NotificationIconPolicy.shouldKeepHostDefault(policyConfig, sbn.isOplusPush())) return null
        resolveThemeIconReplacement(context, sbn)?.toIconOrNull("status_bar")?.let { return it }
        val originalDrawable = originalSmallIcon?.loadDrawable(context)?.mutate() ?: return null
        val originalIsMonochrome = IconBitmapClassifier.isMonochromeDrawable(originalDrawable)
        resolveRuleIconReplacement(sbn, originalIsMonochrome)
            ?.toIconOrNull("status_bar")
            ?.let { return it }

        if (
            NotificationIconPolicy.shouldRestoreOriginal(
                originalIsMonochrome,
                currentStatusBarIcon?.isMonochrome(context),
            )
        ) return originalSmallIcon
        return null
    }

    fun resolvePanelIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
        currentDrawable: Drawable?,
    ): PanelIconRenderPlan? = resolveOrFallback("panel", "通知面板图标解析失败") {
        if (
            !NotificationIconPolicy.shouldProcessPanel(
                config = policyConfig,
                isMediaNotification = sbn.isMediaNotification(),
                isOplusPush = sbn.isOplusPush(),
            )
        ) return null
        resolveThemeIconReplacement(context, sbn)
            ?.toPanelIconRenderPlanOrNull(context, "panel")
            ?.let { return it }

        val originalDrawable = originalSmallIcon?.loadDrawable(context)?.mutate() ?: return null
        val originalIsMonochrome = IconBitmapClassifier.isMonochromeDrawable(originalDrawable)
        resolveRuleIconReplacement(sbn, originalIsMonochrome)
            ?.toPanelIconRenderPlanOrNull(context, "panel")
            ?.let { return it }

        if (
            NotificationIconPolicy.shouldRestoreOriginal(
                originalIsMonochrome,
                currentDrawable?.let(IconBitmapClassifier::isMonochromeDrawable),
            )
        ) {
            return PanelIconRenderPlan(
                drawable = originalDrawable,
                tintColor = context.defaultPanelIconTint,
            )
        }
        return null
    }

    private fun resolveThemeIconReplacement(
        context: Context,
        sbn: StatusBarNotification,
    ): IconReplacement? {
        if (!NotificationIconPolicy.shouldResolveTheme(policyConfig)) return null
        return themeIcons.resolve(context, sbn)?.let(IconReplacement::ThemeIcon)
    }

    private fun resolveRuleIconReplacement(
        sbn: StatusBarNotification,
        originalIsMonochrome: Boolean,
    ): IconReplacement? {
        val rule = rules[sbn.rulePackageName()]
        return when (
            NotificationIconPolicy.selectRuleReplacement(
                config = policyConfig,
                ruleEnabled = rule?.isEnabled == true,
                ruleEnabledAll = rule?.isEnabledAll == true,
                originalIsMonochrome = originalIsMonochrome,
            )
        ) {
            RuleReplacement.Rule -> rule?.let(IconReplacement::RuleIcon)
            RuleReplacement.Placeholder -> IconReplacement.Placeholder
            RuleReplacement.None -> null
        }
    }

    private fun IconReplacement.toIcon(): Icon = when (this) {
        is IconReplacement.ThemeIcon -> Icon.createWithBitmap(bitmap)
        is IconReplacement.RuleIcon -> Icon.createWithBitmap(rule.iconBitmap)
        IconReplacement.Placeholder -> Icon.createWithBitmap(PlaceholderIconFactory.createBitmap())
    }

    private fun IconReplacement.toIconOrNull(feature: String): Icon? = try {
        toIcon()
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.toPanelIconRenderPlan(context: Context): PanelIconRenderPlan = when (this) {
        is IconReplacement.ThemeIcon -> PanelIconRenderPlan(
            drawable = bitmap.toDrawable(context.resources),
            tintColor = null,
        )
        is IconReplacement.RuleIcon -> PanelIconRenderPlan(
            drawable = rule.iconBitmap.toDrawable(context.resources),
            tintColor = rule.iconColor.takeIf { it != 0 } ?: context.defaultPanelIconTint,
        )
        IconReplacement.Placeholder -> PanelIconRenderPlan(
            drawable = PlaceholderIconFactory.createBitmap().toDrawable(context.resources),
            tintColor = context.defaultPanelIconTint,
        )
    }

    private fun IconReplacement.toPanelIconRenderPlanOrNull(
        context: Context,
        feature: String,
    ): PanelIconRenderPlan? = try {
        toPanelIconRenderPlan(context)
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.reportReplacementFailure(feature: String, exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature:replacement:$diagnosticName",
            message = "候选图标无法解码或构造，跳过该候选",
            cause = exception,
            revision = revision,
        )
    }

    private val Context.defaultPanelIconTint: Int
        get() {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                DARK_PANEL_ICON_TINT
            } else {
                LIGHT_PANEL_ICON_TINT
            }
        }

    private fun Icon.isMonochrome(context: Context): Boolean? =
        loadDrawable(context)?.mutate()?.let(IconBitmapClassifier::isMonochromeDrawable)

    private inline fun <T> resolveOrFallback(
        feature: String,
        message: String,
        block: () -> T?,
    ): T? = try {
        block()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature",
            message = message,
            cause = exception,
            revision = revision,
        )
        null
    }

    private fun StatusBarNotification.rulePackageName(): String =
        packageName.orEmpty()

    private fun StatusBarNotification.isOplusPush(): Boolean =
        opPkg == SYSTEM_FRAMEWORK_PACKAGE && opPkg != packageName

    private fun StatusBarNotification.isMediaNotification(): Boolean {
        if (notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        return notification.category == Notification.CATEGORY_TRANSPORT
    }

    private sealed class IconReplacement(val diagnosticName: String) {
        data class ThemeIcon(val bitmap: Bitmap) : IconReplacement("theme")
        data class RuleIcon(val rule: IconRule) : IconReplacement("rule")
        object Placeholder : IconReplacement("placeholder")
    }

    private fun RuleStore.ModuleConfig.toPolicyConfig() = IconPolicyConfig(
        rulesEnabled = rulesEnabled,
        source = when (iconSourceMode) {
            RuleStore.IconSourceMode.RuleLibrary -> PolicyIconSource.RuleLibrary
            RuleStore.IconSourceMode.DesktopTheme -> PolicyIconSource.DesktopTheme
        },
        panelEnabled = panelIconReplacementEnabled,
        handleOplusPush = oplusPushSpecialHandlingEnabled,
        placeholderEnabled = placeholderIconEnabled,
    )

    private companion object {
        const val SYSTEM_FRAMEWORK_PACKAGE = "android"
        const val LIGHT_PANEL_ICON_TINT = 0xFF707173.toInt()
        const val DARK_PANEL_ICON_TINT = 0xFFDCDCDC.toInt()
    }
}
