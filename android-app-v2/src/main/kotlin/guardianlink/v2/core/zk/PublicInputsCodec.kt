package guardianlink.v2.core.zk

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Named decode failure for a GLZK1 block. Any malformed input is this — never an unnamed surprise. */
class PublicInputsException(message: String) : Exception(message)

/** One parameter row of a GLZK1 block: descriptor + value commitment V_i + envelope commitment C_i. */
class PublicParam(
    val descriptor: ZkParamDescriptor,
    valueCommitment: ByteArray,
    envelopeCommitment: ByteArray,
) {
    val valueCommitment: ByteArray = valueCommitment.copyOf()
    val envelopeCommitment: ByteArray = envelopeCommitment.copyOf()
}

/** A decoded GLZK1 block (ZK-GATE3-DESIGN §4.1). */
class ParsedPublicInputs(
    val actionId: String,
    renderingHash: ByteArray,
    val issuedAtMs: Long,
    val params: List<PublicParam>,
) {
    val renderingHash: ByteArray = renderingHash.copyOf()
}

/**
 * The canonical public-inputs codec (§4.1). Pure Kotlin, JVM-tested.
 * The Rust crate implements the identical byte discipline; this codec
 * is what the app's Gate 5 port (§4.3) parses with, and what the
 * ceremony uses to build the vacuity block and to cross-check the
 * bridge's prover output before anything is submitted.
 *
 * Layout:
 * ```
 * "GLZK1"                       domain separator (not length-prefixed)
 * u32be len | actionId          UTF-8
 * u32be len | renderingHash     SHA-256(rendering as UTF-8)   [32 bytes]
 * i64be     issuedAtMs          tau-domain issuance
 * u16be     paramCount m
 * per param, in descriptor order:
 *   u32be len | name            UTF-8
 *   u32be     scale
 *   u8        bits
 *   i64be     domain offset
 *   32 bytes  V_i               value commitment
 *   32 bytes  C_i               envelope commitment
 * ```
 */
object PublicInputsCodec {

    const val DOMAIN_SEPARATOR = "GLZK1"

    /**
     * The canonical proof bytes for the EMPTY statement (an action
     * with no governed numeric parameters): a domain-separated
     * vacuity marker, not a range proof. It proves nothing and claims
     * nothing — the §2.2 statement is vacuously true when there are
     * no governed parameters — and the verifier accepts it ONLY
     * against a paramCount-0 block whose binding and freshness check
     * out (the binding is enforced by Gate 5 coherence, §4.3).
     */
    val VACUITY_PROOF: ByteArray = "GLZK0".toByteArray(Charsets.US_ASCII)

    const val MAX_BLOCK_BYTES = 64 * 1024
    const val MAX_PARAMS = 64

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun encode(
        actionId: String,
        renderingHash: ByteArray,
        issuedAtMs: Long,
        params: List<PublicParam>,
    ): ByteArray {
        require(renderingHash.size == 32) { "rendering hash must be 32 bytes, got ${renderingHash.size}" }
        require(params.size <= MAX_PARAMS) { "param count ${params.size} exceeds cap $MAX_PARAMS" }
        val out = ByteArrayOutputStream()
        out.write(DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII))
        writeLenPrefixed(out, actionId.toByteArray(Charsets.UTF_8))
        writeLenPrefixed(out, renderingHash)
        writeI64(out, issuedAtMs)
        writeU16(out, params.size)
        for (p in params) {
            require(p.valueCommitment.size == 32 && p.envelopeCommitment.size == 32) {
                "commitments for param ${p.descriptor.name} must be 32 bytes each"
            }
            writeLenPrefixed(out, p.descriptor.name.toByteArray(Charsets.UTF_8))
            writeU32(out, p.descriptor.scale)
            out.write(p.descriptor.bits)
            writeI64(out, p.descriptor.offset)
            out.write(p.valueCommitment)
            out.write(p.envelopeCommitment)
        }
        return out.toByteArray()
    }

    /** Decode a GLZK1 block. Total: any malformation throws [PublicInputsException]. */
    fun decode(bytes: ByteArray): ParsedPublicInputs {
        if (bytes.size > MAX_BLOCK_BYTES) {
            throw PublicInputsException("publicInputs length ${bytes.size} exceeds cap $MAX_BLOCK_BYTES")
        }
        val r = Reader(bytes)
        val tag = r.take(5, "domain separator")
        if (!tag.contentEquals(DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII))) {
            throw PublicInputsException("wrong domain separator (expected GLZK1)")
        }
        val actionId = String(r.lenPrefixed("actionId"), Charsets.UTF_8)
        val renderingHash = r.lenPrefixed("renderingHash")
        if (renderingHash.size != 32) {
            throw PublicInputsException("renderingHash must be 32 bytes, got ${renderingHash.size}")
        }
        val issuedAtMs = r.i64("issuedAtMs")
        val m = r.u16("paramCount")
        if (m > MAX_PARAMS) throw PublicInputsException("param count $m exceeds cap $MAX_PARAMS")
        val params = mutableListOf<PublicParam>()
        for (i in 0 until m) {
            val name = String(r.lenPrefixed("param name"), Charsets.UTF_8)
            val scale = r.u32("scale")
            val bits = r.take(1, "bits")[0].toInt() and 0xFF
            if (bits != 8 && bits != 16 && bits != 32 && bits != 64) {
                throw PublicInputsException("param $name has invalid bit size $bits")
            }
            val offset = r.i64("offset")
            val v = r.take(32, "value commitment")
            val c = r.take(32, "envelope commitment")
            params.add(PublicParam(ZkParamDescriptor(name, scale, bits, offset), v, c))
        }
        if (r.remaining() != 0) {
            throw PublicInputsException("${r.remaining()} trailing bytes after the final parameter")
        }
        return ParsedPublicInputs(actionId, renderingHash, issuedAtMs, params)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        for (shift in 24 downTo 0 step 8) out.write((v ushr shift) and 0xFF)
    }

    private fun writeI64(out: ByteArrayOutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
    }

    private fun writeLenPrefixed(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeU32(out, bytes.size)
        out.write(bytes)
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0
        fun remaining(): Int = bytes.size - pos
        fun take(n: Int, what: String): ByteArray {
            if (remaining() < n) throw PublicInputsException("truncated at $what")
            val s = bytes.copyOfRange(pos, pos + n)
            pos += n
            return s
        }
        fun u16(what: String): Int {
            val b = take(2, what)
            return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        }
        fun u32(what: String): Int {
            val b = take(4, what)
            var v = 0
            for (x in b) v = (v shl 8) or (x.toInt() and 0xFF)
            return v
        }
        fun i64(what: String): Long {
            val b = take(8, what)
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }
        fun lenPrefixed(what: String): ByteArray {
            val len = u32(what)
            if (len < 0 || len > MAX_BLOCK_BYTES) throw PublicInputsException("$what length $len exceeds cap")
            return take(len, what)
        }
    }
}

/** Verifier reason codes (mirror of the Rust crate's `reason` module; FFI contract). */
object ZkReason {
    const val OK = 0
    const val PARSE = 1
    const val FRESHNESS = 2
    const val PROOF_INVALID = 3
    const val INTERNAL = 4

    /** The §5.4 user-facing failure class for a reason code. */
    fun className(code: Int): String = when (code) {
        OK -> "ok"
        PARSE -> "parse"
        FRESHNESS -> "freshness"
        PROOF_INVALID -> "proof-invalid"
        INTERNAL -> "bridge-internal"
        else -> "unknown"
    }
}
