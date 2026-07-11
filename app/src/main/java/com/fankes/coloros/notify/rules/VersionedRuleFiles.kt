package com.fankes.coloros.notify.rules

internal object VersionedRuleFiles {
    private val sha256 = Regex("[0-9a-f]{64}")
    private val versionedName = Regex("rules-${sha256.pattern}\\.json")
    private val temporaryName = Regex(
        "\\.rules-${sha256.pattern}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp"
    )

    fun nameFor(sha256: String): String {
        require(sha256.matches(this.sha256)) { "SHA-256 must be lowercase hexadecimal" }
        return "rules-$sha256.json"
    }

    fun shaFromName(fileName: String): String? =
        fileName.takeIf(versionedName::matches)?.removePrefix("rules-")?.removeSuffix(".json")

    fun isTemporaryName(fileName: String): Boolean = temporaryName.matches(fileName)

    fun previousFor(
        targetFile: String,
        currentFile: String?,
        previousFile: String?,
    ): String? = when {
        currentFile != null && currentFile != targetFile && versionedName.matches(currentFile) -> currentFile
        previousFile != null && previousFile != targetFile && versionedName.matches(previousFile) -> previousFile
        else -> null
    }

    fun previousAfterValidatedUpdate(
        targetFile: String,
        validatedCurrentFile: String?,
        storedPreviousFile: String?,
    ): String? = previousFor(
        targetFile = targetFile,
        currentFile = validatedCurrentFile,
        previousFile = storedPreviousFile.takeIf { validatedCurrentFile == targetFile },
    )

    fun obsoleteFiles(
        allFiles: Iterable<String>,
        currentFile: String,
        previousFile: String?,
        includeLegacy: Boolean,
    ): Set<String> {
        val retained = setOfNotNull(
            currentFile,
            previousFile?.takeIf(versionedName::matches),
        )
        return allFiles
            .filter { versionedName.matches(it) || includeLegacy && it == RuleStore.LEGACY_RULES_FILE_NAME }
            .filterNot(retained::contains)
            .toSet()
    }
}
