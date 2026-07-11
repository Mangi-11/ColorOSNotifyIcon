package com.fankes.coloros.notify.rules

import com.fankes.coloros.notify.core.ModuleInfo
import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.framework.MainThreadCallbacks
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RuleRepository {

    data class SyncResult(
        val count: Int,
        val updatedAt: Long,
    )

    sealed class SyncFailure(
        val code: String,
        val userMessage: String,
        message: String,
        cause: Exception? = null,
    ) : Exception(message, cause) {
        class Download(source: String, cause: Exception) : SyncFailure(
            code = "download",
            userMessage = "规则下载失败",
            message = "Unable to download $source rule catalog",
            cause = cause,
        )

        class InvalidPayload(cause: Exception) : SyncFailure(
            code = "invalid_payload",
            userMessage = "下载的规则数据无效",
            message = "Downloaded rule catalog is invalid",
            cause = cause,
        )

        class LocalSave(cause: Exception) : SyncFailure(
            code = "local_save",
            userMessage = "规则保存失败",
            message = "Unable to save downloaded rule catalog",
            cause = cause,
        )

        class Scheduling(cause: Exception) : SyncFailure(
            code = "schedule",
            userMessage = "规则同步任务无法调度",
            message = "Unable to schedule rule synchronization",
            cause = cause,
        )
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rule-downloader")
    }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun syncRules(onResult: (Result<SyncResult>) -> Unit) {
        try {
            executor.execute {
                val result = try {
                    Result.success(syncBlocking())
                } catch (exception: Exception) {
                    logFailure(exception)
                    Result.failure(exception)
                }
                deliver(result, onResult)
            }
        } catch (exception: Exception) {
            val failure = SyncFailure.Scheduling(exception)
            logFailure(failure)
            deliver(Result.failure(failure), onResult)
        }
    }

    private fun deliver(result: Result<SyncResult>, callback: (Result<SyncResult>) -> Unit) {
        MainThreadCallbacks.dispatch("rules_sync") { callback(result) }
    }

    private fun syncBlocking(): SyncResult {
        val osRules = download("ColorOS", ModuleInfo.RULES_OS_URL, MAX_TOTAL_DOWNLOAD_BYTES)
        val appRules = download(
            source = "applications",
            url = ModuleInfo.RULES_APP_URL,
            maxBytes = MAX_TOTAL_DOWNLOAD_BYTES - osRules.byteCount,
        )
        val merged = try {
            mergeArrays(osRules.json, appRules.json)
        } catch (exception: Exception) {
            throw SyncFailure.InvalidPayload(exception)
        }
        val updatedAt = System.currentTimeMillis()
        val catalog = try {
            RuleStore.updateRules(merged, updatedAt)
        } catch (exception: RuleParseException) {
            throw SyncFailure.InvalidPayload(exception)
        } catch (exception: IllegalArgumentException) {
            throw SyncFailure.InvalidPayload(exception)
        } catch (exception: Exception) {
            throw SyncFailure.LocalSave(exception)
        }
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.RulesDownloaded,
            message = "Rule catalogs downloaded and validated",
            attributes = mapOf("rules" to catalog.size),
        )
        return SyncResult(count = catalog.size, updatedAt = updatedAt)
    }

    private data class DownloadedPayload(
        val json: String,
        val byteCount: Int,
    )

    private fun download(
        source: String,
        url: String,
        maxBytes: Int,
    ): DownloadedPayload = try {
        require(maxBytes > 0) { "Combined rule payload exceeds $MAX_TOTAL_DOWNLOAD_BYTES bytes" }
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val contentLength = response.body.contentLength()
            require(contentLength < 0L || contentLength <= maxBytes) {
                "Response exceeds remaining rule payload budget"
            }
            val bytes = response.body.byteStream().use { input ->
                RulePayloadIO.readBytes(input, maxBytes)
            }
            val json = bytes.toString(Charsets.UTF_8).trim()
            require(json.startsWith("[")) { "Response is not a JSON array" }
            DownloadedPayload(json, bytes.size)
        }
    } catch (exception: Exception) {
        throw SyncFailure.Download(source, exception)
    }

    private fun mergeArrays(left: String, right: String): String {
        val merged = JSONArray()
        val leftArray = JSONArray(left)
        val rightArray = JSONArray(right)
        for (index in 0 until leftArray.length()) merged.put(leftArray.get(index))
        for (index in 0 until rightArray.length()) merged.put(rightArray.get(index))
        return merged.toString()
    }

    private fun logFailure(exception: Exception) {
        val event = when (exception) {
            is SyncFailure.InvalidPayload -> DiagnosticEvent.RulesParseFailed
            is SyncFailure.LocalSave -> DiagnosticEvent.RulesSaveFailed
            else -> DiagnosticEvent.RulesDownloadFailed
        }
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = event,
            message = (exception as? SyncFailure)?.userMessage ?: "规则同步失败",
            cause = exception,
            attributes = mapOf(
                "failure" to ((exception as? SyncFailure)?.code ?: "unexpected"),
            ),
        )
    }

    private const val MAX_TOTAL_DOWNLOAD_BYTES = RulePayloadIO.MAX_PAYLOAD_BYTES
}
