package guardianlink.v2.core

/**
 * Message framing (his correction, 2026-10-25): a dictated
 * message is reported speech ABOUT the recipient — "tell
 * Shannon I love her" — but what the recipient must receive is
 * a message FROM the sovereign, addressed TO them: "I love
 * you." The body is reframed at proposal time, so the review
 * prompt shows the exact words the recipient will read and
 * assent binds to those words, never to the raw dictation.
 *
 * The pass is deliberately narrow and mechanical: recipient-
 * referring third-person pronouns shift to second person, with
 * subject–verb agreement on the common pairs. It does not
 * paraphrase, decorate, or guess referents — a pronoun that
 * isn't the recipient's can be shifted wrongly, which is why
 * the reframed text always passes through the sovereign's
 * review before it can fire. Nuance beyond mechanics (rendering
 * "with hearts and kisses emojis" as actual emojis) belongs to
 * the language readers, whose prompt carries the same rule.
 */
object MessageFraming {

    private val phraseRules: List<Pair<Regex, String>> = listOf(
        "she is" to "you are",
        "he is" to "you are",
        "they are" to "you are",
        "she's" to "you're",
        "he's" to "you're",
        "they're" to "you're",
        "she was" to "you were",
        "he was" to "you were",
        "she has" to "you have",
        "he has" to "you have",
        "she will" to "you will",
        "he will" to "you will",
        "she'll" to "you'll",
        "he'll" to "you'll",
    ).map { (a, b) -> Regex("\\b${Regex.escape(a)}\\b", RegexOption.IGNORE_CASE) to b }

    private val tokenMap = mapOf(
        "she" to "you",
        "he" to "you",
        "they" to "you",
        "her" to "you",
        "him" to "you",
        "them" to "you",
        "his" to "your",
        "their" to "your",
        "hers" to "yours",
        "theirs" to "yours",
    )
    private val tokenRegex = Regex(
        "\\b(${tokenMap.keys.joinToString("|")})\\b",
        RegexOption.IGNORE_CASE,
    )

    fun reframe(body: String): String {
        var out = body
        for ((regex, replacement) in phraseRules) {
            out = regex.replace(out) { m -> matchCase(m.value, replacement) }
        }
        out = tokenRegex.replace(out) { m ->
            val replacement = tokenMap[m.value.lowercase()] ?: m.value
            matchCase(m.value, replacement)
        }
        return out
    }

    private fun matchCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.uppercase() }
        } else {
            replacement
        }
}
