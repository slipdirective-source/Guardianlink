package guardianlink.core

import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.CheckResult
import guardianlink.capability.EpochCapability
import guardianlink.capability.EpochEffect
import guardianlink.capability.MutationCapability
import guardianlink.capability.sha256Hex
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 2 falsification battery for the capability mechanism
 * (frozen spec v2.1: two classes, predicate-not-possession, brutal
 * expiry, no self-renewal, autonomous attenuation, state deps,
 * revocation ≠ expiry).
 *
 * Red-team target: ∃ path, m, t_c: Committed(m, t_c) ∧ m ∉ A(t_c).
 * Every test below is an attempted violation. A passing suite means
 * every attempt failed — the mechanism held.
 */
class CapabilityFalsificationTest {

    // ---------- helpers ----------

    private var nowMs = 1_000_000L
    private fun issuer() = CapabilityIssuer(clockMs = { nowMs })

    private fun refusedReason(r: CheckResult): String {
        assertIs<CheckResult.Refused>(r)
        return (r as CheckResult.Refused).reason
    }

    /** ConciergeInterface with stub biometric (voice orthogonal here) and injectable issuer. */
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

    /**
     * An issuer whose clock is synced to the gates' FakeClock(60_000L).
     * The commit point binds notBeforeMs to the gates' t_c; a mismatched
     * issuer clock makes fresh capabilities "not yet valid".
     */
    private fun syncedIssuer() = CapabilityIssuer(clockMs = { 60_000L })

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

    // ---------- MutationCapability falsification ----------

    @Test
    fun `forged capability never minted refuses`() {
        val iss = issuer()
        val forged = MutationCapability(
            id = UUID.randomUUID().toString(),
            mutationHash = sha256Hex("rendering"),
            notBeforeMs = nowMs,
            notAfterMs = nowMs + 60_000L,
            stateDeps = emptyMap(),
            issuerId = "engine-admission-boundary",
        )
        val r = iss.checkAndConsumeMutation(forged, "rendering", nowMs)
        assertTrue(
            refusedReason(r).contains("not minted"),
            "forged capability was accepted: $r"
        )
    }

    @Test
    fun `expired mutation capability refuses even by 1ms`() {
        val iss = issuer()
        val cap = iss.mintMutation(sha256Hex("rendering"), ttlMs = 1_000L)
        nowMs += 1_001L // 1ms past brutal expiry
        val r = iss.checkAndConsumeMutation(cap, "rendering", nowMs)
        assertTrue(
            refusedReason(r).contains("expired"),
            "expired capability was accepted: $r"
        )
    }

    @Test
    fun `mutation capability for a different rendering refuses`() {
        val iss = issuer()
        val cap = iss.mintMutation(sha256Hex("rendering-a"))
        val r = iss.checkAndConsumeMutation(cap, "rendering-b", nowMs)
        assertTrue(
            refusedReason(r).contains("hash mismatch"),
            "capability bound to another object was accepted: $r"
        )
    }

    @Test
    fun `consumed mutation capability refuses replay`() {
        val iss = issuer()
        val cap = iss.mintMutation(sha256Hex("rendering"))
        assertIs<CheckResult.Ok>(iss.checkAndConsumeMutation(cap, "rendering", nowMs))
        val replay = iss.checkAndConsumeMutation(cap, "rendering", nowMs)
        assertTrue(
            refusedReason(replay).contains("already consumed"),
            "replayed capability was accepted: $replay"
        )
    }

    @Test
    fun `revoked mutation capability refuses`() {
        val iss = issuer()
        val cap = iss.mintMutation(sha256Hex("rendering"))
        iss.revoke(cap.id)
        val r = iss.checkAndConsumeMutation(cap, "rendering", nowMs)
        assertTrue(
            refusedReason(r).contains("revoked"),
            "revoked capability was accepted: $r"
        )
    }

    @Test
    fun `state dep change voids mutation capability`() {
        val iss = issuer()
        // Bind a dep, then change it: the admission premises no longer hold.
        iss.securityState.noteChange("voiceprint")
        val cap = iss.mintMutation(sha256Hex("rendering"))
        iss.securityState.noteChange("voiceprint") // enrollment replaced
        val r = iss.checkAndConsumeMutation(cap, "rendering", nowMs)
        assertTrue(
            refusedReason(r).contains("void"),
            "capability survived a security-relevant state change: $r"
        )
    }

    @Test
    fun `tampered fields refuse even with a live id`() {
        val iss = issuer()
        val cap = iss.mintMutation(sha256Hex("rendering"), ttlMs = 1_000L)
        // Attacker takes a live id and extends the lifetime in the fields.
        val tampered = cap.copy(notAfterMs = cap.notAfterMs + 3_600_000L)
        val r = iss.checkAndConsumeMutation(tampered, "rendering", nowMs)
        assertTrue(
            refusedReason(r).contains("differ from issuance record"),
            "tampered capability was accepted: $r"
        )
    }

    @Test
    fun `execute seals through the capability commit point`() {
        val concierge = conciergeWith(syncedIssuer())
        val action = Action.Write("profile", mapOf("cap" to "1"))
        val result = concierge.execute(action, evidenceFor(action, "cap-1"))
        assertIs<ConciergeInterface.ExecutionResult.Executed>(
            result, "happy path broke under the capability mechanism: $result"
        )
    }

    @Test
    fun `execute refuses when the capability check refuses`() {
        // The commit point is gated on the capability: if the check fails,
        // nothing commits — even though the gates admitted.
        val rigged = object : CapabilityIssuer(clockMs = { 60_000L }) {
            override fun checkAndConsumeMutation(
                cap: MutationCapability, rendering: String, t_c: Long,
            ): CheckResult = CheckResult.Refused("rigged refusal")
        }
        val concierge = conciergeWith(rigged)
        val action = Action.Write("profile", mapOf("cap" to "1"))
        val result = concierge.execute(action, evidenceFor(action, "cap-rigged"))
        assertIs<ConciergeInterface.ExecutionResult.Rejected>(result)
        assertTrue(
            (result as ConciergeInterface.ExecutionResult.Rejected)
                .reason.contains("capability refused"),
            "wrong refusal reason: $result"
        )
        // And nothing committed.
        assertNull(concierge.currentSubstrate().records["profile"]?.get("cap"))
    }

    // ---------- EpochCapability falsification ----------

    private fun epochIssuer() = issuer()
    private fun tunEpoch(iss: CapabilityIssuer) = iss.mintEpoch(
        predicate = CapabilityPredicate(
            targetPrefix = "net/",
            allowedTypes = setOf("tun-packet"),
        ),
        maxMutations = 100L,
        maxBytes = 1_000_000L,
    )
    private fun packet(target: String, bytes: Long = 100L) =
        EpochEffect(target = target, type = "tun-packet", bytes = bytes)

    @Test
    fun `epoch effect within predicate commits`() {
        val iss = epochIssuer()
        val cap = tunEpoch(iss)
        val r = iss.checkAndRecordEpoch(cap, packet("net/10.0.0.1"), nowMs)
        assertIs<CheckResult.Ok>(r, "in-predicate effect refused: $r")
        assertEquals(1L to 100L, iss.usageOf(cap.id))
    }

    @Test
    fun `epoch effect outside target prefix refuses`() {
        val iss = epochIssuer()
        val cap = tunEpoch(iss)
        val r = iss.checkAndRecordEpoch(cap, packet("disk/secret"), nowMs)
        assertTrue(
            refusedReason(r).contains("predicate"),
            "out-of-prefix effect was made valid: $r"
        )
    }

    @Test
    fun `epoch effect of disallowed type refuses`() {
        val iss = epochIssuer()
        val cap = tunEpoch(iss)
        val r = iss.checkAndRecordEpoch(
            cap, EpochEffect("net/10.0.0.1", "raw-socket", 100L), nowMs
        )
        assertTrue(
            refusedReason(r).contains("predicate"),
            "disallowed type was made valid: $r"
        )
    }

    @Test
    fun `epoch mutation bound is enforced without overspend`() {
        val iss = epochIssuer()
        val cap = iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = 2L, maxBytes = null,
        )
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(cap, packet("net/1"), nowMs))
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(cap, packet("net/2"), nowMs))
        val third = iss.checkAndRecordEpoch(cap, packet("net/3"), nowMs)
        assertTrue(
            refusedReason(third).contains("mutation bound"),
            "bound exceeded: $third"
        )
        assertEquals(2L, iss.usageOf(cap.id)?.first)
    }

    @Test
    fun `epoch byte bound is enforced`() {
        val iss = epochIssuer()
        val cap = iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = null, maxBytes = 100L,
        )
        assertIs<CheckResult.Ok>(
            iss.checkAndRecordEpoch(cap, packet("net/1", bytes = 60L), nowMs)
        )
        val over = iss.checkAndRecordEpoch(cap, packet("net/2", bytes = 50L), nowMs)
        assertTrue(
            refusedReason(over).contains("byte bound"),
            "byte bound exceeded: $over"
        )
        assertEquals(60L, iss.usageOf(cap.id)?.second)
    }

    @Test
    fun `expired epoch refuses`() {
        val iss = epochIssuer()
        val cap = iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = null, maxBytes = null, ttlMs = 1_000L,
        )
        nowMs += 1_001L
        val r = iss.checkAndRecordEpoch(cap, packet("net/1"), nowMs)
        assertTrue(refusedReason(r).contains("expired"), "dead epoch accepted: $r")
    }

    @Test
    fun `revoked epoch capability refuses while ttl live`() {
        val iss = epochIssuer()
        val cap = tunEpoch(iss)
        iss.revoke(cap.id) // TTL still live: revocation ≠ expiry
        val r = iss.checkAndRecordEpoch(cap, packet("net/1"), nowMs)
        assertTrue(refusedReason(r).contains("revoked"), "revoked epoch accepted: $r")
    }

    @Test
    fun `revoked epoch kills every capability in it`() {
        val iss = epochIssuer()
        val cap1 = tunEpoch(iss)
        val cap2 = tunEpoch(iss)
        iss.revokeEpoch(cap1.epoch)
        assertEquals(cap1.epoch, cap2.epoch - 1)
        // cap2 is a different epoch: still live.
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(cap2, packet("net/1"), nowMs))
        // Mint another in cap1's epoch is impossible (epochs are monotonic),
        // so kill cap2's epoch too and verify both die.
        iss.revokeEpoch(cap2.epoch)
        val r = iss.checkAndRecordEpoch(cap2, packet("net/2"), nowMs)
        assertTrue(refusedReason(r).contains("epoch revoked"), "epoch kill failed: $r")
    }

    @Test
    fun `attenuation cannot widen the predicate`() {
        val iss = epochIssuer()
        val parent = tunEpoch(iss)
        // Wider prefix.
        assertNull(
            parent.attenuateTo(CapabilityPredicate(null, setOf("tun-packet"))),
            "attenuation widened the target prefix"
        )
        // Wider types.
        assertNull(
            parent.attenuateTo(
                CapabilityPredicate("net/", setOf("tun-packet", "raw-socket"))
            ),
            "attenuation widened the allowed types"
        )
        // Equal is not wider: allowed.
        assertTrue(
            parent.attenuateTo(CapabilityPredicate("net/", setOf("tun-packet"))) != null
        )
    }

    @Test
    fun `attenuation cannot extend lifetime`() {
        val iss = epochIssuer()
        val parent = tunEpoch(iss)
        assertNull(
            parent.attenuateTo(
                CapabilityPredicate("net/10.0.0.0/8", setOf("tun-packet")),
                notAfterMs = parent.notAfterMs + 1L,
            ),
            "attenuation extended lifetime: self-renewal"
        )
        // Shorter lifetime is fine.
        assertTrue(
            parent.attenuateTo(
                CapabilityPredicate("net/10.0.0.0/8", setOf("tun-packet")),
                notAfterMs = parent.notAfterMs - 1L,
            ) != null
        )
    }

    @Test
    fun `attenuation cannot raise bounds`() {
        val iss = epochIssuer()
        val parent = tunEpoch(iss) // maxMutations=100
        assertNull(
            parent.attenuateTo(
                CapabilityPredicate("net/", setOf("tun-packet")),
                maxMutations = 101L,
            ),
            "attenuation raised the mutation bound"
        )
        assertNull(
            parent.attenuateTo(
                CapabilityPredicate("net/", setOf("tun-packet")),
                maxMutations = null, // unbounded is wider than 100
            ),
            "attenuation dropped the mutation bound"
        )
    }

    @Test
    fun `attenuated child enforces the narrowed predicate`() {
        val iss = epochIssuer()
        val parent = tunEpoch(iss)
        // Note: predicates are STRING prefixes, not CIDR. "net/10." is a
        // string-narrowing of "net/".
        val child = parent.attenuateTo(
            CapabilityPredicate("net/10.", setOf("tun-packet"))
        ) ?: error("valid attenuation returned null")
        // Inside the child's narrowed envelope: commits.
        assertIs<CheckResult.Ok>(
            iss.checkAndRecordEpoch(child, packet("net/10.0.0.1"), nowMs)
        )
        // Inside the parent's envelope but outside the child's: refused.
        val r = iss.checkAndRecordEpoch(child, packet("net/192.168.1.1"), nowMs)
        assertTrue(
            refusedReason(r).contains("predicate"),
            "child authorized outside its narrowed predicate: $r"
        )
    }

    @Test
    fun `attenuated children share the parent usage envelope`() {
        val iss = epochIssuer()
        val parent = iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = 3L, maxBytes = null,
        )
        val child1 = parent.attenuateTo(
            CapabilityPredicate("net/", setOf("tun-packet")), maxMutations = 2L
        ) ?: error("valid attenuation returned null")
        val child2 = parent.attenuateTo(
            CapabilityPredicate("net/", setOf("tun-packet")), maxMutations = 2L
        ) ?: error("valid attenuation returned null")
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(child1, packet("net/1"), nowMs))
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(child1, packet("net/2"), nowMs))
        // child1's own bound now exhausted...
        assertTrue(
            refusedReason(iss.checkAndRecordEpoch(child1, packet("net/3"), nowMs))
                .contains("bound")
        )
        // ...but child2 still has room within the parent's envelope (3 total).
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(child2, packet("net/4"), nowMs))
        // Parent envelope (3) now exhausted: no further commits via any child.
        val over = iss.checkAndRecordEpoch(child2, packet("net/5"), nowMs)
        assertTrue(
            refusedReason(over).contains("parent"),
            "attenuation escaped the parent envelope: $over"
        )
    }

    @Test
    fun `concurrent epoch commits never overspend`() {
        val iss = epochIssuer()
        val cap = iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = 8L, maxBytes = null,
        )
        val n = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val oks = AtomicInteger(0)
        repeat(n) { i ->
            thread {
                start.await()
                try {
                    if (iss.checkAndRecordEpoch(cap, packet("net/$i"), nowMs)
                        is CheckResult.Ok
                    ) oks.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "epoch commits did not finish")
        assertEquals(8, oks.get(), "overspend or underspend under concurrency")
        assertEquals(8L, iss.usageOf(cap.id)?.first)
    }

    @Test
    fun `no self-renewal operation exists`() {
        // Constitutional (§4): no function in the holder's power derives
        // extended lifetime. Fail the build if one appears.
        val forbidden = setOf("renew", "extend", "extendLifetime", "renewEpoch")
        val issuerMethods = CapabilityIssuer::class.java.methods.map { it.name }
        val epochMethods = EpochCapability::class.java.methods.map { it.name }
        val found = (issuerMethods + epochMethods).filter { it in forbidden }
        assertTrue(found.isEmpty(), "self-renewal operation exists: $found")
        // notAfterMs must be immutable (no setter).
        assertTrue(
            EpochCapability::class.java.methods.none {
                it.name == "setNotAfterMs"
            },
            "EpochCapability.notAfterMs is mutable"
        )
        assertTrue(
            MutationCapability::class.java.methods.none {
                it.name == "setNotAfterMs"
            },
            "MutationCapability.notAfterMs is mutable"
        )
    }

    // C-4 characterization: epoch-local reset is intentional current behavior.
    // A newly issued epoch receives a fresh local budget. This test pins the
    // exact contract: maxMutations constrains an epoch, NOT a continuing
    // authority lineage. If a future lineage mechanism changes this behavior,
    // this test must be deliberately replaced, not silently altered.

    @Test
    fun `c4 new epoch receives fresh local budget (characterization)`() {
        val iss = epochIssuer()
        val predicate = CapabilityPredicate("net/", setOf("tun-packet"))

        // First epoch: exhaust its budget of 2.
        val epoch1 = iss.mintEpoch(
            predicate = predicate,
            maxMutations = 2L, maxBytes = null,
        )
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epoch1, packet("net/1"), nowMs))
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epoch1, packet("net/2"), nowMs))
        val third = iss.checkAndRecordEpoch(epoch1, packet("net/3"), nowMs)
        assertTrue(refusedReason(third).contains("mutation bound"))

        // Revoke the first epoch so the second does not overlap (C-4).
        iss.revokeEpoch(epoch1.epoch)

        // Second epoch with same predicate: fresh budget of 2.
        // This is INTENTIONAL per C-4: epoch-local accounting.
        // It does NOT imply lineage safety; see c4-decision-cross-epoch.md.
        val epoch2 = iss.mintEpoch(
            predicate = predicate,
            maxMutations = 2L, maxBytes = null,
        )
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epoch2, packet("net/1"), nowMs))
        assertIs<CheckResult.Ok>(iss.checkAndRecordEpoch(epoch2, packet("net/2"), nowMs))
        val epoch2Third = iss.checkAndRecordEpoch(epoch2, packet("net/3"), nowMs)
        assertTrue(
            refusedReason(epoch2Third).contains("mutation bound"),
            "second epoch enforces its own local bound: $epoch2Third"
        )
    }

    @Test
    fun `c4 overlapping live epoch without explicit authorization refuses`() {
        val iss = epochIssuer()
        val predicate = CapabilityPredicate("net/", setOf("tun-packet"))

        iss.mintEpoch(
            predicate = predicate,
            maxMutations = 2L, maxBytes = null,
        )

        // Second epoch with identical predicate while first is live: must refuse.
        // Two live epochs over the same scope would provide additive budgets.
        val ex = assertThrows<IllegalArgumentException> {
            iss.mintEpoch(
                predicate = predicate,
                maxMutations = 2L, maxBytes = null,
            )
        }
        assertTrue(
            ex.message!!.contains("overlapping live epoch"),
            "refusal names the C-4 overlap: ${ex.message}"
        )
    }

    @Test
    fun `c4 overlapping epoch with explicit additive authorization succeeds`() {
        val iss = epochIssuer()
        val predicate = CapabilityPredicate("net/", setOf("tun-packet"))

        iss.mintEpoch(
            predicate = predicate,
            maxMutations = 2L, maxBytes = null,
        )

        // With allowOverlap=true (explicit sovereign authorization), succeeds.
        val second = iss.mintEpoch(
            predicate = predicate,
            maxMutations = 2L, maxBytes = null,
            allowOverlap = true,
        )
        assertNotNull(second.id)
    }

    @Test
    fun `c4 non-overlapping predicate does not trigger overlap refusal`() {
        val iss = epochIssuer()

        iss.mintEpoch(
            predicate = CapabilityPredicate("net/", setOf("tun-packet")),
            maxMutations = 2L, maxBytes = null,
        )

        // Different predicate: no overlap, should succeed.
        val other = iss.mintEpoch(
            predicate = CapabilityPredicate("profile/", setOf("write")),
            maxMutations = 2L, maxBytes = null,
        )
        assertNotNull(other.id)
    }
}
