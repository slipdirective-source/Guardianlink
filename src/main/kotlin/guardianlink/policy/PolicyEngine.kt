package guardianlink.policy

import guardianlink.integrity.DeviceIntegrityTier

/**
 * Authorization hook for hard-rail lifecycle.
 *
 * TRUST BOUNDARY: hard-rail registration/revocation is a privileged
 * operation. Production deployments MUST supply an authorizer backed by
 * real authentication (capability tokens, signed admin commands, hardware
 * attestation). The default [DENY_ALL] denies every lifecycle call — an
 * engine constructed without an authorizer cannot arm any hard rail, which
 * is the fail-closed posture. Demos and tests pass an explicit permissive
 * authorizer; that is never deployment-safe.
 */
fun interface RailAuthorizer {
    enum class RailAction { REGISTER, REVOKE }
    fun authorized(action: RailAction, railId: String): Boolean

    companion object {
        val DENY_ALL: RailAuthorizer = RailAuthorizer { _, _ -> false }

        /** Demo/test only. Never use in production. */
        val PERMISSIVE: RailAuthorizer = RailAuthorizer { _, _ -> true }
    }
}

class PolicyEngine(
    activeHardRails: MutableSet<String> = mutableSetOf(),
    private val authorizer: RailAuthorizer = RailAuthorizer.DENY_ALL,
) {
    // Defensive copy: the caller-owned set must not be an unauthenticated
    // side channel. Without the copy, whoever holds the reference passed
    // here could arm or disarm hard rails by mutating it directly,
    // bypassing the authorizer entirely.
    private val activeHardRails: MutableSet<String> = activeHardRails.toMutableSet()
    enum class Tier { EVIDENCE_ONLY, SOFT_ADVISORY, HARD_ENFORCE }

    sealed class EnforcementDecision {
        data class Evidence(val note: String) : EnforcementDecision()
        data class Advisory(val note: String) : EnforcementDecision()
        data class Enforce internal constructor(val hardRailRef: String) : EnforcementDecision()
        data class Denied(val reason: String) : EnforcementDecision()
    }

    /**
     * Arm a hard rail. Returns false (and changes nothing) when the
     * authorizer denies the call — registration is never unauthenticated.
     */
    fun registerHardRail(id: String): Boolean {
        if (!authorizer.authorized(RailAuthorizer.RailAction.REGISTER, id)) return false
        activeHardRails.add(id)
        return true
    }

    /**
     * Disarm a hard rail. Returns false (and changes nothing) when the
     * authorizer denies the call.
     */
    fun revokeHardRail(id: String): Boolean {
        if (!authorizer.authorized(RailAuthorizer.RailAction.REVOKE, id)) return false
        activeHardRails.remove(id)
        return true
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