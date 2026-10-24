package guardianlink.v2.core

/**
 * A small STRICT JSON parser — the read side the canonical writer in
 * [Canonical] never needed until persistence did.
 *
 * Why this exists, and why it is strict: [StateStore] loads a document
 * whose hashes are about to be trusted. The [Sync] port deliberately
 * shipped no JSON parser for foreign sync bundles ("importing untrusted
 * text requires the strict-parser treatment, not a lenient one"); the
 * state store is exactly that treatment, applied to our own format:
 *
 *  - full JSON grammar, nothing else: no trailing content, no trailing
 *    commas, no unquoted keys, no single-quoted strings, no comments,
 *    no NaN/Infinity, no leading-zero numbers, no duplicate keys in an
 *    object, nesting capped at [MAX_DEPTH];
 *  - strings accept exactly the JSON escapes (\" \\ \/ \b \f \n \r \t
 *    \uXXXX); raw control characters in strings are rejected;
 *  - numbers parse to Int when the literal is integral and fits, else
 *    Long, else Double for fraction/exponent literals — this keeps the
 *    canonical rendering of a re-serialized value byte-identical to the
 *    original (Int 5 and Long 5 both render "5"; a Double 5.0 also
 *    renders "5", so hash bodies are stable across a round trip).
 *
 * Every failure throws [StateStoreException] with kind PARSE_ERROR and
 * the character offset — a named error, never a bare exception type the
 * caller has to guess at.
 */
internal object JsonParser {

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.parseValue(0)
        p.skipWhitespace()
        if (!p.atEnd()) p.fail("trailing content after the top-level value")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun fail(message: String): Nothing =
            throw StateStoreException(StateStoreError.PARSE_ERROR, "JSON parse error at offset $pos: $message")

        fun skipWhitespace() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
                pos += 1
            }
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else fail("unexpected end of input")

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH")
            return when (peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-' -> parseNumber()
                in '0'..'9' -> parseNumber()
                else -> fail("unexpected character '${text[pos]}'")
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return value
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos += 1 // '{'
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos += 1
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object keys must be strings")
                val key = parseString()
                if (out.containsKey(key)) fail("duplicate key \"$key\"")
                skipWhitespace()
                if (peek() != ':') fail("expected ':' after object key")
                pos += 1
                skipWhitespace()
                out[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos += 1
                    '}' -> {
                        pos += 1
                        return out
                    }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos += 1 // '['
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos += 1
                return out
            }
            while (true) {
                skipWhitespace()
                out.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos += 1
                    ']' -> {
                        pos += 1
                        return out
                    }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            pos += 1 // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos]
                when {
                    c == '"' -> {
                        pos += 1
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos += 1
                        if (pos >= text.length) fail("unterminated escape")
                        when (text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) fail("truncated unicode escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: fail("invalid unicode escape \"$hex\"")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("invalid escape '\\${text[pos]}'")
                        }
                        pos += 1
                    }
                    c < ' ' -> fail("raw control character in string")
                    else -> {
                        sb.append(c)
                        pos += 1
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos += 1
            if (pos >= text.length) fail("truncated number")
            // integer part: exactly "0", or a non-zero digit followed by digits
            if (text[pos] == '0') {
                pos += 1
            } else if (text[pos] in '1'..'9') {
                while (pos < text.length && text[pos].isDigit()) pos += 1
            } else {
                fail("invalid number")
            }
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos += 1
                if (pos >= text.length || !text[pos].isDigit()) fail("fraction requires at least one digit")
                while (pos < text.length && text[pos].isDigit()) pos += 1
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos += 1
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos += 1
                if (pos >= text.length || !text[pos].isDigit()) fail("exponent requires at least one digit")
                while (pos < text.length && text[pos].isDigit()) pos += 1
            }
            val literal = text.substring(start, pos)
            if (isDouble) {
                return literal.toDoubleOrNull() ?: fail("invalid number \"$literal\"")
            }
            return literal.toIntOrNull()
                ?: literal.toLongOrNull()
                ?: fail("integer literal out of range \"$literal\"")
        }
    }
}
