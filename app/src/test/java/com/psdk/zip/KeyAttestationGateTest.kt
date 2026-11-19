package com.psdk.zip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A device failing the integrity check: the player reads why, and whether
// trying again can help (an uncertified device can't pass later)
class KeyAttestationGateTest {
    @Test
    fun aDeviceThatCanNeverPassIsTold() {
        for (reason in listOf("software_only_security_level", "verified_boot_failed", "root_not_recognized")) {
            assertTrue(reason, KeyAttestationGate.isPermanent(reason))
            val message = KeyAttestationGate.playerMessage(reason)
            assertTrue(message, message.contains("can't be played on this device"))
            assertTrue(message, message.contains("($reason)"))
        }
    }

    @Test
    fun aCheckThatMayPassLaterSaysToTryAgain() {
        val reason = "attestation_exception:ProviderException"
        assertFalse(KeyAttestationGate.isPermanent(reason))
        val message = KeyAttestationGate.playerMessage(reason)
        assertTrue(message, message.contains("Try again"))
        assertTrue(message, message.contains("($reason)"))
    }
}
