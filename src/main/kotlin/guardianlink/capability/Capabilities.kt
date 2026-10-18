package guardianlink.capability

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

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
        versions.merge(dep, 1L) { a, b -> a + b }
    }
    fun snapshot(): Map<String, Long> = versions.toMap()
}

/**
 * Small, decidable predicate language (§2): `targetPrefix`, `allowedTypes`.
 * Bounds (`maxMutations`, `maxBytes`) live on the capability and are
 * checked against usage (WithinBounds), not against the effect.
 * A predicate that cannot be evaluated fails closed.
 */
/**
 * C-4 Sovereign Issuance: Identity separation.
 *
 * Three distinct identity types:
 * - SovereignRootId: the persistent authority source. Stable across all epochs/lineages.
 * - LineageId: a continuing constraint history. Stable across rotation. Exists to key
 *   a future persistent counter; does NOT currently enforce cumulative constraints.
 * - EpochId: a single epoch (one issuance, one local budget). Unique per mint.
 *
 * These are value classes for zero-overhead type safety. They prevent accidental
 * confusion of identifiers at the type level.
 */
@JvmInline
value class SovereignRootId(val value: String)

@JvmInline
value class LineageId(val value: String)

/**
 * C-4 Sovereign Issuance Authority.
 *
 * This is a distinct type from MutationCapability and EpochCapability. It represents
 * the sovereign's authority to issue new epochs. It cannot be:
 * - Constructed through the public capability API (private constructor)
 * - Derived from a MutationCapability or EpochCapability (no conversion method exists)
 * - Attenuated or delegated (no such operation)
 * - Recovered from an epoch (epochs carry IDs, not issuance authority)
 *
 * Threat model: On the JVM, `internal` is public in bytecode and reflection can
 * construct anything. This object-capability pattern prevents *accidental* holder
 * access and makes the issuance boundary explicit in the type system. Against a
 * reflective adversary, the mechanical guarantee requires a MAC/signature permit
 * with the key outside the holder's address space (future work for cross-process).
 *
 * The authority is single-use: each `mintEpoch` consumes one authority object.
 * This makes issuance an explicit, countable act, mirroring the consumable
 * structure already used for mutation commits.
 */
class SovereignIssuanceAuthority private constructor(
    val rootId: SovereignRootId,
    val permitId: String,
    val lineageId: LineageId?,
    val newLineageId: LineageId?,
) {
    private var consumed = false

    internal fun tryConsume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    companion object {
        internal fun create(
            rootId: SovereignRootId,
            lineageId: LineageId?,
            newLineageId: LineageId?,
        ): SovereignIssuanceAuthority {
            require((lineageId == null) != (newLineageId == null)) {
                "Exactly one of lineageId (rotate) or newLineageId (new lineage) must be set"
            }
            return SovereignIssuanceAuthority(
                rootId = rootId,
                permitId = java.util.UUID.randomUUID().toString(),
                lineageId = lineageId,
                newLineageId = newLineageId,
            )
        }
    }
}

/**
 * A signed issuance permit for cross-process sovereign authority.
 *
 * The in-process SovereignIssuanceAuthority is an object-capability: it works
 * within a single process because object identity is unforgeable. Across
 * process boundaries, object identity does not survive serialization.
 *
 * This permit carries the same authority as a MAC (HMAC-SHA256). The signing
 * key is held by the SovereignRoot/issuer and never leaves it. A holder
 * cannot forge a permit without the key.
 *
 * The permitId is recorded in the WAL on use, providing single-use
 * enforcement across restart.
 */
data class SignedIssuancePermit(
    val rootId: String,
    val permitId: String,
    val lineageId: String?,
    val newLineageId: String?,
    val signature: String, // hex-encoded HMAC-SHA256
) {
    /** The signed payload: rootId|permitId|lineageId|newLineageId */
    fun payload(): String = listOf(
        rootId,
        permitId,
        lineageId ?: "",
        newLineageId ?: ""
    ).joinToString("|")

    companion object {
        internal fun sign(
            rootId: String,
            permitId: String,
            lineageId: String?,
            newLineageId: String?,
            key: ByteArray,
        ): SignedIssuancePermit {
            val payload = listOf(rootId, permitId, lineageId ?: "", newLineageId ?: "").joinToString("|")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            return SignedIssuancePermit(
                rootId = rootId,
                permitId = permitId,
                lineageId = lineageId,
                newLineageId = newLineageId,
                signature = sig.joinToString("") { "%02x".format(it) },
            )
        }

        internal fun verify(permit: SignedIssuancePermit, key: ByteArray): Boolean {
            val expected = sign(
                permit.rootId,
                permit.permitId,
                permit.lineageId,
                permit.newLineageId,
                key,
            )
            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(expected.signature, permit.signature)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }
    }
}

/**
 * The sovereign root: the persistent authority source.
 *
 * Created once via [bootstrap]. The root is NOT a consumable permit; it is the
 * authority source from which single-use issuance authorities are derived.
 */
class SovereignRoot private constructor(
    val rootId: SovereignRootId,
) {
    fun rotateAuthority(lineageId: LineageId): SovereignIssuanceAuthority =
        SovereignIssuanceAuthority.create(
            rootId = rootId,
            lineageId = lineageId,
            newLineageId = null,
        )

    fun newLineageAuthority(newLineageId: LineageId): SovereignIssuanceAuthority =
        SovereignIssuanceAuthority.create(
            rootId = rootId,
            lineageId = null,
            newLineageId = newLineageId,
        )

    companion object {
        fun bootstrap(rootId: SovereignRootId = SovereignRootId(java.util.UUID.randomUUID().toString())): SovereignRoot =
            SovereignRoot(rootId)
    }
}


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
    /**
     * Epoch binding (C-1): the ID of the EpochCapability under which this
     * mutation was authorized, bound by the issuer at mint time. Null if
     * the mutation was not authorized under an epoch (no cumulative bound).
     * The commit path derives the epoch from this authenticated binding —
     * never from caller-supplied evidence — and enforces the epoch's
     * cumulative bound via checkAndRecordEpoch. Fail closed if the binding
     * is absent when required, invalid, or unusable.
     */
    val epochId: String? = null,
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
    /** C-4: The sovereign root that authorized this epoch. */
    val rootId: SovereignRootId,
    /** C-4: The lineage this epoch belongs to. Stable across rotation. */
    val lineageId: LineageId,
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
    /**
     * C-4: The sovereign root this issuer serves. Epochs minted by this issuer
     * carry this rootId. The issuer refuses authorities from a different root.
     * Defaults to a fresh random root for backward compatibility with tests
     * that do not model sovereign setup; production should pass an explicit
     * root from SovereignRoot.bootstrap().
     */
    val rootId: SovereignRootId = SovereignRootId(java.util.UUID.randomUUID().toString()),
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
    /**
     * C-4: Lineage IDs that have been established via NEW_LINEAGE issuance.
     * Used to enforce uniqueness: a "new lineage" with a reused ID is a
     * history-reset path and is refused.
     */
    private val usedLineageIds = ConcurrentHashMap.newKeySet<LineageId>()
    /**
     * C-4: Permit IDs that have been consumed. Enforces single-use issuance
     * authorities. (In-memory; durable WAL integration is future work for
     * cross-restart single-use.)
     */
    private val consumedPermitIds = ConcurrentHashMap.newKeySet<String>()

    fun mintMutation(
        mutationHash: String,
        ttlMs: Long = maxMutationTtlMs,
        notBeforeMs: Long = clockMs(),
        /**
         * Epoch binding (C-1): ID of the EpochCapability under which this
         * mutation is authorized. The issuer verifies the epoch is live,
         * unrevoked, unexpired, and its epoch not revoked — then binds the
         * ID into the MutationCapability. Fail closed (do not mint) if the
         * epoch is unknown, revoked, expired, or otherwise unusable.
         * Null = no epoch bound (no cumulative bound enforced).
         */
        epochId: String? = null,
    ): MutationCapability {
        require(ttlMs in 1..maxMutationTtlMs) { "mutation TTL out of bounds" }
        val now = clockMs()
        // Verify the epoch binding BEFORE minting. This is the authorization-
        // time establishment of the epoch relationship. The commit path will
        // recover this binding from the minted capability — never by guessing.
        if (epochId != null) {
            val rec = synchronized(lock) { live[epochId] }
                ?: throw IllegalArgumentException("epoch not minted by this issuer: $epochId")
            if (rec.revoked) throw IllegalArgumentException("epoch revoked: $epochId")
            val epochCap = rec.capability as? EpochCapability
                ?: throw IllegalArgumentException("not an epoch capability: $epochId")
            if (epochCap.epoch in synchronized(lock) { revokedEpochs }) {
                throw IllegalArgumentException("epoch number revoked: $epochId")
            }
            if (now < epochCap.notBeforeMs || now >= epochCap.notAfterMs) {
                throw IllegalArgumentException("epoch not currently valid: $epochId")
            }
        }
        val cap = MutationCapability(
            id = UUID.randomUUID().toString(),
            mutationHash = mutationHash,
            notBeforeMs = notBeforeMs,
            notAfterMs = notBeforeMs + ttlMs,
            stateDeps = securityState.snapshot(),
            issuerId = issuerId,
            epochId = epochId,
        )
        live[cap.id] = LiveRecord(cap)
        return cap
    }

    fun mintEpoch(
        /**
         * C-4: Sovereign issuance authority. Must be a live, unconsumed authority
         * object obtained from the SovereignRoot. This is what makes epoch creation
         * structurally incapable of being reached by holders. The authority is
         * consumed by this call (single-use).
         */
        authority: SovereignIssuanceAuthority,
        predicate: CapabilityPredicate,
        maxMutations: Long?,
        maxBytes: Long?,
        ttlMs: Long = maxEpochTtlMs,
        /**
         * C-4: Explicit authorization for overlapping epochs. If false (default),
         * minting refuses when a live epoch already governs the same exact predicate.
         * Two live epochs over the same scope would provide additive budgets (2N),
         * a composition channel. Set true only when the overlap is explicitly
         * authorized as additive by the sovereign issuance path. Note: this flag
         * alone does not authorize issuance; the SovereignIssuanceAuthority is
         * still required. A holder cannot set allowOverlap=true to bypass the
         * sovereign boundary.
         */
        allowOverlap: Boolean = false,
    ): EpochCapability {
        require(ttlMs in 1..maxEpochTtlMs) { "epoch TTL out of bounds" }
        // C-4: Sovereign issuance verification. The authority must be from this root,
        // must not already be consumed, and must specify exactly one of rotate/new-lineage.
        require(authority.rootId == rootId) {
            "C-4: issuance authority from different root (authority=${authority.rootId.value}, issuer=${rootId.value})"
        }
        val now = clockMs()
        val (epoch, lineageId) = synchronized(lock) {
            // Single-use: refuse if this permit was already consumed.
            if (authority.permitId in consumedPermitIds) {
                throw IllegalArgumentException(
                    "C-4: issuance authority already consumed (permit=${authority.permitId})"
                )
            }
            if (!authority.tryConsume()) {
                throw IllegalArgumentException(
                    "C-4: issuance authority already consumed (permit=${authority.permitId})"
                )
            }
            consumedPermitIds.add(authority.permitId)

            // Determine lineage: rotate uses existing, new-lineage establishes fresh.
            val lid: LineageId = if (authority.lineageId != null) {
                // ROTATE: lineage must already exist.
                require(authority.lineageId in usedLineageIds) {
                    "C-4: rotate to unknown lineage (${authority.lineageId.value}); " +
                    "establish it first via newLineageAuthority"
                }
                authority.lineageId
            } else {
                // NEW_LINEAGE: ID must be fresh (durable uniqueness).
                val newId = authority.newLineageId!!
                require(newId !in usedLineageIds) {
                    "C-4: lineage ID already exists (${newId.value}); " +
                    "reusing it would be a history-reset path"
                }
                usedLineageIds.add(newId)
                newId
            }

            if (!allowOverlap) {
                // C-4: prohibit concurrent epochs over the same exact scope.
                // A live epoch is unrevoked, unexpired, and its epoch number not revoked.
                for (rec in live.values) {
                    val existing = rec.capability as? EpochCapability ?: continue
                    if (rec.revoked) continue
                    if (existing.epoch in revokedEpochs) continue
                    if (now < existing.notBeforeMs || now >= existing.notAfterMs) continue
                    if (existing.predicate == predicate) {
                        throw IllegalArgumentException(
                            "C-4: overlapping live epoch for same predicate " +
                            "(existing=${existing.id}, epoch=${existing.epoch}). " +
                            "Set allowOverlap=true only with explicit sovereign authorization."
                        )
                    }
                }
            }
            val e = nextEpoch++
            Pair(e, lid)
        }
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
            rootId = rootId,
            lineageId = lineageId,
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
        val rec = live[cap.id] ?: return CheckResult.Refused("capability not minted by this issuer")
        if (rec.revoked) return CheckResult.Refused("capability revoked")
        if (rec.consumed) return CheckResult.Refused("capability already consumed")
        if (rec.capability != cap) return CheckResult.Refused("capability fields differ from issuance record")
        if (t_c < cap.notBeforeMs) return CheckResult.Refused("capability not yet valid")
        if (t_c > cap.notAfterMs) return CheckResult.Refused("capability expired") // brutal: no grace, even 1ms
        if (cap.mutationHash != sha256Hex(rendering)) return CheckResult.Refused("mutation hash mismatch")
        for ((dep, v) in cap.stateDeps) {
            if (securityState.versionOf(dep) != v)
                return CheckResult.Refused("state dep '$dep' changed: capability void")
        }
        rec.consumed = true
        return CheckResult.Ok
    }

    /**
     * Look up a live EpochCapability by ID for the C-1 commit path.
     * Returns null if unknown, revoked, or not an epoch capability.
     * The caller (commit path) derives the ID from the authenticated
     * MutationCapability binding — never from caller-supplied evidence.
     */
    fun liveEpoch(epochId: String): EpochCapability? = synchronized(lock) {
        val rec = live[epochId] ?: return null
        if (rec.revoked) return null
        val cap = rec.capability as? EpochCapability ?: return null
        if (cap.epoch in revokedEpochs) return null
        val now = clockMs()
        if (now < cap.notBeforeMs || now >= cap.notAfterMs) return null
        cap
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
                ?: return CheckResult.Refused("attenuated parent unknown to this issuer")
            if (parentRec.revoked) return CheckResult.Refused("attenuated parent revoked")
            val p = parentRec.capability as? EpochCapability
                ?: return CheckResult.Refused("attenuated parent is not an epoch capability")
            if (p.epoch in revokedEpochs) return CheckResult.Refused("attenuated parent epoch revoked")
            if (!p.predicate.isNarrowerOrEqual(cap.predicate))
                return CheckResult.Refused("attenuated predicate wider than parent")
            if (cap.notAfterMs > p.notAfterMs)
                return CheckResult.Refused("attenuated lifetime exceeds parent")
            if (!boundNarrowerOrEqual(cap.maxMutations, p.maxMutations))
                return CheckResult.Refused("attenuated mutation bound exceeds parent")
            if (!boundNarrowerOrEqual(cap.maxBytes, p.maxBytes))
                return CheckResult.Refused("attenuated byte bound exceeds parent")
            if (cap.stateDeps != p.stateDeps)
                return CheckResult.Refused("attenuated state deps differ from parent")
            rec = LiveRecord(cap)
            live[cap.id] = rec
        }
        rec = rec ?: return CheckResult.Refused("capability not minted by this issuer")
        if (rec.revoked) return CheckResult.Refused("capability revoked")
        val epochOf: Long = (rec.capability as? EpochCapability)?.epoch
            ?: return CheckResult.Refused("not an epoch capability")
        if (epochOf in revokedEpochs) return CheckResult.Refused("epoch revoked")
        // Attenuated child already registered: the parent's epoch must
        // still be live (revocation propagates down).
        if (cap.parentId != null && parentRec == null) {
            parentRec = live[cap.parentId] ?: return CheckResult.Refused("attenuated parent unknown")
            if (parentRec.revoked) return CheckResult.Refused("attenuated parent revoked")
            val p = parentRec.capability as? EpochCapability
            if (p == null || p.epoch in revokedEpochs) return CheckResult.Refused("attenuated parent epoch revoked")
        }
        if (rec.capability != cap) return CheckResult.Refused("capability fields differ from issuance record")
        if (t_c < cap.notBeforeMs) return CheckResult.Refused("capability not yet valid")
        if (t_c > cap.notAfterMs) return CheckResult.Refused("capability expired")
        if (!cap.predicate.allows(effect.target, effect.type))
            return CheckResult.Refused("effect outside admitted predicate")
        for ((dep, v) in cap.stateDeps) {
            if (securityState.versionOf(dep) != v)
                return CheckResult.Refused("state dep '$dep' changed: capability void")
        }
        // H1: bound-check and consumption-record are one atomic step.
        // Overcounting (conservative) is preferred to undercounting:
        // uncertainty may cost availability, never authority.
        val newMutations = rec.usageMutations + 1
        val newBytes = rec.usageBytes + effect.bytes
        if (cap.maxMutations != null && newMutations > cap.maxMutations)
            return CheckResult.Refused("epoch mutation bound exceeded")
        if (cap.maxBytes != null && newBytes > cap.maxBytes)
            return CheckResult.Refused("epoch byte bound exceeded")
        // Attenuated child: the parent's envelope must also hold.
        if (parentRec != null) {
            val p = parentRec.capability as EpochCapability
            val pNewMutations = parentRec.usageMutations + 1
            val pNewBytes = parentRec.usageBytes + effect.bytes
            if (p.maxMutations != null && pNewMutations > p.maxMutations)
                return CheckResult.Refused("parent epoch mutation bound exceeded")
            if (p.maxBytes != null && pNewBytes > p.maxBytes)
                return CheckResult.Refused("parent epoch byte bound exceeded")
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
