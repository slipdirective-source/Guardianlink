package guardianlink.adapters

import guardianlink.crypto.AssentCodec
import guardianlink.crypto.Ed25519
import guardianlink.gates.Action
import guardianlink.gates.GateContext
import guardianlink.gates.ReferenceTransition
import guardianlink.gates.Revocation
import guardianlink.gates.SubstrateState
import guardianlink.gates.Verifiers
import java.security.PublicKey

/**
 * Production [Verifiers]: real Ed25519 cryptography on the signature
 * ports, a deployment-owned [RevocationFeed], and fail-closed defaults
 * everywhere else.
 *
 * Wiring map:
 * - Gate 1 `verifySignature` — Ed25519 under the operator identity key.
 * - Gate 3 `verifyZk` — injected; DEFAULT IS DENY. There is no bundled
 *   ZK prover/verifier: until the deployment supplies a real one, Gate 3
 *   halts every action. That is the honest production posture, not a
 *   limitation to route around — a permissive default here would be
 *   RailAuthorizer.PERMISSIVE-class error.
 * - Gate 7 `verifyAssent` — Ed25519 over the canonical
 *   (rendering, assentedAtMs, actionId) triple via [AssentCodec]. A
 *   signature over the rendering alone does NOT verify: the binding
 *   contract is enforced by the codec, not by operator discipline.
 * - Gate 8 `revocationsFor` — delegated to the deployment's
 *   [RevocationFeed]; a throwing feed fails the engine closed.
 * - Gate 8 `verifyRevocation` — Ed25519 over the canonical
 *   (actionId, revokedAtMs) claim under the revocation authority key
 *   (which SHOULD differ from the operator key: the hand that assents
 *   must not be the hand that revokes).
 * - Gates 2/6/5 policy ports — injected; DEFAULT IS DENY (false / +Inf /
 *   false). Policy is deployment governance, not engine logic; the
 *   engine must never invent a permissive default for it.
 * - Gate 8 `applyAction` — the canonical reference transition by
 *   default; deployments with a real substrate override it. The engine
 *   never mutates inside `evaluate()` regardless: this computes the
 *   *prepare* phase only.
 */
class ProductionVerifiers(
    private val operatorKey: PublicKey,
    private val revocationAuthorityKey: PublicKey,
    private val revocationFeed: RevocationFeed,
    private val zkVerifier: (proof: ByteArray, publicInputs: ByteArray) -> Boolean = { _, _ -> false },
    private val boundaryCheck: (action: Action, substrate: SubstrateState) -> Boolean = { _, _ -> false },
    private val policyCheck: (action: Action, substrate: SubstrateState) -> Boolean = { _, _ -> false },
    private val divergenceFn: (action: Action) -> Double = { Double.POSITIVE_INFINITY },
    private val loopCheck: (context: GateContext) -> Boolean = { false },
    private val transitionFn: (substrate: SubstrateState, action: Action) -> SubstrateState = ReferenceTransition::apply,
) : Verifiers {

    override fun verifySignature(message: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(operatorKey, message, signature)

    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray): Boolean =
        try {
            zkVerifier(proof, publicInputs)
        } catch (_: Exception) {
            false
        }

    override fun verifyAssent(
        rendering: String,
        assentedAtMs: Long,
        actionId: String,
        signature: ByteArray,
    ): Boolean = AssentCodec.verify(operatorKey, rendering, assentedAtMs, actionId, signature)

    override fun revocationsFor(actionId: String): List<Revocation> =
        revocationFeed.revocationsFor(actionId)

    override fun verifyRevocation(revocation: Revocation): Boolean =
        Ed25519.verify(
            revocationAuthorityKey,
            AssentCodec.encodeRevocation(revocation.actionId, revocation.revokedAtMs),
            revocation.signature,
        )

    override fun boundaryPermitted(action: Action, substrate: SubstrateState): Boolean =
        try {
            boundaryCheck(action, substrate)
        } catch (_: Exception) {
            false
        }

    override fun policyRules(action: Action, substrate: SubstrateState): Boolean =
        try {
            policyCheck(action, substrate)
        } catch (_: Exception) {
            false
        }

    override fun governanceDivergence(action: Action): Double =
        try {
            divergenceFn(action).takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
        } catch (_: Exception) {
            Double.POSITIVE_INFINITY
        }

    override fun metaLoopConsistent(context: GateContext): Boolean =
        try {
            loopCheck(context)
        } catch (_: Exception) {
            false
        }

    override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState =
        transitionFn(substrate, action)
}
