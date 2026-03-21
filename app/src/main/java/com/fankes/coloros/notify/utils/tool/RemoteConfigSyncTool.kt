package com.fankes.coloros.notify.utils.tool

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fankes.coloros.notify.const.ModuleInfo
import com.fankes.coloros.notify.data.ConfigData
import io.github.libxposed.service.XposedService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

object RemoteConfigSyncTool {

    data class SyncResult(
        val rulesCount: Int,
        val payloadBytes: Int,
        val configUpdatedAt: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun syncAsync(
        service: XposedService,
        onResult: ((Result<SyncResult>) -> Unit)? = null,
    ) {
        executor.execute {
            val result = syncBlocking(service)
            if (onResult != null) {
                mainHandler.post { onResult(result) }
            }
        }
    }

    fun syncBlocking(service: XposedService): Result<SyncResult> = runCatching {
        val remotePrefs = service.getRemotePreferences(ConfigData.GROUP_CONFIG)
        val localConfigUpdatedAt = ConfigData.localConfigUpdatedAt
        val committed = ConfigData.mirrorTo(remotePrefs)
        check(committed) { "远程偏好设置提交失败" }

        val payload = ConfigData.rulesJson.toByteArray(Charsets.UTF_8)
        service.openRemoteFile(ConfigData.RULES_FILE_NAME).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { output ->
                output.channel.truncate(0)
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
        }

        val mirroredCount = remotePrefs.getInt(ConfigData.KEY_RULES_COUNT, -1)
        check(mirroredCount == ConfigData.rulesCount) {
            "远程规则数量不一致：local=${ConfigData.rulesCount}, remote=$mirroredCount"
        }
        val mirroredUpdatedAt = remotePrefs.getLong(ConfigData.KEY_CONFIG_UPDATED_AT, -1L)
        check(mirroredUpdatedAt == localConfigUpdatedAt) {
            "远程配置版本不一致：local=$localConfigUpdatedAt, remote=$mirroredUpdatedAt"
        }
        val mirroredJson = service.openRemoteFile(ConfigData.RULES_FILE_NAME).use { pfd ->
            FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        }
        check(mirroredJson == ConfigData.rulesJson) { "远程规则文件校验失败" }

        ConfigData.markRemoteMirrorSuccess(localConfigUpdatedAt)
        SyncResult(
            rulesCount = mirroredCount,
            payloadBytes = payload.size,
            configUpdatedAt = localConfigUpdatedAt,
        )
    }

    fun syncIfPending(service: XposedService) {
        if (!ConfigData.hasPendingRemoteSync) return
        syncAsync(service) { result ->
            result.onFailure {
                Log.w(
                    ModuleInfo.LOG_TAG,
                    "框架服务恢复后自动镜像失败：${it.message ?: it.javaClass.simpleName}",
                    it
                )
            }
        }
    }
}
