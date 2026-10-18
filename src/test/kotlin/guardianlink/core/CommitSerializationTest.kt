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
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val results = Collections.synchronizedList(
            mutableListOf<ConciergeInterface.ExecutionResult>()
        )
        repeat(n) { i ->
            thread {
                start.await()
                try {
                    val action = Action.Write("conc-$i", mapOf("v" to "$i"))
                    results.add(
                        rig.concierge.execute(
                            action,
                            testEvidence(action, rig.voice, actionId = "conc-$i")
                        )
                    )
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "executes did not finish")
        val mc = maxConcurrent.get()
        val execCount = results.filterIsInstance<ConciergeInterface.ExecutionResult.Executed>().size
        if (mc != 1) {
            error("DIAG applyCalls=${applyCalls.get()} maxConcurrent=$mc results=${results.size} executed=$execCount")
        }
        val executed = results.filterIsInstance<ConciergeInterface.ExecutionResult.Executed>()
        assertEquals(n, executed.size, "expected all $n executes to seal")
        // Every mutation landed exactly once.
        val substrate = rig.concierge.currentSubstrate()
        repeat(n) { i ->
            assertEquals("$i", substrate.records["conc-$i"]?.get("v"))
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
