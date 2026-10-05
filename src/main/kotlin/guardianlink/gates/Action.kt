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
 *
 * Bounds cover the WHOLE AST, not just action nodes: condition subtrees
 * (nodes and depth), every string atom in UTF-8 bytes (multibyte characters
 * count as their byte length, not their char count), Read field counts,
 * Write map cardinalities, and total payload bytes. An attacker-shaped AST
 * with a tiny action skeleton but a megabyte condition or a megabyte string
 * is not in A_total.
 */
fun validateAction(a: Action, rails: Rails): Boolean {
    var actionNodes = 0
    var conditionNodes = 0
    var payloadBytes = 0L

    fun strOk(s: String): Boolean {
        val bytes = s.toByteArray(Charsets.UTF_8).size
        if (bytes > rails.maxStringBytes) return false
        payloadBytes += bytes
        return payloadBytes <= rails.maxPayloadBytes
    }

    fun condOk(c: Condition, depth: Int): Boolean {
        if (++conditionNodes > rails.maxConditionNodes) return false
        if (depth > rails.maxConditionDepth) return false
        return when (c) {
            is Condition.FieldEquals ->
                strOk(c.recordId) && strOk(c.field) && strOk(c.expected)
            is Condition.And ->
                c.parts.isNotEmpty() && c.parts.all { condOk(it, depth + 1) }
            is Condition.Not -> condOk(c.inner, depth + 1)
        }
    }

    fun actOk(n: Action, depth: Int): Boolean {
        if (++actionNodes > rails.maxActionNodes) return false
        if (depth > rails.maxActionDepth) return false
        return when (n) {
            is Action.Read ->
                n.fields.size <= rails.maxReadFields &&
                    strOk(n.recordId) && n.fields.all(::strOk)
            is Action.Write ->
                n.fields.size <= rails.maxWriteFields &&
                    strOk(n.recordId) && n.fields.all { (k, v) -> strOk(k) && strOk(v) }
            is Action.Delete -> strOk(n.recordId)
            is Action.Sequence ->
                n.steps.isNotEmpty() && n.steps.all { actOk(it, depth + 1) }
            is Action.Guarded ->
                condOk(n.condition, 1) && actOk(n.then, depth + 1) &&
                    (n.otherwise?.let { actOk(it, depth + 1) } ?: true)
        }
    }

    return actOk(a, 1)
}

/** Total payload bytes across every string atom of the AST (UTF-8). */
fun payloadBytes(a: Action): Long {
    var total = 0L
    fun s(x: String) { total += x.toByteArray(Charsets.UTF_8).size }
    fun cond(c: Condition): Unit = when (c) {
        is Condition.FieldEquals -> { s(c.recordId); s(c.field); s(c.expected) }
        is Condition.And -> c.parts.forEach(::cond)
        is Condition.Not -> cond(c.inner)
    }
    fun act(n: Action): Unit = when (n) {
        is Action.Read -> { s(n.recordId); n.fields.forEach(::s) }
        is Action.Write -> { s(n.recordId); n.fields.forEach { (k, v) -> s(k); s(v) } }
        is Action.Delete -> s(n.recordId)
        is Action.Sequence -> n.steps.forEach(::act)
        is Action.Guarded -> { cond(n.condition); act(n.then); n.otherwise?.let { act(it) }; Unit }
    }
    act(a)
    return total
}

/**
 * Conservative static resource estimates from the AST (Gate 7, theta_mem).
 * Payload bytes are accounted: each payload byte must be stored (memory)
 * and at least copied/hashed once (cycles). Node-only estimates
 * understated actions with large string payloads.
 */
fun estimatedMemoryBytes(a: Action, rails: Rails): Long =
    nodeCount(a) * rails.bytesPerNode + payloadBytes(a)

fun estimatedCycles(a: Action, rails: Rails): Long =
    nodeCount(a) * rails.cyclesPerNode + payloadBytes(a)

private fun nodeCount(n: Action): Int = when (n) {
    is Action.Read, is Action.Write, is Action.Delete -> 1
    is Action.Sequence -> 1 + n.steps.sumOf(::nodeCount)
    is Action.Guarded -> 1 + nodeCount(n.then) + (n.otherwise?.let(::nodeCount) ?: 0)
}
