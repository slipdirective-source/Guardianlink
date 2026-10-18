package guardianlink.core

import guardianlink.gates.Action
import guardianlink.gates.FakeClock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Red-team: assent replay at the execution boundary.
 *
 * Gate 7 binds the assent signature to (rendering, assentedAtMs, actionId),
 * which defeats *forged* and *cross-action* replay. It does not defeat
 * *exact* replay: presenting the same authorized (action, evidence) pair a
 * second time. Nothing tracks that the assent was already consumed by a
 * sealed execution, so one human assent can mutate the substrate N times.
 *
 * The first test below FAILS on the current engine (the replay executes)
 * and PASSES once assent consumption is enforced at the seal boundary.
 * The second test is a control: cross-action replay is already defeated.
 */
class AssentReplayRedTeamTest {

    @Test
    fun exactReplayOfSealedActionMustHalt() {
        val rig = testRig()
        val action = Action.Write("profile", mapOf("name" to "Neo"))
        val evidence = testEvidence(action, rig.voice)

        val first = rig.concierge.execute(action, evidence)
        assertTrue(first is ConciergeInterface.ExecutionResult.Executed,
            "setup: first execution must pass, got $first")

        // Exact replay: the same authorized pair, one assent, second mutation.
        val second = rig.concierge.execute(action, evidence)
        assertTrue(second is ConciergeInterface.ExecutionResult.Rejected,
            "REPLAY: the same (action, evidence) executed twice on a single " +
                "assent — the substrate mutated twice, anchored twice. " +
                "Got: $second")

        // Exactly one seal line: one assent, one transition, one anchor.
        assertEquals(1, anchorLines(rig),
            "expected exactly one anchored seal for one assent")
    }

    @Test
    fun crossActionReplayAlreadyDefeated() {
        val rig = testRig()
        val actionA = Action.Write("profile", mapOf("name" to "Neo"))
        val actionB = Action.Write("profile", mapOf("name" to "Mallory"))
        // Assent ceremony performed for A; attacker submits B with A's evidence.
        val evidenceA = testEvidence(actionA, rig.voice)

        val result = rig.concierge.execute(actionB, evidenceA)
        val rejected = assertIs<ConciergeInterface.ExecutionResult.Rejected>(result)
        assertEquals(7, rejected.atGate,
            "cross-action replay must halt at Gate 7 (rendering binding)")
        assertEquals("Caleb",
            rig.concierge.currentSubstrate().records["profile"]?.get("name"))
    }

    private fun anchorLines(rig: TestRig): Int {
        val f = rig.anchorDir.resolve("anchors.log")
        if (!java.nio.file.Files.exists(f)) return 0
        return java.nio.file.Files.readAllLines(f, Charsets.UTF_8)
            .count { it.isNotBlank() }
    }

    private inline fun <reified T> assertIs(value: Any): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but was $value")
        return value
    }

    @Test
    fun sealKeysDecayWithTheAssentTheyConsumed() {
        val clock = FakeClock(60_000L)
        val rig = testRig(clock = clock)
        val action = Action.Write("profile", mapOf("name" to "Neo"))
        val evidence = testEvidence(action, rig.voice, assentedAtMs = 60_000L)

        // Seal it: the key is live.
        assertIs<ConciergeInterface.ExecutionResult.Executed>(
            rig.concierge.execute(action, evidence))

        // Immediate replay → halted by the live seal key.
        val replay = assertIs<ConciergeInterface.ExecutionResult.Rejected>(
            rig.concierge.execute(action, evidence))
        assertTrue(replay.reason.contains("already consumed"),
            "expected consumption halt, got: ${replay.reason}")

        // 25h pass with no ledger activity: the assent dies by the idle
        // rule, and the seal key decays with it.
        clock.t += 25 * 3_600_000L

        // The old ceremony, presented with fresh proofs (isolating the
        // assent's death from proof staleness), must halt at Gate 7 on
        // theta_assent_idle — not at the seal check, proving the key decayed.
        val staleEvidence = testEvidence(action, rig.voice,
            tau = clock.t, assentedAtMs = 60_000L)
        val staleReplay = assertIs<ConciergeInterface.ExecutionResult.Rejected>(
            rig.concierge.execute(action, staleEvidence))
        assertTrue(staleReplay.reason.contains("theta_assent_idle"),
            "expected idle-expiry halt after key decay, got: ${staleReplay.reason}")
    }

    @Test
    fun freshCeremonyAfterDecayIsANewKey() {
        val clock = FakeClock(60_000L)
        val rig = testRig(clock = clock)
        val action = Action.Write("profile", mapOf("name" to "Neo"))

        assertIs<ConciergeInterface.ExecutionResult.Executed>(
            rig.concierge.execute(action, testEvidence(action, rig.voice,
                assentedAtMs = 60_000L)))

        // 25h of silence: the old key decays.
        clock.t += 25 * 3_600_000L

        // A fresh ceremony (new assent) for the same actionId is a new key:
        // the decayed record must not block it.
        val fresh = rig.concierge.execute(action, testEvidence(action, rig.voice,
            tau = clock.t, assentedAtMs = clock.t))
        assertIs<ConciergeInterface.ExecutionResult.Executed>(fresh)
    }
}
