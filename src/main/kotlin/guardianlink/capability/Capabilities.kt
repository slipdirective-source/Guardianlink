package guardianlink.capability

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Epoch capabilities — the capability mechanism (frozen spec v2.1).
 *
 * Two classes:
 * - [MutationCapability]: authorization of ONE admitted mutation object.
 *   Consumable, nonreplayable. Commit rule:
 *   `Commit(m, t_c) ⇐ Valid(MC, t_c) ∧ H(m) == MC.mutationHash ∧ ¬MC.consumed`
 * - [EpochCapability]: authorization of a BOUNDED SET of effects.
 *   Reuse within the epoch is intended. Commit rule:
 *   `Commit(m, t_c) ⇐ Valid(EC, t_c) ∧ P_EC(m) ∧ WithinBounds(EC, usage)
 *     ∧ StateDepsHold(EC) ∧ ¬Revoked(EC)`
 *
 * Predicate-not-possession (hard): possession of a valid capability
 * authorizes nothing by itself. Every commit re-evaluates the predicate
 * and the bounds at the commit boundary.
 *
 * Unforgeability in this phase is via the issuer's live-set: a presented
 * capability must have been minted by THIS issuer and be live (not
 * consumed, not revoked). The Ed25519 signature over the canonical
 * encoding (spec §1) arrives in the keys phase per H2 (mediation before
 * crypto); within the engine trust boundary the live-set is the
 * unforgeability mechanism, and it is the stronger one here — a signature
 * cannot express consumption or revocation.
 *
 * Durable accounting (crash-persistent consumption/usage) is the next
 * phase per H2/H1. This phase proves the mediation semantics in memory,
 * with bound-check and consumption-record atomic w.r.t. the commit
 * decision (synchronized on the issuer).
 */

/** SHA-256 hex of the canonical rendering: the object binding. */
fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Security-relevant state versions. A capability binds a snapshot of the
 * deps it was admitted under; if any bound dep's version changes before
 * commit, the capability is VOID (not merely refused — the admission
 * premises no longer hold). Canonical example: enrollment replacement
 * kills a TUN epoch issued under the old voiceprint.
 */
class SecurityState {
    private val versions = ConcurrentHashMap<String, Long>()
    fun versionOf(dep: String): Long = versions.getOrDefault(dep, 0L)
    fun noteChange(dep: String) {
        versions.merge(dep, 1L, Long::sum)
    }
    fun snapshot(): Map<String, Long> = versions.toMap()
}

/**
 * Small, decidable predicate language (§2): `targetPrefix`, `allowedTypes`.
 * Bounds (`maxMutations`, `maxBytes`) live on the capability and are
 * checked against usage (WithinBounds), not against the effect.
 * A predicate that cannot be evaluated fails closed.
 */
data class CapabilityPredicate(
    val targetPrefix: String?,
    val allowedTypes: Set<String>?,
) {
    fun allows(target: String, type: String): Boolean {
        if (targetPrefix != null && !target.startsWith(targetPrefix)) return false
        if (allowedTypes != null && type !in allowedTypes) return false
        return true
    }

    /** True iff [narrower] grants no more than this predicate. */
    fun isNarrowerOrEqual(narrower: CapabilityPredicate): Boolean {
        val prefixOk = when {
            targetPrefix == null -> true
            narrower.targetPrefix == null -> false // dropping the constraint widens
            else -> narrower.targetPrefix.startsWith(targetPrefix)
        }
        val typesOk = when {
            allowedTypes == null -> true
            narrower.allowedTypes == null -> false // dropping the constraint widens
            else -> narrower.allowedTypes.all { it in allowedTypes }
        }
        return prefixOk && typesOk
    }
}

/** One admitted mutation object. Single-use: commit consumes it. */
data class MutationCapability(
    val id: String,
    val mutationHash: String,
    val notBeforeMs: Long,
    val notAfterMs: Long,
    val stateDeps: Map<String, Long>,
    val issuerId: String,
)

/**
 * Authorization of a bounded set of effects. Reuse within the epoch is
 * intended; the discriminator is whether an effect OUTSIDE the admitted
 * set was made valid (§8).
 */
data class EpochCapability(
    val id: String,
    val epoch: Long,
    val predicate: CapabilityPredicate,
    val notBeforeMs: Long,
    val notAfterMs: Long,
    val stateDeps: Map<String, Long>,
    val issuerId: String,
    val issuedAtMs: Long,
    val maxMutations: Long?,
    val maxBytes: Long?,
    /** Non-null iff derived by holder-autonomous attenuation. */
    val parentId: String?,
) {
    /**
     * Holder-autonomous attenuation (macaroon-style, §5): derive a strictly
     * non-wider capability with no new authorization and no issuer call.
     * Returns null if the narrowing attempts ANY increase — wider
     * predicate, extended lifetime, or raised bounds. The narrowing needs
     * no signature because it grants strictly less; the commit boundary
     * re-verifies the derivation on presentation.
     */
    fun attenuateTo(
        narrower: CapabilityPredicate,
        notAfterMs: Long = this.notAfterMs,
        maxMutations: Long? = this.maxMutations,
        maxBytes: Long? = this.maxBytes,
    ): EpochCapability? {
        if (!predicate.isNarrowerOrEqual(narrower)) return null
        if (notAfterMs > this.notAfterMs) return null // constitutional: no self-renewal
        if (!boundNarrowerOrEqual(maxMutations, this.maxMutations)) return null
        if (!boundNarrowerOrEqual(maxBytes, this.maxBytes)) return null
        return copy(
            id = UUID.randomUUID().toString(),
            predicate = narrower,
            notAfterMs = notAfterMs,
            maxMutations = maxMutations,
            maxBytes = maxBytes,
            parentId = this.id,
        )
    }
}

/** null = unbounded. True iff [narrower] ≤ [parent]. */
internal fun boundNarrowerOrEqual(narrower: Long?, parent: Long?): Boolean {
    if (parent == null) return true // parent unbounded: anything is narrower
    if (narrower == null) return false // unbounded is wider than a bound
    return narrower <= parent
}

/** One effect proposed under an epoch capability. */
data class EpochEffect(val target: String, val type: String, val bytes: Long)

sealed interface CheckResult {
    data object Ok : CheckResult
    data class Refused(val reason: String) : CheckResult
}

/**
 * The admission boundary's capability output and the commit-point check.
 *
 * Minting is restricted: mutation capabilities are minted by the engine
 * after the gates admit; epoch capabilities are minted by an admission
 * event (ceremony or standing governance rule). There is deliberately NO
 * operation that derives extended lifetime from an existing capability —
 * renewal crosses the admission boundary again (§4, constitutional).
 *
 * All commit-point checks ([checkAndConsumeMutation], [checkAndRecordEpoch])
 * are synchronized: bound-check and consumption-record are one atomic step
 * w.r.t. the commit decision (H1, in-memory phase; crash-persistent
 * accounting is the next phase).
 */
open class CapabilityIssuer(
    val issuerId: String = "engine-admission-boundary",
    private val clockMs: () -> Long,
    val securityState: SecurityState = SecurityState(),
    private val maxMutationTtlMs: Long = 5 * 60 * 1000L,
    private val maxEpochTtlMs: Long = 60 * 60 * 1000L,
) {
    private data class LiveRecord(
        val capability: Any, // MutationCapability | EpochCapability
        var consumed: Boolean = false,
        var revoked: Boolean = false,
        var usageMutations: Long = 0L,
        var usageBytes: Long = 0L,
    )

    private val live = ConcurrentHashMap<String, LiveRecord>()
    private val revokedEpochs = ConcurrentHashMap.newKeySet<Long>()
    private var nextEpoch = 0L
    private val lock = Any()

    fun mintMutation(
        mutationHash: String,
        ttlMs: Long = maxMutationTtlMs,
    ): MutationCapability {
        require(ttlMs in 1..maxMutationTtlMs) { "mutation TTL out of bounds" }
        val now = clockMs()
        val cap = MutationCapability(
            id = UUID.randomUUID().toString(),
            mutationHash = mutationHash,
            notBeforeMs = now,
            notAfterMs = now + ttlMs,
            stateDeps = securityState.snapshot(),
            issuerId = issuerId,
        )
        live[cap.id] = LiveRecord(cap)
        return cap
    }

    fun mintEpoch(
        predicate: CapabilityPredicate,
        maxMutations: Long?,
        maxBytes: Long?,
        ttlMs: Long = maxEpochTtlMs,
    ): EpochCapability {
        require(ttlMs in 1..maxEpochTtlMs) { "epoch TTL out of bounds" }
        val now = clockMs()
        val epoch = synchronized(lock) { nextEpoch++ }
        val cap = EpochCapability(
            id = UUID.randomUUID().toString(),
            epoch = epoch,
            predicate = predicate,
            notBeforeMs = now,
            notAfterMs = now + ttlMs,
            stateDeps = securityState.snapshot(),
            issuerId = issuerId,
            issuedAtMs = now,
            maxMutations = maxMutations,
            maxBytes = maxBytes,
            parentId = null,
        )
        live[cap.id] = LiveRecord(cap)
        return cap
    }

    /**
     * The mutation commit point: validates AND consumes atomically.
     * A second commit with the same capability fails here.
     */
    open fun checkAndConsumeMutation(
        cap: MutationCapability,
        rendering: String,
        t_c: Long,
    ): CheckResult = synchronized(lock) {
        val rec = live[cap.id] ?: return Refused("capability not minted by this issuer")
        if (rec.revoked) return Refused("capability revoked")
        if (rec.consumed) return Refused("capability already consumed")
        if (rec.capability != cap) return Refused("capability fields differ from issuance record")
        if (t_c < cap.notBeforeMs) return Refused("capability not yet valid")
        if (t_c > cap.notAfterMs) return Refused("capability expired") // brutal: no grace, even 1ms
        if (cap.mutationHash != sha256Hex(rendering)) return Refused("mutation hash mismatch")
        for ((dep, v) in cap.stateDeps) {
            if (securityState.versionOf(dep) != v)
                return Refused("state dep '$dep' changed: capability void")
        }
        rec.consumed = true
        return CheckResult.Ok
    }

    /**
     * The epoch commit point: validates AND records usage atomically (H1).
     * Attenuated children (parentId != null) are verified against the live
     * parent on presentation: the derivation is re-checked, and usage
     * counts against BOTH the child's bounds and the parent's envelope,
     * so attenuation cannot escape the parent's bounds.
     */
    open fun checkAndRecordEpoch(
        cap: EpochCapability,
        effect: EpochEffect,
        t_c: Long,
    ): CheckResult = synchronized(lock) {
        var rec = live[cap.id]
        var parentRec: LiveRecord? = null
        if (rec == null && cap.parentId != null) {
            // Attenuated child, first presentation: verify the derivation
            // against the live parent. The holder derived autonomously;
            // the boundary re-verifies.
            parentRec = live[cap.parentId]
                ?: return Refused("attenuated parent unknown to this issuer")
            if (parentRec.revoked) return Refused("attenuated parent revoked")
            val p = parentRec.capability as? EpochCapability
                ?: return Refused("attenuated parent is not an epoch capability")
            if (p.epoch in revokedEpochs) return Refused("attenuated parent epoch revoked")
            if (!p.predicate.isNarrowerOrEqual(cap.predicate))
                return Refused("attenuated predicate wider than parent")
            if (cap.notAfterMs > p.notAfterMs)
                return Refused("attenuated lifetime exceeds parent")
            if (!boundNarrowerOrEqual(cap.maxMutations, p.maxMutations))
                return Refused("attenuated mutation bound exceeds parent")
            if (!boundNarrowerOrEqual(cap.maxBytes, p.maxBytes))
                return Refused("attenuated byte bound exceeds parent")
            if (cap.stateDeps != p.stateDeps)
                return Refused("attenuated state deps differ from parent")
            rec = LiveRecord(cap)
            live[cap.id] = rec
        }
        rec = rec ?: return Refused("capability not minted by this issuer")
        if (rec.revoked) return Refused("capability revoked")
        val epochOf: Long = (rec.capability as? EpochCapability)?.epoch
            ?: return Refused("not an epoch capability")
        if (epochOf in revokedEpochs) return Refused("epoch revoked")
        // Attenuated child already registered: the parent's epoch must
        // still be live (revocation propagates down).
        if (cap.parentId != null && parentRec == null) {
            parentRec = live[cap.parentId] ?: return Refused("attenuated parent unknown")
            if (parentRec.revoked) return Refused("attenuated parent revoked")
            val p = parentRec.capability as? EpochCapability
            if (p == null || p.epoch in revokedEpochs) return Refused("attenuated parent epoch revoked")
        }
        if (rec.capability != cap) return Refused("capability fields differ from issuance record")
        if (t_c < cap.notBeforeMs) return Refused("capability not yet valid")
        if (t_c > cap.notAfterMs) return Refused("capability expired")
        if (!cap.predicate.allows(effect.target, effect.type))
            return Refused("effect outside admitted predicate")
        for ((dep, v) in cap.stateDeps) {
            if (securityState.versionOf(dep) != v)
                return Refused("state dep '$dep' changed: capability void")
        }
        // H1: bound-check and consumption-record are one atomic step.
        // Overcounting (conservative) is preferred to undercounting:
        // uncertainty may cost availability, never authority.
        val newMutations = rec.usageMutations + 1
        val newBytes = rec.usageBytes + effect.bytes
        if (cap.maxMutations != null && newMutations > cap.maxMutations)
            return Refused("epoch mutation bound exceeded")
        if (cap.maxBytes != null && newBytes > cap.maxBytes)
            return Refused("epoch byte bound exceeded")
        // Attenuated child: the parent's envelope must also hold.
        if (parentRec != null) {
            val p = parentRec.capability as EpochCapability
            val pNewMutations = parentRec.usageMutations + 1
            val pNewBytes = parentRec.usageBytes + effect.bytes
            if (p.maxMutations != null && pNewMutations > p.maxMutations)
                return Refused("parent epoch mutation bound exceeded")
            if (p.maxBytes != null && pNewBytes > p.maxBytes)
                return Refused("parent epoch byte bound exceeded")
            parentRec.usageMutations = pNewMutations
            parentRec.usageBytes = pNewBytes
        }
        rec.usageMutations = newMutations
        rec.usageBytes = newBytes
        return CheckResult.Ok
    }

    /** Revocation is separate from expiry (§7): kills the remaining lifetime. */
    fun revoke(id: String) {
        live[id]?.revoked = true
    }

    /** Coarse revocation: kills the whole epoch. */
    fun revokeEpoch(epoch: Long) {
        revokedEpochs.add(epoch)
    }

    fun usageOf(id: String): Pair<Long, Long>? =
        live[id]?.let { it.usageMutations to it.usageBytes }

    fun isConsumed(id: String): Boolean = live[id]?.consumed == true
    fun isRevoked(id: String): Boolean = live[id]?.revoked == true
}
