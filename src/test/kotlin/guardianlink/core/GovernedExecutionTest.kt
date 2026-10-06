package guardianlink.core

import guardianlink.audit.AppendOnlyFileAnchor
import guardianlink.audit.ExternalAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.FakeClock
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Revocation
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.Verifiers
import guardianlink.gates.render
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Permissive verifiers for composition tests: all crypto checks pass. */
internal class PermissiveVerifiers(
    var revocationList: List<Revocation> = emptyList(),
) : Verifiers {
    override fun verifySignature(message: ByteArray, signature: ByteArray) = true
    override fun verifyZk(proof: ByteArray, publicInputs: ByteArray) = true
    override fun verifyAssent(rendering: String, assentedAtMs: Long, actionId: String, signature: ByteArray) = true
    override fun verifyRevocation(revocation: Revocation) = true
    override fun revocationsFor(actionId: String) = revocationList.filter { it.actionId == actionId }
    override fun boundaryPermitted(action: Action, substrate: SubstrateState) = true
    override fun policyRules(action: Action, substrate: SubstrateState) = true
    override fun governanceDivergence(action: Action) = 0.0
    override fun metaLoopConsistent(context: guardianlink.gates.GateContext) = true
    override fun applyAction(substrate: SubstrateState, action: Action) =
        guardianlink.gates.ReferenceTransition.apply(substrate, action)
}

internal data class TestRig(
    val concierge: ConciergeInterface,
    val gates: NineGates,
    val anchor: AppendOnlyFileAnchor,
    val anchorDir: Path,
    val verifiers: PermissiveVerifiers,
)

/** A composed concierge with engine-owned instruments and a real anchor file. */
internal fun testRig(tau: Long = 60_000L, verifiers: PermissiveVerifiers = PermissiveVerifiers()): TestRig {
    val anchorDir = Files.createTempDirectory("guardianlink-test-anchors")
    val gates = NineGates(Rails(), verifiers, clock = FakeClock(tau), ledger = MerkleAuditLog())
    val anchor = AppendOnlyFileAnchor(anchorDir)
    val concierge = ConciergeInterface(
        PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE),
        MerkleAuditLog(),
        gates,
        anchor,
        initialSubstrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
    )
    return TestRig(concierge, gates, anchor, anchorDir, verifiers)
}

internal fun testEvidence(
    action: Action,
    tau: Long = 60_000L,
    rendering: String = render(action),
    actionId: String = "test-1",
    assentedAtMs: Long = 0L,
) = ConciergeInterface.ActionEvidence(
    actionId = actionId,
    signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
    keys = KeyMaterial(
        "req".toByteArray(), "sig".toByteArray(),
        doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0),
    ),
    proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = tau),
    contextEntropyBits = 2.0,
    contextParseTrees = 1,
    rendering = rendering,
    assent = Assent("s_a".toByteArray()),
    assentedAtMs = assentedAtMs,
    intentVector = doubleArrayOf(1.0, 0.0),
    currentVector = doubleArrayOf(1.0, 0.0),
)

class GovernedExecutionTest {

    @Test
    fun executeAllPassAdoptsSubstrateAndAnchorsSeal() {
        val rig = testRig()
        val action = Action.Write("profile", mapOf("name" to "Neo"))

        val result = rig.concierge.execute(action, testEvidence(action))

        val executed = assertIs<ConciergeInterface.ExecutionResult.Executed>(result)
        assertEquals("Neo", executed.newSubstrate.records["profile"]?.get("name"))
        // The anchored root is the engine's seal root — anchored without
        // touching the engine's ledger.
        assertEquals(rig.gates.ledger.root, executed.anchorReceipt.root)
        assertTrue(executed.anchorReceipt.location.startsWith(rig.anchorDir.toString()))
        // Composition carries state: the concierge now governs the new substrate.
        assertEquals("Neo", rig.concierge.currentSubstrate().records["profile"]?.get("name"))
        // One anchor line, chain intact.
        assertEquals(-1, rig.anchor.verifyChain())
    }

    @Test
    fun executeTamperedRenderingRejectsAndAnchorsNothing() {
        val rig = testRig()
        val action = Action.Write("profile", mapOf("name" to "Neo"))

        val result = rig.concierge.execute(
            action, testEvidence(action, rendering = "WRITE profile {name=Attacker}")
        )

        val rejected = assertIs<ConciergeInterface.ExecutionResult.Rejected>(result)
        assertEquals(7, rejected.atGate)
        // Nothing adopted, nothing anchored.
        assertEquals("Caleb", rig.concierge.currentSubstrate().records["profile"]?.get("name"))
        assertEquals(0, anchorLineCount(rig.anchorDir))
        assertEquals(-1, rig.anchor.verifyChain())
    }

    @Test
    fun executeRevokedRejectsAndAnchorsNothing() {
        val rig = testRig()
        rig.verifiers.revocationList = listOf(Revocation("test-1", "rev".toByteArray(), revokedAtMs = 59_000L))
        val action = Action.Write("profile", mapOf("name" to "Neo"))

        val result = rig.concierge.execute(action, testEvidence(action))

        val rejected = assertIs<ConciergeInterface.ExecutionResult.Rejected>(result)
        assertEquals(8, rejected.atGate)
        assertEquals("Caleb", rig.concierge.currentSubstrate().records["profile"]?.get("name"))
        assertEquals(0, anchorLineCount(rig.anchorDir))
    }

    @Test
    fun executeCarriesStateAcrossCalls() {
        val rig = testRig()
        val write = Action.Write("profile", mapOf("name" to "Neo"))
        assertIs<ConciergeInterface.ExecutionResult.Executed>(
            rig.concierge.execute(write, testEvidence(write))
        )
        // Second action sees the first action's substrate — one governed
        // state machine, not one shot per call.
        val delete = Action.Delete("profile")
        val result = rig.concierge.execute(delete, testEvidence(delete))
        val executed = assertIs<ConciergeInterface.ExecutionResult.Executed>(result)
        assertTrue("profile" !in executed.newSubstrate.records)
        assertEquals(2, anchorLineCount(rig.anchorDir))
        assertEquals(-1, rig.anchor.verifyChain())
    }

    @Test
    fun rejectedExecutionLeavesSubstrateAdoptable() {
        val rig = testRig()
        val write = Action.Write("profile", mapOf("name" to "Neo"))
        // Rejected first…
        rig.concierge.execute(write, testEvidence(write, rendering = "tampered"))
        // …then a valid execution still works on the unmodified substrate.
        val result = rig.concierge.execute(write, testEvidence(write))
        val executed = assertIs<ConciergeInterface.ExecutionResult.Executed>(result)
        assertEquals("Neo", executed.newSubstrate.records["profile"]?.get("name"))
        assertEquals(1, anchorLineCount(rig.anchorDir))
    }

    @Test
    fun anchorChainDetectsTampering() {
        val rig = testRig()
        val action = Action.Write("profile", mapOf("name" to "Neo"))
        rig.concierge.execute(action, testEvidence(action))
        assertEquals(-1, rig.anchor.verifyChain())

        // Attacker rewrites the anchor file: the chain must detect it.
        val anchorFile = rig.anchorDir.resolve("anchors.log")
        Files.write(anchorFile, "forged line\n".toByteArray(Charsets.UTF_8), APPEND)
        assertTrue(rig.anchor.verifyChain() != -1)
    }

    @Test
    fun throwingAnchorPropagatesFailLoud() {
        val anchorDir = Files.createTempDirectory("guardianlink-test-anchors")
        val verifiers = PermissiveVerifiers()
        val gates = NineGates(Rails(), verifiers, clock = FakeClock(60_000L), ledger = MerkleAuditLog())
        val failingAnchor = object : ExternalAnchor {
            override fun anchor(root: String, entryIndex: Int, timestampMs: Long): ExternalAnchor.Receipt =
                throw IOException("anchor store unreachable")
        }
        val concierge = ConciergeInterface(
            PolicyEngine(authorizer = RailAuthorizer.PERMISSIVE),
            MerkleAuditLog(),
            gates,
            failingAnchor,
            initialSubstrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
        )
        val action = Action.Write("profile", mapOf("name" to "Neo"))

        // The transition IS applied and sealed locally, but the anchor
        // failed — the throw must propagate, never swallow.
        assertFailsWith<IOException> { concierge.execute(action, testEvidence(action)) }
        assertEquals("Neo", concierge.currentSubstrate().records["profile"]?.get("name"))
        assertEquals(-1, gates.ledger.verifyIntegrity())
    }

    private fun anchorLineCount(dir: Path): Int {
        val f = dir.resolve("anchors.log")
        if (!Files.exists(f)) return 0
        return Files.readAllLines(f, Charsets.UTF_8).count { it.isNotBlank() }
    }

    private inline fun <reified T> assertIs(value: Any): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but was $value")
        return value
    }
}
