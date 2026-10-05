package guardianlink.core

import guardianlink.audit.MerkleAuditLog
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConciergeInterfaceTest {
    private val policyEngine = PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE)
    private val auditLog = MerkleAuditLog()
    private val concierge = ConciergeInterface(policyEngine, auditLog)

    @Test
    fun testHandleRequestWithEvidenceTier() {
        val request = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.EVIDENCE_ONLY,
            hardRailRef = null,
            context = "User accessed profile data"
        )
        
        val response = concierge.handle(request, DeviceIntegrityTier.Level.VERIFIED)
        
        assertTrue(response.decision is PolicyEngine.EnforcementDecision.Evidence)
        assertTrue(response.auditEntryIndex >= 0)
        assertEquals(1, auditLog.size)
    }

    @Test
    fun testHandleRequestWithHardEnforce() {
        policyEngine.registerHardRail("deletion")
        
        val request = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "deletion",
            context = "User requested permanent data deletion"
        )
        
        val response = concierge.handle(request, DeviceIntegrityTier.Level.VERIFIED)
        
        assertTrue(response.decision is PolicyEngine.EnforcementDecision.Enforce)
        assertEquals(1, auditLog.size)
    }

    @Test
    fun testHandleRequestDeniedOnCompromisedDevice() {
        val request = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "test-rail",
            context = "test"
        )
        
        val response = concierge.handle(request, DeviceIntegrityTier.Level.COMPROMISED)
        
        assertTrue(response.decision is PolicyEngine.EnforcementDecision.Denied)
        assertEquals(1, auditLog.size)  // Denied decisions still get logged
    }

    @Test
    fun testAuditTrailGrows() {
        val request1 = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.EVIDENCE_ONLY,
            hardRailRef = null,
            context = "action 1"
        )
        val request2 = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.EVIDENCE_ONLY,
            hardRailRef = null,
            context = "action 2"
        )
        
        concierge.handle(request1, DeviceIntegrityTier.Level.VERIFIED)
        assertEquals(1, auditLog.size)
        
        concierge.handle(request2, DeviceIntegrityTier.Level.VERIFIED)
        assertEquals(2, auditLog.size)
    }

    @Test
    fun testIntegrationEnd2End() {
        // Scenario: User tries to delete data, device is verified
        policyEngine.registerHardRail("data-deletion")
        
        val signals = listOf(
            DeviceIntegrityTier.Signal("bootloader_verified", true),
            DeviceIntegrityTier.Signal("secure_enclave", true)
        )
        val deviceLevel = DeviceIntegrityTier.evaluate(signals)
        assertEquals(DeviceIntegrityTier.Level.VERIFIED, deviceLevel)
        
        val request = ConciergeInterface.Request(
            tier = PolicyEngine.Tier.HARD_ENFORCE,
            hardRailRef = "data-deletion",
            context = "User confirmed permanent deletion of all personal data"
        )
        
        val response = concierge.handle(request, deviceLevel)
        
        assertTrue(response.decision is PolicyEngine.EnforcementDecision.Enforce)
        assertTrue(response.auditEntryIndex >= 0)
        assertEquals(1, auditLog.size)
        
        val integrityStatus = auditLog.verifyIntegrity()
        assertEquals(-1, integrityStatus)  // all good
    }
}
