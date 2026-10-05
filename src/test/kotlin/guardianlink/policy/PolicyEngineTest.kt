package guardianlink.policy

import guardianlink.integrity.DeviceIntegrityTier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyEngineTest {
    // Tests exercise enforcement logic with an explicit permissive
    // authorizer; authorization-denial is tested separately below.
    private val engine = PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE)

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

    // ------------------------------------------------------------------
    // Rail lifecycle authorization: unauthenticated registration/revocation
    // is denied by default (fail-closed).
    // ------------------------------------------------------------------

    @Test
    fun testRegisterDeniedWithoutAuthorizer() {
        val locked = PolicyEngine() // DENY_ALL by default
        assertEquals(false, locked.registerHardRail("sneaky"))
        val decision = locked.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "sneaky",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Denied)
    }

    @Test
    fun testRevokeDeniedWithoutAuthorizer() {
        val locked = PolicyEngine(authorizer = RailAuthorizer { action, _ ->
            action == RailAuthorizer.RailAction.REGISTER
        })
        assertTrue(locked.registerHardRail("sticky"))
        assertEquals(false, locked.revokeHardRail("sticky"))
        // Rail still armed: the denied revoke changed nothing.
        val decision = locked.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "sticky",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "test"
        )
        assertTrue(decision is PolicyEngine.EnforcementDecision.Enforce)
    }

    @Test
    fun testSelectiveAuthorizer() {
        var allowed = setOf<String>()
        val engine = PolicyEngine(authorizer = RailAuthorizer { _, railId -> railId in allowed })
        assertEquals(false, engine.registerHardRail("nope"))
        allowed = setOf("yes")
        assertTrue(engine.registerHardRail("yes"))
    }

    @Test
    fun testCallerSetMutationDoesNotArmRail() {
        // Round-2: the constructor retained the caller's MutableSet, so anyone
        // holding the reference could arm/disarm rails past the authorizer.
        // The engine must copy the set; external mutation changes nothing.
        val smuggled = mutableSetOf<String>()
        val engine = PolicyEngine(activeHardRails = smuggled) // default DENY_ALL
        smuggled.add("ghost-rail")
        val decision = engine.evaluate(
            requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "ghost-rail",
            deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
            context = "smuggle test"
        )
        assertTrue(
            decision is PolicyEngine.EnforcementDecision.Denied,
            "mutating the constructor set must not arm a rail, got: $decision"
        )
    }
}
