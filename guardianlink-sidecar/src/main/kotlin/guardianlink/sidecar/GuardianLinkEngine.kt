package guardianlink.sidecar

import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.ExternalAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.DurableMonotonicClock
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.impactOf
import guardianlink.gates.render
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.voice.AudioSample
import guardianlink.voice.Gmm
import guardianlink.voice.GmmUbmVoiceVerifier
import guardianlink.voice.Mfcc
import guardianlink.voice.SyntheticVoice
import guardianlink.voice.VoiceTemplate
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Malformed ceremony audio on the wire. Fail-closed: HTTP 400, never reaches the engine. */
class BadAudioException(message: String) : Exception(message)

/**
 * The ONLY wiring between the sidecar and the GuardianLink engine.
 *
 * INVARIANT (structural, reviewable in this one file): the sole path from
 * an HTTP request to an executed action is [assent] ->
 * [ConciergeInterface.execute]. This class exposes no other engine entry
 * point — no direct NineGates access, no ledger access, no substrate
 * mutation. [submit] parses and renders only: it is pure with respect to
 * the governed substrate (no execution, no state change beyond recording
 * the pending action). [issueChallenge] only asks the voice adapter for a
 * liveness challenge: it executes nothing. If a future method here needs
 * to touch the engine, it goes through concierge.execute — there is no
 * other correct way, and any method that doesn't is a bug.
 *
 * VOICE CEREMONY: the engine's biometric adapter holds exactly one active
 * challenge, so ceremonies are serial per sidecar instance: [issueChallenge]
 * refuses with [ChallengeResult.CeremonyBusy] while another action's
 * ceremony is live. [assent] requires a live ceremony for its own actionId;
 * without one it returns [AssentResult.NoActiveCeremony] and never touches
 * the engine. Interleavings the sidecar can't see fail closed inside the
 * engine (content mismatch → liveness NOT_LIVE → Gate 7 halt).
 *
 * Threading: ConciergeInterface is documented not-thread-safe, so [assent]
 * and [issueChallenge] are serialized on [executeLock]. [submit] touches
 * only the pending-action map (a ConcurrentHashMap) and pure functions,
 * and never touches the engine.
 */
class GuardianLinkEngine(
    dataDir: Path,
    hmacSecret: ByteArray,
    policy: SidecarPolicy = SidecarPolicy(),
    initialSubstrate: SubstrateState = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
) {
    private val executeLock = ReentrantLock()
    private val random = SecureRandom()

    private val anchorDir: Path = dataDir.resolve("anchors").also {
        it.toFile().mkdirs()
    }
    private val anchor = AppendOnlyFileAnchor(anchorDir)
    private val verifiers = SidecarVerifiers(hmacSecret, policy)
    private val rails = Rails()
    private val clock = DurableMonotonicClock(dataDir.resolve("clock.state").toFile())
    private val ledger = MerkleAuditLog()

    /**
     * Demo-grade voice anchor: UBM over a synthetic background population,
     * template enrolled for the synthetic demo "person" with phrase
     * references for every challenge phrase. Mirrors Main.kt's
     * demoVoiceAnchor — same speakers, same phrases.
     *
     * This is DEMO enrollment, not a biometric claim: it lets the real
     * pipeline (MFCC → GMM-UBM → challenge/DTW → Gate 7) run without
     * microphone capture. A real deployment enrolls the person on
     * microphone audio, stores the template in its own enrollment store,
     * and constructs NineGates with a real adapter instead.
     *
     * The challenge seed is random per process: unlike the engine default,
     * the challenge sequence must not be predictable across restarts.
     */
    private val voiceAnchor: Pair<GmmUbmVoiceVerifier, VoiceTemplate> = buildVoiceAnchor()
    private val voiceVerifier: GmmUbmVoiceVerifier get() = voiceAnchor.first
    private val enrolledVoice: VoiceTemplate get() = voiceAnchor.second

    private val concierge = ConciergeInterface(
        PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE),
        MerkleAuditLog(),
        NineGates(rails, verifiers, voiceVerifier, enrolledVoice, clock = clock, ledger = ledger),
        anchor,
        initialSubstrate = initialSubstrate,
    )

    private data class PendingAction(
        val action: Action,
        val rendering: String,
        val submittedAtMs: Long,
    )

    private data class ActiveCeremony(
        val actionId: String,
        val phraseId: String,
        val issuedAtMs: Long,
    )

    private val pending = ConcurrentHashMap<String, PendingAction>()

    /** Guarded by [executeLock]. Null when no ceremony is live. */
    private var activeCeremony: ActiveCeremony? = null

    data class SubmittedAction(
        val actionId: String,
        val rendering: String,
        val impact: String,
        val coolingWindowMs: Long,
        val serverTimeMs: Long,
    )

    sealed interface ChallengeResult {
        /** [challengeId] is the phrase key; [phrase] is the prompt to speak. */
        data class Issued(val challengeId: String, val phrase: String) : ChallengeResult
        data object UnknownAction : ChallengeResult

        /** Another action's ceremony is live; the engine holds one challenge at a time. */
        data object CeremonyBusy : ChallengeResult
    }

    sealed interface AssentResult {
        data class Executed(
            val sealRoot: String,
            val anchorReceipt: ExternalAnchor.Receipt,
            val newSubstrate: SubstrateState,
        ) : AssentResult

        data class Rejected(val atGate: Int, val reason: String) : AssentResult
        data object UnknownAction : AssentResult

        /**
         * No live voice ceremony for this actionId: no challenge was
         * issued, it lapsed, or the live ceremony belongs to another
         * action. Fail-closed before the engine is touched.
         */
        data object NoActiveCeremony : AssentResult

        /**
         * The gates passed and the transition was sealed locally, but the
         * external anchor threw. Fail-loud: the caller gets the seal root
         * for reconciliation instead of a bare 500. Do NOT retry the
         * assent blindly — reconcile via /v1/ledger/verify and
         * /v1/substrate first.
         */
        data class AnchoringFailed(val sealRoot: String, val reason: String) : AssentResult
    }

    data class LedgerReport(
        val merkleOk: Boolean,
        val merkleEntries: Int,
        val merkleRoot: String,
        val anchorOk: Boolean,
        val anchorLines: Long,
    )

    /**
     * Phase 1: parse the action, render it canonically, hold it for
     * assent. No execution happens here — the returned rendering is what
     * the person must be shown and sign. [serverTimeMs] is the engine's
     * clock at submit; clients SHOULD derive assentedAtMs from it
     * (serverTimeMs + local elapsed) rather than trusting the client
     * clock, so clock skew can't push the assent into the future and
     * trip Gate 7's future-dated-assent halt.
     */
    fun submit(action: Action): SubmittedAction {
        evictExpired()
        val actionId = "act_" + ByteArray(16).also(random::nextBytes).toHex()
        val rendering = render(action)
        pending[actionId] = PendingAction(action, rendering, clock.nowMs())
        return SubmittedAction(
            actionId = actionId,
            rendering = rendering,
            impact = impactOf(action).name,
            coolingWindowMs = rails.coolingWindowMs(impactOf(action)),
            serverTimeMs = clock.nowMs(),
        )
    }

    /**
     * Voice ceremony, step 1: issue a single-use liveness challenge for a
     * pending action. Returns the phrase the person must speak; the spoken
     * response is submitted as base64 PCM with the assent. Re-issuing for
     * the same action replaces the challenge (the engine's challenge is
     * single-use); issuing while another action's ceremony is live is
     * refused — the engine holds exactly one active challenge, so silent
     * replacement would be a confusion vector.
     */
    fun issueChallenge(actionId: String): ChallengeResult = executeLock.withLock {
        evictExpired()
        val now = clock.nowMs()
        activeCeremony?.let { c ->
            if (now - c.issuedAtMs <= CHALLENGE_TTL_MS) {
                if (c.actionId == actionId) {
                    // Re-issue for the same action: replace the challenge.
                    val renewed = voiceVerifier.issueChallenge()
                    activeCeremony = c.copy(phraseId = renewed.phraseId, issuedAtMs = now)
                    return ChallengeResult.Issued(renewed.phraseId, renewed.prompt)
                }
                return ChallengeResult.CeremonyBusy
            }
            activeCeremony = null // lapsed
        }
        if (!pending.containsKey(actionId)) return ChallengeResult.UnknownAction
        val challenge = voiceVerifier.issueChallenge()
        activeCeremony = ActiveCeremony(actionId, challenge.phraseId, now)
        ChallengeResult.Issued(challengeId = challenge.phraseId, phrase = challenge.prompt)
    }

    /**
     * Decode ceremony audio from the wire: base64 of little-endian
     * float32 mono PCM at 16 kHz.
     *
     * float32 — not int16 — is deliberate: int16's absolute quantization
     * step destroys near-silent frames (the phoneme edge envelopes) in
     * log-mel space, pushing the DTW liveness distance over the engine's
     * threshold for some phrases even for bit-faithful renders. float32's
     * relative precision keeps the wire transparent (measured DTW ~1e-5
     * vs a 2.0 threshold).
     *
     * Malformed input throws [BadAudioException] (HTTP 400). Empty audio
     * is NOT rejected here: it reaches the engine, which fail-closes at
     * Gate 7 (theta_voice) — missing audio halts in the engine, never
     * default-allows.
     */
    fun decodeCeremonyAudio(b64: String): AudioSample {
        val bytes = try {
            Base64.getDecoder().decode(b64.trim())
        } catch (_: IllegalArgumentException) {
            throw BadAudioException("not valid base64")
        }
        if (bytes.size > MAX_AUDIO_BYTES)
            throw BadAudioException("audio too large (${bytes.size} bytes > $MAX_AUDIO_BYTES)")
        if (bytes.size % 4 != 0)
            throw BadAudioException("byte length not a multiple of 4: not float32 PCM")
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val pcm = DoubleArray(bytes.size / 4) { buf.float.toDouble() }
        return AudioSample(pcm)
    }

    /**
     * Phase 2: THE execution entry point. Requires a live voice ceremony
     * for this actionId (see [issueChallenge]) — without one this returns
     * [AssentResult.NoActiveCeremony] and never touches the engine. The
     * stored action is replayed — never the caller's bytes — and the
     * caller's rendering is passed through so Gate 7's theta_render checks
     * what the person actually signed against Render(action). The ceremony
     * audio is verified by the engine at Gate 7 (theta_voice): wrong
     * speaker, wrong phrase, or no liveness halts there. Consumes the
     * pending action and the ceremony: one submit, one challenge, one
     * assent, no double-execution.
     */
    fun assent(
        actionId: String,
        rendering: String,
        assentedAtMs: Long,
        assentSignatureHex: String,
        keyMessage: ByteArray,
        keySignatureHex: String,
        assentAudio: AudioSample,
        intentVector: DoubleArray = doubleArrayOf(1.0, 0.0),
        currentVector: DoubleArray = doubleArrayOf(1.0, 0.0),
    ): AssentResult {
        evictExpired()
        // 1. A live ceremony for THIS action must exist; otherwise fail
        //    closed before the engine is touched. The pending action is
        //    NOT consumed here, so the client can fetch a challenge and
        //    retry.
        val ceremonyLive = executeLock.withLock {
            val c = activeCeremony
            when {
                c == null -> false
                c.actionId != actionId -> false
                clock.nowMs() - c.issuedAtMs > CHALLENGE_TTL_MS -> {
                    activeCeremony = null
                    false
                }
                else -> true
            }
        }
        if (!ceremonyLive) return AssentResult.NoActiveCeremony

        // 2. Consume the pending action.
        val pendingAction = pending.remove(actionId)
        if (pendingAction == null) {
            executeLock.withLock {
                if (activeCeremony?.actionId == actionId) activeCeremony = null
            }
            return AssentResult.UnknownAction
        }

        val assentSig = assentSignatureHex.hexToBytes()
            ?: return AssentResult.Rejected(7, "theta_assent: malformed assent signature (not hex)")
        val keySig = keySignatureHex.hexToBytes()
            ?: return AssentResult.Rejected(1, "theta_sig: malformed caller signature (not hex)")

        val evidence = ConciergeInterface.ActionEvidence(
            actionId = actionId,
            signal = Signal("sidecar".toByteArray(Charsets.UTF_8), snr = 20.0, wellFormed = true),
            keys = KeyMaterial(
                message = keyMessage,
                signature = keySig,
            ),
            // Issued server-side at assent time: always within Gate 3's
            // freshness window, never future-dated.
            proofs = Proofs(
                "sidecar".toByteArray(Charsets.UTF_8),
                "sidecar".toByteArray(Charsets.UTF_8),
                issuedAtMs = clock.nowMs(),
            ),
            contextEntropyBits = 2.0,
            contextParseTrees = 1,
            rendering = rendering,
            assent = Assent(assentSig),
            assentedAtMs = assentedAtMs,
            // Ceremony PCM: the engine verifies it against its own
            // enrolled voice template at Gate 7 (theta_voice). Evidence
            // only — the template is engine-owned, never caller-supplied.
            assentAudio = assentAudio,
            intentVector = intentVector,
            currentVector = currentVector,
        )

        // THE invariant: this is the only engine call in the sidecar.
        // The ceremony is consumed whether the gates pass or fail.
        return executeLock.withLock {
            try {
                when (val r = concierge.execute(pendingAction.action, evidence)) {
                    is ConciergeInterface.ExecutionResult.Executed ->
                        AssentResult.Executed(
                            sealRoot = ledger.root,
                            anchorReceipt = r.anchorReceipt,
                            newSubstrate = r.newSubstrate,
                        )
                    is ConciergeInterface.ExecutionResult.Rejected ->
                        AssentResult.Rejected(r.atGate, r.reason)
                }
            } catch (e: Exception) {
                // Only the anchor throws out of execute() (fail-loud by
                // contract): the transition IS sealed in the engine ledger.
                AssentResult.AnchoringFailed(
                    sealRoot = ledger.root,
                    reason = (e.message ?: e.javaClass.simpleName),
                )
            } finally {
                activeCeremony = null
            }
        }
    }

    fun verifyLedger(): LedgerReport {
        val anchorLines = anchorDir.resolve("anchors.log").toFile()
            .takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)?.count { it.isNotBlank() } ?: 0
        return LedgerReport(
            merkleOk = ledger.verifyIntegrity() < 0,
            merkleEntries = ledger.size,
            merkleRoot = ledger.root,
            anchorOk = anchor.verifyChain() < 0,
            anchorLines = anchorLines.toLong(),
        )
    }

    /** Read-only inspection of the governed substrate. Bypasses nothing. */
    fun currentSubstrate(): SubstrateState = executeLock.withLock {
        concierge.currentSubstrate()
    }

    private fun evictExpired() {
        val now = clock.nowMs()
        pending.entries.removeIf { (_, p) -> now - p.submittedAtMs > PENDING_TTL_MS }
    }

    companion object {
        /** Pending actions expire if never assented — unbounded growth is a DoS vector. */
        const val PENDING_TTL_MS: Long = 15 * 60 * 1000L

        /** A voice ceremony lapses if the assent doesn't follow promptly. */
        const val CHALLENGE_TTL_MS: Long = 5 * 60 * 1000L

        /**
         * Largest ceremony audio accepted: 1 MiB of float32 = ~16 s at
         * 16 kHz mono. A challenge phrase is ~2 s; this bound is generous.
         */
        const val MAX_AUDIO_BYTES: Int = 1 shl 20

        /**
         * Demo-grade voice anchor: UBM over a synthetic background
         * population, template enrolled for the synthetic demo "person"
         * with phrase references for every challenge phrase. Mirrors
         * Main.kt's demoVoiceAnchor — same speakers, same phrases.
         */
        fun buildVoiceAnchor(): Pair<GmmUbmVoiceVerifier, VoiceTemplate> {
            val person = SyntheticVoice.Speaker(f0Hz = 120.0, formantScale = 1.0, seed = 7L)
            val backgroundSpeakers = listOf(
                SyntheticVoice.Speaker(f0Hz = 165.0, formantScale = 1.18, seed = 99L),
                SyntheticVoice.Speaker(f0Hz = 95.0, formantScale = 0.88, seed = 1234L),
                SyntheticVoice.Speaker(f0Hz = 140.0, formantScale = 1.07, seed = 555L),
            )
            val phraseIds = GmmUbmVoiceVerifier.phraseDigits.keys.toList()
            val backgroundFrames = backgroundSpeakers.flatMap { sp ->
                phraseIds.flatMap { pid -> Mfcc.extract(SyntheticVoice.speak(sp, pid).pcm) }
            }
            val ubm = Gmm.trainEm(backgroundFrames, components = 8)
            // Random challenge seed per process: the challenge sequence
            // must not be predictable across restarts (the engine default
            // is deterministic).
            val verifier = GmmUbmVoiceVerifier(ubm, seed = SecureRandom().nextLong())
            val speakerSamples = phraseIds.map { pid -> SyntheticVoice.speak(person, pid) }
            val template = verifier.enrollPhrases(
                verifier.enroll(speakerSamples),
                phraseIds.associateWith { pid -> SyntheticVoice.speak(person, pid) },
            )
            return verifier to template
        }

        /** The demo "person" — must match the enrolled speaker in [buildVoiceAnchor]. */
        val demoSpeaker = SyntheticVoice.Speaker(f0Hz = 120.0, formantScale = 1.0, seed = 7L)
    }
}
