package guardianlink.friction

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class FrictionStateMachine {

    enum class RailType { SOFT_RULE, HARD_RAIL }
    enum class RailState { ACTIVE, COOLING_OFF, MODIFIABLE }

    data class Rail(
        val id: String,
        val type: RailType,
        val state: RailState,
        val coolingOffUntilMs: Long,
        val monotonicStamp: Long,
        val anomalyEscalationCount: Int = 0
    )

    data class TransitionResult(val success: Boolean, val rail: Rail?, val conflict: Boolean = false)

    private val rails = mutableMapOf<String, AtomicReference<Rail>>()
    private var monotonicCounter = 0L

    fun createHardRail(id: String, baseCoolingOffMs: Long): Rail {
        val rail = Rail(
            id = id,
            type = RailType.HARD_RAIL,
            state = RailState.ACTIVE,
            coolingOffUntilMs = 0L,
            monotonicStamp = nextTick()
        )
        rails[id] = AtomicReference(rail)
        return rail
    }

    fun createSoftRule(id: String): Rail {
        val rail = Rail(
            id = id,
            type = RailType.SOFT_RULE,
            state = RailState.ACTIVE,
            coolingOffUntilMs = 0L,
            monotonicStamp = nextTick()
        )
        rails[id] = AtomicReference(rail)
        return rail
    }

    @Synchronized
    private fun nextTick(): Long = ++monotonicCounter

    fun requestModification(
        id: String,
        baseCoolingOffMs: Long,
        now: Instant = Instant.now()
    ): TransitionResult {
        val ref = rails[id] ?: return TransitionResult(success = false, rail = null)

        while (true) {
            val prior = ref.get()
            val nowMs = now.toEpochMilli()

            val escalation = if (prior.state == RailState.COOLING_OFF) prior.anomalyEscalationCount + 1 else 0
            val extendedCoolingOff = baseCoolingOffMs * (1L shl minOf(escalation, 6))

            val updated = prior.copy(
                state = RailState.COOLING_OFF,
                coolingOffUntilMs = nowMs + extendedCoolingOff,
                monotonicStamp = nextTick(),
                anomalyEscalationCount = escalation
            )

            if (ref.compareAndSet(prior, updated)) {
                return TransitionResult(success = true, rail = updated)
            }
        }
    }

    fun finalizeModification(id: String, now: Instant = Instant.now()): TransitionResult {
        val ref = rails[id] ?: return TransitionResult(success = false, rail = null)

        while (true) {
            val prior = ref.get()
            if (prior.state != RailState.COOLING_OFF) {
                return TransitionResult(success = false, rail = prior, conflict = true)
            }
            val nowMs = now.toEpochMilli()
            if (nowMs < prior.coolingOffUntilMs) {
                return TransitionResult(success = false, rail = prior)
            }

            val updated = prior.copy(
                state = RailState.MODIFIABLE,
                monotonicStamp = nextTick()
            )
            if (ref.compareAndSet(prior, updated)) {
                return TransitionResult(success = true, rail = updated)
            }
        }
    }

    fun getRail(id: String): Rail? = rails[id]?.get()
}