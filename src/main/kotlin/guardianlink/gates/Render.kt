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
 *
 * INJECTIVITY (adversarial requirement): distinct ASTs must render to
 * distinct strings, otherwise an assent signature for a benign reading
 * authorizes a malicious AST with a byte-identical rendering. Two
 * mechanisms enforce this:
 *  1. Every string atom (record ids, field names/values, expected values)
 *     is double-quoted with backslash escaping, so payload text can never
 *     collide with structural characters.
 *  2. Guarded branches are parenthesized: without parens,
 *     `IF c1 THEN IF c2 THEN A ELSE B` is ambiguous between
 *     `Guarded(c1, Guarded(c2,A,B), null)` and `Guarded(c1, Guarded(c2,A,null), B)`.
 */
fun render(a: Action): String = when (a) {
    is Action.Read -> "READ ${q(a.recordId)} {${a.fields.joinToString(",") { q(it) }}}"
    is Action.Write -> {
        val fields = a.fields.entries.sortedBy { it.key }
            .joinToString(",") { "${q(it.key)}=${q(it.value)}" }
        "WRITE ${q(a.recordId)} {$fields}"
    }
    is Action.Delete -> "DELETE ${q(a.recordId)}"
    is Action.Sequence -> "SEQ[${a.steps.joinToString(";") { render(it) }}]"
    is Action.Guarded -> {
        val els = a.otherwise?.let { " ELSE (${render(it)})" } ?: ""
        "IF (${renderCondition(a.condition)}) THEN (${render(a.then)})$els"
    }
}

/** Quote a string atom so payload text cannot collide with structure. */
private fun q(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun renderCondition(c: Condition): String = when (c) {
    is Condition.FieldEquals -> "${q(c.recordId)}.${q(c.field)}==${q(c.expected)}"
    is Condition.And -> c.parts.joinToString(" AND ", "(", ")") { renderCondition(it) }
    is Condition.Not -> "NOT(${renderCondition(c.inner)})"
}
