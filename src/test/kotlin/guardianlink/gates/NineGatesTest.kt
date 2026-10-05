package guardianlink.gates

import guardianlink.audit.MerkleAuditLog
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verification of the Nine Gates FSM (Codex v2.2, §§III–V).
 * Deterministic: every time predicate runs against a FakeClock (tau).
 */
class NineGatesTest {

    class TestVerifiers(
        var sigOk: Boolean = true,
        var zkOk: Boolean = true,
        var assentOk: Boolean = true,
        var revocationOk: Boolean = false,
        var boundaryOk: Boolean = true,
        var rulesOk: Boolean = true,
        var divergence: Double = 0.0,
        var loopOk: Boolean = true,
    ) : Verifiers {
        override fun verifySignature(message: ByteArray, signature: ByteArray) = sigOk
        override fun verifyZk(proof: ByteArray, publicInputs: ByteArray) = zkOk
        override fun verifyAssent(rendering: String, signature: ByteArray) = assentOk
        override fun verifyRevocation(revocation: Revocation) = revocationOk
        override fun boundaryPermitted(action: Action, substrate: SubstrateState) = boundaryOk
        override fun policyRules(action: Action, substrate: SubstrateState) = rulesOk
        override fun governanceDivergence(action: Action) = divergence
        override fun metaLoopConsistent(context: GateContext) = loopOk

        override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState {
            fun cond(c: Condition, s: SubstrateState): Boolean = when (c) {
                is Condition.FieldEquals -> s.records[c.recordId]?.get(c.field) == c.expected
                is Condition.And -> c.parts.all { cond(it, s) }
                is Condition.Not -> !cond(c.inner, s)
            }
            fun step(s: SubstrateState, a: Action): SubstrateState {
                return when (a) {
                    is Action.Read -> s
                    is Action.Write -> {
                        val rec = s.records[a.recordId] ?: return s
                        s.copy(records = s.records + (a.recordId to (rec + a.fields)))
                    }
                    is Action.Delete -> s.copy(records = s.records - a.recordId)
                    is Action.Sequence -> a.steps.fold(s, ::step)
                    is Action.Guarded ->
                        if (cond(a.condition, s)) step(s, a.then)
                        else a.otherwise?.let { step(s, it) } ?: s
                }
            }
            return step(substrate, action)
        }
    }

    private val rails = Rails()
    private val verifiers = TestVerifiers()
    private val gates = NineGates(rails, verifiers)

    /** kotlin-test 1.9 has no assertIsInstance; this is the equivalent. */
    private inline fun <reified T : GateOutcome> assertOutcome(outcome: GateOutcome): T {
        assertTrue(outcome is T, "expected ${T::class.simpleName} but was $outcome")
        return outcome
    }

    private fun baseAction() = Action.Write("profile", mapOf("name" to "Neo"))

    /** A context that passes every gate. */
    private fun passingContext(
        action: Action = baseAction(),
        clock: FakeClock = FakeClock(t = 20_000L),
    ): GateContext {
        val rendered = render(action)
        return GateContext(
            signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
            keys = KeyMaterial(
                message = "req".toByteArray(),
                signature = "sig".toByteArray(),
                biometricTemplate = doubleArrayOf(0.0, 0.0),
                enrolledTemplate = doubleArrayOf(0.0, 0.0),
            ),
            proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = clock.nowMs()),
            contextEntropyBits = 2.0,
            contextParseTrees = 1,
            action = action,
            actionId = "a1",
            substrate = SubstrateState(mapOf("profile" to mapOf("name" to "Caleb"))),
            ledger = MerkleAuditLog(),
            rendering = rendered,
            assent = Assent("s_a".toByteArray()),
            assentedAtMs = 0L,
            revocations = emptyList(),
            clock = clock,
            intentVector = doubleArrayOf(1.0, 0.0),
            currentVector = doubleArrayOf(1.0, 0.0),
        )
    }

    @Test
    fun testAllGatesPassYieldsIntegration() {
        val ctx = passingContext()
        val outcome = gates.evaluate(ctx)
        val integrated = assertOutcome<GateOutcome.Integrated>(outcome)
        assertEquals("Neo", integrated.newSubstrate.records["profile"]?.get("name"))
        assertEquals(-1, ctx.ledger.verifyIntegrity())
        assertTrue(ctx.ledger.size >= 1) // the seal
    }

    @Test
    fun testRenderIsCanonical() {
        assertEquals(
            "WRITE \"r\" {\"a\"=\"1\",\"b\"=\"2\"}",
            render(Action.Write("r", mapOf("b" to "2", "a" to "1")))
        )
        assertEquals("DELETE \"r\"", render(Action.Delete("r")))
    }

    @Test
    fun testGate0HaltsOnLowSnr() {
        val ctx = passingContext().copy(signal = Signal("req".toByteArray(), snr = 1.0, wellFormed = true))
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(0, halted.atGate)
    }

    @Test
    fun testGate0HaltsOnOversizedAction() {
        val big = Action.Sequence(List(100) { Action.Read("profile", listOf("name")) })
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(passingContext(action = big)))
        assertEquals(0, halted.atGate)
    }

    @Test
    fun testGate1HaltsOnBadSignature() {
        verifiers.sigOk = false
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(passingContext()))
        assertEquals(1, halted.atGate)
    }

    @Test
    fun testGate1HaltsOnBiometricMismatch() {
        val ctx = passingContext().copy(
            keys = KeyMaterial(
                "req".toByteArray(), "sig".toByteArray(),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0),
            )
        )
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(1, halted.atGate)
    }

    @Test
    fun testGate2HaltsOnUnknownRecord() {
        val ctx = passingContext(action = Action.Write("ghost", mapOf("x" to "y")))
            .copy(rendering = render(Action.Write("ghost", mapOf("x" to "y"))))
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(2, halted.atGate)
    }

    @Test
    fun testGate3HaltsOnStaleProof() {
        val clock = FakeClock(t = 20_000L)
        val ctx = passingContext(clock = clock).copy(
            proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = 20_000L - rails.deltaT - 1)
        )
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(3, halted.atGate)
    }

    @Test
    fun testGate4HaltsOnHighEntropy() {
        val ctx = passingContext().copy(contextEntropyBits = 99.0)
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(4, halted.atGate)
    }

    @Test
    fun testGate4HaltsOnAmbiguity() {
        val ctx = passingContext().copy(contextParseTrees = 2)
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(4, halted.atGate)
    }

    @Test
    fun testGate5HaltsOnDrift() {
        val ctx = passingContext().copy(currentVector = doubleArrayOf(-1.0, 0.0))
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(5, halted.atGate)
    }

    @Test
    fun testGate2HaltsOnBoundaryDenied() {
        verifiers.boundaryOk = false
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(passingContext()))
        assertEquals(2, halted.atGate)
    }

    @Test
    fun testGate6HaltsOnRuleFailure() {
        verifiers.rulesOk = false
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(passingContext()))
        assertEquals(6, halted.atGate)
    }

    @Test
    fun testGate7HaltsOnTamperedRendering() {
        val ctx = passingContext().copy(rendering = "EVIL RENDERING")
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(7, halted.atGate)
    }

    @Test
    fun testGate7HaltsWhenCoolingNotElapsed() {
        // WRITE impact -> 10_000 ms window; assent was "just now".
        val clock = FakeClock(t = 20_000L)
        val ctx = passingContext(clock = clock).copy(assentedAtMs = 20_000L)
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(7, halted.atGate)
    }

    @Test
    fun testGate7CoolingScalesWithComputedImpact() {
        // DELETE is DESTRUCTIVE -> 15_000 ms window.
        val delete = Action.Delete("profile")
        val clock = FakeClock(t = 20_000L)
        val tooSoon = passingContext(action = delete, clock = clock).copy(assentedAtMs = 8_000L)
        assertOutcome<GateOutcome.Halted>(gates.evaluate(tooSoon))

        clock.advance(10_000L) // elapsed now 22_000 ms >= 15_000
        val outcome = gates.evaluate(passingContext(action = delete, clock = clock).copy(assentedAtMs = 8_000L))
        val integrated = assertOutcome<GateOutcome.Integrated>(outcome)
        assertTrue("profile" !in integrated.newSubstrate.records)
    }

    @Test
    fun testGate7ReadOnlyActionShorterCooling() {
        // READ impact -> 5_000 ms window; 6_000 ms elapsed passes.
        val read = Action.Read("profile", listOf("name"))
        val clock = FakeClock(t = 20_000L)
        val ctx = passingContext(action = read, clock = clock).copy(assentedAtMs = 14_000L)
        assertOutcome<GateOutcome.Integrated>(gates.evaluate(ctx))
    }

    @Test
    fun testGate8HaltsOnSignedRevocation() {
        verifiers.revocationOk = true
        val clock = FakeClock(t = 20_000L)
        val ctx = passingContext(clock = clock).copy(
            revocations = listOf(Revocation("a1", "rev-sig".toByteArray(), revokedAtMs = 19_000L))
        )
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(8, halted.atGate)
    }

    @Test
    fun testEveryHaltIsAppendedToLedger() {
        verifiers.sigOk = false
        val ctx = passingContext()
        gates.evaluate(ctx)
        assertEquals(1, ctx.ledger.size)
        assertEquals(-1, ctx.ledger.verifyIntegrity())
    }

    @Test
    fun testFailClosedOnVectorMismatch() {
        // Ambiguous identity evidence -> halt, never pass.
        val ctx = passingContext().copy(
            keys = KeyMaterial(
                "req".toByteArray(), "sig".toByteArray(),
                doubleArrayOf(0.0), doubleArrayOf(0.0, 0.0),
            )
        )
        val halted = assertOutcome<GateOutcome.Halted>(gates.evaluate(ctx))
        assertEquals(1, halted.atGate)
    }
}
