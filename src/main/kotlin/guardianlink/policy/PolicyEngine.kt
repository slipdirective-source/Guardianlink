package guardianlink.policy

import guardianlink.integrity.DeviceIntegrityTier

class PolicyEngine(
    private val activeHardRails: MutableSet<String> = mutableSetOf()
) {
    enum class Tier { EVIDENCE_ONLY, SOFT_ADVISORY, HARD_ENFORCE }

    sealed class EnforcementDecision {
        data class Evidence(val note: String) : EnforcementDecision()
        data class Advisory(val note: String) : EnforcementDecision()
        data class Enforce internal constructor(val hardRailRef: String) : EnforcementDecision()
        data class Denied(val reason: String) : EnforcementDecision()
    }

    fun registerHardRail(id: String) {
        activeHardRails.add(id)
    }

    fun revokeHardRail(id: String) {
        activeHardRails.remove(id)
    }

    fun evaluate(
        requestedTier: Tier,
        hardRailRef: String?,
        deviceIntegrity: DeviceIntegrityTier.Level,
        context: String
    ): EnforcementDecision {
        if (deviceIntegrity == DeviceIntegrityTier.Level.COMPROMISED ||
            deviceIntegrity == DeviceIntegrityTier.Level.UNKNOWN
        ) {
            return EnforcementDecision.Denied("device integrity tier ($deviceIntegrity) blocks enforcement/advisory; evidence-only")
        }

        return when (requestedTier) {
            Tier.EVIDENCE_ONLY -> EnforcementDecision.Evidence(context)

            Tier.SOFT_ADVISORY -> EnforcementDecision.Advisory(context)

            Tier.HARD_ENFORCE -> {
                if (hardRailRef != null && activeHardRails.contains(hardRailRef)) {
                    EnforcementDecision.Enforce(hardRailRef)
                } else {
                    EnforcementDecision.Denied("HARD_ENFORCE requested with no active hard rail — denied, not downgraded")
                }
            }
        }
    }
}