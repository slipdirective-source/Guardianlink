package guardianlink.v2.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Clock port for the v0.2 core (TS `Clock`). Tests inject a controllable one. */
fun interface V2Clock {
    fun now(): Instant
}

/**
 * ISO-8601 UTC timestamps in exactly the `Date.toISOString()` shape the TS
 * core emits (`2026-10-23T14:00:00.000Z`). Fixed-width UTC strings compare
 * lexicographically in chronological order, which the TS core relies on
 * (e.g. `assertedAt >= since`) and this port preserves.
 */
object IsoTime {
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMATTER.format(instant)

    fun parse(text: String): Instant = Instant.parse(text)

    fun nowIso(): String = format(Instant.now())
}

/**
 * Four-valued uncertainty (Lexicon delta). The values are NOT collapsible:
 *  - DISTINGUISHED: one determinate live answer
 *  - EQUIVALENT: multiple independent sources agree on the same answer
 *  - UNKNOWN: insufficient evidence, or live evidence in conflict
 *  - INAPPLICABLE: the question does not apply to this state (declared,
 *    not inferred from absence)
 *
 * Port of TS `Delta` in `src/types.ts`; [wire] is the TS string.
 */
enum class Delta(val wire: String) {
    DISTINGUISHED("distinguished"),
    EQUIVALENT("equivalent"),
    UNKNOWN("unknown"),
    INAPPLICABLE("inapplicable");

    companion object {
        fun fromWire(wire: String): Delta = entries.first { it.wire == wire }
    }
}

/** Port of TS `EventKind`; [wire] is the TS string. */
enum class EventKind(val wire: String) {
    SENSOR_OBSERVATION("sensor_observation"),
    DERIVED_INFERENCE("derived_inference"),
    USER_REPORT("user_report"),
    EXTERNAL_REPORT("external_report"),
    AGENT_DERIVED("agent_derived"),
    SYSTEM("system"),
    ACTION_PROPOSAL("action_proposal"),
    ASSENT("assent"),
    EXECUTION("execution"),
    OUTCOME("outcome");

    companion object {
        fun fromWire(wire: String): EventKind = entries.first { it.wire == wire }
    }
}

/** Port of TS `RelationType`; [wire] is the TS string. */
enum class RelationType(val wire: String) {
    SUPPORTS("supports"),
    CONFLICTS("conflicts"),
    RETRACTS("retracts"),
    SUPERSEDES("supersedes"),
    DERIVES_FROM("derives-from"),
    EXECUTES("executes"),
    AUTHORIZES("authorizes");

    companion object {
        fun fromWire(wire: String): RelationType = entries.first { it.wire == wire }
    }
}

data class Relation(val type: RelationType, val targetId: String) {
    /** JSON-shaped view for canonical hashing. */
    fun toCanonicalMap(): Map<String, Any?> = mapOf("type" to type.wire, "targetId" to targetId)
}

enum class Polarity(val wire: String) {
    AFFIRM("affirm"),
    NEGATE("negate");

    companion object {
        fun fromWire(wire: String): Polarity = entries.first { it.wire == wire }
    }
}

/**
 * One evidence event (spec §3.2). Port of TS `EvidenceEvent`.
 *
 * [assertedAt] is when the assertion entered the system; [observedAt] is
 * when the thing asserted actually happened/was observed (may precede).
 * [agentDerived] is true when submitted by an outside agent through a
 * plug — such evidence is never silently promoted to observation.
 */
data class EvidenceEvent(
    val id: String,
    val seq: Int,
    val kind: EventKind,
    /** dot-namespaced subject, e.g. 'medication.dose', 'location', 'collector.location' */
    val topic: String,
    /** human-readable assertion */
    val claim: String,
    val polarity: Polarity,
    /** who/what asserted it: 'sovereign', 'sensor:gps', 'account:<id>', 'agent:<id>', 'system', ... */
    val source: String,
    val assertedAt: String,
    val observedAt: String,
    val confidence: Double, // 0..1
    val relations: List<Relation>,
    val agentDerived: Boolean,
    val prevHash: String,
    val hash: String,
) {
    /**
     * The hashed body: every field except [hash] itself, as a JSON-shaped
     * map. Canonical key sorting makes field order irrelevant; the field
     * SET matches the TS body exactly.
     */
    fun bodyMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "seq" to seq,
        "kind" to kind.wire,
        "topic" to topic,
        "claim" to claim,
        "polarity" to polarity.wire,
        "source" to source,
        "assertedAt" to assertedAt,
        "observedAt" to observedAt,
        "confidence" to confidence,
        "relations" to relations.map { it.toCanonicalMap() },
        "agentDerived" to agentDerived,
        "prevHash" to prevHash,
    )

    companion object {
        /** Event hash exactly as the TS ledger computes it: sha256(prevHash + '|' + canonical(body)). */
        fun computeHash(prevHash: String, body: Map<String, Any?>): String =
            Canonical.sha256Hex(prevHash + "|" + Canonical.stringify(body))
    }
}

/** Input for admitting a new event. Port of TS `AdmitInput` (defaults match). */
data class AdmitInput(
    val kind: EventKind,
    val topic: String,
    val claim: String,
    val polarity: Polarity = Polarity.AFFIRM,
    val source: String,
    val assertedAt: String? = null,
    val observedAt: String? = null,
    val confidence: Double = 0.9,
    val relations: List<Relation> = emptyList(),
    val agentDerived: Boolean? = null,
)
