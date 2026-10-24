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
import guardianlink.v2.core.zk.PublicInputsCodec
import guardianlink.v2.core.zk.PublicInputsException
import guardianlink.v2.core.zk.UnavailableZkBridge
import guardianlink.v2.core.zk.ZkBridgeException
import guardianlink.v2.core.zk.ZkPlanResult
import guardianlink.v2.core.zk.ZkProvePlan
import guardianlink.v2.core.zk.ZkProveRequest
import guardianlink.v2.core.zk.ZkStatementAssembly
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
    /**
     * The Gate 3 statement assembled at begin() (ZK-GATE3-DESIGN
     * §5.1): descriptors, quantized values/ceilings, and the rail's
     * envelope commitments and blindings. Null = the EMPTY statement
     * (the proposal carries no governed numeric parameters); the
     * bundle then carries the canonical vacuity marker, not a range
     * proof. Witnesses live here, in memory only, between begin()
     * and submit() — they are never written to the ledger, the state
     * store, or logs (§5).
     */
    val zkPlan: ZkProvePlan?,
) {
    /** Set by confirm(); the assent triple the sovereign produced. */
    internal var assentSignature: ByteArray? = null
    internal var assentedAtMs: Long = 0L

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
 *     actionId) under the operator key (Gate 7 assent evidence) —
 *     and NOTHING else is stamped here (§5.2: the proofs issuance
 *     time is set at submit, when proving happens). Voice audio is
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
 * Gate 3 (ZK-GATE3-DESIGN §5): begin() additionally assembles the
 * proof statement from the covering rail's envelope commitments —
 * a governed parameter without a descriptor/commitment, or a
 * parameter with no committed envelope, refuses the ceremony HERE
 * with the named reason (the action is not Gate-3-provable).
 * submit() proves AFTER the cooling check passes, and only then:
 * issuedAtMs is read from the engine clock immediately before the
 * prover bridge runs, and that single value lands in the GLZK1
 * block, in Proofs.issuedAtMs, and in the Gate 5 coherence check
 * (§4.2/§4.3). (Recorded discipline: a transcript-bound timestamp
 * cannot be stamped after the proof that binds it exists, so
 * proving time sits inside the freshness window — bounded by the
 * §8 benchmark gate, ≤10 s target against the 60 s window; the
 * proof can only ever be OLDER than declared by the proving time,
 * never fresher.) A bridge that is unavailable or refuses means
 * the submission does not happen: [CeremonyOutcome.Refused] names
 * Gate 3 and the reason. Nothing is ever submitted with a
 * fabricated or absent proof.
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
        // Gate 3 statement assembly (§5.1): the covering rail's
        // envelope commitments and descriptors for this proposal's
        // governed parameters. Not provable = refused HERE, named —
        // never discovered as a Gate 3 surprise after assent.
        val coveringRail = core.actions.railCovering(proposal.actionType, proposal.target)
        val zkPlan = when (val a = ZkStatementAssembly.planFor(coveringRail, proposal)) {
            is ZkPlanResult.Refused -> return CeremonyBegin.Refused("Gate 3 statement refused: ${a.reason}")
            is ZkPlanResult.Plan -> a.plan
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
                zkPlan = zkPlan,
            ),
        )
    }

    /**
     * The sovereign's confirm action: produce the Gate 7 assent
     * signature over (rendering, now, actionId) — and only that
     * (§5.2: confirm no longer stamps a proofs issuance time;
     * issuance is set at submit, when proving happens). Returns the
     * earliest tau at which [submit] may run.
     */
    fun confirm(prepared: PreparedCeremony): Long {
        val now = clock.nowMs()
        prepared.assentedAtMs = now
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
        // Prove at submit (§5.3): after the cooling check, and only
        // then. issuedAtMs is read ONCE, immediately before proving;
        // that value is bound into the GLZK1 block, carried as
        // Proofs.issuedAtMs, and cross-checked by the Gate 5 port.
        val issuedAtMs = clock.nowMs()
        val proofs = try {
            assembleProofs(prepared, issuedAtMs)
        } catch (e: ZkBridgeException) {
            return CeremonyOutcome.Refused(
                "Gate 3 cannot be passed for ${prepared.actionId}: ${e.message} " +
                    "Nothing was submitted — a governed action is never submitted with a fabricated or absent proof.",
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
            proofs = proofs,
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
     * Assemble the Gate 3 proofs block (§5.3). The empty statement
     * (no governed numeric parameters) carries the canonical
     * vacuity marker + a paramCount-0 GLZK1 block. A real statement
     * goes through the prover bridge, and the returned block is
     * CROSS-CHECKED against the assembled statement through the
     * codec before anything is submitted — a bridge that returns a
     * block bound to a different action, rendering, timestamp, or
     * commitment set is refused here, named.
     */
    private fun assembleProofs(prepared: PreparedCeremony, issuedAtMs: Long): Proofs {
        val renderingHash = PublicInputsCodec.sha256(prepared.rendering.toByteArray(Charsets.UTF_8))
        val plan = prepared.zkPlan
            ?: return Proofs(
                zkProof = PublicInputsCodec.VACUITY_PROOF,
                publicInputs = PublicInputsCodec.encode(prepared.actionId, renderingHash, issuedAtMs, emptyList()),
                issuedAtMs = issuedAtMs,
            )
        val bridge = core.actions.zkBridge()
        if (!bridge.isAvailable()) {
            throw ZkBridgeException("${UnavailableZkBridge.REASON}; the governed submission was not proven and will not be submitted.")
        }
        val bundle = bridge.prove(ZkProveRequest(prepared.actionId, renderingHash, issuedAtMs, plan))
        val parsed = try {
            PublicInputsCodec.decode(bundle.publicInputs)
        } catch (e: PublicInputsException) {
            throw ZkBridgeException("the ZK prover returned an undecodable public-input block: ${e.message}")
        }
        if (parsed.actionId != prepared.actionId ||
            !parsed.renderingHash.contentEquals(renderingHash) ||
            parsed.issuedAtMs != issuedAtMs ||
            parsed.params.size != plan.descriptors.size
        ) {
            throw ZkBridgeException("the ZK prover returned a public-input block that does not match the assembled statement (binding mismatch); refusing to submit")
        }
        for (i in plan.descriptors.indices) {
            val p = parsed.params[i]
            if (p.descriptor != plan.descriptors[i] || !p.envelopeCommitment.contentEquals(plan.envelopeCommitments[i])) {
                throw ZkBridgeException("the ZK prover returned a public-input block whose parameter $i does not match the assembled statement; refusing to submit")
            }
        }
        return Proofs(zkProof = bundle.proof, publicInputs = bundle.publicInputs, issuedAtMs = issuedAtMs)
    }

    /**
     * Per-gate honesty for the surface: a Gate 3 halt says the proof
     * did not verify, in those words, rather than leaking a gate
     * number as if it were an explanation. The app layer refines
     * this with the verifier's failure class (§5.4) from its reason
     * code; the engine's reason is carried verbatim either way.
     */
    private fun userMessageForHalt(atGate: Int, reason: String): String =
        if (atGate == 3) {
            "engine execution halts at Gate 3 (ZK proof did not verify): $reason"
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
