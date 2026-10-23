package guardianlink.v2.app

import android.app.Application
import android.util.Log
import guardianlink.adapters.ProductionVerifiers
import guardianlink.adapters.RevocationFeed
import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.durability.DurableCapabilityStore
import guardianlink.gates.DurableMonotonicClock
import guardianlink.gates.MonotonicClock
import guardianlink.gates.NineGates
import guardianlink.gates.Rails
import guardianlink.gates.Revocation
import guardianlink.gates.SubstrateState
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.v2.core.GuardianLink
import guardianlink.voice.AudioSample
import guardianlink.voice.BiometricVerifier
import guardianlink.voice.SpeakerEmbedding
import guardianlink.voice.VerificationResult
import guardianlink.voice.VoiceTemplate
import java.io.File
import java.nio.file.Paths
import java.security.KeyPair
import kotlin.concurrent.thread

/**
 * GuardianLink v0.2 Application.
 *
 * Two layers, both real:
 *
 * 1. The v0.2 CORE ([GuardianLink], package guardianlink.v2.core): the
 *    evidence ledger, standing questions, conversation, governed local
 *    actions, registries, sync, audit. Pure Kotlin, created synchronously
 *    in [onCreate] — the conversational surface works from the first
 *    frame and never waits on the engine.
 *
 * 2. The ENGINE (guardianlink.*): the NineGates governance stack, built
 *    on a background thread exactly as the scaffold's GuardianApp builds
 *    it — DurableMonotonicClock, MerkleAuditLog with a file anchor,
 *    DurableCapabilityStore as the permit WAL, ConciergeInterface as the
 *    only execution doorway.
 *
 * =====================================================================
 * PRODUCTION WIRING — HOW THIS DIFFERS FROM THE SCAFFOLD, AND WHY
 * =====================================================================
 * The scaffold wires `ScaffoldVerifiers` (every check returns true) and
 * `PolicyEngine(RailAuthorizer.PERMISSIVE)`. That posture passes
 * anything and is explicitly DEMO-only. This class wires the opposite:
 *
 * - [ProductionVerifiers] with REAL Ed25519 operator/revocation keys and
 *   ALL of its deployment ports left at their built-in DENY defaults:
 *   ZK verification denies (no prover exists), boundary/policy/loop
 *   checks deny, governance divergence is +Inf. Any action that somehow
 *   reached the gates would halt.
 * - [FailClosedEvidenceProvider] (see GovernedActionBridge.kt): no
 *   ActionEvidence can even be constructed in this cut, so no engine
 *   action can be submitted at all. Governed engine execution is
 *   UNAVAILABLE in v0.2-first-cut and refuses loudly instead of faking.
 * - [RailAuthorizer.DENY_ALL] on the PolicyEngine: hard-rail lifecycle
 *   calls are denied until a real authorizer (capability/signature
 *   backed) exists. The PERMISSIVE authorizer is never used here.
 * - [DenyBiometricVerifier] + an inert placeholder template: NineGates
 *   requires a biometric port and a non-null template. The scaffold
 *   bootstraps a GMM on SYNTHETIC voices and enrolls a silent-audio
 *   placeholder — a template that exists only to satisfy a type. Here
 *   the verifier REJECTS every ceremony (nothing is enrolled, and
 *   enrollment itself throws), so the placeholder template can never
 *   authorize anything. No synthetic population is trained.
 *
 * KNOWN GAPS before governed engine execution can be enabled (all in
 * the module README): operator/revocation keys are EPHEMERAL (generated
 * per install-process here; persistence behind Keystore-wrapped storage
 * is required before any real assent binds to them), the revocation
 * feed is empty unless a signed list file is deployed, and the evidence
 * ceremony (Gate 1 signer, Gate 3 prover, AssentCodec + voice assent)
 * does not exist yet. Because evidence fails closed, none of these gaps
 * can authorize anything in this cut.
 */
class GuardianLinkV2App : Application() {

    /** The v0.2 core harness. Available immediately after onCreate. */
    lateinit var core: GuardianLink
        private set

    /**
     * Serializes all access to [core]: the TS/Kotlin core is not
     * thread-safe (same contract as the engine's doorway). The UI,
     * the sensing service, and collectors all take this lock.
     */
    val coreLock = Any()

    @Volatile
    var engineReady: Boolean = false
        private set

    /** Governed engine bridge. Null until [engineReady]. */
    @Volatile
    var bridge: GovernedActionBridge? = null
        private set

    lateinit var clock: MonotonicClock
        private set

    override fun onCreate() {
        super.onCreate()
        core = GuardianLink()
        // The Android layer's own collector: device state (screen,
        // battery, network) — see DeviceStateCollector. Registered in
        // the core so its evidence path is governed like every other
        // collector (pause/deny take effect at the boundary).
        core.collectors.register(
            "device_state",
            "Screen, battery/charging, and connectivity state of the phone",
        )
        // Location/motion collectors are stubs in this cut: they mark
        // themselves unavailable in the ledger — visible in Live Now —
        // and emit no data. Nothing is fabricated.
        SensorStubs.markUnavailable(core)
        thread(name = "gl-v2-engine-init", isDaemon = true) { initEngine() }
    }

    private fun initEngine() {
        try {
            val filesDir = filesDir

            // Engine-owned monotonic clock: crash-safe across restarts.
            clock = DurableMonotonicClock(File(filesDir, "clock.state"))

            // Gate ledger + concierge audit log, anchored in the sandbox.
            val gatesLedger = MerkleAuditLog()
            val auditLog = MerkleAuditLog { rootHash, index ->
                Log.i(TAG, "audit checkpoint entry=$index root=$rootHash")
            }
            val anchorDir = Paths.get(filesDir.absolutePath, "anchors")
            val anchor = AppendOnlyFileAnchor(anchorDir)

            // --- Identity keys (EPHEMERAL in this cut — see class doc) ---
            val operatorKeys: KeyPair = guardianlink.crypto.Ed25519.generateKeyPair()
            val revocationKeys: KeyPair = guardianlink.crypto.Ed25519.generateKeyPair()
            Log.w(
                TAG,
                "operator/revocation keys are ephemeral in this cut; " +
                    "Keystore-wrapped persistence is required before real assent",
            )

            // --- Revocation feed: signed list if deployed, else empty ---
            val revocationFeed: RevocationFeed = loadRevocationFeed(filesDir, revocationKeys)

            // --- Production verifiers: real crypto, DENY defaults elsewhere ---
            val verifiers = ProductionVerifiers(
                operatorKey = operatorKeys.public,
                revocationAuthorityKey = revocationKeys.public,
                revocationFeed = revocationFeed,
                // zkVerifier, boundaryCheck, policyCheck, divergenceFn,
                // loopCheck: INTENTIONALLY LEFT AT ProductionVerifiers'
                // FAIL-CLOSED DEFAULTS. Do not pass permissive lambdas
                // here — that would recreate the scaffold's posture.
            )

            // --- Policy engine: hard-rail lifecycle DENIED until a real authorizer exists ---
            val policyEngine = PolicyEngine(authorizer = RailAuthorizer.DENY_ALL)

            val gates = NineGates(
                rails = Rails(),
                verifiers = verifiers,
                biometricVerifier = DenyBiometricVerifier,
                enrolledVoice = inertTemplate(),
                clock = clock,
                ledger = gatesLedger,
            )

            val concierge = ConciergeInterface(
                policyEngine = policyEngine,
                auditLog = auditLog,
                gates = gates,
                anchor = anchor,
                initialSubstrate = SubstrateState(emptyMap()),
                // R-U1: permit-consumption WAL in the app sandbox, beside
                // clock.state and the anchors — same durability root.
                permitStore = DurableCapabilityStore(filesDir),
            )

            bridge = GovernedActionBridge(concierge, clock, FailClosedEvidenceProvider())
            engineReady = true
            Log.i(TAG, "engine initialized (production verifiers, fail-closed evidence)")
        } catch (t: Throwable) {
            // Fail-closed overall: without the engine there is no governed
            // execution path at all; the v0.2 core (ledger/questions/
            // conversation/local actions) is unaffected and keeps working.
            Log.e(TAG, "engine init failed; governed engine execution unavailable", t)
        }
    }

    /**
     * Loads the deployment's signed revocation list from
     * `filesDir/revocations.jsonl` when one has been deployed. A present
     * but malformed/unverifiable file THROWS (fail-closed, per
     * SignedRevocationListFeed's contract) and engine init aborts.
     * Absent file = empty feed ("none known"), logged loudly.
     */
    private fun loadRevocationFeed(filesDir: File, revocationKeys: KeyPair): RevocationFeed {
        val path = Paths.get(filesDir.absolutePath, "revocations.jsonl")
        val file = path.toFile()
        if (!file.isFile) {
            Log.w(TAG, "no signed revocation list deployed; revocation feed is empty in this cut")
            return object : RevocationFeed {
                override fun revocationsFor(actionId: String): List<Revocation> = emptyList()
            }
        }
        return guardianlink.adapters.SignedRevocationListFeed.load(path, revocationKeys.public)
    }

    /**
     * Inert template satisfying NineGates' non-null port. It can never
     * verify: [DenyBiometricVerifier] rejects every sample regardless of
     * template, and enrollment is not offered in this cut.
     */
    private fun inertTemplate(): VoiceTemplate =
        VoiceTemplate(
            speakerEmbedding = SpeakerEmbedding.Vector(DoubleArray(0)),
            phraseReferences = emptyMap(),
        )

    companion object {
        const val TAG = "GuardianLinkV2"
    }
}

/**
 * Voice biometric port that rejects everything. NineGates requires a
 * BiometricVerifier; v0.2 has no enrollment flow yet, so the only honest
 * implementation is one that can never ACCEPT. (The scaffold instead
 * trains a GMM on synthetic voices — a demo bootstrap this module does
 * not inherit.)
 */
object DenyBiometricVerifier : BiometricVerifier {
    override fun enroll(samples: List<AudioSample>): VoiceTemplate {
        throw UnsupportedOperationException(
            "voice enrollment is not available in GuardianLink v0.2 (first cut); no template can be created",
        )
    }

    override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult =
        VerificationResult(
            score = 0.0,
            decision = VerificationResult.Decision.REJECT,
            liveness = VerificationResult.Liveness.NOT_CHECKED,
        )
}
