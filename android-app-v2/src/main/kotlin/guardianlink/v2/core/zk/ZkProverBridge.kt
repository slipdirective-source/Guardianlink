package guardianlink.v2.core.zk

/**
 * The FFI seam (ZK-GATE3-DESIGN §7.1): the interface the core proves
 * and verifies through. The native implementation lives in the app
 * layer (`NativeZkBridge`, over the uniFFI-generated bindings to the
 * Rust `glzk` crate — the JNI/uniFFI surface is Android-coupled);
 * tests use a fake. All curve arithmetic exists in exactly one place:
 * the Rust crate. This interface moves bytes and quantized integers
 * only — Kotlin never does curve math.
 */
interface ZkProverBridge {
    /** True when the native proof system is actually reachable. Checked before any proving is promised. */
    fun isAvailable(): Boolean

    /**
     * Rail-commit (§5): commit to a quantized ceiling. The blinding is
     * generated inside the Rust crate from the OS RNG and returned so
     * the caller can store it in the rail's private sealed record.
     */
    fun commitCeiling(ceilingQ: Long): ZkCommitResult

    /**
     * Prove envelope compliance for the assembled plan (§2.2). The
     * returned publicInputs are the canonical GLZK1 block the proof is
     * bound to; the ceremony cross-checks them through
     * [PublicInputsCodec] before submitting anything.
     */
    fun prove(request: ZkProveRequest): ZkProofBundle
}

/** The verifier half of the seam (the ProductionVerifiers port consumes this via the app wiring). */
interface ZkVerifierBridge {
    /**
     * Verify a proof against its publicInputs at tau time [nowMs].
     * Returns a [ZkReason] code. Total by contract: implementations
     * return INTERNAL on any internal failure rather than throwing.
     */
    fun verify(proof: ByteArray, publicInputs: ByteArray, nowMs: Long): Int
}

/** Rail-commit result: commitment (public face) + blinding (private record only). */
class ZkCommitResult(commitment: ByteArray, blinding: ByteArray) {
    val commitment: ByteArray = commitment.copyOf()
    val blinding: ByteArray = blinding.copyOf()
}

/** A prover request: the assembled statement plus its binding material (§4.1, §5.3). */
class ZkProveRequest(
    val actionId: String,
    val renderingHash: ByteArray,
    val issuedAtMs: Long,
    val plan: ZkProvePlan,
)

/** A completed proof: Bulletproofs bytes + the canonical publicInputs they are bound to. */
class ZkProofBundle(proof: ByteArray, publicInputs: ByteArray) {
    val proof: ByteArray = proof.copyOf()
    val publicInputs: ByteArray = publicInputs.copyOf()
}

/** Named bridge failure: unavailable native library, or a prover refusal carried across the seam. */
class ZkBridgeException(message: String) : Exception(message)

/**
 * The absent bridge: no native proof system is reachable from this
 * process. Proving and committing throw [ZkBridgeException] with the
 * named reason; verification returns [ZkReason.INTERNAL] (the wiring
 * maps it to false — fail-closed, §8 case 15). This is the default
 * for a core constructed without a bridge, and it is what a device
 * without the .so experiences: Gate 3 halts, named, never faked.
 */
object UnavailableZkBridge : ZkProverBridge, ZkVerifierBridge {
    const val REASON = "ZK prover bridge unavailable: the native Gate 3 proof library (libglzk) is not loaded in this process"

    override fun isAvailable(): Boolean = false

    override fun commitCeiling(ceilingQ: Long): ZkCommitResult = throw ZkBridgeException(REASON)

    override fun prove(request: ZkProveRequest): ZkProofBundle = throw ZkBridgeException(REASON)

    override fun verify(proof: ByteArray, publicInputs: ByteArray, nowMs: Long): Int = ZkReason.INTERNAL
}
