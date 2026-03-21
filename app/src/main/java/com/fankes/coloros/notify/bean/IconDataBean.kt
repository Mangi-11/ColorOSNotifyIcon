package com.fankes.coloros.notify.bean

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.json.JSONObject

/**
 * 在线图标规则。
 */
data class IconDataBean(
    val appName: String,
    val packageName: String,
    val iconBitmap: Bitmap,
    val iconColor: Int = 0,
    val contributorName: String = "",
    val isEnabled: Boolean = true,
    val isEnabledAll: Boolean = false,
) {
    companion object {

        fun fromJson(json: JSONObject): IconDataBean? = runCatching {
            IconDataBean(
                appName = json.optString("appName"),
                packageName = json.getString("packageName"),
                iconBitmap = decodeBitmap(json.getString("iconBitmap")) ?: error("图标解码失败"),
                iconColor = decodeColor(json.optString("iconColor")),
                contributorName = json.optString("contributorName"),
                isEnabled = json.optBoolean("isEnabled", true),
                isEnabledAll = json.optBoolean("isEnabledAll", false),
            )
        }.getOrNull()

        private fun decodeBitmap(base64: String): Bitmap? {
            if (base64.isBlank()) return null
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        private fun decodeColor(raw: String): Int {
            if (raw.isBlank()) return 0
            return runCatching { Color.parseColor(raw) }.getOrDefault(0)
        }
    }
}
