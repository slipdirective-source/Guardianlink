package guardianlink.core

import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.MerkleAuditLog
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
import guardianlink.integrity.DeviceIntegrityTier
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 1 negative controls for the sole-authority-path.
 *
 * The commit boundary serializes callers internally
 * ([ConciergeInterface.commitLock]): callers cannot reach the commit
 * relation without serialization. These tests fail if serialization
 * is ever moved back out to embedders.
 */
class CommitSerializationTest {

    @Test
    fun `sequential executes all seal`() {
        val rig = testRig()
        repeat(8) { i ->
            // Write to the existing "profile" record: Gate 6 psi_inv
            // forbids creating records (the AST has no create node).
            val action = Action.Write("profile", mapOf("seq-$i" to "$i"))
            val result = rig.concierge.execute(
                action, testEvidence(action, rig.voice, actionId = "seq-$i")
            )
            assertTrue(
                result is ConciergeInterface.ExecutionResult.Executed,
                "sequential execute $i did not seal: $result"
            )
        }
    }

    @Test
    fun `execute holds the commit lock for its full duration`() {
        // White-box: proves serialization is enforced BY the boundary,
        // not by callers. If the lock ever moves out of execute(), this fails.
        val rig = testRig()
        val lockField = ConciergeInterface::class.java.getDeclaredField("commitLock")
        lockField.isAccessible = true
        val lock = lockField.get(rig.concierge) as java.util.concurrent.locks.ReentrantLock

        val action = Action.Write("profile", mapOf("lockprobe" to "1"))
        val evidence = testEvidence(action, rig.voice, actionId = "lockprobe")
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = CountDownLatch(1)

        // Hold the lock ourselves: execute() must block until we release.
        lock.lock()
        try {
            thread {
                try {
                    val result = rig.concierge.execute(action, evidence)
                    entered.set(result is ConciergeInterface.ExecutionResult.Executed)
                } finally {
                    done.countDown()
                }
            }
            // Give the background thread a chance to (incorrectly) proceed.
            Thread.sleep(200)
            assertTrue(!entered.get(), "execute() proceeded without the commit lock")
            assertTrue(!done.await(0, TimeUnit.MILLISECONDS), "execute() finished while lock held")
        } finally {
            lock.unlock()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "execute() did not finish after unlock")
        assertTrue(entered.get(), "execute() did not seal after unlock")
    }

    @Test
    @Test
    fun `concurrent executes never overlap inside the gates`() {
        val concurrentEntries = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val applyCalls = AtomicInteger(0)
        val verifiers = object : PermissiveVerifiers() {
            override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState {
                applyCalls.incrementAndGet()
                val c = concurrentEntries.incrementAndGet()
                maxConcurrent.updateAndGet { m -> maxOf(m, c) }
                try {
                    // Widen any race window: without the commit lock,
                    // overlap here is certain, not timing-dependent.
                    Thread.sleep(25)
                    return super.applyAction(substrate, action)
                } finally {
                    concurrentEntries.decrementAndGet()
                }
            }
        }
        // Stub biometric: always ACCEPT/LIVE, no challenge state. The real
        // verifier's single-use challenge would make all but one concurrent
        // execute fail at Gate 7, confounding the serialization measurement.
        // Voice ceremony is orthogonal to commit serialization.
        val stubBiometric = object : BiometricVerifier {
            override fun enroll(samples: List<AudioSample>): VoiceTemplate = TestVoice.enrolled
            override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult =
                VerificationResult(
                    score = 1.0,
                    decision = VerificationResult.Decision.ACCEPT,
                    liveness = VerificationResult.Liveness.LIVE,
                )
        }
        val anchorDir = Files.createTempDirectory("guardianlink-test-anchors")
        val gates = NineGates(
            Rails(), verifiers, stubBiometric, TestVoice.enrolled,
            clock = FakeClock(60_000L), ledger = MerkleAuditLog(),
        )
        val concierge = ConciergeInterface(
            PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE),
            MerkleAuditLog(),
            gates,
            AppendOnlyFileAnchor(anchorDir),
            initialSubstrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
        )
        val n = 8
        // Evidence is fully deterministic; the stub verifier ignores audio.
        // Built upfront so the only concurrent activity is execute().
        val cases = (0 until n).map { i ->
            val action = Action.Write("profile", mapOf("conc-$i" to "$i"))
            val evidence = ConciergeInterface.ActionEvidence(
                actionId = "conc-$i",
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
            action to evidence
        }
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val results = Collections.synchronizedList(
            mutableListOf<ConciergeInterface.ExecutionResult>()
        )
        cases.forEach { (action, evidence) ->
            thread {
                start.await()
                try {
                    results.add(concierge.execute(action, evidence))
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "executes did not finish")
        assertEquals(
            n, applyCalls.get(),
            "expected $n applyAction calls, got ${applyCalls.get()}"
        )
        assertEquals(
            1, maxConcurrent.get(),
            "execute() overlapped inside the gates"
        )
        val executed = results.filterIsInstance<ConciergeInterface.ExecutionResult.Executed>()
        assertEquals(n, executed.size, "expected all $n executes to seal")
        // Every mutation landed exactly once.
        val profile = concierge.currentSubstrate().records["profile"]
        repeat(n) { i ->
            assertEquals("$i", profile?.get("conc-$i"))
        }
        // Ledger integrity survived concurrency.
        assertTrue(gates.ledger.verifyIntegrity() < 0)
    }

    fun `handle does not mutate governed state`() {
        // Policy queries are read-only w.r.t. governed state: the only
        // mutation path is execute() through the gates.
        val rig = testRig()
        val before = rig.concierge.currentSubstrate()
        rig.concierge.handle(
            ConciergeInterface.Request(
                tier = PolicyEngine.Tier.EVIDENCE_ONLY,
                hardRailRef = null,
                context = "probe",
            ),
            DeviceIntegrityTier.Level.VERIFIED,
        )
        assertEquals(before, rig.concierge.currentSubstrate())
    }
}
