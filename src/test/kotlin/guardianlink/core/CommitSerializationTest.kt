package guardianlink.core

import guardianlink.gates.Action
import guardianlink.gates.SubstrateState
import guardianlink.integrity.DeviceIntegrityTier
import guardianlink.policy.PolicyEngine
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
        val rig = testRig(verifiers = verifiers)
        val n = 8
        // Build all evidence upfront, SEQUENTIALLY: the voice verifier's
        // challenge state is per-instance, so concurrent issueChallenge
        // calls would interfere. The concurrency under test is execute()
        // alone.
        val cases = (0 until n).map { i ->
            val action = Action.Write("profile", mapOf("conc-$i" to "$i"))
            action to testEvidence(action, rig.voice, actionId = "conc-$i")
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
                    results.add(rig.concierge.execute(action, evidence))
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "executes did not finish")
        assertEquals(
            1, maxConcurrent.get(),
            "execute() overlapped inside the gates: applyCalls=${applyCalls.get()}"
        )
        val executed = results.filterIsInstance<ConciergeInterface.ExecutionResult.Executed>()
        assertEquals(n, executed.size, "expected all $n executes to seal")
        // Every mutation landed exactly once.
        val profile = rig.concierge.currentSubstrate().records["profile"]
        repeat(n) { i ->
            assertEquals("$i", profile?.get("conc-$i"))
        }
        // Ledger integrity survived concurrency.
        assertTrue(rig.gates.ledger.verifyIntegrity() < 0)
    }

    @Test
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
