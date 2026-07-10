package com.fankes.coloros.notify.hook.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderIconClaimRegistryTest {

    private val registry = HeaderIconClaimRegistry<Any, Any>()

    @Test
    fun `claim matches only the same notification and rendered content`() {
        val target = Any()
        val content = Any()

        registry.claim(target, "notification-a", content)

        assertTrue(registry.isCurrentClaim(target, "notification-a", content))
    }

    @Test
    fun `reused target invalidates a claim from another notification`() {
        val target = Any()
        val content = Any()

        registry.claim(target, "notification-a", content)

        assertFalse(registry.isCurrentClaim(target, "notification-b", content))
        assertFalse(registry.isCurrentClaim(target, "notification-a", content))
    }

    @Test
    fun `replaced rendered content invalidates an existing claim`() {
        val target = Any()

        registry.claim(target, "notification-a", Any())

        assertFalse(registry.isCurrentClaim(target, "notification-a", Any()))
    }

    @Test
    fun `release and clear remove claims`() {
        val firstTarget = Any()
        val firstContent = Any()
        val secondTarget = Any()
        val secondContent = Any()
        registry.claim(firstTarget, "notification-a", firstContent)
        registry.claim(secondTarget, "notification-b", secondContent)

        registry.release(firstTarget)
        assertFalse(registry.isCurrentClaim(firstTarget, "notification-a", firstContent))
        assertTrue(registry.isCurrentClaim(secondTarget, "notification-b", secondContent))

        registry.clear()
        assertFalse(registry.isCurrentClaim(secondTarget, "notification-b", secondContent))
    }
}
