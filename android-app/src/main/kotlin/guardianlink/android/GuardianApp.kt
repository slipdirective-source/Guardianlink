package guardianlink.android

import android.app.Application
import android.util.Log
import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.durability.DurableCapabilityStore
import guardianlink.gates.DurableMonotonicClock
import guardianlink.voice.Gmm
import guardianlink.gates.MonotonicClock
import guardianlink.gates.NineGates
import guardianlink.gates.Rails
import guardianlink.gates.ReferenceTransition
import guardianlink.gates.Revocation
import guardianlink.gates.Verifiers
import guardianlink.gates.Action
import guardianlink.gates.SubstrateState
import guardianlink.gates.GateContext
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.voice.AudioSample
import guardianlink.voice.GmmUbmVoiceVerifier
import guardianlink.voice.Mfcc
import guardianlink.voice.SyntheticVoice
import guardianlink.voice.VoiceTemplate
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Application: builds the engine graph once, in-process (the "embedded
 * engine" deployment shape from docs/android-integration.md).
 *
 * Wiring posture is SCAFFOLD/DEMO, mirroring Main.kt's demo wiring:
 * permissive verifiers, synthetic-population UBM bootstrap. Every
 * demo-grade choice is marked DEMO below. Production MUST replace them
 * with [guardianlink.adapters.ProductionVerifiers] (real Ed25519,
 * signed revocation feed, deny-by-default policy/ZK ports), a real
 * background population for the UBM, and supervised multi-phrase
 * enrollment — before any market-facing claim.
 */
class GuardianApp : Application() {

    lateinit var dispatcher: ActionDispatcher
        private set

    /** Returns true if the engine has finished initialization. */
    fun isEngineReady(): Boolean = ::dispatcher.isInitialized
    lateinit var evidenceSupplier: EvidenceSupplier
        private set
    lateinit var voiceVerifier: GmmUbmVoiceVerifier
        private set
    lateinit var templateStore: TemplateStore
        private set
    lateinit var clock: MonotonicClock
        private set
    lateinit var egress: EgressPlane
        private set

    @Volatile
    var enrolledTemplate: VoiceTemplate? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // Engine construction is CPU-heavy (UBM bootstrap); never on main.
        thread(name = "gl-engine-init", isDaemon = true) { initEngine() }
    }

    private fun initEngine() {
        try {
            val filesDir = filesDir

            // Engine-owned monotonic clock: crash-safe across restarts.
            clock = DurableMonotonicClock(File(filesDir, "clock.state"))

            // Audit ledger + durable anchor, both in the app sandbox.
            val ledger = MerkleAuditLog()
            val auditLog = MerkleAuditLog { rootHash, index ->
                Log.i(TAG, "audit checkpoint entry=$index root=$rootHash")
            }
            val anchorDir = Paths.get(filesDir.absolutePath, "anchors")
            val anchor = AppendOnlyFileAnchor(anchorDir)

            // Voice: UBM bootstrap over a synthetic background population.
            // DEMO: production trains the UBM on a real background corpus.
            voiceVerifier = GmmUbmVoiceVerifier(bootstrapUbm())

            templateStore = TemplateStore(this)
            enrolledTemplate = templateStore.load()

            // DEMO verifiers: all-pass, exactly like Main.kt's demo wiring.
            // Production MUST use ProductionVerifiers here.
            val verifiers = ScaffoldVerifiers()

            val policyEngine = PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE)

            val gates = NineGates(
                rails = Rails(),
                verifiers = verifiers,
                biometricVerifier = voiceVerifier,
                // DEMO: a null template means the voice ceremony cannot run
                // until the user enrolls. NineGates requires non-null, so
                // the dispatcher refuses voice-gated actions pre-enrollment.
                enrolledVoice = enrolledTemplate ?: placeholderTemplate(voiceVerifier),
                clock = clock,
                ledger = ledger,
            )

            val concierge = ConciergeInterface(
                policyEngine,
                auditLog,
                gates,
                anchor,
                initialSubstrate = SubstrateState(emptyMap()),
                // R-U1: permit-consumption WAL in the app sandbox, beside
                // clock.state and the anchors — same durability root.
                permitStore = DurableCapabilityStore(filesDir),
            )

            evidenceSupplier = AndroidEvidenceSupplier(this)
            dispatcher = ActionDispatcher(concierge, clock, evidenceSupplier)
            // DEMO egress: governed packets are counted, not forwarded. A
            // real userspace IP stack is integration scope (see EgressPlane).
            egress = CountingEgress()

            Log.i(TAG, "engine initialized; enrolled=${enrolledTemplate != null}")
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
        }
    }

    fun setEnrolledTemplate(template: VoiceTemplate) {
        enrolledTemplate = template
        templateStore.save(template)
        // Rebuild is out of scope for the scaffold: restart the app to
        // rewire NineGates with the enrolled template.
        Log.i(TAG, "template enrolled; restart app to activate voice ceremony")
    }

    private fun bootstrapUbm(): Gmm {
        val backgroundSpeakers = listOf(
            SyntheticVoice.Speaker(f0Hz = 165.0, formantScale = 1.18, seed = 99L),
            SyntheticVoice.Speaker(f0Hz = 95.0, formantScale = 0.88, seed = 1234L),
            SyntheticVoice.Speaker(f0Hz = 140.0, formantScale = 1.07, seed = 555L),
        )
        val phraseIds = GmmUbmVoiceVerifier.phraseDigits.keys.toList()
        val frames = backgroundSpeakers.flatMap { sp ->
            phraseIds.flatMap { pid -> Mfcc.extract(SyntheticVoice.speak(sp, pid).pcm) }
        }
        return Gmm.trainEm(frames, components = 8)
    }

    private fun placeholderTemplate(verifier: GmmUbmVoiceVerifier): VoiceTemplate {
        // Satisfies the non-null constructor port pre-enrollment. The
        // dispatcher refuses to build voice evidence until a real
        // enrollment replaces this.
        return verifier.enroll(listOf(AudioSample(DoubleArray(Mfcc.SAMPLE_RATE) { 0.0 })))
    }

    companion object {
        const val TAG = "GuardianLink"
    }
}

/**
 * DEMO verifiers: permissive on every port, mirroring Main.kt's
 * demoVerifiers(). Production deployments MUST supply real
 * authentication here — this is the RailAuthorizer.PERMISSIVE-class
 * configuration the engine docs warn about.
 */
private class ScaffoldVerifiers : Verifiers {
    var revocationFeed: List<Revocation> = emptyList()
    override fun verifySignature(message: ByteArray, signature: ByteArray) = true
    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray) = true
    override fun verifyAssent(rendering: String, assentedAtMs: Long, actionId: String, signature: ByteArray) = true
    override fun verifyRevocation(revocation: Revocation) = true
    override fun revocationsFor(actionId: String) = revocationFeed.filter { it.actionId == actionId }
    override fun boundaryPermitted(action: Action, substrate: SubstrateState) = true
    override fun policyRules(action: Action, substrate: SubstrateState) = true
    override fun governanceDivergence(action: Action) = 0.0
    override fun metaLoopConsistent(context: GateContext) = true
    override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState =
        ReferenceTransition.apply(substrate, action)
}

/**
 * Egress plane: what happens to a packet the gates authorized.
 * The scaffold counts; a real deployment forwards via a userspace
 * IP stack on a protected socket. The dispatcher is still the sole
 * writer — nothing reaches ANY egress without an Executed result.
 */
interface EgressPlane {
    fun send(packet: ByteArray)
    fun stats(): String
}

private class CountingEgress : EgressPlane {
    private var packets = 0L
    private var bytes = 0L
    @Synchronized
    override fun send(packet: ByteArray) {
        packets++
        bytes += packet.size
    }
    @Synchronized
    override fun stats(): String = "egress(demo): $packets packets, $bytes bytes counted (not forwarded)"
}
