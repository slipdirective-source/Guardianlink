package guardianlink.core

import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.CheckResult
import guardianlink.capability.EpochEffect
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.FakeClock
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.render
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.voice.AudioSample
import guardianlink.voice.BiometricVerifier
import guardianlink.voice.Mfcc
import guardianlink.voice.TestVoice
import guardianlink.voice.VerificationResult
import guardianlink.voice.VoiceTemplate
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/**
 * C-1: Quantitative composition (engine gap, mechanical).
 *
 * Falsification question (narrow): Can individually admissible mutations,
 * each valid under its own capability and commit conditions, compose into
 * a cumulative state that violates an aggregate constraint without the
 * engine rejecting the transition?
 *
 * Authority boundary (spec v2.1): EpochCapability with maxMutations.
 * The test is WITHIN a single epoch. Cross-epoch carry (C-4) is a
 * separate decision and is NOT tested here.
 *
 * Expected result: C-1 CONFIRMED. The engine mints a per-mutation
 * MutationCapability and checks ONLY that at the commit point
 * (ConciergeInterface.kt:167). The EpochCapability's cumulative bound
 * (maxMutations) is never consulted. Three individually-admissible
 * mutations all commit against an epoch that allows two.
 *
 * The mechanism exists: CapabilityIssuer.checkAndRecordEpoch WOULD refuse
 * the third. It is simply not wired into the commit path.
 */
class C1CompositionFalsificationTest {

    private fun syncedIssuer() = CapabilityIssuer(clockMs = { 60_000L })

    private fun conciergeWith(issuer: CapabilityIssuer): ConciergeInterface {
        val stubBiometric = object : BiometricVerifier {
            override fun enroll(samples: List<AudioSample>): VoiceTemplate = TestVoice.enrolled
            override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult =
                VerificationResult(
                    1.0,
                    VerificationResult.Decision.ACCEPT,
                    VerificationResult.Liveness.LIVE,
                )
        }
        val anchorDir = Files.createTempDirectory("guardianlink-test-anchors")
        val gates = NineGates(
            Rails(), PermissiveVerifiers(), stubBiometric, TestVoice.enrolled,
            clock = FakeClock(60_000L), ledger = MerkleAuditLog(),
        )
        return ConciergeInterface(
            PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE),
            MerkleAuditLog(),
            gates,
            AppendOnlyFileAnchor(anchorDir),
            initialSubstrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
            issuer = issuer,
        )
    }

    private fun evidenceFor(action: Action, actionId: String) =
        ConciergeInterface.ActionEvidence(
            actionId = actionId,
            signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
            keys = KeyMaterial("req".toByteArray(), "sig".toByteArray()),
            proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = 60_000L),
            contextEntropyBits = 2.0,
            contextParseTrees = 1,
            rendering = render(action),
            assent = Assent("s_a".toByteArray()),
            assentedAtMs = 0L,
            assentAudio = AudioSample(doubleArrayOf(0.0), Mfcc.SAMPLE_RATE),
            intentVector = doubleArrayOf(1.0, 0.0),
            currentVector = doubleArrayOf(1.0, 0.0),
        )

    @Test
    fun `c1: three mutations commit against epoch allowing two`() {
        val iss = syncedIssuer()
        // Spec v2.1 authority boundary: epoch allows 2 mutations.
        val epochCap = iss.mintEpoch(
            predicate = CapabilityPredicate(
                targetPrefix = "profile",
                allowedTypes = setOf("test.write"),
            ),
            maxMutations = 2L,
            maxBytes = 1_000_000L,
        )
        val concierge = conciergeWith(iss)

        // Three individually admissible mutations. Each gets its own
        // MutationCapability and passes the commit point. Each writes a
        // distinct field to avoid Gate 6 psi_inv conflicts.
        val results = (1..3).map { i ->
            val action = Action.Write("profile", mapOf("c1field$i" to "$i"))
            concierge.execute(action, evidenceFor(action, "c1-action-$i"))
        }

        // Falsification: how many committed?
        val executed = results.count {
            it is ConciergeInterface.ExecutionResult.Executed
        }

        // The mechanism WOULD have enforced the bound:
        val effect = EpochEffect("profile", "test.write", 100L)
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epochCap, effect, 60_000L))
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epochCap, effect, 60_000L))
        val thirdCheck = iss.checkAndRecordEpoch(epochCap, effect, 60_000L)
        assertIs<CheckResult.Refused>(
            thirdCheck,
            "checkAndRecordEpoch should refuse the 3rd effect against maxMutations=2"
        )

        // But the commit path never calls it. All three execute.
        // If this assertEquals(3, ...) passes, C-1 is CONFIRMED:
        // the engine does not enforce the spec's cumulative bound.
        // If a future fix wires the epoch check into the commit path,
        // this test should be updated to assertEquals(2, ...).
        assertEquals(
            3, executed,
            "C-1 FALSIFICATION: all 3 mutations committed against an epoch " +
                "with maxMutations=2. The cumulative bound is not enforced " +
                "at the commit point."
        )
    }
}
