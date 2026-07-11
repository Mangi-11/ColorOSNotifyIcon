package com.fankes.coloros.notify.rules

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class LocalRuleStorage(
    private val directory: File,
) {
    fun writeVerified(
        payload: String,
        sha256: String,
    ): String {
        val fileName = VersionedRuleFiles.nameFor(sha256)
        ensureDirectory()
        val destination = File(directory, fileName)
        val destinationMatches = if (destination.isFile) {
            try {
                hash(destination) == sha256
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (destinationMatches) return fileName

        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= RulePayloadIO.MAX_PAYLOAD_BYTES) {
            "Rule payload exceeds ${RulePayloadIO.MAX_PAYLOAD_BYTES} bytes"
        }
        val temporary = File(directory, ".$fileName.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(hash(temporary) == sha256) { "Local rule file verification failed" }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            check(hash(destination) == sha256) { "Published local rule file verification failed" }
            return fileName
        } finally {
            temporary.delete()
        }
    }

    fun readVerified(
        fileName: String,
        sha256: String,
    ): String {
        require(fileName == VersionedRuleFiles.nameFor(sha256)) { "Rule pointer does not match SHA-256" }
        val file = File(directory, fileName)
        check(file.isFile) { "Local rule file does not exist: $fileName" }
        val bytes = file.inputStream().use { RulePayloadIO.readBytes(it) }
        val actualSha256 = RuleCatalogParser.sha256(bytes)
        check(actualSha256 == sha256) {
            "Local rule file SHA-256 mismatch: expected $sha256, actual $actualSha256"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun cleanup(
        currentFile: String,
        previousFile: String?,
        includeTemporary: Boolean = false,
    ): List<String> {
        if (!directory.isDirectory) return emptyList()
        val obsolete = VersionedRuleFiles.obsoleteFiles(
            allFiles = directory.list().orEmpty().asIterable(),
            currentFile = currentFile,
            previousFile = previousFile,
            includeLegacy = false,
        ) + if (includeTemporary) {
            directory.list().orEmpty().filter(VersionedRuleFiles::isTemporaryName)
        } else {
            emptyList()
        }
        return obsolete.filterNot { File(directory, it).delete() }
    }

    private fun ensureDirectory() {
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create local rule directory"
        }
    }

    private fun hash(file: File): String = file.inputStream().use(RuleCatalogParser::sha256)

}
