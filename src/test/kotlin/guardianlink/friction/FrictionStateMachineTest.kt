package guardianlink.friction

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrictionStateMachineTest {
    private val fsm = FrictionStateMachine()

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
        val now = Instant.now()
        fsm.createHardRail("escalation-test", 100L)
        
        val mod1 = fsm.requestModification("escalation-test", 100L, now)
        assertEquals(0, mod1.rail!!.anomalyEscalationCount)
        
        val mod2 = fsm.requestModification("escalation-test", 100L, now)
        assertEquals(1, mod2.rail!!.anomalyEscalationCount)
        
        val mod3 = fsm.requestModification("escalation-test", 100L, now)
        assertEquals(2, mod3.rail!!.anomalyEscalationCount)
    }

    @Test
    fun testEscalationCountCapsBackoffMultiplier() {
        val now = Instant.now()
        fsm.createHardRail("cap-test", 1000L)
        
        var result = fsm.requestModification("cap-test", 1000L, now)
        var window = result.rail!!.coolingOffUntilMs - now.toEpochMilli()
        
        repeat(10) {
            result = fsm.requestModification("cap-test", 1000L, now)
        }
        
        val finalWindow = result.rail!!.coolingOffUntilMs - now.toEpochMilli()
        val maxExpected = 1000L * (1L shl 6)  // 64x cap
        
        assertTrue(finalWindow <= maxExpected)
        assertEquals(10, result.rail!!.anomalyEscalationCount)  // uncapped in storage
    }

    @Test
    fun testFinalizeModificationBeforeCoolingOff() {
        val now = Instant.now()
        fsm.createHardRail("finalize-test", 5000L)
        fsm.requestModification("finalize-test", 5000L, now)
        
        // Try to finalize immediately (still in cooling-off)
        val result = fsm.finalizeModification("finalize-test", now)
        assertFalse(result.success)
        assertTrue(result.conflict == false)  // not a conflict, just not ready
    }

    @Test
    fun testFinalizeModificationAfterCoolingOff() {
        val now = Instant.now()
        fsm.createHardRail("finalize-after", 100L)
        fsm.requestModification("finalize-after", 100L, now)
        
        val later = now.plusMillis(200L)
        val result = fsm.finalizeModification("finalize-after", later)
        
        assertTrue(result.success)
        assertEquals(FrictionStateMachine.RailState.MODIFIABLE, result.rail!!.state)
    }

    @Test
    fun testEscalationCountPersistsAfterFinalize() {
        val now = Instant.now()
        fsm.createHardRail("persist-test", 50L)
        
        // Rack up some escalation
        fsm.requestModification("persist-test", 50L, now)
        fsm.requestModification("persist-test", 50L, now)
        val beforeFinalize = fsm.getRail("persist-test")!!
        assertEquals(1, beforeFinalize.anomalyEscalationCount)
        
        // Finalize
        val later = now.plusMillis(100L)
        fsm.finalizeModification("persist-test", later)
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
}
