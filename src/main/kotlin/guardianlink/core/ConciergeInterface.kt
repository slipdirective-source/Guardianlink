package guardianlink.core

import guardianlink.audit.MerkleAuditLog
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.policy.PolicyEngine

class ConciergeInterface(
    private val policyEngine: PolicyEngine,
    private val auditLog: MerkleAuditLog
) {
    data class Request(val tier: PolicyEngine.Tier, val hardRailRef: String?, val context: String)
    data class Response(val decision: PolicyEngine.EnforcementDecision, val auditEntryIndex: Int)

    fun handle(request: Request, deviceIntegrity: DeviceIntegrityTier.Level): Response {
        val decision = policyEngine.evaluate(
            requestedTier = request.tier,
            hardRailRef = request.hardRailRef,
            deviceIntegrity = deviceIntegrity,
            context = request.context
        )

        val entry = auditLog.append(
            "${request.tier}|${request.hardRailRef}|${decision}".toByteArray(Charsets.UTF_8)
        )

        return Response(decision, entry.index)
    }
}