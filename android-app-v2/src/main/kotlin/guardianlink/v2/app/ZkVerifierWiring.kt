package guardianlink.v2.app

import guardianlink.v2.core.zk.ZkReason
import guardianlink.v2.core.zk.ZkVerifierBridge

/**
 * The ProductionVerifiers `zkVerifier` port, wired for real
 * (ZK-GATE3-DESIGN §7.2): a total wrapper over the bridge verifier.
 * Every exception, FFI failure, or native absence returns false —
 * the engine then halts at Gate 3, fail-closed, as it intends
 * (§8 case 15).
 *
 * The verifier enforces the §4.2.3 freshness window on the timestamp
 * INSIDE publicInputs against the engine's own tau clock (supplied
 * here as [nowMs] — the ZK module never reads a clock itself). The
 * cross-field coherence between the block and the bundle (actionId /
 * renderingHash / issuedAtMs) is NOT this port's job — the verifyZk
 * signature never sees those fields; the Gate 5 port checks them
 * (§4.3, EnginePorts).
 */
class ZkVerifierWiring(
    private val bridge: ZkVerifierBridge,
    private val nowMs: () -> Long,
) {
    /** The last reason code the bridge returned (diagnostics; the surface names the failure class from it). */
    @Volatile
    var lastReasonCode: Int = ZkReason.INTERNAL
        private set

    fun verify(proof: ByteArray, publicInputs: ByteArray): Boolean {
        val code = try {
            bridge.verify(proof, publicInputs, nowMs())
        } catch (t: Throwable) {
            ZkReason.INTERNAL
        }
        lastReasonCode = code
        return code == ZkReason.OK
    }

    /** A ProductionVerifiers-shaped lambda over this wiring. */
    fun asLambda(): (ByteArray, ByteArray) -> Boolean = { proof, inputs -> verify(proof, inputs) }
}
