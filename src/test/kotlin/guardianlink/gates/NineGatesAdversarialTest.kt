package guardianlink.gates

import guardianlink.audit.MerkleAuditLog
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial + fuzz screening for the Nine Gates FSM.
 *
 * Every test uses a fixed seed: the suite is fully deterministic and
 * replayable. A failure here is a real fail-closed violation, not flakiness.
 *
 * Threat model: the GateContext is built from untrusted signal/context
 * (NaN/Inf numerics, hostile clocks), the Verifiers ports are plugins that
 * may misbehave (throw, return NaN), and the action AST is attacker-shaped
 * (tampered renderings, smuggled records, understated impact).
 */
class NineGatesAdversarialTest {

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private class FuzzVerifiers(
        var sigOk: Boolean = true,
        var zkOk: Boolean = true,
        var assentOk: Boolean = true,
        var revocationOk: Boolean = false,
        var boundaryOk: Boolean = true,
        var rulesOk: Boolean = true,
        var divergence: Double = 0.0,
        var loopOk: Boolean = true,
        var throwPorts: Set<String> = emptySet(),
        var evilApply: ((SubstrateState, Action) -> SubstrateState)? = null,
    ) : Verifiers {
        private fun maybeThrow(port: String) {
            if (port in throwPorts) throw RuntimeException("hostile port: $port")
        }

        override fun verifySignature(message: ByteArray, signature: ByteArray): Boolean {
            maybeThrow("sig"); return sigOk
        }

        override fun verifyZk(proof: ByteArray, publicInputs: ByteArray): Boolean {
            maybeThrow("zk"); return zkOk
        }

        override fun verifyAssent(rendering: String, signature: ByteArray): Boolean {
            maybeThrow("assent"); return assentOk
        }

        override fun verifyRevocation(revocation: Revocation): Boolean {
            maybeThrow("revocation"); return revocationOk
        }

        override fun boundaryPermitted(action: Action, substrate: SubstrateState): Boolean {
            maybeThrow("boundary"); return boundaryOk
        }

        override fun policyRules(action: Action, substrate: SubstrateState): Boolean {
            maybeThrow("rules"); return rulesOk
        }

        override fun governanceDivergence(action: Action): Double {
            maybeThrow("divergence"); return divergence
        }

        override fun metaLoopConsistent(context: GateContext): Boolean {
            maybeThrow("loop"); return loopOk
        }

        override fun applyAction(substrate: SubstrateState, action: Action): SubstrateState {
            maybeThrow("apply")
            return evilApply?.invoke(substrate, action) ?: benignApply(substrate, action)
        }

        private fun benignApply(substrate: SubstrateState, action: Action): SubstrateState {
            fun cond(c: Condition, s: SubstrateState): Boolean = when (c) {
                is Condition.FieldEquals -> s.records[c.recordId]?.get(c.field) == c.expected
                is Condition.And -> c.parts.all { cond(it, s) }
                is Condition.Not -> !cond(c.inner, s)
            }
            fun step(s: SubstrateState, a: Action): SubstrateState = when (a) {
                is Action.Read -> s
                is Action.Write -> {
                    val rec = s.records[a.recordId]
                    if (rec == null) s
                    else s.copy(records = s.records + (a.recordId to (rec + a.fields)))
                }
                is Action.Delete -> s.copy(records = s.records - a.recordId)
                is Action.Sequence -> a.steps.fold(s, ::step)
                is Action.Guarded ->
                    if (cond(a.condition, s)) step(s, a.then)
                    else a.otherwise?.let { step(s, it) } ?: s
            }
            return step(substrate, action)
        }
    }

    /** A clock that violates monotonicity: every read is adversarial. */
    private class ChaosClock(val rng: Random, val base: Long = 20_000L) : MonotonicClock {
        override fun nowMs(): Long = base + rng.nextLong(-120_000L, 120_000L)
    }

    private val rails = Rails()

    private val recordPool = listOf("profile", "wallet", "session", "config")

    private fun Random.leaf(): Action = when (nextInt(3)) {
        0 -> Action.Read(recordPool.random(this), listOf("f${nextInt(5)}"))
        1 -> Action.Write(recordPool.random(this), mapOf("f${nextInt(5)}" to "v${nextInt(100)}"))
        else -> Action.Delete(recordPool.random(this))
    }

    private fun Random.condition(depth: Int): Condition {
        if (depth <= 0) return Condition.FieldEquals(recordPool.random(this), "f${nextInt(3)}", "v${nextInt(10)}")
        return when (nextInt(3)) {
            0 -> Condition.FieldEquals(recordPool.random(this), "f${nextInt(3)}", "v${nextInt(10)}")
            1 -> Condition.And(List(nextInt(1, 4)) { condition(depth - 1) })
            else -> Condition.Not(condition(depth - 1))
        }
    }

    private fun Random.action(depth: Int): Action {
        if (depth <= 0) return leaf()
        return when (nextInt(6)) {
            0, 1, 2 -> leaf()
            3 -> Action.Sequence(List(nextInt(1, 4)) { action(depth - 1) })
            4 -> Action.Guarded(condition(depth - 1), action(depth - 1))
            else -> Action.Guarded(condition(depth - 1), action(depth - 1), action(depth - 1))
        }
    }

    /** A context that passes every gate for the given action. */
    private fun passing(
        action: Action,
        clock: MonotonicClock = FakeClock(20_000L),
        assentedAtMs: Long = 0L,
        revocations: List<Revocation> = emptyList(),
    ): GateContext = GateContext(
        signal = Signal("req".toByteArray(), snr = 20.0, wellFormed = true),
        keys = KeyMaterial(
            "req".toByteArray(), "sig".toByteArray(),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0),
        ),
        proofs = Proofs("zk".toByteArray(), "pub".toByteArray(), issuedAtMs = clock.nowMs()),
        contextEntropyBits = 2.0,
        contextParseTrees = 1,
        action = action,
        actionId = "a1",
        substrate = SubstrateState(recordPool.associateWith { mapOf("name" to "x") }),
        ledger = MerkleAuditLog(),
        rendering = render(action),
        assent = Assent("s_a".toByteArray()),
        assentedAtMs = assentedAtMs,
        revocations = revocations,
        clock = clock,
        intentVector = doubleArrayOf(1.0, 0.0),
        currentVector = doubleArrayOf(1.0, 0.0),
    )

    private fun assertHalted(o: GateOutcome): GateOutcome.Halted {
        assertTrue(o is GateOutcome.Halted, "expected Halted but was $o")
        return o
    }

    private fun assertIntegrated(o: GateOutcome): GateOutcome.Integrated {
        assertTrue(o is GateOutcome.Integrated, "expected Integrated but was $o")
        return o
    }

    // ------------------------------------------------------------------
    // Render fidelity: the person signs r, the engine executes a.
    // r != Render(a) must halt; Render must be injective and deterministic.
    // ------------------------------------------------------------------

    @Test
    fun renderIsInjectiveFuzz() {
        val rng = Random(0x9E37)
        val seen = mutableMapOf<String, Action>()
        repeat(2000) {
            val a = rng.action(4)
            val r = render(a)
            val prev = seen.putIfAbsent(r, a)
            assertTrue(prev == null || prev == a, "RENDER COLLISION: $a vs $prev both render as $r")
        }
    }

    @Test
    fun renderIsDeterministicFuzz() {
        val rng = Random(0xA11CE)
        repeat(500) {
            val a = rng.action(4)
            assertEquals(render(a), render(a), "render not deterministic for $a")
            // Write field order must not leak through: canonical sort.
            if (a is Action.Write) {
                val shuffled = Action.Write(a.recordId, a.fields.entries.shuffled(rng).associate { it.key to it.value })
                assertEquals(render(a), render(shuffled), "render depends on map order for $a")
            }
        }
    }

    @Test
    fun tamperedRenderingAlwaysHaltsAtGate7() {
        val rng = Random(0xC0DE)
        repeat(40) {
            val v = FuzzVerifiers()
            val gates = NineGates(rails, v)
            val a = rng.action(3)
            val r = render(a)
            val positions = r.indices.shuffled(rng).take(8)
            for (p in positions) {
                val mutated = r.substring(0, p) + (if (r[p] != 'X') 'X' else 'Y') + r.substring(p + 1)
                val h = assertHalted(gates.evaluate(passing(a).copy(rendering = mutated)))
                assertEquals(7, h.atGate, "tampered rendering integrated: $mutated")
                assertTrue(h.reason.contains("theta_render"), "wrong halt reason: ${h.reason}")
            }
            for (m in listOf(r.dropLast(1), "$r ", " $r")) {
                if (m == r) continue
                val h = assertHalted(gates.evaluate(passing(a).copy(rendering = m)))
                assertEquals(7, h.atGate, "mutated rendering integrated: '$m'")
            }
        }
    }

    // ------------------------------------------------------------------
    // Render injectivity regressions (Copilot HIGH: dangling-ELSE collision).
    // ------------------------------------------------------------------

    @Test
    fun renderDanglingElseIsInjective() {
        val c1 = Condition.FieldEquals("r", "f", "1")
        val c2 = Condition.FieldEquals("r", "f", "2")
        val a = Action.Read("a", listOf("x"))
        val b = Action.Read("b", listOf("y"))
        // Ambiguous without parens: ELSE binds to inner or outer Guarded?
        val innerElse = Action.Guarded(c1, Action.Guarded(c2, a, b), null)
        val outerElse = Action.Guarded(c1, Action.Guarded(c2, a, null), b)
        val r1 = render(innerElse)
        val r2 = render(outerElse)
        assertTrue(r1 != r2, "DANGLING-ELSE COLLISION: both render as $r1")
        // Both must be fully parenthesized so the binding is explicit.
        assertTrue("ELSE (${render(b)})" in r1, "inner ELSE not parenthesized: $r1")
        assertTrue("ELSE (${render(b)})" in r2, "outer ELSE not parenthesized: $r2")
    }

    @Test
    fun renderStructuralCharactersCannotCollide() {
        val evil = listOf(
            "\"", "\\", "\"\"", "\\\"",
            "{", "}", "{a=1,b=2}", "{}",
            "(", ")", "IF", "THEN", "ELSE", "AND", "NOT",
            "==", "=", ",", ";", ".", "SEQ[", "]",
            "WRITE", "READ", "DELETE",
            "r\" {a=\"1", "a\",b=\"2",
            "\n", "\t", " ", "",
            "üñîçødé", "𝄞", "\u0000",
        )
        val seen = mutableMapOf<String, Action>()
        fun check(a: Action) {
            val r = render(a)
            val prev = seen.putIfAbsent(r, a)
            assertTrue(prev == null || prev == a, "RENDER COLLISION on hostile payload: $a vs $prev -> $r")
        }
        for (s in evil) {
            check(Action.Read(s, listOf(s)))
            check(Action.Write(s, mapOf(s to s)))
            check(Action.Delete(s))
            check(Action.Guarded(Condition.FieldEquals(s, s, s), Action.Read(s, listOf(s)), Action.Delete(s)))
        }
        // Cross-constructor: a Write and a Delete whose payloads mimic each
        // other's syntax must never collide.
        assertTrue(
            render(Action.Write("r", mapOf("a" to "1"))) != render(Action.Delete("r\" {\"a\"=\"1\"}")),
            "cross-constructor collision",
        )
    }

    @Test
    fun renderInjectivityPropertyWithHostileAlphabet() {
        // Property: render(a) == render(b) ==> a == b, over a hostile alphabet.
        val rng = Random(0x1EC7)
        val atoms = listOf("r", "f", "v", "\"", "\\", "{", "}", "(", ")", "ELSE", "==", ";", " ", "ünï")
        fun atom(): String = (1..rng.nextInt(1, 4)).map { atoms.random(rng) }.joinToString("")
        fun cond(d: Int): Condition =
            if (d <= 0 || rng.nextBoolean()) Condition.FieldEquals(atom(), atom(), atom())
            else if (rng.nextBoolean()) Condition.And(List(rng.nextInt(1, 3)) { cond(d - 1) })
            else Condition.Not(cond(d - 1))
        fun act(d: Int): Action = when {
            d <= 0 -> when (rng.nextInt(3)) {
                0 -> Action.Read(atom(), List(rng.nextInt(0, 3)) { atom() })
                1 -> Action.Write(atom(), (1..rng.nextInt(0, 3)).associate { atom() to atom() })
                else -> Action.Delete(atom())
            }
            else -> when (rng.nextInt(4)) {
                0 -> Action.Sequence(List(rng.nextInt(1, 3)) { act(d - 1) })
                1 -> Action.Guarded(cond(d - 1), act(d - 1))
                2 -> Action.Guarded(cond(d - 1), act(d - 1), act(d - 1))
                else -> act(0)
            }
        }
        val seen = mutableMapOf<String, Action>()
        repeat(3000) {
            val a = act(3)
            val r = render(a)
            val prev = seen.putIfAbsent(r, a)
            assertTrue(prev == null || prev == a, "INJECTIVITY VIOLATION: $a vs $prev both render as $r")
        }
    }

    // ------------------------------------------------------------------
    // Impact soundness: cooling scales with COMPUTED impact. If impactOf
    // ever understates, the cooling window is too short. Never allowed.
    // ------------------------------------------------------------------

    private fun leafImpacts(a: Action): List<Impact> = when (a) {
        is Action.Read -> listOf(Impact.READ_ONLY)
        is Action.Write -> listOf(Impact.WRITE)
        is Action.Delete -> listOf(Impact.DESTRUCTIVE)
        is Action.Sequence -> a.steps.flatMap(::leafImpacts)
        is Action.Guarded -> leafImpacts(a.then) + (a.otherwise?.let(::leafImpacts) ?: emptyList())
    }

    @Test
    fun impactNeverUnderstatesFuzz() {
        val rng = Random(0x1AC7)
        repeat(2000) {
            val a = rng.action(5)
            val expected = leafImpacts(a).maxOrNull() ?: Impact.READ_ONLY
            assertEquals(expected, impactOf(a), "IMPACT UNDERSTATED for $a")
        }
    }

    @Test
    fun coolingWindowBoundaries() {
        val cases = listOf(
            Action.Read("profile", listOf("name")),
            Action.Write("profile", mapOf("name" to "y")),
            Action.Delete("profile"),
        )
        for (a in cases) {
            val window = rails.coolingWindowMs(impactOf(a))
            val t = 100_000L
            // elapsed = window - 1 -> halt on theta_cool
            var h = assertHalted(
                NineGates(rails, FuzzVerifiers()).evaluate(
                    passing(a, FakeClock(t), assentedAtMs = t - window + 1)
                )
            )
            assertEquals(7, h.atGate)
            assertTrue(h.reason.contains("theta_cool"), h.reason)
            // elapsed = window -> integrate
            assertIntegrated(
                NineGates(rails, FuzzVerifiers()).evaluate(
                    passing(a, FakeClock(t), assentedAtMs = t - window)
                )
            )
            // elapsed = window + 1 -> integrate
            assertIntegrated(
                NineGates(rails, FuzzVerifiers()).evaluate(
                    passing(a, FakeClock(t), assentedAtMs = t - window - 1)
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Gate 0 boundaries: node count, depth, empty sequence.
    // ------------------------------------------------------------------

    @Test
    fun gate0Boundaries() {
        fun seq(n: Int) = Action.Sequence(List(n) { Action.Read("profile", listOf("name")) })
        // seq(n) has n+1 nodes; bound is 64.
        assertIntegrated(NineGates(rails, FuzzVerifiers()).evaluate(passing(seq(63))))
        assertEquals(0, assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(passing(seq(64)))).atGate)

        fun nest(d: Int): Action =
            if (d <= 1) Action.Read("profile", listOf("name"))
            else Action.Guarded(Condition.FieldEquals("profile", "name", "x"), nest(d - 1))
        // nest(d) has depth d; bound is 8.
        assertIntegrated(NineGates(rails, FuzzVerifiers()).evaluate(passing(nest(8))))
        assertEquals(0, assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(passing(nest(9)))).atGate)

        assertEquals(
            0,
            assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(passing(Action.Sequence(emptyList())))).atGate
        )
    }

    // ------------------------------------------------------------------
    // Gate 8 revocation matrix: halt iff id matches AND sig valid AND
    // revocation time is not in the future.
    // ------------------------------------------------------------------

    @Test
    fun revocationMatrix() {
        val t = 50_000L
        for (idOk in listOf(true, false)) {
            for (sigOk in listOf(true, false)) {
                for (timeOk in listOf(true, false)) {
                    val v = FuzzVerifiers(revocationOk = sigOk)
                    val rev = Revocation(
                        if (idOk) "a1" else "other",
                        "s".toByteArray(),
                        if (timeOk) t - 1000 else t + 1000
                    )
                    val out = NineGates(rails, v).evaluate(
                        passing(Action.Read("profile", listOf("name")), FakeClock(t), revocations = listOf(rev))
                    )
                    if (idOk && sigOk && timeOk) {
                        val h = assertHalted(out)
                        assertEquals(8, h.atGate, "valid revocation did not halt (id=$idOk sig=$sigOk time=$timeOk)")
                    } else {
                        assertIntegrated(out)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // NaN / infinite numerics: every comparison gate must treat
    // non-finite as hostile. NaN < x is false — without explicit guards
    // NaN sails through every threshold check.
    // ------------------------------------------------------------------

    @Test
    fun nanInputsAlwaysHalt() {
        val nan = Double.NaN
        val base = passing(Action.Read("profile", listOf("name")))
        val cases = listOf(
            "snr" to base.copy(signal = Signal("r".toByteArray(), nan, true)),
            "entropy" to base.copy(contextEntropyBits = nan),
            "biometric" to base.copy(
                keys = base.keys.copy(
                    biometricTemplate = doubleArrayOf(nan, nan),
                    enrolledTemplate = doubleArrayOf(0.0, 0.0)
                )
            ),
            "intentVector" to base.copy(intentVector = doubleArrayOf(nan, nan)),
            "currentVector" to base.copy(currentVector = doubleArrayOf(nan, nan)),
        )
        for ((name, ctx) in cases) {
            val out = NineGates(rails, FuzzVerifiers()).evaluate(ctx)
            assertTrue(out is GateOutcome.Halted, "NaN $name INTEGRATED")
        }
        val v = FuzzVerifiers(divergence = nan)
        val out = NineGates(rails, v).evaluate(base)
        assertTrue(out is GateOutcome.Halted, "NaN divergence INTEGRATED")
    }

    @Test
    fun infiniteInputsAlwaysHalt() {
        for (inf in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val base = passing(Action.Read("profile", listOf("name")))
            val cases = listOf(
                base.copy(signal = Signal("r".toByteArray(), inf, true)),
                base.copy(contextEntropyBits = inf),
                base.copy(
                    keys = base.keys.copy(
                        biometricTemplate = doubleArrayOf(inf, inf),
                        enrolledTemplate = doubleArrayOf(0.0, 0.0)
                    )
                ),
            )
            for (ctx in cases) {
                val out = NineGates(rails, FuzzVerifiers()).evaluate(ctx)
                assertTrue(out is GateOutcome.Halted, "infinite input ($inf) INTEGRATED")
            }
        }
    }

    // ------------------------------------------------------------------
    // Hostile ports: a throwing verifier must halt the engine, never
    // propagate. The contract says ports are total; this is defense in depth.
    // ------------------------------------------------------------------

    @Test
    fun throwingVerifiersNeverEscape() {
        val ports = listOf("sig", "zk", "assent", "revocation", "boundary", "rules", "divergence", "loop", "apply")
        for (p in ports) {
            val v = FuzzVerifiers(throwPorts = setOf(p), revocationOk = true)
            val revs = if (p == "revocation") listOf(Revocation("a1", "s".toByteArray(), 1000L)) else emptyList()
            val out = NineGates(rails, v).evaluate(
                passing(Action.Read("profile", listOf("name")), revocations = revs)
            )
            assertTrue(out is GateOutcome.Halted, "throwing port '$p' ESCAPED the engine")
        }
    }

    // ------------------------------------------------------------------
    // Hostile clock: non-monotonic, jumping. The engine must never crash
    // and must always resolve to Integrated or Halted.
    // ------------------------------------------------------------------

    @Test
    fun hostileClockNeverCrashes() {
        val rng = Random(0xBADC10C)
        repeat(300) {
            val v = FuzzVerifiers()
            val out = NineGates(rails, v).evaluate(passing(rng.action(3), ChaosClock(rng)))
            assertTrue(
                out is GateOutcome.Integrated || out is GateOutcome.Halted,
                "hostile clock produced impossible outcome: $out"
            )
        }
    }

    // ------------------------------------------------------------------
    // Ledger discipline: exactly one append per evaluation; integrity holds.
    // ------------------------------------------------------------------

    @Test
    fun ledgerAppendsExactlyOnceFuzz() {
        val rng = Random(0x1ED6E)
        repeat(200) {
            val v = FuzzVerifiers()
            when (rng.nextInt(6)) {
                0 -> v.sigOk = false
                1 -> v.zkOk = false
                2 -> v.rulesOk = false
                3 -> v.boundaryOk = false
                4 -> v.loopOk = false
                5 -> { /* all pass */ }
            }
            val ctx = passing(rng.action(3))
            val before = ctx.ledger.size
            NineGates(rails, v).evaluate(ctx)
            assertEquals(before + 1, ctx.ledger.size, "evaluate must append exactly one ledger entry")
            assertEquals(-1, ctx.ledger.verifyIntegrity())
        }
    }

    @Test
    fun merkleLogRandomAppendsVerify() {
        val rng = Random(0x9E5)
        val log = MerkleAuditLog()
        repeat(500) {
            log.append(ByteArray(rng.nextInt(1, 256)) { rng.nextInt(256).toByte() })
        }
        assertEquals(500, log.size)
        assertEquals(-1, log.verifyIntegrity())
    }

    // ------------------------------------------------------------------
    // Smuggling: unknown records nested deep must halt at Gate 2 (phi_iso).
    // ------------------------------------------------------------------

    @Test
    fun smuggledRecordsAlwaysHaltAtGate2() {
        val rng = Random(0x611)
        repeat(200) {
            val ghost = Action.Write("ghost_${rng.nextInt(1000)}", mapOf("x" to "y"))
            val a: Action = when (rng.nextInt(3)) {
                0 -> Action.Sequence(listOf(Action.Read("profile", listOf("name")), ghost))
                1 -> Action.Guarded(Condition.FieldEquals("profile", "name", "x"), ghost)
                else -> Action.Guarded(
                    Condition.FieldEquals("profile", "name", "x"),
                    Action.Read("profile", listOf("name")),
                    ghost
                )
            }
            val h = assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(passing(a)))
            assertEquals(2, h.atGate, "smuggled record not caught at gate 2: $a")
            assertTrue(h.reason.contains("phi_iso"), h.reason)
        }
    }

    // ------------------------------------------------------------------
    // Malicious substrate transition: a port that creates records must
    // trip psi_inv at Gate 6, never integrate.
    // ------------------------------------------------------------------

    @Test
    fun recordCreatingTransitionHaltsAtGate6() {
        val v = FuzzVerifiers()
        v.evilApply = { sub, _ -> sub.copy(records = sub.records + ("planted" to mapOf("x" to "y"))) }
        val h = assertHalted(
            NineGates(rails, v).evaluate(passing(Action.Read("profile", listOf("name"))))
        )
        assertEquals(6, h.atGate)
        assertTrue(h.reason.contains("psi_inv"), h.reason)
    }

    // ------------------------------------------------------------------
    // Empty vectors: ambiguous evidence halts, never passes, never crashes.
    // ------------------------------------------------------------------

    @Test
    fun emptyVectorsHaltFailClosed() {
        val base = passing(Action.Read("profile", listOf("name")))
        val e1 = base.copy(
            keys = base.keys.copy(biometricTemplate = doubleArrayOf(), enrolledTemplate = doubleArrayOf())
        )
        assertEquals(1, assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(e1)).atGate)
        val e2 = base.copy(intentVector = doubleArrayOf(), currentVector = doubleArrayOf())
        assertEquals(5, assertHalted(NineGates(rails, FuzzVerifiers()).evaluate(e2)).atGate)
    }
}
