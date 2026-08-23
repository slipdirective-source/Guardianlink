package guardianlink.integrity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceIntegrityTierTest {

    @Test
    fun testEvaluateAllPassed() {
        val signals = listOf(
            DeviceIntegrityTier.Signal("bootloader", true),
            DeviceIntegrityTier.Signal("secure_enclave", true),
            DeviceIntegrityTier.Signal("attestation", true)
        )
        val level = DeviceIntegrityTier.evaluate(signals)
        assertEquals(DeviceIntegrityTier.Level.VERIFIED, level)
    }

    @Test
    fun testEvaluateSomeFailed() {
        val signals = listOf(
            DeviceIntegrityTier.Signal("bootloader", true),
            DeviceIntegrityTier.Signal("secure_enclave", false),
            DeviceIntegrityTier.Signal("attestation", true)
        )
        val level = DeviceIntegrityTier.evaluate(signals)
        assertEquals(DeviceIntegrityTier.Level.DEGRADED, level)
    }

    @Test
    fun testEvaluateAllFailed() {
        val signals = listOf(
            DeviceIntegrityTier.Signal("bootloader", false),
            DeviceIntegrityTier.Signal("secure_enclave", false)
        )
        val level = DeviceIntegrityTier.evaluate(signals)
        assertEquals(DeviceIntegrityTier.Level.COMPROMISED, level)
    }

    @Test
    fun testEvaluateEmpty() {
        val level = DeviceIntegrityTier.evaluate(emptyList())
        assertEquals(DeviceIntegrityTier.Level.UNKNOWN, level)
    }

    @Test
    fun testOverrideEligibleVerified() {
        assertTrue(DeviceIntegrityTier.overrideEligible(DeviceIntegrityTier.Level.VERIFIED))
    }

    @Test
    fun testOverrideEligibleDegraded() {
        assertTrue(DeviceIntegrityTier.overrideEligible(DeviceIntegrityTier.Level.DEGRADED))
    }

    @Test
    fun testOverrideNotEligibleCompromised() {
        assertFalse(DeviceIntegrityTier.overrideEligible(DeviceIntegrityTier.Level.COMPROMISED))
    }

    @Test
    fun testOverrideNotEligibleUnknown() {
        assertFalse(DeviceIntegrityTier.overrideEligible(DeviceIntegrityTier.Level.UNKNOWN))
    }
}
