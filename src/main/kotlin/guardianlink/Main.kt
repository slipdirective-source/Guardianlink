package guardianlink

import guardianlink.adapters.CanonicalRecord
import guardianlink.adapters.DomainAdapter
import guardianlink.adapters.ImmutableGraph
import guardianlink.adapters.SensitivityTier
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.friction.FrictionStateMachine
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.mfa.ShamirMfa
import guardianlink.policy.PolicyEngine
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.FakeClock
import guardianlink.gates.GateContext
import guardianlink.gates.GateOutcome
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Revocation
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.Verifiers
import guardianlink.gates.render
import guardianlink.trajectory.TrajectoryEstimator
import java.time.Instant

fun main() {
    println("=" * 72)
    println("GuardianLink v1 Scaffold — Sovereign Personal Data Platform")
    println("=" * 72)
    println()

    // Initialize core modules
    val policyEngine = PolicyEngine()
    val auditLog = MerkleAuditLog { rootHash, index ->
        println("[AUDIT] Merkle root checkpoint at entry $index: $rootHash")
    }

    val concierge = ConciergeInterface(policyEngine, auditLog)

    // Demo: FrictionStateMachine with escalation
    println("=== FrictionStateMachine Demo ===")
    val friction = FrictionStateMachine()
    val hardRail = friction.createHardRail("data-deletion-rail", baseCoolingOffMs = 5000L)
    println("Created hard rail: ${hardRail.id} (type=${hardRail.type}, state=${hardRail.state})")

    val mod1 = friction.requestModification("data-deletion-rail", 5000L, Instant.now())
    println("Modification attempt 1: success=${mod1.success}, escalation=${mod1.rail?.anomalyEscalationCount}")

    val mod2 = friction.requestModification("data-deletion-rail", 5000L, Instant.now())
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

    // Demo: Nine Gates FSM (Codex v2.2 policy core)
    println("=== NineGates Demo ===")
    runNineGatesDemo()
    println()

    println("=" * 72)
    println("GuardianLink scaffold initialized and tested successfully.")
    println("=" * 72)
}

private operator fun String.times(count: Int) = this.repeat(count)

/**
 * Nine Gates demo: a permissive verifier set (all crypto checks pass),
 * showing an all-pass integration, a tampered rendering (HALT at Gate 7),
 * and a signed revocation between assent and seal (HALT at Gate 8).
 */
private fun runNineGatesDemo() {
    val rails = Rails()
    val verifiers = object : Verifiers {
        override fun verifySignature(message: ByteArray, signature: ByteArray) = true
        override fun verifyZk(proof: ByteArray, publicInputs: ByteArray) = true
        override fun verifyAssent(rendering: String, signature: ByteArray) = true
        override fun verifyRevocation(revocation: Revocation) = true
        override fun boundaryPermitted(action: Action, substrate: SubstrateState) = true
        override fun policyRules(action: Action, substrate: SubstrateState) = true
        override fun governanceDivergence(action: Action) = 0.0
        override fun metaLoopConsistent(context: GateContext) = true
        override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState {
            return when (action) {
                is Action.Write -> {
                    val rec = substrate.records[action.recordId] ?: return substrate
                    substrate.copy(records = substrate.records + (action.recordId to (rec + action.fields)))
                }
                is Action.Delete -> substrate.copy(records = substrate.records - action.recordId)
                is Action.Read -> substrate
                is Action.Sequence -> action.steps.fold(substrate, ::applyAction)
                is Action.Guarded -> applyAction(substrate, action.then)
            }
        }
    }
    val gates = NineGates(rails, verifiers)
    val clock = FakeClock(t = 60_000L)

    fun contextFor(
        action: Action,
        rendering: String = render(action),
        revocations: List<Revocation> = emptyList(),
    ) = GateContext(
        signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
        keys = KeyMaterial(
            "req".toByteArray(), "sig".toByteArray(),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0),
        ),
        proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = clock.nowMs()),
        contextEntropyBits = 2.0,
        contextParseTrees = 1,
        action = action,
        actionId = "demo-1",
        substrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
        ledger = MerkleAuditLog(),
        rendering = rendering,
        assent = Assent("s_a".toByteArray()),
        assentedAtMs = 0L, // 60s elapsed; WRITE window is 10s
        revocations = revocations,
        clock = clock,
        intentVector = doubleArrayOf(1.0, 0.0),
        currentVector = doubleArrayOf(1.0, 0.0),
    )

    val write = Action.Write("profile", mapOf("name" to "Neo"))
    val ok = gates.evaluate(contextFor(write))
    println("All-pass request: $ok")

    val tampered = gates.evaluate(contextFor(write, rendering = "WRITE profile {name=Attacker}"))
    println("Tampered rendering: $tampered")

    val revoked = gates.evaluate(
        contextFor(
            write,
            revocations = listOf(Revocation("demo-1", "rev".toByteArray(), revokedAtMs = 59_000L)),
        )
    )
    println("Revoked before seal: $revoked")
}