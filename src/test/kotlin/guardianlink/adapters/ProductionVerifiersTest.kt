package guardianlink.adapters

import guardianlink.crypto.AssentCodec
import guardianlink.crypto.Ed25519
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.GateContext
import guardianlink.gates.KeyMaterial
import guardianlink.gates.Proofs
import guardianlink.gates.Revocation
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.voice.AudioSample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ProductionVerifiers: real Ed25519 on the crypto ports, fail-closed
 * defaults everywhere else. A verifier that defaults permissive is a
 * RailAuthorizer.PERMISSIVE-class error — these tests pin the deny
 * defaults.
 */
class ProductionVerifiersTest {

    @TempDir
    lateinit var dir: Path

    private fun verifiers(
        feed: RevocationFeed = object : RevocationFeed {
            override fun revocationsFor(actionId: String) = emptyList<Revocation>()
        },
        operatorKp: java.security.KeyPair = Ed25519.generateKeyPair(),
        authorityKp: java.security.KeyPair = Ed25519.generateKeyPair(),
    ) = Triple(
        ProductionVerifiers(
            operatorKey = operatorKp.public,
            revocationAuthorityKey = authorityKp.public,
            revocationFeed = feed,
        ),
        operatorKp,
        authorityKp,
    )

    private fun gateContext(): GateContext {
        val action = Action.Write("profile", mapOf("name" to "Neo"))
        return GateContext(
            signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
            keys = KeyMaterial("req".toByteArray(), "sig".toByteArray()),
            proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = 0L),
            contextEntropyBits = 2.0,
            contextParseTrees = 1,
            action = action,
            actionId = "act-1",
            substrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
            rendering = "WRITE profile {name=Neo}",
            assent = Assent("s".toByteArray()),
            assentAudio = AudioSample(doubleArrayOf(0.0)),
            assentedAtMs = 0L,
            intentVector = doubleArrayOf(1.0, 0.0),
            currentVector = doubleArrayOf(1.0, 0.0),
        )
    }

    @Test
    fun `verifySignature enforces Ed25519`() {
        val (v, operatorKp, _) = verifiers()
        val msg = "operator command".toByteArray()
        val sig = Ed25519.sign(operatorKp.private, msg)
        assertTrue(v.verifySignature(msg, sig))
        assertFalse(v.verifySignature("tampered".toByteArray(), sig))
    }

    @Test
    fun `verifyAssent enforces the triple binding`() {
        val (v, operatorKp, _) = verifiers()
        val sig = AssentCodec.sign(operatorKp.private, "WRITE profile {name=Neo}", 5000L, "act-1")
        assertTrue(v.verifyAssent("WRITE profile {name=Neo}", 5000L, "act-1", sig))
        // Backdate, replay, tamper — all rejected.
        assertFalse(v.verifyAssent("WRITE profile {name=Neo}", 1000L, "act-1", sig))
        assertFalse(v.verifyAssent("WRITE profile {name=Neo}", 5000L, "act-2", sig))
        assertFalse(v.verifyAssent("WRITE profile {name=X}", 5000L, "act-1", sig))
    }

    @Test
    fun `verifyZk defaults to deny, injected verifier is honored and wrapped`() {
        val (v, _, _) = verifiers()
        assertFalse(v.verifyZk("proof".toByteArray(), "pub".toByteArray()))
        val permissive = ProductionVerifiers(
            operatorKey = Ed25519.generateKeyPair().public,
            revocationAuthorityKey = Ed25519.generateKeyPair().public,
            revocationFeed = object : RevocationFeed {
                override fun revocationsFor(actionId: String) = emptyList<Revocation>()
            },
            zkVerifier = { _, _ -> true },
        )
        assertTrue(permissive.verifyZk("proof".toByteArray(), "pub".toByteArray()))
        val throwing = ProductionVerifiers(
            operatorKey = Ed25519.generateKeyPair().public,
            revocationAuthorityKey = Ed25519.generateKeyPair().public,
            revocationFeed = object : RevocationFeed {
                override fun revocationsFor(actionId: String) = emptyList<Revocation>()
            },
            zkVerifier = { _, _ -> throw RuntimeException("boom") },
        )
        assertFalse(throwing.verifyZk("proof".toByteArray(), "pub".toByteArray()))
    }

    @Test
    fun `policy ports default to deny`() {
        val (v, _, _) = verifiers()
        val ctx = gateContext()
        assertFalse(v.boundaryPermitted(ctx.action, ctx.substrate))
        assertFalse(v.policyRules(ctx.action, ctx.substrate))
        assertFalse(v.metaLoopConsistent(ctx))
        assertEquals(Double.POSITIVE_INFINITY, v.governanceDivergence(ctx.action))
    }

    @Test
    fun `throwing policy lambdas fail closed`() {
        val v = ProductionVerifiers(
            operatorKey = Ed25519.generateKeyPair().public,
            revocationAuthorityKey = Ed25519.generateKeyPair().public,
            revocationFeed = object : RevocationFeed {
                override fun revocationsFor(actionId: String) = emptyList<Revocation>()
            },
            boundaryCheck = { _, _ -> throw RuntimeException("boom") },
            divergenceFn = { Double.NaN },
        )
        val ctx = gateContext()
        assertFalse(v.boundaryPermitted(ctx.action, ctx.substrate))
        // NaN divergence is not finite -> +Inf (deny).
        assertEquals(Double.POSITIVE_INFINITY, v.governanceDivergence(ctx.action))
    }

    @Test
    fun `revocations flow from the feed and verify under the authority key`() {
        val operatorKp = Ed25519.generateKeyPair()
        val authorityKp = Ed25519.generateKeyPair()
        val line = SignedRevocationListFeed.signEntry(authorityKp.private, "act-1", 7000L)
        val p = dir.resolve("revs.jsonl")
        Files.write(p, line.toByteArray(StandardCharsets.UTF_8))
        val feed = SignedRevocationListFeed.load(p, authorityKp.public)
        val v = ProductionVerifiers(
            operatorKey = operatorKp.public,
            revocationAuthorityKey = authorityKp.public,
            revocationFeed = feed,
        )
        val revs = v.revocationsFor("act-1")
        assertEquals(1, revs.size)
        assertTrue(v.verifyRevocation(revs[0]))
        // A revocation signed by the OPERATOR key (not the authority) is rejected:
        // the hand that assents must not be the hand that revokes.
        val forgedLine = SignedRevocationListFeed.signEntry(operatorKp.private, "act-1", 7000L)
        val p2 = dir.resolve("revs2.jsonl")
        Files.write(p2, forgedLine.toByteArray(StandardCharsets.UTF_8))
        assertFailsWith<IllegalArgumentException> {
            SignedRevocationListFeed.load(p2, authorityKp.public)
        }
    }

    @Test
    fun `throwing revocation feed propagates (engine fail-closes)`() {
        val v = ProductionVerifiers(
            operatorKey = Ed25519.generateKeyPair().public,
            revocationAuthorityKey = Ed25519.generateKeyPair().public,
            revocationFeed = object : RevocationFeed {
                override fun revocationsFor(actionId: String): List<Revocation> =
                    throw RuntimeException("feed down")
            },
        )
        try {
            v.revocationsFor("act-1")
            throw AssertionError("expected the feed exception to propagate")
        } catch (e: RuntimeException) {
            assertEquals("feed down", e.message)
        }
    }

    @Test
    fun `applyAction defaults to the canonical reference transition`() {
        val (v, _, _) = verifiers()
        val ctx = gateContext()
        val next = v.applyAction(ctx.substrate, ctx.action)
        assertEquals("Neo", next.records["profile"]?.get("name"))
    }
}
