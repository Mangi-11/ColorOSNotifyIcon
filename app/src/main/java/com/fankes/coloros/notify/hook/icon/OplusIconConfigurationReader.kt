package com.fankes.coloros.notify.hook.icon

import android.content.Context
import android.content.res.Configuration
import com.fankes.coloros.notify.diagnostics.Diagnostics
import com.fankes.coloros.notify.hook.memberMissing
import com.fankes.coloros.notify.hook.runtimeFailure
import java.lang.reflect.Modifier

internal data class OplusIconConfiguration(
    val uiMode: Int,
    val themeChanged: Long,
    val themeChangedFlags: Long,
    val uxIconConfig: Long,
)

internal class OplusIconConfigurationReader(
    private val diagnostics: Diagnostics,
) {

    fun read(context: Context): OplusIconConfiguration {
        val configuration = context.resources.configuration
        val extra = configuration.oplusExtraConfigurationOrNull()
        return OplusIconConfiguration(
            uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            themeChanged = extra?.longMember("mThemeChanged", "getThemeChanged") ?: 0L,
            themeChangedFlags = extra?.longMember("mThemeChangedFlags", "getThemeChangedFlags") ?: 0L,
            uxIconConfig = extra?.longMember("mUxIconConfig", "getUxIconConfig") ?: -1L,
        )
    }

    private fun Configuration.oplusExtraConfigurationOrNull(): Any? {
        val accessor = try {
            javaClass.getMethod("getOplusExtraConfiguration")
        } catch (_: NoSuchMethodException) {
            null
        }
        if (
            accessor != null &&
            !Modifier.isStatic(accessor.modifiers) &&
            accessor.returnType != Void.TYPE
        ) {
            try {
                return accessor.invoke(this)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "icon_configuration:extra_accessor",
                    message = "读取 Oplus 图标配置失败，尝试字段回退",
                    cause = exception,
                )
            }
        } else if (accessor != null) {
            diagnostics.memberMissing(
                scope = "icon_configuration:extra_accessor",
                message = "Oplus 图标配置访问器签名不匹配，尝试字段回退",
            )
        }
        return try {
            javaClass.getDeclaredField("mOplusExtraConfiguration")
                .apply { isAccessible = true }
                .get(this)
        } catch (exception: NoSuchFieldException) {
            diagnostics.memberMissing(
                scope = "icon_configuration:extra_field",
                message = "未找到 Oplus 图标配置成员，使用安全默认值",
                cause = exception,
            )
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:extra_field",
                message = "读取 Oplus 图标配置字段失败，使用安全默认值",
                cause = exception,
            )
            null
        }
    }

    private fun Any.longMember(fieldName: String, methodName: String): Long? {
        try {
            javaClass.getDeclaredField(fieldName)
                .apply { isAccessible = true }
                .get(this)
                .toLongOrNull()
                ?.let { return it }
        } catch (_: NoSuchFieldException) {
            // Try the known accessor below.
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:$fieldName",
                message = "读取 Oplus 图标配置字段失败，尝试访问器",
                cause = exception,
            )
        }
        val accessor = try {
            javaClass.getMethod(methodName)
        } catch (_: NoSuchMethodException) {
            null
        }?.takeIf {
            it.returnType == Long::class.javaPrimitiveType || Number::class.java.isAssignableFrom(it.returnType)
        } ?: run {
            diagnostics.memberMissing(
                scope = "icon_configuration:$fieldName",
                message = "未找到 Oplus 图标配置成员，使用安全默认值",
            )
            return null
        }
        return try {
            accessor.invoke(this).toLongOrNull()
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:$methodName",
                message = "读取 Oplus 图标配置访问器失败，使用安全默认值",
                cause = exception,
            )
            null
        }
    }

    private fun Any?.toLongOrNull(): Long? = (this as? Number)?.toLong()
}
