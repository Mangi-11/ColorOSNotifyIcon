package com.fankes.coloros.notify.hook.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarColorabilityPolicyTest {

    @Test
    fun `app icon marker is delegated while host behavior is preserved`() {
        assertEquals(
            StatusBarColorabilityAction.KeepHostResult,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = null,
                usesAppIcon = true,
                keepHostAppIconBehavior = true,
                keepNotificationHostDefault = false,
            )
        )
    }

    @Test
    fun `notification preservation always delegates to host predicate`() {
        assertEquals(
            StatusBarColorabilityAction.KeepHostResult,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = null,
                usesAppIcon = true,
                keepHostAppIconBehavior = false,
                keepNotificationHostDefault = true,
            )
        )
    }

    @Test
    fun `module-owned unclaimed notification ignores marker from original notification`() {
        assertEquals(
            StatusBarColorabilityAction.ClassifyNotificationMask,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = null,
                usesAppIcon = true,
                keepHostAppIconBehavior = false,
                keepNotificationHostDefault = false,
            )
        )
    }

    @Test
    fun `managed notification uses notification mask classifier`() {
        assertEquals(
            StatusBarColorabilityAction.ClassifyNotificationMask,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = null,
                usesAppIcon = false,
                keepHostAppIconBehavior = true,
                keepNotificationHostDefault = false,
            )
        )
    }

    @Test
    fun `module notification mask overrides the original app icon marker`() {
        assertEquals(
            StatusBarColorabilityAction.MarkColorable,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = true,
                usesAppIcon = true,
                keepHostAppIconBehavior = true,
                keepNotificationHostDefault = false,
            )
        )
    }

    @Test
    fun `module full color theme icon is never tinted`() {
        assertEquals(
            StatusBarColorabilityAction.MarkNotColorable,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = false,
                usesAppIcon = false,
                keepHostAppIconBehavior = false,
                keepNotificationHostDefault = false,
            )
        )
    }

    @Test
    fun `exact rendered module claim wins over current host preservation`() {
        assertEquals(
            StatusBarColorabilityAction.MarkColorable,
            StatusBarColorabilityPolicy.select(
                moduleReplacementColorable = true,
                usesAppIcon = true,
                keepHostAppIconBehavior = false,
                keepNotificationHostDefault = true,
            )
        )
    }
}
