package guardianlink.friction

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrictionStateMachineTest {
    // Time is injected, never caller-supplied: the clock lambda is the only
    // time source the machine sees.
    private var now = 1_000_000L
    private val fsm = FrictionStateMachine(clock = { now })

    @Test
    fun testCreateHardRail() {
        val rail = fsm.createHardRail("test-rail", 1000L)
        assertEquals("test-rail", rail.id)
        assertEquals(FrictionStateMachine.RailType.HARD_RAIL, rail.type)
        assertEquals(FrictionStateMachine.RailState.ACTIVE, rail.state)
        assertEquals(0, rail.anomalyEscalationCount)
    }

    @Test
    fun testCreateSoftRule() {
        val rule = fsm.createSoftRule("soft-rule")
        assertEquals("soft-rule", rule.id)
        assertEquals(FrictionStateMachine.RailType.SOFT_RULE, rule.type)
        assertEquals(FrictionStateMachine.RailState.ACTIVE, rule.state)
    }

    @Test
    fun testRequestModificationSucceeds() {
        fsm.createHardRail("rail-1", 1000L)
        val result = fsm.requestModification("rail-1", 1000L)
        assertTrue(result.success)
        assertNotNull(result.rail)
        assertEquals(FrictionStateMachine.RailState.COOLING_OFF, result.rail!!.state)
    }

    @Test
    fun testRequestModificationNonexistentRail() {
        val result = fsm.requestModification("nonexistent", 1000L)
        assertFalse(result.success)
    }

    @Test
    fun testEscalationCountIncrementsOnRetry() {
        fsm.createHardRail("escalation-test", 100L)

        val mod1 = fsm.requestModification("escalation-test", 100L)
        assertEquals(0, mod1.rail!!.anomalyEscalationCount)

        val mod2 = fsm.requestModification("escalation-test", 100L)
        assertEquals(1, mod2.rail!!.anomalyEscalationCount)

        val mod3 = fsm.requestModification("escalation-test", 100L)
        assertEquals(2, mod3.rail!!.anomalyEscalationCount)
    }

    @Test
    fun testEscalationCountCapsBackoffMultiplier() {
        fsm.createHardRail("cap-test", 1000L)

        var result = fsm.requestModification("cap-test", 1000L)

        repeat(10) {
            result = fsm.requestModification("cap-test", 1000L)
        }

        val finalWindow = result.rail!!.coolingOffUntilMs - now
        val maxExpected = 1000L * (1L shl 6)  // 64x cap

        assertTrue(finalWindow <= maxExpected)
        assertEquals(10, result.rail!!.anomalyEscalationCount)  // uncapped in storage
    }

    @Test
    fun testFinalizeModificationBeforeCoolingOff() {
        fsm.createHardRail("finalize-test", 5000L)
        fsm.requestModification("finalize-test", 5000L)

        // Try to finalize immediately (still in cooling-off)
        val result = fsm.finalizeModification("finalize-test")
        assertFalse(result.success)
        assertTrue(result.conflict == false)  // not a conflict, just not ready
    }

    @Test
    fun testFinalizeModificationAfterCoolingOff() {
        fsm.createHardRail("finalize-after", 100L)
        fsm.requestModification("finalize-after", 100L)

        now += 200L
        val result = fsm.finalizeModification("finalize-after")

        assertTrue(result.success)
        assertEquals(FrictionStateMachine.RailState.MODIFIABLE, result.rail!!.state)
    }

    @Test
    fun testEscalationCountPersistsAfterFinalize() {
        fsm.createHardRail("persist-test", 50L)

        // Rack up some escalation
        fsm.requestModification("persist-test", 50L)
        fsm.requestModification("persist-test", 50L)
        val beforeFinalize = fsm.getRail("persist-test")!!
        assertEquals(1, beforeFinalize.anomalyEscalationCount)

        // Finalize
        now += 100L
        fsm.finalizeModification("persist-test")
        val afterFinalize = fsm.getRail("persist-test")!!

        // Escalation count unchanged
        assertEquals(1, afterFinalize.anomalyEscalationCount)
    }

    @Test
    fun testGetRail() {
        fsm.createHardRail("get-rail", 1000L)
        val rail = fsm.getRail("get-rail")
        assertNotNull(rail)
        assertEquals("get-rail", rail.id)
    }

    @Test
    fun testNegativeCoolingBaseRejected() {
        // Round-2: a negative base placed coolingOffUntilMs in the past,
        // letting finalizeModification succeed immediately (cooling bypass).
        fsm.createHardRail("neg-rail", 1000L)
        assertFailsWith<IllegalArgumentException> {
            fsm.requestModification("neg-rail", -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            fsm.createHardRail("neg-create", -5L)
        }
    }

    @Test
    fun testOverflowingCoolingBaseStaysCooling() {
        // Round-2: base * 64 overflowed Long and wrapped the window into the
        // past — an instant cooling bypass. Saturation must keep the rail
        // cooling essentially forever instead.
        fsm.createHardRail("huge-rail", 1000L)
        val mod = fsm.requestModification("huge-rail", Long.MAX_VALUE)
        assertTrue(mod.success)
        val fin = fsm.finalizeModification("huge-rail")
        assertFalse(fin.success, "overflowing cooling window must not finalize")
        assertEquals(
            FrictionStateMachine.RailState.COOLING_OFF,
            fsm.getRail("huge-rail")?.state
        )
    }

    @Test
    fun testBackwardClockJumpCannotShortenCoolingWindow() {
        // Defense in depth: the internal monotonic high-water mark means a
        // backward jump of the injected clock never shortens a live window.
        fsm.createHardRail("backward-test", 5000L)
        fsm.requestModification("backward-test", 5000L)
        val until = fsm.getRail("backward-test")!!.coolingOffUntilMs
        assertEquals(now + 5000L, until)

        now -= 10_000L // clock jumps backward
        val result = fsm.finalizeModification("backward-test")
        assertFalse(result.success, "backward clock jump shortened the cooling window")
        assertEquals(until, fsm.getRail("backward-test")!!.coolingOffUntilMs)
    }
}
