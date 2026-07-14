package com.fankes.coloros.notify.ui.rules

internal data class InstalledPackageSnapshot(
    val names: Set<String>,
    val available: Boolean,
)

/**
 * Collects packages installed for the current user or another profile exposed by LauncherApps.
 *
 * LauncherApps intentionally limits [readAccessibleProfiles] to profiles visible to the caller.
 * Callers must treat an exception from profile enumeration or package lookup as an incomplete
 * snapshot instead of classifying unresolved packages as not installed.
 */
internal object InstalledPackageInventory {

    fun <Profile> collect(
        rulePackageNames: Set<String>,
        readCurrentUserPackages: () -> Set<String>,
        readAccessibleProfiles: () -> List<Profile>,
        isInstalledForProfile: (packageName: String, profile: Profile) -> Boolean,
    ): InstalledPackageSnapshot {
        val installedPackages = readCurrentUserPackages().toMutableSet()
        val unresolvedRulePackages = rulePackageNames - installedPackages
        if (unresolvedRulePackages.isEmpty()) {
            return InstalledPackageSnapshot(names = installedPackages, available = true)
        }

        val profiles = readAccessibleProfiles().distinct()
        unresolvedRulePackages.forEach { packageName ->
            if (profiles.any { profile -> isInstalledForProfile(packageName, profile) }) {
                installedPackages += packageName
            }
        }
        return InstalledPackageSnapshot(names = installedPackages, available = true)
    }
}
