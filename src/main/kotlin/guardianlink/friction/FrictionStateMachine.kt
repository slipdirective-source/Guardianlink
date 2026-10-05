package guardianlink.friction

import java.util.concurrent.atomic.AtomicReference

/**
 * Friction state machine: cooling-off rails for high-stakes modifications.
 *
 * TIME TRUST BOUNDARY: all time comes from the injected [clock] (epoch ms).
 * Callers MUST inject a monotonic source — a wall clock that jumps backward
 * shortens cooling windows (fail-open direction). As defense in depth the
 * machine additionally holds a monotonic high-water mark internally, so a
 * backward jump can never shorten an already-started window; it can only
 * delay this machine's own view of time. There is deliberately no
 * caller-supplied timestamp parameter: time is a capability of the
 * deployment, not an argument of the request.
 */
class FrictionStateMachine(
    private val clock: () -> Long = System::currentTimeMillis,
) {

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

    // Monotonic high-water mark for the injected clock (see class KDoc).
    private var lastNowMs = 0L

    @Synchronized
    private fun nowMs(): Long {
        val t = clock()
        if (t > lastNowMs) lastNowMs = t
        return lastNowMs
    }

    fun createHardRail(id: String, baseCoolingOffMs: Long): Rail {
        // baseCoolingOffMs is reserved for future use (the window is set per
        // requestModification call); validated anyway so a negative value
        // cannot silently become meaningful later.
        require(baseCoolingOffMs >= 0) { "baseCoolingOffMs must be >= 0" }
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
        baseCoolingOffMs: Long
    ): TransitionResult {
        // A negative base would place coolingOffUntilMs in the past and let
        // finalizeModification succeed immediately — a cooling bypass.
        require(baseCoolingOffMs >= 0) { "baseCoolingOffMs must be >= 0" }
        val ref = rails[id] ?: return TransitionResult(success = false, rail = null)

        while (true) {
            val prior = ref.get()
            val nowMs = nowMs()

            // Uncapped: this is the true retry-pressure count and belongs in the
            // audit trail as-is (evidence-only fidelity — don't clamp what actually
            // happened). A rail retried 23 times should show 23, not 6.
            val escalation = if (prior.state == RailState.COOLING_OFF) prior.anomalyEscalationCount + 1 else 0

            // Capped separately: only the backoff *multiplier* needs a ceiling, so
            // the cooling-off window doesn't grow unbounded on paper while still
            // reflecting true attempt count in the stored field. Clamped at both
            // ends: a negative escalation (Int overflow after 2^31 retries) must
            // not reach the shift below.
            val cappedForBackoff = minOf(6, maxOf(0, escalation))
            // Saturating: baseCoolingOffMs * 64 overflows Long for bases above
            // ~1.4e17, and nowMs + window overflows for huge windows. Wrapping
            // would place coolingOffUntilMs in the past — an instant cooling
            // bypass. Saturation extends the window instead, fail-closed.
            val extendedCoolingOff = try {
                Math.multiplyExact(baseCoolingOffMs, 1L shl cappedForBackoff)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            val coolingOffUntil = try {
                Math.addExact(nowMs, extendedCoolingOff)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }

            val updated = prior.copy(
                state = RailState.COOLING_OFF,
                coolingOffUntilMs = coolingOffUntil,
                monotonicStamp = nextTick(),
                anomalyEscalationCount = escalation  // uncapped, stored as-is
            )

            if (ref.compareAndSet(prior, updated)) {
                return TransitionResult(success = true, rail = updated)
            }
        }
    }

    fun finalizeModification(id: String): TransitionResult {
        val ref = rails[id] ?: return TransitionResult(success = false, rail = null)

        while (true) {
            val prior = ref.get()
            if (prior.state != RailState.COOLING_OFF) {
                return TransitionResult(success = false, rail = prior, conflict = true)
            }
            val nowMs = nowMs()
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