package guardianlink.gates

/**
 * A_total — the total action space (Codex v2.2, §I.2, Gate 0).
 *
 * Every action must parse into a total, well-typed, bounded, acyclic AST.
 * Anything that does not validate is rejected at Gate 0, so every later
 * predicate evaluates in bounded time. Acyclicity is structural: the AST
 * is a finite tree. [validateAction] additionally enforces the node-count
 * and depth bounds from the fixed [Rails].
 *
 * The AST is closed: there are no external-effect nodes (no network, no
 * IO). Gate 2's leak predicate (phi_leak) therefore holds by construction —
 * an action cannot name an effect outside the substrate.
 */
sealed interface Action {
    /** Read fields of a record. Pure: no substrate mutation. */
    data class Read(val recordId: String, val fields: List<String>) : Action

    /** Write fields of an existing record. */
    data class Write(val recordId: String, val fields: Map<String, String>) : Action

    /** Delete a record. Highest impact class. */
    data class Delete(val recordId: String) : Action

    /** Bounded sequence of actions, executed in order. */
    data class Sequence(val steps: List<Action>) : Action

    /** Conditional execution over a pure, side-effect-free condition. */
    data class Guarded(val condition: Condition, val then: Action, val otherwise: Action? = null) : Action
}

/** Pure conditions over the substrate state; total and side-effect free. */
sealed interface Condition {
    data class FieldEquals(val recordId: String, val field: String, val expected: String) : Condition
    data class And(val parts: List<Condition>) : Condition
    data class Not(val inner: Condition) : Condition
}

/**
 * Impact class, COMPUTED from the AST — never self-declared (Codex v2.2,
 * Gate 7: "Impact(a) is computed from the AST, never self-declared").
 */
enum class Impact { READ_ONLY, WRITE, DESTRUCTIVE }

fun impactOf(a: Action): Impact = when (a) {
    is Action.Read -> Impact.READ_ONLY
    is Action.Write -> Impact.WRITE
    is Action.Delete -> Impact.DESTRUCTIVE
    is Action.Sequence -> a.steps.map(::impactOf).maxOrNull() ?: Impact.READ_ONLY
    is Action.Guarded -> maxOf(impactOf(a.then), a.otherwise?.let(::impactOf) ?: Impact.READ_ONLY)
}

/**
 * Gate 0 membership check: a ∈ A_total.
 * Type-safety is enforced by the Kotlin type system; boundedness here.
 */
fun validateAction(a: Action, rails: Rails): Boolean {
    fun count(n: Action): Int = when (n) {
        is Action.Read, is Action.Write, is Action.Delete -> 1
        is Action.Sequence -> 1 + n.steps.sumOf(::count)
        is Action.Guarded -> 1 + count(n.then) + (n.otherwise?.let(::count) ?: 0)
    }
    fun depth(n: Action): Int = when (n) {
        is Action.Read, is Action.Write, is Action.Delete -> 1
        is Action.Sequence -> 1 + (n.steps.maxOfOrNull(::depth) ?: 0)
        is Action.Guarded -> 1 + maxOf(depth(n.then), n.otherwise?.let(::depth) ?: 0)
    }
    if (a is Action.Sequence && a.steps.isEmpty()) return false
    return count(a) <= rails.maxActionNodes && depth(a) <= rails.maxActionDepth
}

/** Conservative static resource estimates from the AST (Gate 7, theta_mem). */
fun estimatedMemoryBytes(a: Action, rails: Rails): Long = nodeCount(a) * rails.bytesPerNode
fun estimatedCycles(a: Action, rails: Rails): Long = nodeCount(a) * rails.cyclesPerNode

private fun nodeCount(n: Action): Int = when (n) {
    is Action.Read, is Action.Write, is Action.Delete -> 1
    is Action.Sequence -> 1 + n.steps.sumOf(::nodeCount)
    is Action.Guarded -> 1 + nodeCount(n.then) + (n.otherwise?.let(::nodeCount) ?: 0)
}
