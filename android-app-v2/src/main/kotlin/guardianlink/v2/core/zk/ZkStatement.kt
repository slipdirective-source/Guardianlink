package guardianlink.v2.core.zk

import guardianlink.v2.core.ActionProposal
import guardianlink.v2.core.Rail
import kotlin.math.roundToLong

/**
 * Gate 3 statement assembly (ZK-GATE3-DESIGN §2.3, §5.1).
 *
 * A governed parameter carries a descriptor, fixed when its rail is
 * committed: (name, scale, bits, domain offset). Quantization maps
 * the core's Double parameters onto the integers the range proof
 * ranges over:
 *
 *   quantized(v) = round((v − offset) × scale)   must satisfy 0 ≤ q < 2^bits
 *
 * v1 defaults: scale 1000 (milli-unit precision), 32 bits, offset 0 —
 * amounts to 0.001 precision up to ~4.29M units. A ceiling that does
 * not quantize into the domain is a CONFIGURATION ERROR at rail
 * commit (§8 case 13), never a surprise at Gate 3.
 */
data class ZkParamDescriptor(
    val name: String,
    val scale: Int = DEFAULT_SCALE,
    val bits: Int = DEFAULT_BITS,
    val offset: Long = DEFAULT_OFFSET,
) {
    companion object {
        const val DEFAULT_SCALE = 1000
        const val DEFAULT_BITS = 32
        const val DEFAULT_OFFSET = 0L
    }
}

/**
 * One parameter's rail-commit ZK record (§2.2, §5): the descriptor,
 * the quantized ceiling, the Pedersen commitment C_i (the envelope's
 * public face), and the blinding t_i.
 *
 * The blinding is a WITNESS: it lives only in the device-local sealed
 * state record, is never written to the ledger or logs, and must
 * never be synced in cleartext (§5's named interim leak channel is
 * about cleartext ceilings in OTHER records — this record is the
 * sealed one). Byte arrays are defensively copied at construction.
 */
class RailZkParam(
    val descriptor: ZkParamDescriptor,
    val ceilingQ: Long,
    envelopeCommitment: ByteArray,
    envelopeBlinding: ByteArray,
) {
    val envelopeCommitment: ByteArray = envelopeCommitment.copyOf()
    val envelopeBlinding: ByteArray = envelopeBlinding.copyOf()
}

/** The ZK record of one committed rail: one [RailZkParam] per governed parameter, in descriptor order. */
class RailZkRecord(
    val railId: String,
    val params: List<RailZkParam>,
) {
    fun param(name: String): RailZkParam? = params.firstOrNull { it.descriptor.name == name }
}

/**
 * A fully assembled Gate 3 statement for one ceremony: descriptors,
 * quantized values and ceilings, and the rail's commitments and
 * blindings, all in descriptor order. Held in memory only between
 * begin() and submit() (§5) — witnesses are never persisted.
 */
class ZkProvePlan(
    val railId: String,
    val descriptors: List<ZkParamDescriptor>,
    val valuesQ: LongArray,
    val ceilingsQ: LongArray,
    val envelopeCommitments: List<ByteArray>,
    val envelopeBlindings: List<ByteArray>,
)

sealed interface ZkPlanResult {
    /** A statement is assembled and provable (possibly the empty statement — see [ZkStatementAssembly]). */
    data class Plan(val plan: ZkProvePlan?) : ZkPlanResult

    /** The action is not Gate-3-provable; [reason] names exactly why. The ceremony refuses at begin(). */
    data class Refused(val reason: String) : ZkPlanResult
}

/** Quantization (§2.3). Pure; returns null for anything outside the domain. */
object ZkQuantization {
    fun quantize(value: Double, descriptor: ZkParamDescriptor): Long? {
        if (!value.isFinite()) return null
        val scaled = (value - descriptor.offset.toDouble()) * descriptor.scale.toDouble()
        if (!scaled.isFinite() || scaled < 0.0) return null
        val bound = Math.pow(2.0, descriptor.bits.toDouble())
        if (scaled >= bound) return null
        val q = scaled.roundToLong()
        if (q < 0 || q.toDouble() >= bound) return null
        return q
    }
}

/**
 * Assemble the Gate 3 statement for a proposal against its covering
 * rail (design §5.1). The rules, all fail-closed:
 *
 *  - Governed parameters are the proposal's NUMERIC parameters that
 *    fall under the covering rail's envelope. The statement exists
 *    only relative to a committed rail envelope (§2.2).
 *  - Covering rail with a ZK record: every numeric parameter of the
 *    proposal must be governed by the record (an ungoverned numeric
 *    parameter is the §8 case-14 smuggle — refused), and every
 *    governed parameter must carry a value in the proposal (a value
 *    is never invented).
 *  - Covering rail WITHOUT a ZK record (committed before the ZK
 *    stack, or committed while the bridge was unavailable): refused —
 *    the rail has no envelope commitments, so nothing is provable
 *    against it.
 *  - No covering rail (fresh-assent path): numeric parameters have no
 *    committed envelope to prove against — refused. A proposal with
 *    NO numeric parameters at all yields the EMPTY statement
 *    (plan = null): the §2.2 claim quantifies over governed numeric
 *    parameters and is vacuously true for it; the ceremony carries
 *    the canonical vacuity marker instead of a range proof (see
 *    [PublicInputsCodec.VACUITY_PROOF]).
 */
object ZkStatementAssembly {

    fun planFor(rail: Rail?, proposal: ActionProposal): ZkPlanResult {
        val numericParams = proposal.parameters.filterValues { it is Number }
        if (rail == null) {
            if (numericParams.isNotEmpty()) {
                return ZkPlanResult.Refused(
                    "proposal ${proposal.id} carries numeric parameters ${numericParams.keys.sorted()} but no committed rail " +
                        "envelope covers it; Gate 3 v1 proves rail-envelope compliance only, and there is no envelope to prove against.",
                )
            }
            return ZkPlanResult.Plan(null)
        }
        val record = rail.zk
            ?: return ZkPlanResult.Refused(
                "rail ${rail.id} has no envelope commitments (it was committed without the ZK bridge); " +
                    "the action is not Gate-3-provable against it. Re-commit the rail with the ZK stack available.",
            )
        // Every numeric parameter must be governed (§8 case 14).
        for (name in numericParams.keys) {
            if (record.param(name) == null) {
                return ZkPlanResult.Refused(
                    "proposal ${proposal.id} carries numeric parameter \"$name\" which the covering rail ${rail.id} " +
                        "does not govern; an ungoverned parameter in a governed action refuses the ZK path (design section 2.3).",
                )
            }
        }
        val descriptors = mutableListOf<ZkParamDescriptor>()
        val valuesQ = mutableListOf<Long>()
        val ceilingsQ = mutableListOf<Long>()
        val commitments = mutableListOf<ByteArray>()
        val blindings = mutableListOf<ByteArray>()
        for (param in record.params) {
            val raw = numericParams[param.descriptor.name]
                ?: return ZkPlanResult.Refused(
                    "governed parameter \"${param.descriptor.name}\" of rail ${rail.id} has no value in proposal " +
                        "${proposal.id}; the ceremony does not invent values.",
                )
            val q = ZkQuantization.quantize((raw as Number).toDouble(), param.descriptor)
                ?: return ZkPlanResult.Refused(
                    "value ${raw} for governed parameter \"${param.descriptor.name}\" does not quantize into " +
                        "[0, 2^${param.descriptor.bits}) at scale ${param.descriptor.scale}; outside the governed domain (design section 2.3).",
                )
            descriptors.add(param.descriptor)
            valuesQ.add(q)
            ceilingsQ.add(param.ceilingQ)
            commitments.add(param.envelopeCommitment)
            blindings.add(param.envelopeBlinding)
        }
        if (descriptors.isEmpty()) return ZkPlanResult.Plan(null)
        return ZkPlanResult.Plan(
            ZkProvePlan(
                railId = rail.id,
                descriptors = descriptors,
                valuesQ = valuesQ.toLongArray(),
                ceilingsQ = ceilingsQ.toLongArray(),
                envelopeCommitments = commitments,
                envelopeBlindings = blindings,
            ),
        )
    }
}
