package com.fankes.coloros.notify.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleCatalogParserTest {

    @Test
    fun buildsDefinitionsAndIndexesWithoutMixingOverrides() {
        val asset = IconAsset.fromBytesForTest(byteArrayOf(1, 2, 3))
        val catalog = RuleCatalogParser.fromInputs(
            inputs = listOf(input(packageName = " com.example.app ")),
            contentSha256 = "hash",
            iconFactory = { asset },
        )

        val definition = catalog.definitions.single()
        assertEquals("com.example.app", definition.packageName)
        assertEquals(0xFF112233.toInt(), definition.iconColor)
        assertSame(asset, definition.icon)
        assertSame(definition, catalog.byPackage.getValue("com.example.app"))

        val resolved = catalog.resolve(
            RuleOverrides(enabled = mapOf("com.example.app" to false))
        )
        assertEquals(false, resolved.rules.single().isEnabled)
        assertSame(asset, resolved.rules.single().iconAsset)
    }

    @Test
    fun rejectsDuplicatePackagesInsteadOfSilentlyDroppingEntries() {
        val exception = assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.fromInputs(
                inputs = listOf(input(), input()),
                iconFactory = { IconAsset.fromBytesForTest(byteArrayOf(1)) },
            )
        }

        assertEquals("Duplicate packageName: com.example.app", exception.cause?.message)
    }

    @Test
    fun invalidOptionalColorFallsBackToNoTint() {
        val catalog = RuleCatalogParser.fromInputs(
            inputs = listOf(input(iconColor = "#ffFFFFF")),
            iconFactory = { IconAsset.fromBytesForTest(byteArrayOf(1)) },
        )

        assertEquals(0, catalog.definitions.single().iconColor)
    }

    @Test
    fun hashesUtf8PayloadWithLowercaseSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RuleCatalogParser.sha256("abc"),
        )
    }

    @Test
    fun rejectsInvalidPackageName() {
        val exception = assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.fromInputs(
                inputs = listOf(input(packageName = "com.example.bad-name")),
                iconFactory = { IconAsset.fromBytesForTest(byteArrayOf(1)) },
            )
        }

        assertEquals("packageName is invalid: com.example.bad-name", exception.cause?.message)
    }

    @Test
    fun rejectsExcessiveRuleCountBeforeCreatingAssets() {
        assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.fromInputs(
                inputs = List(4097) { input(packageName = "com.example.p$it") },
                iconFactory = { IconAsset.fromBytesForTest(byteArrayOf(1)) },
            )
        }
    }

    @Test
    fun rejectsOversizedDisplayFields() {
        val exception = assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.fromInputs(
                inputs = listOf(input().copy(appName = "a".repeat(257))),
                iconFactory = { IconAsset.fromBytesForTest(byteArrayOf(1)) },
            )
        }

        assertEquals("appName is too long", exception.cause?.message)
    }

    @Test
    fun rejectsOversizedJsonBeforeParsing() {
        assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.parse(" ".repeat(8 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun rejectsOversizedUtf8PayloadEvenWhenCharacterCountFits() {
        assertThrows(RuleParseException::class.java) {
            RuleCatalogParser.parse("你".repeat(3 * 1024 * 1024))
        }
    }

    private fun input(
        packageName: String = "com.example.app",
        iconColor: String = "#112233",
    ) = RuleInput(
        appName = "Example",
        packageName = packageName,
        iconBase64 = "unused-by-test-factory",
        iconColor = iconColor,
        contributorName = "Tester",
        enabledByDefault = true,
        enabledAllByDefault = false,
    )
}
