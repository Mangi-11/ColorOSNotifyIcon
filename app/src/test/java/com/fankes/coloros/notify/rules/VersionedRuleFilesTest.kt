package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionedRuleFilesTest {

    private val firstHash = "1".repeat(64)
    private val secondHash = "2".repeat(64)
    private val thirdHash = "3".repeat(64)

    @Test
    fun derivesStableContentAddressedFileName() {
        assertEquals("rules-$firstHash.json", VersionedRuleFiles.nameFor(firstHash))
        assertThrows(IllegalArgumentException::class.java) {
            VersionedRuleFiles.nameFor("A".repeat(64))
        }
    }

    @Test
    fun retainsCurrentAndPreviousVersionAndRemovesLegacyFile() {
        val current = VersionedRuleFiles.nameFor(firstHash)
        val previous = VersionedRuleFiles.nameFor(secondHash)
        val stale = VersionedRuleFiles.nameFor(thirdHash)

        assertEquals(
            setOf(stale, RuleStore.LEGACY_RULES_FILE_NAME),
            VersionedRuleFiles.obsoleteFiles(
                allFiles = listOf(current, previous, stale, RuleStore.LEGACY_RULES_FILE_NAME, "keep.me"),
                currentFile = current,
                previousFile = previous,
                includeLegacy = true,
            ),
        )
    }

    @Test
    fun keepsPreviousRuleVersionAcrossConfigOnlyPublication() {
        val current = VersionedRuleFiles.nameFor(firstHash)
        val previous = VersionedRuleFiles.nameFor(secondHash)

        assertEquals(previous, VersionedRuleFiles.previousFor(current, current, previous))
        assertEquals(
            current,
            VersionedRuleFiles.previousFor(
                targetFile = VersionedRuleFiles.nameFor(thirdHash),
                currentFile = current,
                previousFile = previous,
            ),
        )
    }

    @Test
    fun keepsValidatedFallbackInsteadOfCorruptPersistedCurrent() {
        val corruptCurrent = VersionedRuleFiles.nameFor(firstHash)
        val validatedFallback = VersionedRuleFiles.nameFor(secondHash)
        val target = VersionedRuleFiles.nameFor(thirdHash)

        // The caller passes the validated selection, never the persisted corrupt pointer.
        assertEquals(
            validatedFallback,
            VersionedRuleFiles.previousAfterValidatedUpdate(target, validatedFallback, corruptCurrent),
        )
    }

    @Test
    fun dropsUnvalidatedPreviousWhenNoReadableCatalogExists() {
        val corruptPrevious = VersionedRuleFiles.nameFor(firstHash)
        val target = VersionedRuleFiles.nameFor(secondHash)

        assertNull(VersionedRuleFiles.previousAfterValidatedUpdate(target, null, corruptPrevious))
    }

    @Test
    fun keepsStoredPreviousWhenUpdatingTheSameValidatedCatalog() {
        val current = VersionedRuleFiles.nameFor(firstHash)
        val previous = VersionedRuleFiles.nameFor(secondHash)

        assertEquals(
            previous,
            VersionedRuleFiles.previousAfterValidatedUpdate(current, current, previous),
        )
    }
}
