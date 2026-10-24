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
import guardianlink.v2.core.AssentCeremony
import guardianlink.v2.core.CeremonyMeasurements
import guardianlink.v2.core.CorePolicyPorts
import guardianlink.v2.core.EngineTranslation
import guardianlink.v2.core.GuardianLink
import guardianlink.v2.core.Intent
import guardianlink.v2.core.KeyCustody
import guardianlink.v2.core.KeyCustodyException
import guardianlink.v2.core.StateStorage
import guardianlink.v2.core.StateStore
import guardianlink.v2.core.StateStoreException
import guardianlink.voice.SpeakerEmbedding
import guardianlink.voice.VoiceTemplate
import java.io.File
import java.nio.file.Paths
import java.security.KeyPair
import kotlin.concurrent.thread
import kotlin.math.ln

/**
 * GuardianLink v0.2 Application.
 *
 * Two layers, both real:
 *
 * 1. The v0.2 CORE ([GuardianLink], package guardianlink.v2.core): the
 *    evidence ledger, standing questions, conversation, governed local
 *    actions, registries, sync, audit. Pure Kotlin, created synchronously
 *    in [onCreate] — the conversational surface works from the first
 *    frame and never waits on the engine. The core state is PERSISTED
 *    in an encrypted store ([EncryptedStateStorage] over StateStore)
 *    with both hash chains verified before any of it is trusted; a
 *    store that fails verification is a visible, named failure
 *    ([persistenceError]) and is never overwritten by the session.
 *
 * 2. The ENGINE (guardianlink.*): the NineGates governance stack, built
 *    on a background thread as the scaffold builds it —
 *    DurableMonotonicClock, MerkleAuditLog with a file anchor,
 *    DurableCapabilityStore as the permit WAL, ConciergeInterface as
 *    the only execution doorway, seeded with the core's substrate
 *    projection (EngineTranslation).
 *
 * =====================================================================
 * PRODUCTION WIRING — THE EVIDENCE PATH (this cut)
 * =====================================================================
 * The scaffold wires `ScaffoldVerifiers` (every check returns true) and
 * `PolicyEngine(RailAuthorizer.PERMISSIVE)`. This class wires the
 * opposite at every seam, as before — and this cut replaces the first
 * cut's fail-closed evidence stub with the real ceremony:
 *
 * - KEY CUSTODY: persistent operator + SEPARATE revocation-authority
 *   Ed25519 keypairs ([KeyCustodyProvider] over the encrypted store).
 *   Key ids are recorded in the core state document and checked for
 *   continuity at startup; a swap or an unreadable custody document
 *   leaves governed execution UNAVAILABLE and says so ([engineStatus])
 *   — keys are never silently regenerated. (First-run creation, when
 *   the store is verifiably absent, is the only generation path.)
 * - POLICY PORTS: Gates 2/5/6 are implemented from the core's own
 *   governance state ([CorePolicyPorts]) — boundary from the
 *   translation boundary, policy from live proposal/rail/assent state,
 *   divergence from the core's conflict/drift signals, loop from
 *   bundle coherence. No deny defaults remain on those ports.
 * - GATE 3 (ZK): REAL — Caleb ruled Option A (2026-10-23), and this
 *   cut wires it: ProductionVerifiers' zkVerifier is
 *   [ZkVerifierWiring] over [NativeZkBridge] (uniFFI → the Rust glzk
 *   crate, dalek Bulletproofs over Ristretto255), and the core is
 *   constructed with the same bridge as its prover, so rails commit
 *   envelope commitments and the ceremony proves at submit
 *   (ZK-GATE3-DESIGN §5/§7). If the native library is absent from the
 *   APK, the wiring returns false and submissions halt at Gate 3,
 *   named — fail-closed, never faked.
 * - CEREMONY: [AssentCeremony] behind [GovernedActionBridge] —
 *   translation, Gate 1 origin signature under the operator key, the
 *   Render(a) string shown to the sovereign, AssentCodec assent
 *   signature at confirm, cooling-window-respecting submission through
 *   the concierge, and outcome write-back into the v2 ledger.
 * - VOICE (Gate 7 theta_voice): [VoiceBiometricPort] over
 *   [VoiceTemplateStore]. No template is enrolled and no UBM corpus is
 *   deployed in this build, so the port can never ACCEPT — voice
 *   evidence is absent, honestly, and enrollment is the named next
 *   step. No synthetic-voice bootstrap (the scaffold's demo approach)
 *   is inherited.
 * - [RailAuthorizer.DENY_ALL] on the PolicyEngine: hard-rail lifecycle
 *   calls stay denied until a real authorizer exists. PERMISSIVE is
 *   never used here.
 * - REVOCATION FEED: a deployed signed list if present (a bad file
 *   aborts engine init — fail-closed), else an empty feed under the
 *   persistent revocation key, logged as the posture it is.
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

    /** The encrypted state store the core was loaded from / is saved to. */
    private var stateStorage: StateStorage? = null

    /** Human-readable persistence posture, rendered by the UI. Set in [onCreate]. */
    var persistenceStatus: String = "Persistence: not initialized yet."
        private set

    /**
     * Non-null when the persisted state FAILED verification at startup.
     * Fail-closed: the corrupt store is left exactly as found (never
     * overwritten by [persistCore]), and the UI surfaces this.
     */
    var persistenceError: StateStoreException? = null
        private set

    /**
     * Human-readable governed-engine posture, rendered by the UI.
     * Names exactly what the engine path can and cannot do right now.
     */
    @Volatile
    var engineStatus: String = "Engine: initializing… (core is live; governed engine execution not yet up)"
        private set

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
        core = loadOrInitCore()
        // The Android layer's own collector: device state (screen,
        // battery, network) — see DeviceStateCollector. Registered in
        // the core so its evidence path is governed like every other
        // collector (pause/deny take effect at the boundary). On a
        // restored core it is already registered with its persisted
        // status — registering again would reset a sovereign pause.
        if (core.collectors.get("device_state") == null) {
            core.collectors.register(
                "device_state",
                "Screen, battery/charging, and connectivity state of the phone",
            )
        }
        // Location/motion are real collectors now; their registry state
        // is reconciled with the OS permission grants (a missing grant
        // is NAMED in the standing answers, exactly as the old stubs
        // named their absence — no data is fabricated either way).
        CollectorAvailability.refresh(this)
        thread(name = "gl-v2-engine-init", isDaemon = true) { initEngine() }
    }

    /**
     * Load-or-initialize the core from the encrypted state store.
     * Verification failure is fail-closed and visible: see
     * [persistenceError]. There is no silent reset to empty, ever.
     */
    private fun loadOrInitCore(): GuardianLink {
        val storage = EncryptedStateStorage(this)
        stateStorage = storage
        return try {
            val restored = StateStore.load(storage, STORE_NAME, zkBridge = NativeZkBridge)
            if (restored != null) {
                persistenceStatus =
                    "Core state restored from the encrypted store: " +
                    "${restored.ledger.allEvents().size} events; ledger and audit chains verified."
                Log.i(TAG, persistenceStatus)
                restored
            } else {
                persistenceStatus =
                    "No persisted core state yet; starting fresh. State is written to the encrypted store at save points."
                GuardianLink(zkBridge = NativeZkBridge)
            }
        } catch (e: StateStoreException) {
            persistenceError = e
            persistenceStatus =
                "PERSISTENCE FAILURE (${e.kind}): ${e.message} " +
                "The saved state was NOT loaded and will NOT be overwritten; " +
                "this session runs on an empty in-memory core only."
            Log.e(TAG, persistenceStatus, e)
            GuardianLink(zkBridge = NativeZkBridge)
        }
    }

    /**
     * Save point: serialize the full core state into the encrypted
     * store (one atomic slot write — see EncryptedStateStorage). Called
     * by the UI at conversation-turn completion and on pause. Refuses
     * outright when startup verification failed, so a corrupt store is
     * preserved for inspection instead of being paved over.
     */
    fun persistCore() {
        if (persistenceError != null) return
        val storage = stateStorage ?: return
        try {
            synchronized(coreLock) { StateStore.save(core, storage, STORE_NAME) }
        } catch (t: Throwable) {
            Log.e(TAG, "persisting core state failed", t)
        }
    }

    /**
     * The ceremony's deployment measurements (GateContext trust
     * boundary: these are MEASURED, never asserted constants):
     *  - signal SNR: the request text's content-word ratio scaled to
     *    the engine's dB-ish rail — content words carry the request,
     *    stopwords are the noise floor. Crude and documented.
     *  - context entropy: Shannon entropy (bits) of the distribution
     *    of Delta values across the standing answers — a real measure
     *    of how unsettled the maintained context is, bounded by
     *    construction under Gate 4's 4-bit rail.
     * Either is null when it cannot be measured (empty request / no
     * answers), and null refuses the ceremony rather than fabricating.
     */
    fun ceremonyMeasurements(requestText: String): CeremonyMeasurements {
        val words = requestText.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
        val snr: Double? = if (words.isEmpty()) {
            null
        } else {
            val content = words.count { w -> !STOPWORDS.contains(w) }
            20.0 * content.toDouble() / words.size.toDouble()
        }
        val entropy: Double? = synchronized(coreLock) {
            val answers = core.snapshot()
            if (answers.isEmpty()) {
                null
            } else {
                val counts = answers.groupingBy { it.current.delta }.eachCount()
                val total = answers.size.toDouble()
                counts.values.sumOf { c ->
                    val p = c / total
                    -p * (ln(p) / ln(2.0))
                }
            }
        }
        return CeremonyMeasurements(signalSnr = snr, contextEntropyBits = entropy)
    }

    /** Intent distribution over Intent entries, one-hot at [intent] — the classifier's output shape for a unique parse. */
    fun intentDistribution(intent: Intent): DoubleArray {
        val v = DoubleArray(Intent.entries.size)
        v[intent.ordinal] = 1.0
        return v
    }

    private fun initEngine() {
        try {
            val filesDir = filesDir

            // Engine-owned monotonic clock: crash-safe across restarts.
            clock = DurableMonotonicClock(File(filesDir, "clock.state"))

            // --- Key custody FIRST: no custody, no engine. ---
            val operatorKeyPair: KeyPair
            val revocationKeyPair: KeyPair
            when (val custody = KeyCustodyProvider(this).loadOrCreate()) {
                is KeyCustodyProvider.State.Unavailable -> {
                    engineStatus = "Engine: UNAVAILABLE — key custody failed: ${custody.reason} " +
                        "Governed engine execution is closed; the v0.2 core (ledger, questions, conversation, local actions) is unaffected. " +
                        "Keys are never silently regenerated."
                    Log.e(TAG, engineStatus)
                    return
                }
                is KeyCustodyProvider.State.Ready -> {
                    // Continuity: the loaded keys must be the keys the
                    // harness state was recorded under. First bind
                    // (state predates identity recording) records the
                    // ids into the state and persists them.
                    try {
                        var boundNow = false
                        synchronized(coreLock) {
                            KeyCustody.checkContinuity(core.operatorKeyId, core.revocationAuthorityKeyId, custody.operator, custody.revocation)
                            if (core.operatorKeyId == null && core.revocationAuthorityKeyId == null) {
                                core.operatorKeyId = custody.operator.keyId
                                core.revocationAuthorityKeyId = custody.revocation.keyId
                                boundNow = true
                            }
                        }
                        // Persist a first bind immediately: the state
                        // document is the continuity record future
                        // startups check these keys against.
                        if (boundNow) persistCore()
                    } catch (e: KeyCustodyException) {
                        engineStatus = "Engine: UNAVAILABLE — key continuity check failed (${e.kind}): ${e.message} " +
                            "Governed engine execution is closed rather than adopting different keys silently."
                        Log.e(TAG, engineStatus, e)
                        return
                    }
                    operatorKeyPair = custody.operator.toKeyPair()
                    revocationKeyPair = custody.revocation.toKeyPair()
                    Log.i(
                        TAG,
                        "key custody ready (operator ${custody.operator.keyId.take(12)}…, revocation ${custody.revocation.keyId.take(12)}…, fresh=${custody.createdFresh})",
                    )
                }
            }

            // Gate ledger + concierge audit log, anchored in the sandbox.
            val gatesLedger = MerkleAuditLog()
            val auditLog = MerkleAuditLog { rootHash, index ->
                Log.i(TAG, "audit checkpoint entry=$index root=$rootHash")
            }
            val anchorDir = Paths.get(filesDir.absolutePath, "anchors")
            val anchor = AppendOnlyFileAnchor(anchorDir)

            // --- Revocation feed: signed list if deployed, else empty ---
            val revocationFeed: RevocationFeed = loadRevocationFeed(filesDir, revocationKeyPair)

            // --- Policy ports from the core's own governance state ---
            val ports = CorePolicyPorts { core }

            // --- Production verifiers: real crypto + real policy ports.
            // Gate 3's zkVerifier is the REAL bridge verifier
            // (ZK-GATE3-DESIGN section 7.2): a total wrapper over the
            // Rust glzk verifier, enforcing the section 4.2.3 inner
            // freshness window against the engine's own tau clock.
            // Native absence or any FFI failure returns false there —
            // Gate 3 halts fail-closed; there is no permissive path.
            val zkWiring = ZkVerifierWiring(NativeZkBridge) { clock.nowMs() }
            val verifiers = ProductionVerifiers(
                operatorKey = operatorKeyPair.public,
                revocationAuthorityKey = revocationKeyPair.public,
                revocationFeed = revocationFeed,
                zkVerifier = zkWiring.asLambda(),
                boundaryCheck = ports::boundaryCheck,
                policyCheck = ports::policyCheck,
                divergenceFn = ports::divergence,
                loopCheck = ports::loopCheck,
            )

            // --- Policy engine: hard-rail lifecycle DENIED until a real authorizer exists ---
            val policyEngine = PolicyEngine(authorizer = RailAuthorizer.DENY_ALL)

            // --- Voice port: enrolled template if one exists (none in
            // this build), denying port either way — see VoiceBiometricPort.
            val voiceStore = VoiceTemplateStore(this)
            val enrolledTemplate = voiceStore.load()
            val voicePort = VoiceBiometricPort(enrolledTemplate, delegate = null)

            val gates = NineGates(
                rails = Rails(),
                verifiers = verifiers,
                biometricVerifier = voicePort,
                enrolledVoice = enrolledTemplate ?: inertTemplate(),
                clock = clock,
                ledger = gatesLedger,
            )

            // Seed the engine substrate with the core's projection.
            val initialSubstrate = synchronized(coreLock) { EngineTranslation.substrateFrom(core) }

            val concierge = ConciergeInterface(
                policyEngine = policyEngine,
                auditLog = auditLog,
                gates = gates,
                anchor = anchor,
                initialSubstrate = initialSubstrate,
                // R-U1: permit-consumption WAL in the app sandbox, beside
                // clock.state and the anchors — same durability root.
                permitStore = DurableCapabilityStore(filesDir),
            )

            val ceremony = AssentCeremony(core, concierge, clock, operatorKeyPair.private)
            bridge = GovernedActionBridge(concierge, ceremony, zkWiring)
            engineReady = true
            val zkPosture = if (NativeZkBridge.isAvailable()) {
                "Gate 3 is LIVE: governed submissions carry a Bulletproofs envelope proof (Rust glzk bridge) and halt there if it does not verify."
            } else {
                "Gate 3's native proof library (libglzk) is NOT loaded in this build: governed submissions halt at Gate 3, named, until the .so ships in the APK."
            }
            engineStatus = "Engine: up — persistent key custody, policy ports live from core state, ceremony wired. " +
                zkPosture + " " +
                "Voice assent is not enrolled, so Gate 7 voice evidence is absent until enrollment lands."
            Log.i(TAG, engineStatus)
        } catch (t: Throwable) {
            // Fail-closed overall: without the engine there is no governed
            // execution path at all; the v0.2 core is unaffected.
            engineStatus = "Engine: UNAVAILABLE — init failed: ${t.message}. Governed engine execution is closed; the v0.2 core is unaffected."
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
            Log.w(TAG, "no signed revocation list deployed; revocation feed is empty in this build")
            return object : RevocationFeed {
                override fun revocationsFor(actionId: String): List<Revocation> = emptyList()
            }
        }
        return guardianlink.adapters.SignedRevocationListFeed.load(path, revocationKeys.public)
    }

    /**
     * Inert template satisfying NineGates' non-null port when nothing
     * is enrolled. It can never verify: [VoiceBiometricPort] rejects
     * every ceremony while no template/delegate exists.
     */
    private fun inertTemplate(): VoiceTemplate =
        VoiceTemplate(
            speakerEmbedding = SpeakerEmbedding.Vector(DoubleArray(0)),
            phraseReferences = emptyMap(),
        )

    companion object {
        const val TAG = "GuardianLinkV2"

        /** Logical name of the harness state document in the encrypted store. */
        const val STORE_NAME = "harness-state"

        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with",
            "is", "are", "was", "were", "be", "been", "it", "its", "this", "that",
            "i", "you", "we", "my", "your", "me", "do", "does", "did", "please",
        )
    }
}
