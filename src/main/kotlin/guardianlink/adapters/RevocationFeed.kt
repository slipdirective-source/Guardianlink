package guardianlink.adapters

import guardianlink.crypto.AssentCodec
import guardianlink.crypto.Ed25519
import guardianlink.gates.Revocation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * Revocation feed port implementation contract: the deployment — not the
 * request — controls which revocations the engine sees. See
 * [guardianlink.gates.Verifiers.revocationsFor].
 */
interface RevocationFeed {
    /**
     * All known revocations for [actionId]. Throwing fails the engine
     * closed; returning empty means "none known".
     */
    fun revocationsFor(actionId: String): List<Revocation>
}

/**
 * File-backed, authority-signed revocation list.
 *
 * File format: one JSON object per line —
 * `{"actionId":"...","revokedAtMs":1699999999000,"sig":"base64url..."}`
 * where `sig` is Ed25519 over
 * [AssentCodec.encodeRevocation]`(actionId, revokedAtMs)` under the
 * revocation authority's key.
 *
 * Fail-closed loading: EVERY entry's signature is verified against
 * [authorityKey] at load. A single malformed line, bad signature, or
 * missing key aborts the whole load with an exception — a partially
 * trusted revocation list is worse than none, because the engine would
 * enforce an attacker's revocations or miss the authority's. The engine
 * treats a throwing feed as halt, which is the safe direction.
 *
 * The loaded snapshot is immutable; [reload] swaps it atomically.
 */
class SignedRevocationListFeed private constructor(
    private val path: Path,
    private val authorityKey: PublicKey,
    @Volatile private var snapshot: List<Revocation>,
) : RevocationFeed {

    override fun revocationsFor(actionId: String): List<Revocation> =
        snapshot.filter { it.actionId == actionId }

    /** Re-reads the file and re-verifies every entry. Throws on any problem. */
    fun reload(): Unit {
        snapshot = loadEntries(path, authorityKey)
    }

    companion object {
        /** Loads and fully verifies the list. Throws on any problem. */
        fun load(path: Path, authorityKey: PublicKey): SignedRevocationListFeed {
            if (!Files.isRegularFile(path)) {
                throw IllegalArgumentException("revocation list is not a regular file: $path")
            }
            return SignedRevocationListFeed(path, authorityKey, loadEntries(path, authorityKey))
        }

        /**
         * Issues one signed list line (for the revocation authority's
         * offline tooling). The authority signs; the deployment publishes.
         */
        fun signEntry(
            authorityKey: PrivateKey,
            actionId: String,
            revokedAtMs: Long,
        ): String {
            require(actionId.isNotEmpty()) { "actionId must not be empty" }
            require(revokedAtMs >= 0) { "revokedAtMs must be >= 0" }
            val sig = Ed25519.sign(authorityKey, AssentCodec.encodeRevocation(actionId, revokedAtMs))
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
            return """{"actionId":${jsonString(actionId)},"revokedAtMs":$revokedAtMs,"sig":"$b64"}"""
        }

        private fun loadEntries(path: Path, authorityKey: PublicKey): List<Revocation> {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            val out = ArrayList<Revocation>(lines.size)
            for ((i, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                out.add(parseAndVerify(line, authorityKey, path, i + 1))
            }
            return out.toList()
        }

        private fun parseAndVerify(
            line: String,
            authorityKey: PublicKey,
            path: Path,
            lineNo: Int,
        ): Revocation {
            fun fail(why: String): Nothing =
                throw IllegalArgumentException("revocation list $path line $lineNo: $why")
            val obj = parseObject(line) ?: fail("malformed JSON object")
            if (obj.keys != setOf("actionId", "revokedAtMs", "sig")) fail("keys must be exactly actionId, revokedAtMs, sig")
            val actionId = obj["actionId"] as? String ?: fail("actionId must be a string")
            if (actionId.isEmpty()) fail("actionId must not be empty")
            val revokedAtMs = (obj["revokedAtMs"] as? Long) ?: fail("revokedAtMs must be an integer")
            if (revokedAtMs < 0) fail("revokedAtMs must be >= 0")
            val sigB64 = obj["sig"] as? String ?: fail("sig must be a string")
            val sig = try {
                Base64.getUrlDecoder().decode(sigB64)
            } catch (_: IllegalArgumentException) {
                fail("sig is not valid base64url")
            }
            val claimed = AssentCodec.encodeRevocation(actionId, revokedAtMs)
            if (!Ed25519.verify(authorityKey, claimed, sig)) fail("signature does not verify under the authority key")
            return Revocation(actionId, sig, revokedAtMs)
        }

        private fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        /**
         * Minimal strict JSON parser: exactly one object, string keys,
         * values restricted to strings or integers. Anything else —
         * arrays, nesting, floats, literals, trailing content — is
         * rejected. Returns null on any malformation (never throws).
         */
        private fun parseObject(line: String): Map<String, Any>? {
            var i = 0
            fun ws() { while (i < line.length && line[i].isWhitespace()) i++ }
            fun parseString(): String? {
                if (i >= line.length || line[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < line.length) {
                    val c = line[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= line.length) return null
                            when (val e = line[i++]) {
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (i + 4 > line.length) return null
                                    val hex = line.substring(i, i + 4)
                                    val code = hex.toIntOrNull(16) ?: return null
                                    sb.append(code.toChar())
                                    i += 4
                                }
                                else -> return null
                            }
                        }
                        else -> {
                            if (c < ' ') return null
                            sb.append(c)
                        }
                    }
                }
                return null
            }
            fun parseLong(): Long? {
                val start = i
                if (i < line.length && line[i] == '-') i++
                if (i >= line.length || !line[i].isDigit()) return null
                while (i < line.length && line[i].isDigit()) i++
                // Strict integers only: no fractions, no exponents.
                if (i < line.length && (line[i] == '.' || line[i] == 'e' || line[i] == 'E')) return null
                return line.substring(start, i).toLongOrNull()
            }
            ws()
            if (i >= line.length || line[i] != '{') return null
            i++
            val map = LinkedHashMap<String, Any>()
            ws()
            if (i < line.length && line[i] == '}') {
                i++
                ws()
                return if (i == line.length) map else null
            }
            while (true) {
                ws()
                val key = parseString() ?: return null
                ws()
                if (i >= line.length || line[i] != ':') return null
                i++
                ws()
                val value: Any = if (i < line.length && line[i] == '"') {
                    parseString() ?: return null
                } else {
                    parseLong() ?: return null
                }
                if (key in map) return null // duplicate key
                map[key] = value
                ws()
                if (i >= line.length) return null
                when (line[i++]) {
                    ',' -> continue
                    '}' -> {
                        ws()
                        return if (i == line.length) map else null
                    }
                    else -> return null
                }
            }
        }
    }
}
