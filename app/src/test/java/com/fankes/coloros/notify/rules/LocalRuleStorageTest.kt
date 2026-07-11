package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRuleStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesAndReadsVerifiedContentAddressedPayload() {
        val storage = LocalRuleStorage(temporaryFolder.newFolder("rules"))
        val payload = "[{\"packageName\":\"example\"}]"
        val sha256 = RuleCatalogParser.sha256(payload)

        val fileName = storage.writeVerified(payload, sha256)

        assertEquals(VersionedRuleFiles.nameFor(sha256), fileName)
        assertEquals(payload, storage.readVerified(fileName, sha256))
        assertEquals(fileName, storage.writeVerified(payload, sha256))
    }

    @Test
    fun rejectsPointerWhoseNameDoesNotMatchHash() {
        val storage = LocalRuleStorage(temporaryFolder.newFolder("rules"))

        assertThrows(IllegalArgumentException::class.java) {
            storage.readVerified("rules-${"1".repeat(64)}.json", "2".repeat(64))
        }
    }

    @Test
    fun replacesOversizedCorruptFileWithVerifiedPayloadOfSameName() {
        val directory = temporaryFolder.newFolder("rules")
        val storage = LocalRuleStorage(directory)
        val payload = "valid"
        val sha256 = RuleCatalogParser.sha256(payload)
        directory.resolve(VersionedRuleFiles.nameFor(sha256))
            .writeBytes(ByteArray(RulePayloadIO.MAX_PAYLOAD_BYTES + 1))

        val fileName = storage.writeVerified(payload, sha256)

        assertEquals(payload, storage.readVerified(fileName, sha256))
    }

    @Test
    fun cleanupRetainsOnlyCurrentAndPreviousPayload() {
        val directory = temporaryFolder.newFolder("rules")
        val storage = LocalRuleStorage(directory)
        val files = listOf("one", "two", "three").associateWith { payload ->
            storage.writeVerified(payload, RuleCatalogParser.sha256(payload))
        }
        directory.resolve(".rules-${"a".repeat(64)}.00000000-0000-0000-0000-000000000000.tmp")
            .writeText("partial")

        assertEquals(
            emptyList<String>(),
            storage.cleanup(
                currentFile = files.getValue("three"),
                previousFile = files.getValue("two"),
                includeTemporary = true,
            ),
        )
        assertTrue(directory.resolve(files.getValue("three")).isFile)
        assertTrue(directory.resolve(files.getValue("two")).isFile)
        assertFalse(directory.resolve(files.getValue("one")).exists())
        assertFalse(
            directory.resolve(".rules-${"a".repeat(64)}.00000000-0000-0000-0000-000000000000.tmp")
                .exists()
        )
    }

    @Test
    fun normalPublishCleanupNeverDeletesAnotherWritersTemporaryFile() {
        val directory = temporaryFolder.newFolder("rules")
        val storage = LocalRuleStorage(directory)
        val current = storage.writeVerified("current", RuleCatalogParser.sha256("current"))
        val temporary = directory.resolve(
            ".rules-${"a".repeat(64)}.00000000-0000-0000-0000-000000000000.tmp"
        ).apply { writeText("in-flight") }

        storage.cleanup(currentFile = current, previousFile = null)

        assertTrue(temporary.isFile)
    }
}
