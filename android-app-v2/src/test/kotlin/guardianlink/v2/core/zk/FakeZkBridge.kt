package guardianlink.v2.core.zk

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * TEST-ONLY fake of the Gate 3 bridge seam (ZK-GATE3-DESIGN §7.1:
 * "native impl + test fake"). It is NOT cryptography: commitments
 * and proofs are SHA-256 MACs over the statement, so JVM tests can
 * walk the ceremony, the codec, and the Gate 5 coherence port
 * through the exact production shapes.
 *
 * What the fake deliberately mirrors from the real verifier: the
 * GLZK1 codec, the §4.2.3 freshness window on the inner timestamp,
 * and the vacuity-marker rule for the empty statement. What it
 * deliberately does NOT mirror: envelope soundness — the fake proves
 * any in-domain statement, because proving that an out-of-envelope
 * witness CANNOT verify is the Rust crate's job, and its attack
 * corpus (zk-bridge/tests/gate3_proofs.rs) carries exactly that.
 * Nothing in production code references this class.
 */
object FakeZkBridge : ZkProverBridge, ZkVerifierBridge {

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun be64(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
        return out.toByteArray()
    }

    override fun isAvailable(): Boolean = true

    override fun commitCeiling(ceilingQ: Long): ZkCommitResult {
        val blinding = ByteArray(32) { 0x42 }
        val commitment = sha256("FAKE-COMMIT".toByteArray(Charsets.UTF_8) + be64(ceilingQ) + blinding)
        return ZkCommitResult(commitment, blinding)
    }

    override fun prove(request: ZkProveRequest): ZkProofBundle {
        val plan = request.plan
        val params = plan.descriptors.mapIndexed { i, d ->
            val v = sha256(
                "FAKE-V".toByteArray(Charsets.UTF_8) + request.actionId.toByteArray(Charsets.UTF_8) +
                    d.name.toByteArray(Charsets.UTF_8) + be64(plan.valuesQ[i]),
            )
            PublicParam(d, v, plan.envelopeCommitments[i])
        }
        val block = PublicInputsCodec.encode(request.actionId, request.renderingHash, request.issuedAtMs, params)
        val proof = sha256("FAKE-PROOF".toByteArray(Charsets.UTF_8) + block)
        return ZkProofBundle(proof, block)
    }

    override fun verify(proof: ByteArray, publicInputs: ByteArray, nowMs: Long): Int {
        val parsed = try {
            PublicInputsCodec.decode(publicInputs)
        } catch (e: Exception) {
            return ZkReason.PARSE
        }
        if (parsed.issuedAtMs > nowMs) return ZkReason.FRESHNESS
        if (nowMs - parsed.issuedAtMs > 60_000L) return ZkReason.FRESHNESS
        if (parsed.params.isEmpty()) {
            return if (proof.contentEquals(PublicInputsCodec.VACUITY_PROOF)) ZkReason.OK else ZkReason.PROOF_INVALID
        }
        val expected = sha256("FAKE-PROOF".toByteArray(Charsets.UTF_8) + publicInputs)
        return if (proof.contentEquals(expected)) ZkReason.OK else ZkReason.PROOF_INVALID
    }
}
