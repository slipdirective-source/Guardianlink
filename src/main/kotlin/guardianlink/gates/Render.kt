package guardianlink.gates

/**
 * Render — total, pure deterministic rendering (Codex v2.2, Gate 7,
 * theta_render: r == Render(a)).
 *
 * The person signs the rendering, not the payload. The rendering is the
 * canonical serialization of the AST itself, so the fidelity relation
 * between "what was shown" and "what will execute" is structural: r is
 * derived from the same `a` that Gate 8 executes. A compromised Render
 * implementation would break this — Render must be treated as trusted
 * specification code, not as a plugin point.
 */
fun render(a: Action): String = when (a) {
    is Action.Read -> "READ ${a.recordId} {${a.fields.joinToString(",")}}"
    is Action.Write -> {
        val fields = a.fields.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        "WRITE ${a.recordId} {$fields}"
    }
    is Action.Delete -> "DELETE ${a.recordId}"
    is Action.Sequence -> "SEQ[${a.steps.joinToString(";") { render(it) }}]"
    is Action.Guarded -> {
        val els = a.otherwise?.let { " ELSE ${render(it)}" } ?: ""
        "IF ${renderCondition(a.condition)} THEN ${render(a.then)}$els"
    }
}

private fun renderCondition(c: Condition): String = when (c) {
    is Condition.FieldEquals -> "${c.recordId}.${c.field}==${c.expected}"
    is Condition.And -> c.parts.joinToString(" AND ", "(", ")") { renderCondition(it) }
    is Condition.Not -> "NOT(${renderCondition(c.inner)})"
}
