package com.fankes.coloros.notify.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstalledPackageInventoryTest {

    @Test
    fun `collects rule packages from every accessible profile`() {
        val packagesByProfile = mapOf(
            "personal" to setOf("personal.only"),
            "work" to setOf("work.without.launcher"),
        )

        val snapshot = InstalledPackageInventory.collect(
            rulePackageNames = setOf("personal.only", "work.without.launcher", "missing"),
            readCurrentUserPackages = { setOf("personal.only", "unrelated") },
            readAccessibleProfiles = { listOf("personal", "work") },
            isInstalledForProfile = { packageName, profile ->
                packageName in packagesByProfile.getValue(profile)
            },
        )

        assertEquals(
            setOf("personal.only", "work.without.launcher", "unrelated"),
            snapshot.names,
        )
        assertEquals(true, snapshot.available)
    }

    @Test
    fun `does not require profile access when every rule is installed for current user`() {
        val snapshot = InstalledPackageInventory.collect<String>(
            rulePackageNames = setOf("installed"),
            readCurrentUserPackages = { setOf("installed") },
            readAccessibleProfiles = { error("Profile lookup should not run") },
            isInstalledForProfile = { _, _ -> error("Package lookup should not run") },
        )

        assertEquals(setOf("installed"), snapshot.names)
        assertEquals(true, snapshot.available)
    }

    @Test
    fun `propagates an inaccessible profile so caller can conservatively reject snapshot`() {
        assertThrows(SecurityException::class.java) {
            InstalledPackageInventory.collect(
                rulePackageNames = setOf("unresolved"),
                readCurrentUserPackages = { emptySet() },
                readAccessibleProfiles = { throw SecurityException("profile access denied") },
                isInstalledForProfile = { _, _: String -> false },
            )
        }
    }
}
