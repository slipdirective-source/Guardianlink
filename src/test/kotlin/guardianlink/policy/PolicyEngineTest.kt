package guardianlink.policy

import guardianlink.integrity.DeviceIntegrityTier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyEngineTest {
    private val engine = PolicyEngine()

    @Test
    fun testEvidenceOnlyAlwaysSucceeds() {
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.EVIDENCE_ONLY,
            hardRailRef = null,
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Evidence)
    }

    @Test
    fun testSoftAdvisoryWithVerifiedDevice() {
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.SOFT_ADVISORY,
            hardRailRef = null,
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "advisory test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Advisory)
    }

    @Test
    fun testHardEnforceRequiresRegisteredRail() {
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "unregistered",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "enforce test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Denied)
    }

    @Test
    fun testHardEnforceWithRegisteredRail() {
        engine.registerHardRail("data-deletion")
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "data-deletion",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "enforce with rail"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Enforce)
    }

    @Test
    fun testFailClosedOnCompromisedDevice() {
        engine.registerHardRail("test-rail")
        
        val decisionEvidence = engine.evaluate(
            requestedTier = PolicyEngine.Tier.EVIDENCE_ONLY,
            hardRailRef = "test-rail",
            deviceIntegrity = DeviceIntegrityTier.Level.COMPROMISED,
            context = "test"
        )
        assertTrue(decisionEvidence is PolicyEngine.EnforcementDecision.Denied)
        
        val decisionAdvisory = engine.evaluate(
            requestedTier = PolicyEngine.Tier.SOFT_ADVISORY,
            hardRailRef = "test-rail",
            deviceIntegrity = DeviceIntegrityTier.Level.COMPROMISED,
            context = "test"
        )
        assertTrue(decisionAdvisory is PolicyEngine.EnforcementDecision.Denied)
        
        val decisionEnforce = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "test-rail",
            deviceIntegrity = DeviceIntegrityTier.Level.COMPROMISED,
            context = "test"
        )
        assertTrue(decisionEnforce is PolicyEngine.EnforcementDecision.Denied)
    }

    @Test
    fun testFailClosedOnUnknownDevice() {
        engine.registerHardRail("test-rail")
        
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "test-rail",
            deviceIntegrity = DeviceIntegrityTier.Level.UNKNOWN,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Denied)
    }

    @Test
    fun testRevokeHardRail() {
        engine.registerHardRail("to-revoke")
        
        var decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "to-revoke",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Enforce)
        
        engine.revokeHardRail("to-revoke")
        
        decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "to-revoke",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Denied)
    }
}
