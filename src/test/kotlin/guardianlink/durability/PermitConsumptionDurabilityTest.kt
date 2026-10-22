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
 * R-U1 integration test: signed-permit single-use must survive restart.
 *
 * The issuer's in-memory consumed set dies with the process; the
 * [DurableCapabilityStore] WAL does not. This test mints against a
 * store-backed issuer, then simulates a restart — a brand-new store on
 * the same directory (WAL replay) behind a brand-new issuer that has
 * never seen the permit — and requires the re-presented permit to be
 * refused.
 *
 * MUTATION PROPERTY: removing the `recordPermitConsumption` call from
 * CapabilityIssuer.mintEpochWithPermit's pre-mint consumption step makes
 * the restart assertions below fail — the recovered store reports the
 * permit unconsumed and the fresh issuer mints a second epoch from it.
 */
class PermitConsumptionDurabilityTest {

    private fun tempDir(): File =
        Files.createTempDirectory("permit-durability").toFile()

    private fun issuerWith(root: SovereignRoot, store: DurableCapabilityStore) =
        CapabilityIssuer(
            clockMs = { 60_000L },
            rootId = root.rootId,
            signingKey = root.signingKey,
            permitStore = store,
        )

    @Test
    fun `signed permit consumption survives restart`() {
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val lineage = LineageId("test-${java.util.UUID.randomUUID()}")
        val permit = root.issueSignedPermit(null, lineage)

        // First process lifetime: mint against the store-backed issuer.
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)
        val epoch = issuer.mintEpochWithPermit(
            permit = permit,
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )
        assertEquals(lineage.value, epoch.lineageId.value)

        // The consumption is durable: a NEW store on the same directory
        // replays the WAL and reports the permit consumed, with the
        // PERMIT_CONSUMED record present in the committed history.
        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
        assertTrue(recovered.isPermitConsumed(permit.permitId))
        assertTrue(
            recovered.committedHistory().any {
                it is StoreOp.PermitConsumed && it.permitId == permit.permitId
            }
        )

        // Second process lifetime: a FRESH issuer (empty in-memory
        // consumed set) backed by the recovered store refuses the permit.
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
    fun `fresh permit still mints against a recovered store`() {
        // Positive control: the durable check must refuse REUSE of a
        // consumed permit, not minting itself. After a restart, a
        // different, unconsumed permit mints normally.
        val dir = tempDir()
        val root = SovereignRoot.bootstrap()
        val store = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val issuer = issuerWith(root, store)
        val lineage1 = LineageId("test-${java.util.UUID.randomUUID()}")
        issuer.mintEpochWithPermit(
            permit = root.issueSignedPermit(null, lineage1),
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )

        val recovered = DurableCapabilityStore(dir, clockMs = { 60_000L })
        val freshIssuer = issuerWith(root, recovered)
        val lineage2 = LineageId("test-${java.util.UUID.randomUUID()}")
        val permit2 = root.issueSignedPermit(null, lineage2)
        val epoch = freshIssuer.mintEpochWithPermit(
            permit = permit2,
            predicate = CapabilityPredicate("test/", setOf("write")),
            maxMutations = 5L,
            maxBytes = null,
        )
        assertEquals(lineage2.value, epoch.lineageId.value)
        assertTrue(recovered.isPermitConsumed(permit2.permitId))
    }
}
