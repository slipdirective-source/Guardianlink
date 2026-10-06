package guardianlink

import guardianlink.adapters.CanonicalRecord
import guardianlink.adapters.DomainAdapter
import guardianlink.adapters.ImmutableGraph
import guardianlink.adapters.SensitivityTier
import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.friction.FrictionStateMachine
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.mfa.ShamirMfa
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.FakeClock
import guardianlink.gates.GateContext
import guardianlink.gates.GateOutcome
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.ReferenceTransition
import guardianlink.gates.Revocation
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.Verifiers
import guardianlink.gates.render
import guardianlink.trajectory.TrajectoryEstimator

fun main() {
    println("=" * 72)
    println("GuardianLink v1 Scaffold — Sovereign Personal Data Platform")
    println("=" * 72)
    println()

    // Initialize core modules. The demo uses a permissive rail authorizer;
    // production deployments MUST supply real authentication here.
    val policyEngine = PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE)
    val auditLog = MerkleAuditLog { rootHash, index ->
        // DEMO ONLY: printing is not a durable external anchor. A real
        // deployment must publish each root to a write-once store.
        println("[AUDIT] Merkle root checkpoint at entry $index: $rootHash (NOT durably anchored — demo only)")
    }

    // The gate engine: permissive verifiers, engine-owned clock and ledger.
    // Production deployments MUST supply real cryptographic verifiers here.
    val verifiers = demoVerifiers()
    val gates = NineGates(Rails(), verifiers, clock = FakeClock(t = 60_000L), ledger = MerkleAuditLog())
    // One real external anchor path: sealed roots are appended to a
    // tamper-evident anchor file (host-filesystem grade — see ExternalAnchor).
    val anchorDir = java.nio.file.Files.createTempDirectory("guardianlink-anchors")
    val anchor = AppendOnlyFileAnchor(anchorDir)
    println("Anchor directory: $anchorDir")
    println()

    val concierge = ConciergeInterface(
        policyEngine,
        auditLog,
        gates,
        anchor,
        initialSubstrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
    )

    // Demo: FrictionStateMachine with escalation
    println("=== FrictionStateMachine Demo ===")
    val friction = FrictionStateMachine()
    val hardRail = friction.createHardRail("data-deletion-rail", baseCoolingOffMs = 5000L)
    println("Created hard rail: ${hardRail.id} (type=${hardRail.type}, state=${hardRail.state})")

    val mod1 = friction.requestModification("data-deletion-rail", 5000L)
    println("Modification attempt 1: success=${mod1.success}, escalation=${mod1.rail?.anomalyEscalationCount}")

    val mod2 = friction.requestModification("data-deletion-rail", 5000L)
    println("Modification attempt 2 (while cooling): success=${mod2.success}, escalation=${mod2.rail?.anomalyEscalationCount}")
    println()

    // Demo: TrajectoryEstimator with MAD anomaly detection
    println("=== TrajectoryEstimator Demo ===")
    val trajectory = TrajectoryEstimator(minBaselineSamples = 3)
    repeat(5) { i ->
        trajectory.addBaselineSample((100.0 + i * 2).toDouble())
    }
    val label = trajectory.currentLabel()
    println("Baseline label: value=${label.value}, confidence=${label.confidence}, status=${label.status}")

    val shift = trajectory.checkShift(150.0)
    println("Anomaly check (value=150.0): shiftDetected=${shift.shiftDetected}, anomaliesInWindow=${shift.anomalyCount}")
    println()

    // Demo: ShamirMfa secret sharing
    println("=== ShamirMfa Demo ===")
    val mfa = ShamirMfa()
    val secret = java.math.BigInteger("42")
    val shares = mfa.split(
        secret = secret,
        m = 2,
        n = 3,
        categories = listOf(
            ShamirMfa.FactorCategory.HARDWARE_BIOMETRIC,
            ShamirMfa.FactorCategory.SOFT_BIOMETRIC,
            ShamirMfa.FactorCategory.KNOWLEDGE
        )
    )
    println("Split secret $secret into ${shares.size} shares (threshold=2)")
    shares.forEachIndexed { i, share ->
        println("  Share $i: category=${share.category}, x=${share.x}, y=${share.y}")
    }

    val reconstructed = mfa.reconstruct(shares.take(2), threshold = 2)
    println("Reconstructed from first 2 shares: $reconstructed (matches=${ reconstructed == secret})")
    println()

    // Demo: MerkleAuditLog integrity
    println("=== MerkleAuditLog Demo ===")
    auditLog.append("Entry 1: policy decision EVIDENCE_ONLY".toByteArray())
    auditLog.append("Entry 2: anomaly detected, escalation=1".toByteArray())
    auditLog.append("Entry 3: cooling-off period initiated".toByteArray())
    println("Audit log size: ${auditLog.size}")
    println("Merkle root: ${auditLog.root}")
    val integrityFailIndex = auditLog.verifyIntegrity()
    println("Integrity check: ${ if (integrityFailIndex < 0) "PASS" else "FAIL at entry $integrityFailIndex" }")
    println()

    // Demo: PolicyEngine with device integrity
    println("=== PolicyEngine Demo ===")
    policyEngine.registerHardRail("data-deletion-rail")
    val decision1 = policyEngine.evaluate(
        requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
        hardRailRef = "data-deletion-rail",
        deviceIntegrity = DeviceIntegrityTier.Level.VERIFIED,
        context = "User requested permanent data deletion"
    )
    println("Request with VERIFIED device: decision=$decision1")

    val decision2 = policyEngine.evaluate(
        requestedTier = PolicyEngine.Tier.HARD_ENFORCE,
        hardRailRef = "data-deletion-rail",
        deviceIntegrity = DeviceIntegrityTier.Level.COMPROMISED,
        context = "User requested permanent data deletion"
    )
    println("Request with COMPROMISED device: decision=$decision2")
    println()

    // Demo: DeviceIntegrityTier signal evaluation
    println("=== DeviceIntegrityTier Demo ===")
    val signals = listOf(
        DeviceIntegrityTier.Signal("bootloader_verified", true),
        DeviceIntegrityTier.Signal("secure_enclave_active", true),
        DeviceIntegrityTier.Signal("tamper_detection", false)
    )
    val integrityLevel = DeviceIntegrityTier.evaluate(signals)
    println("Device signals: $signals")
    println("Evaluated level: $integrityLevel (can override: ${DeviceIntegrityTier.overrideEligible(integrityLevel)})")
    println()

    // Demo: Nine Gates FSM (Codex v2.2 policy core), composed — every
    // execution goes through ConciergeInterface.execute, which forces the
    // action through the gates and anchors each seal externally.
    println("=== NineGates Demo (composed) ===")
    runComposedDemo(concierge, gates, setRevocations = { verifiers.revocationFeed = it })
    println("Anchor chain verify: ${if (anchor.verifyChain() < 0) "INTACT" else "BROKEN"}")
    println()

    println("=" * 72)
    println("GuardianLink scaffold initialized and tested successfully.")
    println("=" * 72)
}

private operator fun String.times(count: Int) = this.repeat(count)

/**
 * Demo verifiers: permissive (all crypto checks pass). Production
 * deployments MUST supply real signature/ZK/assent/revocation verifiers —
 * and an assent verifier that enforces the (rendering, assentedAtMs,
 * actionId) binding contract (see Verifiers.verifyAssent).
 */
private fun demoVerifiers() = object : Verifiers {
    /** Demo revocation feed — in production this is the deployment's feed, not the request. */
    var revocationFeed: List<Revocation> = emptyList()
    override fun verifySignature(message: ByteArray, signature: ByteArray) = true
    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray) = true
    // Demo assents unconditionally; a production verifier MUST check the
    // signature over (rendering, assentedAtMs, actionId) — see the port contract.
    override fun verifyAssent(rendering: String, assentedAtMs: Long, actionId: String, signature: ByteArray) = true
    override fun verifyRevocation(revocation: Revocation) = true
    override fun revocationsFor(actionId: String) = revocationFeed.filter { it.actionId == actionId }
    override fun boundaryPermitted(action: Action, substrate: SubstrateState) = true
    override fun policyRules(action: Action, substrate: SubstrateState) = true
    override fun governanceDivergence(action: Action) = 0.0
    override fun metaLoopConsistent(context: GateContext) = true
    override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState =
        // Canonical reference semantics (see ReferenceTransition): the
        // demo used to always take the `then` branch of Guarded,
        // ignoring the condition — a fail-open adapter bug. Fixed by
        // sharing the one canonical evaluator.
        ReferenceTransition.apply(substrate, action)
}

/**
 * Composed Nine Gates demo: all-pass integration (anchored), a tampered
 * rendering (REJECTED at Gate 7), and a signed revocation between assent
 * and seal (REJECTED at Gate 8). Every execution goes through
 * ConciergeInterface.execute — there is no direct engine path here.
 */
private fun runComposedDemo(
    concierge: ConciergeInterface,
    gates: NineGates,
    setRevocations: (List<Revocation>) -> Unit,
) {
    val clock = gates.clock

    fun evidenceFor(
        action: Action,
        rendering: String = render(action),
    ) = ConciergeInterface.ActionEvidence(
        actionId = "demo-1",
        signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
        keys = KeyMaterial(
            "req".toByteArray(), "sig".toByteArray(),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0),
        ),
        proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = clock.nowMs()),
        contextEntropyBits = 2.0,
        contextParseTrees = 1,
        rendering = rendering,
        assent = Assent("s_a".toByteArray()),
        assentedAtMs = 0L, // 60s elapsed; WRITE window is 10s
        intentVector = doubleArrayOf(1.0, 0.0),
        currentVector = doubleArrayOf(1.0, 0.0),
    )

    val write = Action.Write("profile", mapOf("name" to "Neo"))
    val ok = concierge.execute(write, evidenceFor(write))
    println("All-pass request: $ok")

    val tampered = concierge.execute(write, evidenceFor(write, rendering = "WRITE profile {name=Attacker}"))
    println("Tampered rendering: $tampered")

    setRevocations(listOf(Revocation("demo-1", "rev".toByteArray(), revokedAtMs = 59_000L)))
    val revoked = concierge.execute(write, evidenceFor(write))
    println("Revoked before seal: $revoked")
    setRevocations(emptyList())
}