package guardianlink.durability

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.EpochCapability
import guardianlink.capability.LineageId
import guardianlink.capability.SovereignRoot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-U1 cycle 4 — concurrency on a shared store.
 *
 * Multiple threads across multiple issuer instances sharing ONE
 * DurableCapabilityStore present the SAME permit simultaneously.
 * Exactly one mint may win: permit consumption is a check-and-set at
 * the store — the cross-process linearization point — not a check in
 * one place and a record in another. Every loser must be refused as
 * "already consumed", and the WAL must end with exactly one
 * PERMIT_CONSUMED record for the permit.
 */
class PermitConcurrencyTest {

    private fun tempDir(): File =
        Files.createTempDirectory("permit-concurrency").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    @Test
    fun `simultaneous mints on one permit across issuers sharing a store - exactly one wins`() {
        repeat(3) { round ->
            val dir = tempDir()
            val root = SovereignRoot.bootstrap()
            val lineage = LineageId("test-${java.util.UUID.randomUUID()}")
            val permit = root.issueSignedPermit(null, lineage)
            val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
            val issuers = listOf(issuerWith(root, store), issuerWith(root, store))

            val threads = 8
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val successes = ConcurrentLinkedQueue<EpochCapability>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val pool = Executors.newFixedThreadPool(threads)
            try {
                repeat(threads) { i ->
                    pool.submit {
                        ready.countDown()
                        go.await()
                        try {
                            successes.add(
                                issuers[i % issuers.size].mintEpochWithPermit(
                                    permit = permit,
                                    predicate = CapabilityPredicate("test/", setOf("write")),
                                    maxMutations = 5L,
                                    maxBytes = null,
                                    // Isolate the permit check: overlap
                                    // refusal must not mask a double-mint.
                                    allowOverlap = true,
                                )
                            )
                        } catch (t: Throwable) {
                            failures.add(t)
                        }
                    }
                }
                assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not start (round $round)")
                go.countDown()
                pool.shutdown()
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers did not finish (round $round)")
            } finally {
                pool.shutdownNow()
            }

            assertEquals(
                1, successes.size,
                "round $round: expected exactly one winning mint, got ${successes.size} " +
                    "(failures: ${failures.map { it.message }})",
            )
            assertEquals(threads - 1, failures.size, "round $round: every loser must be refused")
            assertTrue(
                failures.all {
                    it is IllegalArgumentException && it.message!!.contains("already consumed")
                },
                "round $round: losers must be refused as already-consumed, got: " +
                    failures.map { "${it::class.simpleName}: ${it.message}" },
            )

            // Exactly one consumption record reached the WAL.
            val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
            assertEquals(
                1,
                recovered.committedHistory().count {
                    it is StoreOp.PermitConsumed && it.permitId == permit.permitId
                },
                "round $round: WAL must contain exactly one PERMIT_CONSUMED record",
            )
        }
    }
}
