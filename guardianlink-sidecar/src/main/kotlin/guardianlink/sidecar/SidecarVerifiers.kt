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
    /** Deployment's revocation feed. Default: no feed wired. */
    val revocationsFor: (String) -> List<Revocation> = { emptyList() },
    /** Validity of a feed revocation. Default: fail-closed (none valid). */
    val verifyRevocation: (Revocation) -> Boolean = { false },
)

/**
 * The sidecar's verifier set.
 *
 * REAL (sidecar-owned trust):
 * - verifyAssent enforces the (rendering, assentedAtMs, actionId) binding
 *   contract with HMAC-SHA256 under the server-side secret. Backdating,
 *   refreshing, and replay across actionIds all fail the MAC.
 * - verifySignature authenticates the caller the same way (pre-shared key).
 *
 * DEPLOYMENT-OWNED (explicit, not stubbed silently):
 * - verifyZk is NOT enforced by the sidecar: there is no ZK system here
 *   to check against. Deployments requiring zero-knowledge context audit
 *   must use the Kotlin engine API with real verifiers.
 * - boundaryPermitted / policyRules / metaLoopConsistent /
 *   governanceDivergence / revocationsFor default to permissive/empty and
 *   are meant to be supplied via [SidecarPolicy]. Shipping them permissive
 *   is the documented demo posture (same as Main.kt's demoVerifiers), not
 *   a claim of enforcement.
 */
class SidecarVerifiers(
    private val hmacSecret: ByteArray,
    private val policy: SidecarPolicy = SidecarPolicy(),
) : Verifiers {

    override fun verifyAssent(
        rendering: String,
        assentedAtMs: Long,
        actionId: String,
        signature: ByteArray,
    ): Boolean {
        val expected = hmacSha256Hex(hmacSecret, assentMessage(actionId, assentedAtMs, rendering))
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

    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray): Boolean = true

    override fun revocationsFor(actionId: String): List<Revocation> =
        policy.revocationsFor(actionId)

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
