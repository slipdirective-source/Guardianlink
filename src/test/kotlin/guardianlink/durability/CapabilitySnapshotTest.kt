package guardianlink.durability

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.LineageId
import guardianlink.capability.SovereignRoot
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The durable store's read-only snapshot — the data source behind the
 * sidecar's GET /v1/capabilities endpoint — must tell the truth:
 *
 * - after a REAL mint through the store-wired issuer (the R-U1 path),
 *   the consumed permit is in the snapshot;
 * - after recovery (a fresh store replaying the same WAL), the snapshot
 *   is unchanged — restart does not rewrite what the store knows;
 * - taking a snapshot mutates nothing: the WAL bytes are identical
 *   before and after, and repeated snapshots are equal;
 * - under concurrent mints, every snapshot is internally consistent:
 *   per-reader, the consumed-permit set only grows and the fencing
 *   epoch never decreases (no torn reads across the store's state).
 */
class CapabilitySnapshotTest {

    private fun tempDir(): File =
        Files.createTempDirectory("capability-snapshot").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    private fun mintOne(root: SovereignRoot, issuer: CapabilityIssuer): String {
        val permit = root.issueSignedPermit(null, LineageId("test-${UUID.randomUUID()}"))
        issuer.mintEpochWithPermit(
            permit = permit,
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
            allowOverlap = true, // isolate permit accounting from epoch-overlap rules
        )
        return permit.permitId
    }

    @Test
    fun `snapshot reflects a real mint through the wired issuer`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)

        assertTrue(store.snapshot().consumedPermitIds.isEmpty())

        val permitId = mintOne(root, issuer)

        val snap = store.snapshot()
        assertEquals(setOf(permitId), snap.consumedPermitIds)
        assertTrue(store.isPermitConsumed(permitId))
        // The snapshot agrees with the WAL's committed history at rest.
        assertEquals(
            store.committedHistory()
                .filterIsInstance<StoreOp.PermitConsumed>()
                .map { it.permitId }
                .toSet(),
            snap.consumedPermitIds,
        )
    }

    @Test
    fun `snapshot is unchanged by recovery`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)

        val permitId = mintOne(root, issuer)
        assertEquals(
            StoreResult.Committed,
            store.commitMutation("cap-1", notAfterMs = Long.MAX_VALUE),
        )
        assertEquals(
            StoreResult.Committed,
            store.recordUsage("cap-1", 42L, null, null, Long.MAX_VALUE),
        )
        store.revoke("cap-2") // bumps the fencing epoch to 1

        val before = store.snapshot()
        assertEquals(setOf(permitId), before.consumedPermitIds)
        assertEquals(setOf("cap-1"), before.state.consumed)
        assertEquals(setOf("cap-2"), before.state.revoked)
        assertEquals(1L to 42L, before.state.usage["cap-1"])
        assertEquals(1L, before.fencingEpoch)

        // Restart: a fresh store replays the WAL. What it knows must be
        // exactly what the live store knew — recovery rewrites nothing.
        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
        assertEquals(before, recovered.snapshot())
    }

    @Test
    fun `snapshot mutates nothing`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)
        mintOne(root, issuer)

        val logFile = dir.resolve("capability.log")
        val bytesBefore = logFile.readBytes()
        val first = store.snapshot()
        repeat(50) {
            assertEquals(first, store.snapshot())
        }
        assertTrue(bytesBefore.contentEquals(logFile.readBytes()))
    }

    @Test
    fun `snapshots stay consistent while mints race`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)

        val minters = 8
        val permitsPerMinter = 5
        val minted = ConcurrentLinkedQueue<String>()
        val violations = ConcurrentLinkedQueue<String>()
        val done = AtomicBoolean(false)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(minters + 4)

        repeat(minters) {
            pool.submit {
                start.await()
                repeat(permitsPerMinter) { minted.add(mintOne(root, issuer)) }
            }
        }
        repeat(4) {
            pool.submit {
                start.await()
                var lastIds: Set<String> = emptySet()
                var lastEpoch = 0L
                while (!done.get()) {
                    val snap = store.snapshot()
                    if (!snap.consumedPermitIds.containsAll(lastIds)) {
                        violations.add("consumed permits shrank: $lastIds -> ${snap.consumedPermitIds}")
                    }
                    if (snap.fencingEpoch < lastEpoch) {
                        violations.add("fencing epoch decreased: $lastEpoch -> ${snap.fencingEpoch}")
                    }
                    lastIds = snap.consumedPermitIds
                    lastEpoch = snap.fencingEpoch
                }
            }
        }
        start.countDown()
        pool.shutdown()
        // Readers stop only via `done`; minters finishing is the signal.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (minted.size < minters * permitsPerMinter && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        done.set(true)
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals(minters * permitsPerMinter, minted.size)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        // At rest, the snapshot is exactly the set of minted permits.
        assertEquals(minted.toSet(), store.snapshot().consumedPermitIds)
    }
}
