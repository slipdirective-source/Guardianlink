package guardianlink.sidecar

import guardianlink.gates.Action
import guardianlink.gates.GateContext
import guardianlink.gates.ReferenceTransition
import guardianlink.gates.Revocation
import guardianlink.gates.SubstrateState
import guardianlink.gates.Verifiers
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Assent message format, shared byte-for-byte with the Python and
 * TypeScript clients. Changing this string breaks every client signer —
 * it is part of the wire protocol, not an implementation detail.
 */
const val ASSENT_HMAC_DOMAIN = "v1|assent"
const val CALLER_HMAC_DOMAIN = "v1|caller"

fun assentMessage(actionId: String, assentedAtMs: Long, rendering: String): ByteArray =
    "$ASSENT_HMAC_DOMAIN|$actionId|$assentedAtMs|$rendering".toByteArray(Charsets.UTF_8)

fun callerMessage(keyMessage: ByteArray): ByteArray =
    CALLER_HMAC_DOMAIN.toByteArray(Charsets.UTF_8) + "|".toByteArray(Charsets.UTF_8) + keyMessage

fun hmacSha256Hex(secret: ByteArray, message: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    return mac.doFinal(message).toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        ByteArray(length / 2) { i ->
            val hi = Character.digit(this[2 * i], 16)
            val lo = Character.digit(this[2 * i + 1], 16)
            if (hi == -1 || lo == -1) return null
            ((hi shl 4) + lo).toByte()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Deployment-controlled governance hooks. The engine's policy ports are
 * contracts the deployment fills in; the sidecar ships safe defaults and
 * documents them as defaults, not as policy.
 */
data class SidecarPolicy(
    /** Gate 2, phi_perm. Default: no boundary policy (permissive). */
    val boundaryPermitted: (Action, SubstrateState) -> Boolean = { _, _ -> true },
    /** Gate 6, psi_rules. Default: no governance rules (permissive). */
    val policyRules: (Action, SubstrateState) -> Boolean = { _, _ -> true },
    /** Gate 6, psi_align. Default: zero divergence. */
    val governanceDivergence: (Action) -> Double = { 0.0 },
    /** Gate 5, theta_loop. Default: loop assumed consistent. */
    val metaLoopConsistent: (GateContext) -> Boolean = { true },
    /**
     * Deployment's revocation feed. Default: NO feed wired (null) —
     * deliberately distinguishable from a wired feed that returns an
     * empty list. With no feed wired, [SidecarVerifiers.revocationsFor]
     * fails closed (throws → the engine halts at Gate 8) unless the
     * sidecar runs in demo mode; "no feed" must never be silently
     * indistinguishable from "feed checked, nothing revoked".
     */
    val revocationFeed: ((String) -> List<Revocation>)? = null,
    /** Validity of a feed revocation. Default: fail-closed (none valid). */
    val verifyRevocation: (Revocation) -> Boolean = { false },
)

/**
 * The sidecar's verifier set.
 *
 * REAL (sidecar-owned trust):
 * - verifyAssent enforces the (rendering, assentedAtMs, actionId) binding
 *   contract with HMAC-SHA256. When a separate [assentSecret] is
 *   configured it — and only it — verifies assent; the caller key
 *   ([hmacSecret]) can then no longer produce "human" assent, which is
 *   the separation the product is built on. Without [assentSecret] the
 *   sidecar falls back to the shared key (Main.kt prints a startup
 *   warning in that posture; existing deployments behave as before).
 * - verifySignature authenticates the caller with the shared key.
 *
 * FAIL-CLOSED BY DEFAULT (the demo posture is opt-in via [demoMode]):
 * - verifyZk: there is no ZK system at this layer to check against, so
 *   outside demo mode it returns false and Gate 3 halts. Returning true
 *   unconditionally (the old behavior) made the README's fail-closed
 *   claim false over REST. Demo mode restores the pass, explicitly.
 * - revocationsFor: with no [SidecarPolicy.revocationFeed] wired, this
 *   throws outside demo mode — the engine treats a throwing feed as
 *   fail-closed and halts at Gate 8. "No feed configured" must never
 *   read as "nothing revoked". Demo mode returns the empty list.
 *
 * DEPLOYMENT-OWNED (explicit, not stubbed silently):
 * - boundaryPermitted / policyRules / metaLoopConsistent /
 *   governanceDivergence default to permissive values and are meant to
 *   be supplied via [SidecarPolicy]. Deployments requiring real
 *   zero-knowledge context audit must use the Kotlin engine API with
 *   real verifiers behind these ports.
 */
class SidecarVerifiers(
    private val hmacSecret: ByteArray,
    private val policy: SidecarPolicy = SidecarPolicy(),
    private val assentSecret: ByteArray? = null,
    private val demoMode: Boolean = false,
) : Verifiers {

    /** The key assent is verified against: the separate secret when set. */
    private val assentKey: ByteArray get() = assentSecret ?: hmacSecret

    override fun verifyAssent(
        rendering: String,
        assentedAtMs: Long,
        actionId: String,
        signature: ByteArray,
    ): Boolean {
        val expected = hmacSha256Hex(assentKey, assentMessage(actionId, assentedAtMs, rendering))
        val presented = signature.toHex()
        // Constant-time compare; also rejects malformed (non-hex) input
        // by length mismatch.
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    override fun verifySignature(message: ByteArray, signature: ByteArray): Boolean {
        val expected = hmacSha256Hex(hmacSecret, callerMessage(message))
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toHex().toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * No ZK system exists at this layer. Fail-closed by default: Gate 3
     * halts. Demo mode ([demoMode]) is the explicit opt-in to the old
     * pass-through posture.
     */
    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray): Boolean = demoMode

    override fun revocationsFor(actionId: String): List<Revocation> {
        policy.revocationFeed?.let { return it(actionId) }
        if (demoMode) return emptyList()
        // Fail-closed: an unwired feed is a deployment error, not a
        // finding of "nothing revoked". The engine catches this throw
        // and halts at Gate 8 (theta_norev: revocation feed threw).
        throw IllegalStateException(
            "no revocation feed configured: wire SidecarPolicy.revocationFeed, " +
                "or run in demo mode (GUARDIANLINK_DEMO=1) to accept the empty-feed posture explicitly"
        )
    }

    override fun verifyRevocation(revocation: Revocation): Boolean =
        policy.verifyRevocation(revocation)

    override fun boundaryPermitted(action: Action, substrate: SubstrateState): Boolean =
        policy.boundaryPermitted(action, substrate)

    override fun policyRules(action: Action, substrate: SubstrateState): Boolean =
        policy.policyRules(action, substrate)

    override fun governanceDivergence(action: Action): Double =
        policy.governanceDivergence(action)

    override fun metaLoopConsistent(context: GateContext): Boolean =
        policy.metaLoopConsistent(context)

    override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState =
        ReferenceTransition.apply(substrate, action)
}
