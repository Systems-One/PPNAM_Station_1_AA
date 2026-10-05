package com.mitas.ppnam.station1aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTagPolicyTest {

    @Test
    fun badgeEpcIsUserTag() {
        assertTrue(UserTagPolicy.isUserTag("50505501AABBCCDDEEFF001122334455"))
    }

    @Test
    fun lowerCaseWithWhitespaceIsUserTag() {
        assertTrue(UserTagPolicy.isUserTag("  50505501aabbccddeeff001122334455 \n"))
    }

    @Test
    fun itemEpcIsNotUserTag() {
        assertFalse(UserTagPolicy.isUserTag("E280689400005015ABCD1234"))
    }

    @Test
    fun legacyBadgeIsNotUserTag() {
        assertFalse(UserTagPolicy.isUserTag("5A39F436A9F28F75D72A24205F22E81A"))
    }

    @Test
    fun nullAndBlankAreNotUserTag() {
        assertFalse(UserTagPolicy.isUserTag(null))
        assertFalse(UserTagPolicy.isUserTag(""))
        assertFalse(UserTagPolicy.isUserTag("   "))
    }

    @Test
    fun itemScanForwardedOnlyForNonEmptyNonBadge() {
        assertTrue(UserTagPolicy.shouldForwardItemScan("E280689400005015ABCD1234"))
        assertFalse(UserTagPolicy.shouldForwardItemScan("50505501AABBCCDDEEFF001122334455"))
        assertFalse(UserTagPolicy.shouldForwardItemScan(null))
        assertFalse(UserTagPolicy.shouldForwardItemScan(""))
    }
}
