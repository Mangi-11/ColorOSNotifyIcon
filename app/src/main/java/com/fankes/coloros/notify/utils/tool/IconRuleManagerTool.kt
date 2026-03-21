package com.fankes.coloros.notify.utils.tool

import android.os.Handler
import android.os.Looper
import com.fankes.coloros.notify.const.ModuleInfo
import com.fankes.coloros.notify.data.ConfigData
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object IconRuleManagerTool {

    data class SyncResult(
        val count: Int,
        val updatedAt: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun syncRules(onResult: (Result<SyncResult>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val osRules = requestJson(ModuleInfo.RULES_OS_URL)
                val appRules = requestJson(ModuleInfo.RULES_APP_URL)
                val merged = mergeArrays(osRules, appRules)
                val parsed = ConfigData.parseRules(merged)
                require(parsed.isNotEmpty()) { "返回的规则为空" }
                val updatedAt = System.currentTimeMillis()
                ConfigData.updateRules(merged, updatedAt)
                SyncResult(count = parsed.size, updatedAt = updatedAt)
            }
            mainHandler.post { onResult(result) }
        }
    }

    private fun requestJson(url: String): String {
        val request = Request.Builder().url(url).get().build()
        return httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string().trim().also { body ->
                require(body.startsWith("[")) { "返回内容不是 JSON 数组" }
            }
        }
    }

    private fun mergeArrays(left: String, right: String): String {
        val merged = JSONArray()
        val leftArray = JSONArray(left)
        val rightArray = JSONArray(right)
        for (index in 0 until leftArray.length()) merged.put(leftArray.get(index))
        for (index in 0 until rightArray.length()) merged.put(rightArray.get(index))
        return merged.toString()
    }
}
