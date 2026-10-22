package guardianlink.durability

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.LineageId
import guardianlink.capability.SovereignRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-U1 cycle 3 — replay pressure on the consumption record.
 *
 * Duplicate PERMIT_CONSUMED records in the WAL (legacy writers, torn-write
 * retries, log duplication) and repeated recovery cycles must neither
 * resurrect a consumed permit nor corrupt the store: replay is
 * idempotent because consumption is a set fact, and every recovery must
 * end with the permit exactly as consumed as the first one did.
 */
class PermitReplayPressureTest {

    private fun tempDir(): File =
        Files.createTempDirectory("permit-replay-pressure").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    @Test
    fun `duplicate WAL records and repeated recovery keep single-use`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val lineage = LineageId("test-${java.util.UUID.randomUUID()}")
        val permit = root.issueSignedPermit(null, lineage)

        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        issuerWith(root, store).mintEpochWithPermit(
            permit = permit,
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )

        // Replay pressure: append duplicate COMMIT records for the same
        // permitId directly to the WAL, as a legacy/duplicating writer
        // could. Recovery must tolerate them.
        val logFile = dir.resolve("capability.log")
        val maxSeq = logFile.readLines(Charsets.UTF_8)
            .mapNotNull { it.split("|").firstOrNull()?.toLongOrNull() }
            .maxOrNull() ?: -1L
        logFile.appendText(
            "${maxSeq + 1}|0|COMMIT|PERMIT_CONSUMED|${permit.permitId}\n" +
                "${maxSeq + 2}|0|COMMIT|PERMIT_CONSUMED|${permit.permitId}\n",
            Charsets.UTF_8,
        )

        // Repeated recovery cycles: each fresh store replays the (now
        // duplicated) log; each fresh issuer must refuse the permit.
        repeat(3) { cycle ->
            val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
            assertTrue(
                recovered.isPermitConsumed(permit.permitId),
                "permit not consumed after recovery cycle $cycle",
            )
            val refusal = assertFailsWith<IllegalArgumentException> {
                issuerWith(root, recovered).mintEpochWithPermit(
                    permit = permit,
                    predicate = CapabilityPredicate("test/", setOf("write")),
                    maxMutations = 5L,
                    maxBytes = null,
                    allowOverlap = true, // isolate the permit-reuse check
                )
            }
            assertTrue(
                refusal.message!!.contains("already consumed"),
                "unexpected refusal in recovery cycle $cycle: ${refusal.message}",
            )
        }

        // The pressure did not corrupt the store: a fresh permit still
        // mints against a store recovered from the duplicated log.
        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val lineage2 = LineageId("test-${java.util.UUID.randomUUID()}")
        val epoch = issuerWith(root, recovered).mintEpochWithPermit(
            permit = root.issueSignedPermit(null, lineage2),
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )
        assertEquals(lineage2.value, epoch.lineageId.value)
    }
}
