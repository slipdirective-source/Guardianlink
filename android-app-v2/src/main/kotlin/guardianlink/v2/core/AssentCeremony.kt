package guardianlink.v2.core

import guardianlink.core.ConciergeInterface
import guardianlink.crypto.AssentCodec
import guardianlink.crypto.Ed25519
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.KeyMaterial
import guardianlink.gates.MonotonicClock
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.impactOf
import guardianlink.gates.render
import guardianlink.voice.AudioSample
import java.security.PrivateKey

/**
 * Measurements the ceremony cannot invent (GateContext's trust
 * boundary: these are the DEPLOYMENT's measurements of the request
 * channel and context). Either may be null — which means UNAVAILABLE,
 * and unavailable refuses the ceremony rather than fabricating a
 * plausible value (the scaffold asserted snr=20.0 / entropy=2.0
 * constants; this type exists so that choice is never made silently
 * here).
 *
 * The app's provider (see README) computes both from real state:
 * snr from the request text's content-word ratio, entropy as the
 * Shannon entropy of the standing answers' delta distribution.
 * Crude, documented, and actually measured — the honest grade for
 * this slice.
 */
data class CeremonyMeasurements(
    val signalSnr: Double?,
    val contextEntropyBits: Double?,
)

/**
 * What starts a ceremony: a core proposal plus the conversational
 * facts the bundle must carry.
 *
 * [parseTrees] is the conversation classifier's parse count for the
 * governing input: the classifier is deterministic and clarify-first,
 * so a governed proposal normally carries exactly 1. Anything else
 * refuses the ceremony at [AssentCeremony.begin] — Gate 4 evidence is
 * the classifier's behavior, and a bundle claiming 1 for an input that
 * did not parse uniquely would be fabricated.
 *
 * [intentVector]/[currentVector] are the classifier's intent
 * distribution (over Intent entries) for the governing request,
 * measured at ceremony start for BOTH slots: session-scale intent
 * tracking is not yet instrumented, so Gate 5's drift term honestly
 * evaluates "no drift measured within this ceremony" (distance 0),
 * while the loop port carries the bundle-consistency burden. This
 * limitation is named here and in the README, not hidden.
 */
data class CeremonyRequest(
    val proposalId: String,
    val requestText: String,
    val parseTrees: Int,
    val intentVector: DoubleArray,
    val currentVector: DoubleArray,
    val measurements: CeremonyMeasurements,
)

sealed interface CeremonyBegin {
    /** The rendering the sovereign must see, and the handle for confirm/submit. */
    data class Prepared(val ceremony: PreparedCeremony) : CeremonyBegin

    /** The ceremony refused to start; [reason] says exactly why. Nothing was signed. */
    data class Refused(val reason: String) : CeremonyBegin
}

sealed interface CeremonyOutcome {
    /** Submitted through the concierge. [result] is the engine's own verdict, verbatim. */
    data class Submitted(
        val actionId: String,
        val result: ConciergeInterface.ExecutionResult,
        /** Human-facing posture line (per-gate honesty; e.g. the Gate 3 sentence). */
        val userMessage: String,
        /** The v2 ledger event recording the outcome (execution or halt write-back). */
        val writeBackEventId: String,
    ) : CeremonyOutcome

    /** Refused before submission (e.g. cooling window not elapsed). Nothing reached the engine. */
    data class Refused(val reason: String) : CeremonyOutcome
}

/**
 * A ceremony between begin() and submit(): translation done, origin
 * signed, rendering fixed. The assent signature does NOT exist yet —
 * it is produced at [AssentCeremony.confirm], at the sovereign's
 * confirm action, which is what makes the confirm the assent.
 */
class PreparedCeremony internal constructor(
    val proposal: ActionProposal,
    val action: Action,
    val actionId: String,
    val rendering: String,
    val originMessage: ByteArray,
    val originSignature: ByteArray,
    val intentVector: DoubleArray,
    val currentVector: DoubleArray,
    val measurements: CeremonyMeasurements,
    /** The request's intent distribution, retained for the bundle. */
    val requestText: String,
) {
    /** Set by confirm(); the assent triple the sovereign produced. */
    internal var assentSignature: ByteArray? = null
    internal var assentedAtMs: Long = 0L
    internal var proofsIssuedAtMs: Long = 0L

    /** Earliest tau at which submit() may run: assent time + the fixed-rail cooling window for this action's computed impact. */
    fun earliestSubmitMs(rails: Rails = Rails()): Long =
        assentedAtMs + rails.coolingWindowMs(impactOf(action))
}

/**
 * The assent ceremony (EVIDENCE-PATH §"The assent ceremony", steps
 * 1–6), replacing FailClosedEvidenceProvider as the production path
 * from a core proposal to a governed engine submission:
 *
 *  1. begin(): the proposal is translated (EngineTranslation;
 *     unrepresentable actions refuse here, named). Parse trees must be
 *     exactly 1. Measurements must be present. The origin message —
 *     the serialized request bound to actionId and rendering — is
 *     signed with the operator private key (Gate 1 evidence).
 *  2. The sovereign reviews [PreparedCeremony.rendering] — Render(a),
 *     the exact string Gate 7 will require the bundle to carry.
 *  3. confirm(): the sovereign's confirm action. The assent signature
 *     is produced NOW via AssentCodec over (rendering, now,
 *     actionId) under the operator key (Gate 7 assent evidence), and
 *     the proofs block is stamped (issuedAt = now). Voice audio is
 *     supplied by the caller at submit: in this slice there is NO
 *     enrolled voice, so the app passes an empty sample and Gate 7's
 *     theta_voice halts with its reason — absent evidence, honestly
 *     labeled, never a synthetic template (see README).
 *  4. submit(): refuses until the cooling window for the action's
 *     computed impact has elapsed since assent (Gate 7 theta_cool is
 *     a fixed rail; the ceremony respects it rather than discovering
 *     it as a halt). Then the bundle is assembled and submitted
 *     through ConciergeInterface — the sole doorway.
 *  5. The outcome is written back into the v2 ledger: Integrated →
 *     an EXECUTION event carrying the seal root; Halted → an OUTCOME
 *     event carrying the gate and the engine's reason VERBATIM. A
 *     sealed pause/resume is also reconciled into the core collector
 *     registry (the projection lifecycle in EngineTranslation).
 *
 * Gate 3 posture: this ceremony assembles an EMPTY zk proof with
 * public inputs bound to (actionId, rendering). Production wiring
 * leaves ProductionVerifiers' verifyZk at its deny default (Caleb's
 * ruling: Gate 3 stays closed until a proof system is chosen), so a
 * production submission halts at Gate 3 and [CeremonyOutcome.Submitted]
 * says so in exactly those terms. A proof system behind the port —
 * real ZK or otherwise — changes only what begin() assembles here.
 *
 * Not thread-safe; the app serializes through the bridge, as before.
 */
class AssentCeremony(
    private val core: GuardianLink,
    private val concierge: ConciergeInterface,
    private val clock: MonotonicClock,
    private val operatorPrivateKey: PrivateKey,
    private val rails: Rails = Rails(),
) {
    private var seq = 0

    fun begin(request: CeremonyRequest): CeremonyBegin {
        val proposal = core.actions.getProposal(request.proposalId)
            ?: return CeremonyBegin.Refused("unknown proposal ${request.proposalId}; the ceremony binds to a governed core proposal, and there is none with that id.")
        if (proposal.status != ProposalStatus.PROPOSED) {
            return CeremonyBegin.Refused("proposal ${proposal.id} is ${proposal.status.wire}; only a live proposal can enter the ceremony.")
        }
        if (request.parseTrees != 1) {
            return CeremonyBegin.Refused(
                "the governing input produced ${request.parseTrees} parse trees, not exactly 1; Gate 4 evidence is the classifier's unambiguity, " +
                    "and the ceremony does not start for an input that did not parse uniquely. Clarify the input first.",
            )
        }
        val snr = request.measurements.signalSnr
            ?: return CeremonyBegin.Refused("signal SNR measurement unavailable; Gate 0 evidence cannot be honestly supplied, so the ceremony does not start.")
        if (!snr.isFinite()) {
            return CeremonyBegin.Refused("signal SNR measurement is non-finite; the ceremony does not start.")
        }
        val entropy = request.measurements.contextEntropyBits
            ?: return CeremonyBegin.Refused("context entropy measurement unavailable; Gate 4 evidence cannot be honestly supplied, so the ceremony does not start.")
        if (!entropy.isFinite()) {
            return CeremonyBegin.Refused("context entropy measurement is non-finite; the ceremony does not start.")
        }
        val substrate = concierge.currentSubstrate()
        val translated = when (val t = EngineTranslation.translate(proposal, substrate)) {
            is TranslationResult.Refused -> return CeremonyBegin.Refused("translation refused: ${t.reason}")
            is TranslationResult.Ok -> t
        }
        val action = translated.action
        val rendering = render(action)
        val actionId = "a-${clock.nowMs()}-${++seq}"
        // Gate 1 origin message: the serialized request, bound to the
        // actionId and the rendering the sovereign will see.
        val originMessage = "guardianlink/v2/origin|$actionId|$rendering|${request.requestText}"
            .toByteArray(Charsets.UTF_8)
        val originSignature = Ed25519.sign(operatorPrivateKey, originMessage)
        return CeremonyBegin.Prepared(
            PreparedCeremony(
                proposal = proposal,
                action = action,
                actionId = actionId,
                rendering = rendering,
                originMessage = originMessage,
                originSignature = originSignature,
                intentVector = request.intentVector,
                currentVector = request.currentVector,
                measurements = request.measurements,
                requestText = request.requestText,
            ),
        )
    }

    /**
     * The sovereign's confirm action: produce the Gate 7 assent
     * signature over (rendering, now, actionId) and stamp the proofs
     * block. Returns the earliest tau at which [submit] may run.
     */
    fun confirm(prepared: PreparedCeremony): Long {
        val now = clock.nowMs()
        prepared.assentedAtMs = now
        prepared.proofsIssuedAtMs = now
        prepared.assentSignature = AssentCodec.sign(operatorPrivateKey, prepared.rendering, now, prepared.actionId)
        return prepared.earliestSubmitMs(rails)
    }

    /**
     * Assemble the evidence bundle and submit through the concierge —
     * the sole doorway. [assentAudio] is the ceremony's voice capture;
     * pass an empty sample when no voice evidence exists (Gate 7 will
     * halt on theta_voice and say so) — never a substitute voice.
     */
    fun submit(prepared: PreparedCeremony, assentAudio: AudioSample): CeremonyOutcome {
        val signature = prepared.assentSignature
            ?: return CeremonyOutcome.Refused("no assent has been produced for ${prepared.actionId}; confirm() is the assent, and it has not happened.")
        val now = clock.nowMs()
        val earliest = prepared.earliestSubmitMs(rails)
        if (now < earliest) {
            return CeremonyOutcome.Refused(
                "cooling window not elapsed: assent was given at ${prepared.assentedAtMs}, the fixed rail for this action's impact opens at $earliest, and tau is $now. Nothing was submitted.",
            )
        }
        val evidence = ConciergeInterface.ActionEvidence(
            actionId = prepared.actionId,
            signal = Signal(
                payload = prepared.requestText.toByteArray(Charsets.UTF_8),
                snr = prepared.measurements.signalSnr!!,
                wellFormed = true,
            ),
            keys = KeyMaterial(message = prepared.originMessage, signature = prepared.originSignature),
            proofs = Proofs(
                zkProof = ByteArray(0),
                publicInputs = "${prepared.actionId}|${prepared.rendering}".toByteArray(Charsets.UTF_8),
                issuedAtMs = prepared.proofsIssuedAtMs,
            ),
            contextEntropyBits = prepared.measurements.contextEntropyBits!!,
            contextParseTrees = 1,
            rendering = prepared.rendering,
            assent = Assent(signature),
            assentedAtMs = prepared.assentedAtMs,
            assentAudio = assentAudio,
            intentVector = prepared.intentVector,
            currentVector = prepared.currentVector,
        )
        val result = concierge.execute(prepared.action, evidence)
        return when (result) {
            is ConciergeInterface.ExecutionResult.Executed -> {
                reconcileCore(prepared.proposal)
                val ev = core.ledger.admit(
                    AdmitInput(
                        kind = EventKind.EXECUTION,
                        topic = "action.${prepared.proposal.actionType}",
                        claim = "Governed engine execution INTEGRATED for proposal ${prepared.proposal.id} (${prepared.proposal.actionType} on ${prepared.proposal.target}); seal root ${result.anchorReceipt.root}.",
                        source = "engine",
                        confidence = 1.0,
                        relations = listOf(Relation(RelationType.EXECUTES, prepared.proposal.id)),
                    ),
                )
                core.audit.record("engine", "engine_execution_integrated", mapOf("proposalId" to prepared.proposal.id, "actionId" to prepared.actionId, "sealRoot" to result.anchorReceipt.root))
                CeremonyOutcome.Submitted(
                    actionId = prepared.actionId,
                    result = result,
                    userMessage = "Executed through the Nine Gates and sealed (root ${result.anchorReceipt.root}).",
                    writeBackEventId = ev.id,
                )
            }
            is ConciergeInterface.ExecutionResult.Rejected -> {
                val ev = core.ledger.admit(
                    AdmitInput(
                        kind = EventKind.OUTCOME,
                        topic = "action.${prepared.proposal.actionType}",
                        claim = "Governed engine execution HALTED at gate ${result.atGate}: ${result.reason}",
                        source = "engine",
                        confidence = 1.0,
                        relations = listOf(Relation(RelationType.DERIVES_FROM, prepared.proposal.id)),
                    ),
                )
                core.audit.record("engine", "engine_execution_halted", mapOf("proposalId" to prepared.proposal.id, "actionId" to prepared.actionId, "atGate" to result.atGate, "reason" to result.reason))
                CeremonyOutcome.Submitted(
                    actionId = prepared.actionId,
                    result = result,
                    userMessage = userMessageForHalt(result.atGate, result.reason),
                    writeBackEventId = ev.id,
                )
            }
        }
    }

    /**
     * Per-gate honesty for the surface: a Gate 3 halt in production
     * wiring has one exact meaning, and the UI says it in those words
     * rather than leaking a gate number as if it were an explanation.
     */
    private fun userMessageForHalt(atGate: Int, reason: String): String =
        if (atGate == 3) {
            "engine execution halts at Gate 3 (ZK proof system not yet deployed): $reason"
        } else {
            "engine execution halted at gate $atGate: $reason"
        }

    /**
     * Keep the core's view coherent with a sealed engine effect: a
     * sealed pause/resume changes the collector the core registry
     * governs. Best-effort and audited — if the registry disagrees
     * (already in the target state), the ledger write-back above is
     * still the record of the engine's act.
     */
    private fun reconcileCore(proposal: ActionProposal) {
        try {
            when (proposal.actionType) {
                "pause_collector" -> core.collectors.pause(proposal.target, actor = "engine")
                "resume_collector" -> core.collectors.resume(proposal.target, actor = "engine")
            }
        } catch (_: Exception) {
            // The seal already happened; a reconciliation miss is a
            // named follow-up, not a reason to misreport the outcome.
            core.audit.record("engine", "engine_reconciliation_missed", mapOf("proposalId" to proposal.id, "actionType" to proposal.actionType, "target" to proposal.target))
        }
    }
}
