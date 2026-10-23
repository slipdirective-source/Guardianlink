package guardianlink.v2.core

import java.security.MessageDigest

/**
 * Canonical serialization + SHA-256 for the v0.2 core.
 *
 * PORT OF: TypeScript core `src/types.ts` (`canonical`, `sha256`), whose
 * semantics this file reproduces so Kotlin-computed hashes mean the same
 * thing as TS-computed ones:
 *
 *  - Objects: keys sorted lexicographically (UTF-16 code-unit order, the
 *    same order TS `Object.keys().sort()` produces for the ASCII keys this
 *    system uses), rendered as `{"k":v,...}` with no whitespace.
 *  - Arrays: order preserved, `[v,...]`.
 *  - Strings: JSON escaping identical to `JSON.stringify` (quotes,
 *    backslash, \b \f \n \r \t, other control chars as \u00XX).
 *  - Numbers: integral values render with no decimal point
 *    (`JSON.stringify(2.0) === "2"`); non-integral doubles render via
 *    `Double.toString`, which matches JS rendering for the magnitudes
 *    this system uses (confidences 0..1, counts). Extreme magnitudes
 *    (>= 1e21, where JS switches to exponent form) are out of scope and
 *    never occur in hashed payloads here.
 *  - Booleans/null: `true` / `false` / `null`.
 *
 * Supported value shapes: null, String, Boolean, Int, Long, Double,
 * Map<String, Any?>, List<Any?>. Anything else throws — a hashed payload
 * must never silently serialize an unexpected shape.
 */
object Canonical {

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> sb.append(renderDouble(value))
            is Map<*, *> -> {
                val keys = value.keys.map { it as String }.sorted()
                sb.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, value[k])
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("canonical: unsupported value type ${value.javaClass.name}")
        }
    }

    private fun renderDouble(d: Double): String {
        require(d.isFinite()) { "canonical: non-finite double cannot be serialized" }
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [text]. Same as TS `sha256`. */
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
