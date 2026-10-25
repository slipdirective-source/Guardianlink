package guardianlink.v2.core

/**
 * The language port — where an LLM plugs into the deterministic
 * core, and the ONLY place it plugs in (his ruling, 2026-10-25:
 * the surface parser is "too small"; plug in an LLM, cloud first).
 *
 * The contract is the doctrine, enforced by the type:
 * - The port READS an utterance and returns a [StructuredIntent] —
 *   data, never an action. It cannot execute, cannot assent, and
 *   cannot widen the registry: [Conversation] validates every
 *   intent against the same registry the deterministic forms use,
 *   derives consequence class and authority from the registry
 *   (never from the model), and drops anything off-registry,
 *   under-specified, or below the confidence floor.
 * - What the sovereign assents to is the VALIDATED structure,
 *   rendered by the deterministic review prompt — the model's
 *   wording never reaches the assent surface.
 * - Every accepted parse is admitted to the ledger as
 *   agent-derived evidence: utterance in, intent out, confidence
 *   attached. A bad parse is auditable after the fact.
 *
 * The deterministic forms run first and remain the floor: the port
 * is consulted only for utterances no form matched. A port that is
 * absent, errors, or times out changes nothing — the floor's
 * behavior (clarify / record) stands.
 */
data class StructuredIntent(
    /** "act", "ask", "report", or "none". Only "act" is acted on in v1. */
    val kind: String,
    /** A registry action type when kind == "act" (see DEVICE_ACTIONS). */
    val actionType: String? = null,
    /** Flat string parameters for the action (to, body, app, text, key…). */
    val params: Map<String, String> = emptyMap(),
    /** The model's own confidence, 0.0–1.0. Below the floor: ignored. */
    val confidence: Double = 0.0,
)

interface LanguagePort {
    /**
     * Read [utterance] and return its structured intent, or null
     * when the port has no reading. MAY BLOCK (network) — callers
     * run it off the UI thread. Implementations must bound their
     * own latency; a throw is treated as null.
     */
    fun parse(utterance: String): StructuredIntent?
}

/** Minimum model confidence for an intent to reach validation. */
const val LANGUAGE_CONFIDENCE_FLOOR: Double = 0.60
