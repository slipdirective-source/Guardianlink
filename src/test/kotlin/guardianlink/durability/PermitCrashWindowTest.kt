package guardianlink.durability

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.LineageId
import guardianlink.capability.SovereignRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-U1 cycle 2 — the crash window between recording permit consumption
 * and mint completion.
 *
 * The issuer records consumption in the WAL BEFORE minting. A crash
 * anywhere inside that record — after the log write, after the fsync,
 * after the store's in-memory update — must yield, after recovery:
 *   (a) NO lost consumption: the recovered store reports the permit
 *       consumed (the COMMIT line is in the log; recovery replays it);
 *   (b) NO double-mint: a fresh issuer refuses the re-presented permit.
 * The mint side of the window needs no injection point of its own: the
 * record precedes minting by construction, so a crash after the record
 * can only have consumed-without-minting (fail-closed), never
 * minted-without-recording.
 */
class PermitCrashWindowTest {

    private fun tempDir(): File =
        Files.createTempDirectory("permit-crash-window").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    private fun crashDuringRecordIsFailClosed(point: CrashPoint) {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val lineage = LineageId("test-${java.util.UUID.randomUUID()}")
        val permit = root.issueSignedPermit(null, lineage)

        // Process lifetime 1: the store crashes inside recordPermitConsumption.
        val crashingStore = DurableCapabilityStore(
            dir,
            clockMs = { 60_000L },
            crash = CrashInjector(setOf(point)),
        )
        val issuer = issuerWith(root, crashingStore)
        assertFailsWith<CrashInjector.CrashInjected> {
            issuer.mintEpochWithPermit(
                permit = permit,
                predicate = CapabilityPredicate("test/", setOf("write")),
                maxMutations = 5L,
                maxBytes = null,
            )
        }

        // Recovery: a new store replays the WAL from the same directory.
        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })

        // (a) No lost consumption.
        assertTrue(
            recovered.isPermitConsumed(permit.permitId),
            "consumption lost after crash at $point",
        )

        // (b) No double-mint: a fresh issuer refuses the same permit.
        val freshIssuer = issuerWith(root, recovered)
        val refusal = assertFailsWith<IllegalArgumentException> {
            freshIssuer.mintEpochWithPermit(
                permit = permit,
                predicate = CapabilityPredicate("test/", setOf("write")),
                maxMutations = 5L,
                maxBytes = null,
                allowOverlap = true, // isolate the permit-reuse check
            )
        }
        assertTrue(refusal.message!!.contains("already consumed"))
    }

    @Test
    fun `crash after WAL write loses no consumption and mints no second epoch`() =
        crashDuringRecordIsFailClosed(CrashPoint.AFTER_COMMIT_WRITE)

    @Test
    fun `crash after WAL fsync loses no consumption and mints no second epoch`() =
        crashDuringRecordIsFailClosed(CrashPoint.AFTER_COMMIT_FSYNC)

    @Test
    fun `crash after store state update loses no consumption and mints no second epoch`() =
        crashDuringRecordIsFailClosed(CrashPoint.AFTER_STATE_UPDATE)
}
