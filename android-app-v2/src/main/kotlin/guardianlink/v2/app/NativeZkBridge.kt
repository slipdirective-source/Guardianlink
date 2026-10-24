package guardianlink.v2.app

import guardianlink.v2.core.zk.ZkBridgeException
import guardianlink.v2.core.zk.ZkCommitResult
import guardianlink.v2.core.zk.ZkProofBundle
import guardianlink.v2.core.zk.ZkProveRequest
import guardianlink.v2.core.zk.ZkProverBridge
import guardianlink.v2.core.zk.ZkReason
import guardianlink.v2.core.zk.ZkVerifierBridge

/**
 * The native Gate 3 bridge (ZK-GATE3-DESIGN §7.1): the core's
 * [ZkProverBridge]/[ZkVerifierBridge] seam implemented over the
 * uniFFI-generated bindings (`uniffi.glzk`, generated from the Rust
 * `glzk` crate — see zk-bridge/) to the dalek Bulletproofs proof
 * system in `libglzk.so`.
 *
 * This class is the Android-coupled layer: the generated bindings load
 * the native library through JNA on first use. EVERY entry point here
 * is total — a missing .so, a JNA linkage failure, or a Rust-side
 * error becomes [ZkBridgeException] (prover side) or
 * [ZkReason.INTERNAL] (verifier side), never an escaping throwable
 * (§8 case 15). Curve arithmetic exists only in the Rust crate; this
 * class moves bytes and quantized integers across the seam.
 */
object NativeZkBridge : ZkProverBridge, ZkVerifierBridge {

    @Volatile
    private var availability: Boolean? = null

    /**
     * Probe once: a 32-byte SHA-256 through the FFI. Any linkage
     * failure (UnsatisfiedLinkError / NoClassDefFoundError from the
     * generated binding's static init) is caught as Throwable and
     * memoized as unavailable.
     */
    override fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = try {
            uniffi.glzk.zkSha256(byteArrayOf(1, 2, 3)).size == 32
        } catch (t: Throwable) {
            false
        }
        availability = ok
        return ok
    }

    override fun commitCeiling(ceilingQ: Long): ZkCommitResult {
        if (ceilingQ < 0) throw ZkBridgeException("ceiling $ceilingQ is negative; quantized ceilings are unsigned")
        return try {
            val r = uniffi.glzk.zkCommitCeiling(ceilingQ.toULong())
            ZkCommitResult(r.commitment, r.blinding)
        } catch (e: uniffi.glzk.FfiZkException) {
            throw ZkBridgeException("ZK commit refused: ${e.message}")
        } catch (t: Throwable) {
            throw ZkBridgeException("ZK commit failed: native bridge error (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    override fun prove(request: ZkProveRequest): ZkProofBundle {
        val plan = request.plan
        return try {
            val bundle = uniffi.glzk.zkProveEnvelope(
                actionId = request.actionId,
                renderingHash = request.renderingHash,
                issuedAtMs = request.issuedAtMs,
                descriptors = plan.descriptors.map { d ->
                    uniffi.glzk.FfiParamDescriptor(
                        name = d.name,
                        scale = d.scale.toUInt(),
                        bits = d.bits.toUByte(),
                        offset = d.offset,
                    )
                },
                valuesQ = plan.valuesQ.map { it.toULong() },
                ceilingsQ = plan.ceilingsQ.map { it.toULong() },
                envelopeCommitments = plan.envelopeCommitments,
                envelopeBlindings = plan.envelopeBlindings,
            )
            ZkProofBundle(bundle.proof, bundle.publicInputs)
        } catch (e: uniffi.glzk.FfiZkException) {
            throw ZkBridgeException("ZK prover refused: ${e.message}")
        } catch (t: Throwable) {
            throw ZkBridgeException("ZK prover failed: native bridge error (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    override fun verify(proof: ByteArray, publicInputs: ByteArray, nowMs: Long): Int =
        try {
            uniffi.glzk.zkVerifyProof(proof, publicInputs, nowMs)
        } catch (t: Throwable) {
            ZkReason.INTERNAL
        }
}
