package guardianlink.durability

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.LineageId
import guardianlink.capability.SovereignRoot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-U1 cycle 5 — failure injection on the store write path.
 *
 * If the durable record cannot be written during mint, the mint must
 * fail closed: no epoch is issued, and no partial state is left
 * behind — neither in the WAL (a recovered store must not report the
 * permit consumed) nor in the issuer (the same permit mints cleanly
 * once the store is healthy). The obstruction replaces capability.log
 * with a directory, so the store's append throws IOException at the
 * record step itself — after the issuer's pre-check, before any mint.
 */
class PermitStoreFailureTest {

    private fun tempDir(): File =
        Files.createTempDirectory("permit-store-failure").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    @Test
    fun `store write failure during mint fails closed with no partial state`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val lineage = LineageId("test-${java.util.UUID.randomUUID()}")
        val permit = root.issueSignedPermit(null, lineage)
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)

        // Obstruct the WAL: capability.log becomes a directory, so the
        // store's append at record time throws.
        val logFile = dir.resolve("capability.log")
        if (logFile.exists()) logFile.delete()
        assertTrue(logFile.mkdir(), "failed to obstruct capability.log")

        // The mint must throw — no epoch is issued.
        assertFailsWith<IOException> {
            issuer.mintEpochWithPermit(
                permit = permit,
                predicate = CapabilityPredicate("test/", setOf("write")),
                maxMutations = 5L,
                maxBytes = null,
            )
        }

        // Heal the obstruction.
        assertTrue(logFile.delete(), "failed to clear the obstruction")

        // No partial state in the WAL: recovery shows the permit was
        // never recorded as consumed.
        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
        assertFalse(
            recovered.isPermitConsumed(permit.permitId),
            "failed mint left a consumption record behind",
        )

        // No partial state in the issuer either: the same permit mints
        // cleanly against the healthy store under default overlap rules
        // (a live epoch leaked by the failed attempt would refuse this).
        issuer.mintEpochWithPermit(
            permit = permit,
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )

        // And the successful mint's consumption is real and durable.
        val recoveredAfter = DurableCapabilityStore(dir, clockMs = { 60_000L })
        assertTrue(
            recoveredAfter.isPermitConsumed(permit.permitId),
            "successful retry did not record consumption durably",
        )
    }
}
