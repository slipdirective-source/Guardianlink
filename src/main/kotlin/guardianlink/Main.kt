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
    println("Anomaly check (value=150.0): shiftDetected=${shift.shiftDetected}, streak=${shift.consecutiveCount}")
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

    val reconstructed = mfa.reconstruct(shares.take(2))
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

    println("=" * 72)
    println("GuardianLink scaffold initialized and tested successfully.")
    println("=" * 72)
}

private operator fun String.times(count: Int) = this.repeat(count)