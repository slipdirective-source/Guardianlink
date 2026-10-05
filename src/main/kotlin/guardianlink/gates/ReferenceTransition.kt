package guardianlink.gates

/**
 * Reference substrate transition: the canonical evaluator for [Action]
 * used by the demo and the test harness.
 *
 * Exists because two hand-rolled evaluators (demo vs tests) DISAGREED on
 * `Guarded` semantics — the demo always took the `then` branch, ignoring
 * the condition. That is a fail-open adapter bug class: the engine is
 * correct, but an adapter that mis-evaluates conditions executes actions
 * the policy never authorized. One canonical implementation, shared by
 * every in-repo consumer, makes that disagreement structurally impossible.
 *
 * Semantics (total, pure, deterministic):
 * - Read: no-op. Write: merge fields into the existing record (no-op if
 *   the record is absent — creation is forbidden, cf. Gate 6 psi_inv).
 * - Delete: remove the record. Sequence: fold left.
 * - Guarded: evaluate the condition against the CURRENT substrate state
 *   (threading through Sequence steps); take `then` when true, `otherwise`
 *   when false, no-op when false and `otherwise` is null.
 *
 * Production adapters MUST implement these exact semantics for Guarded;
 * anything else invalidates the Gate 6/8 invariant checks.
 */
object ReferenceTransition {
    fun conditionHolds(c: Condition, s: SubstrateState): Boolean = when (c) {
        is Condition.FieldEquals -> s.records[c.recordId]?.get(c.field) == c.expected
        is Condition.And -> c.parts.all { conditionHolds(it, s) }
        is Condition.Not -> !conditionHolds(c.inner, s)
    }

    fun apply(substrate: SubstrateState, action: Action): SubstrateState {
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
                if (conditionHolds(a.condition, s)) step(s, a.then)
                else a.otherwise?.let { step(s, it) } ?: s
        }
        return step(substrate, action)
    }
}
