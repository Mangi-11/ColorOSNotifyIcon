package com.fankes.coloros.notify.framework

import com.fankes.coloros.notify.diagnostics.AppDiagnostics
import com.fankes.coloros.notify.diagnostics.DiagnosticEvent
import com.fankes.coloros.notify.diagnostics.DiagnosticLevel
import com.fankes.coloros.notify.rules.RuleStore
import com.fankes.coloros.notify.rules.VersionedRuleFiles
import io.github.libxposed.service.XposedService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

object RemoteRuleMirror {

    data class SyncResult(
        val rulesCount: Int,
        val revision: Long,
        val fileName: String,
    )

    sealed interface PublishFailure {
        val code: String
        val cause: Exception?
        val userMessage: String

        data class InvalidLocalSnapshot(override val cause: Exception) : PublishFailure {
            override val code = "invalid_local_snapshot"
            override val userMessage = "本地规则数据无效"
        }

        data class LocalMutation(override val cause: Exception) : PublishFailure {
            override val code = "local_mutation"
            override val userMessage = "本地设置保存失败"
        }

        data class RemoteAccess(override val cause: Exception) : PublishFailure {
            override val code = "remote_access"
            override val userMessage = "无法访问框架远程存储"
        }

        data class FileWrite(override val cause: Exception) : PublishFailure {
            override val code = "file_write"
            override val userMessage = "远程规则文件写入失败"
        }

        data class Verification(
            val expectedSha256: String,
            val actualSha256: String,
            override val cause: Exception? = null,
        ) : PublishFailure {
            override val code = "verification"
            override val userMessage = "远程规则文件校验失败"
        }

        data class PreferencesCommit(override val cause: Exception?) : PublishFailure {
            override val code = "preferences_commit"
            override val userMessage = "远程配置提交失败"
        }

        data class Scheduling(override val cause: Exception) : PublishFailure {
            override val code = "publisher_schedule"
            override val userMessage = "配置发布任务无法调度"
        }

        data class Unexpected(override val cause: Exception) : PublishFailure {
            override val code = "unexpected"
            override val userMessage = "配置发布发生未知错误"
        }
    }

    sealed interface PublishResult {
        data class Published(val value: SyncResult) : PublishResult
        data class Failed(val failure: PublishFailure) : PublishResult
    }

    private data class Publication(
        val service: XposedService,
        val snapshot: RuleStore.MirrorSnapshot,
    )

    private val snapshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-config-snapshot")
    }
    private val publisherExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-config-publisher")
    }
    private val queue = LatestRequestQueue<Publication, (PublishResult) -> Unit>(
        revisionOf = { it.snapshot.revision },
        isEquivalent = { left, right ->
            left.snapshot.revision == right.snapshot.revision && left.service === right.service
        },
    )

    /**
     * Publishes at most one snapshot at a time. While a write is running, intermediate revisions
     * are conflated into the latest snapshot. A superseded failure is never delivered to the UI.
     */
    fun syncAsync(
        service: XposedService,
        onResult: ((PublishResult) -> Unit)? = null,
    ) {
        try {
            snapshotExecutor.execute {
                val snapshot = try {
                    RuleStore.captureMirrorSnapshot()
                } catch (exception: Exception) {
                    deliverResultOnMain(invalidLocalSnapshot(exception), onResult)
                    return@execute
                }
                val request = queue.enqueue(Publication(service, snapshot), onResult) ?: return@execute
                schedule(request)
            }
        } catch (exception: Exception) {
            deliverResultOnMain(schedulingFailed(exception), onResult)
        }
    }

    internal fun localMutationFailed(exception: Exception): PublishResult.Failed {
        val failure = PublishFailure.LocalMutation(exception)
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.ConfigPublishFailed,
            message = failure.userMessage,
            cause = exception,
            attributes = mapOf("failure" to failure.code),
        )
        return PublishResult.Failed(failure)
    }

    internal fun deliverResultOnMain(
        result: PublishResult,
        callback: ((PublishResult) -> Unit)?,
    ) {
        if (callback == null) return
        MainThreadCallbacks.dispatch("config_publish") { callback(result) }
    }

    private fun schedule(
        request: LatestRequestQueue.Request<Publication, (PublishResult) -> Unit>,
    ) {
        try {
            publisherExecutor.execute { drain(request) }
        } catch (exception: Exception) {
            val result = failed(request.value.snapshot, PublishFailure.Scheduling(exception))
            val completion = queue.complete(request, succeeded = false)
            completion.callbacks.forEach { deliverResultOnMain(result, it) }
            completion.next?.let(::schedule)
        }
    }

    private fun invalidLocalSnapshot(exception: Exception): PublishResult.Failed {
        val failure = PublishFailure.InvalidLocalSnapshot(exception)
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.ConfigPublishFailed,
            message = failure.userMessage,
            cause = exception,
            attributes = mapOf("failure" to failure.code),
        )
        return PublishResult.Failed(failure)
    }

    private fun schedulingFailed(exception: Exception): PublishResult.Failed {
        val failure = PublishFailure.Scheduling(exception)
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.ConfigPublishFailed,
            message = failure.userMessage,
            cause = exception,
            attributes = mapOf("failure" to failure.code),
        )
        return PublishResult.Failed(failure)
    }

    private fun drain(initial: LatestRequestQueue.Request<Publication, (PublishResult) -> Unit>) {
        var request: LatestRequestQueue.Request<Publication, (PublishResult) -> Unit>? = initial
        while (request != null) {
            val active = request
            val result = try {
                publish(active.value)
            } catch (exception: Exception) {
                failed(active.value.snapshot, PublishFailure.Unexpected(exception))
            }
            val completion = queue.complete(active, result is PublishResult.Published)
            completion.callbacks.forEach { deliverResultOnMain(result, it) }
            request = completion.next
        }
    }

    private fun publish(publication: Publication): PublishResult {
        val snapshot = try {
            val catalog = RuleStore.parseCatalog(
                json = publication.snapshot.rulesJson,
                expectedSha256 = publication.snapshot.contentSha256,
            )
            RuleStore.confirmCatalogMetadata(publication.snapshot, catalog.size)
            publication.snapshot.copy(rulesCount = catalog.size)
        } catch (exception: Exception) {
            return failed(publication.snapshot, PublishFailure.InvalidLocalSnapshot(exception))
        }

        val service = publication.service
        val remotePrefs = try {
            service.getRemotePreferences(RuleStore.GROUP_CONFIG)
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.RemoteAccess(exception))
        }
        val publishedFile = try {
            remotePrefs.getString(RuleStore.KEY_RULES_FILE_NAME, null)
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.RemoteAccess(exception))
        }
        val publishedPreviousFile = try {
            remotePrefs.getString(RuleStore.KEY_PREVIOUS_RULES_FILE_NAME, null)
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.RemoteAccess(exception))
        }
        val fileName = VersionedRuleFiles.nameFor(snapshot.contentSha256)
        val previousFile = VersionedRuleFiles.previousFor(
            targetFile = fileName,
            currentFile = publishedFile,
            previousFile = publishedPreviousFile,
        )
        val existingSha256 = try {
            fileName.takeIf { it in service.listRemoteFiles() }?.let {
                readRemoteSha256(service, it)
            }
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.RemoteAccess(exception))
        }
        if (existingSha256 != null &&
            existingSha256 != snapshot.contentSha256 &&
            fileName == publishedFile
        ) {
            // Never truncate the file named by the currently published pointer. A corrupted
            // content-addressed file can only be superseded by publishing a different hash.
            return failed(
                snapshot,
                PublishFailure.Verification(
                    expectedSha256 = snapshot.contentSha256,
                    actualSha256 = existingSha256,
                )
            )
        }
        if (existingSha256 != snapshot.contentSha256) {
            val payload = snapshot.rulesJson.toByteArray(Charsets.UTF_8)
            try {
                service.openRemoteFile(fileName).use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        output.channel.truncate(0L)
                        output.write(payload)
                        output.flush()
                        output.fd.sync()
                    }
                }
            } catch (exception: Exception) {
                return failed(snapshot, PublishFailure.FileWrite(exception))
            }
        }

        val actualSha256 = try {
            readRemoteSha256(service, fileName)
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.RemoteAccess(exception))
        }
        if (actualSha256 != snapshot.contentSha256) {
            return failed(
                snapshot,
                PublishFailure.Verification(
                    expectedSha256 = snapshot.contentSha256,
                    actualSha256 = actualSha256,
                )
            )
        }

        val committed = try {
            RuleStore.mirrorTo(remotePrefs, snapshot, fileName, previousFile)
        } catch (exception: Exception) {
            return failed(snapshot, PublishFailure.PreferencesCommit(exception))
        }
        if (!committed) {
            return failed(snapshot, PublishFailure.PreferencesCommit(cause = null))
        }

        cleanupOldFiles(service, currentFile = fileName, previousFile = previousFile)
        val result = SyncResult(
            rulesCount = snapshot.rulesCount,
            revision = snapshot.revision,
            fileName = fileName,
        )
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.ConfigPublished,
            message = "Remote configuration published",
            attributes = mapOf(
                "revision" to snapshot.revision,
                "rules" to snapshot.rulesCount,
                "file" to fileName,
            ),
        )
        return PublishResult.Published(result)
    }

    private fun cleanupOldFiles(
        service: XposedService,
        currentFile: String,
        previousFile: String?,
    ) {
        try {
            VersionedRuleFiles.obsoleteFiles(
                allFiles = service.listRemoteFiles().asIterable(),
                currentFile = currentFile,
                previousFile = previousFile,
                includeLegacy = true,
            ).forEach(service::deleteRemoteFile)
        } catch (exception: Exception) {
            // Publication is already atomic and visible. Cleanup is deliberately best-effort.
            AppDiagnostics.logger.report(
                level = DiagnosticLevel.Warning,
                event = DiagnosticEvent.ConfigPublishFailed,
                message = "Published configuration but could not clean old rule files",
                cause = exception,
                attributes = mapOf("phase" to "cleanup", "file" to currentFile),
            )
        }
    }

    private fun readRemoteSha256(service: XposedService, fileName: String): String =
        service.openRemoteFile(fileName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                com.fankes.coloros.notify.rules.RuleCatalogParser.sha256(input)
            }
        }

    private fun failed(
        snapshot: RuleStore.MirrorSnapshot,
        failure: PublishFailure,
    ): PublishResult.Failed {
        AppDiagnostics.logger.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.ConfigPublishFailed,
            message = failure.userMessage,
            cause = failure.cause,
            attributes = mapOf(
                "revision" to snapshot.revision,
                "failure" to failure.code,
            ),
        )
        return PublishResult.Failed(failure)
    }
}
