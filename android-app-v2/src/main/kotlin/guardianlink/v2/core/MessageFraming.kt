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
 * "with hearts and kisses emojis" as actual emojis, inside a
 * longer sentence) belongs to the language readers, whose prompt
 * carries the same rule. The one decoration case this layer DOES
 * own is the whole-body one — "send her a bunch of kisses and
 * hearts emojis" — because it is fully mechanical: a closed word
 * list, no prose judgment (added 2026-10-26, after exactly that
 * follow-up shipped its instruction words as literal text once
 * too often — through the deterministic path this time).
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

    private val EMOJI_WORDS = mapOf(
        "kiss" to "\uD83D\uDE18", "kisses" to "\uD83D\uDE18",
        "heart" to "\u2764\uFE0F", "hearts" to "\u2764\uFE0F", "love" to "\u2764\uFE0F",
        "fire" to "\uD83D\uDD25", "flame" to "\uD83D\uDD25", "flames" to "\uD83D\uDD25",
        "thumbs up" to "\uD83D\uDC4D", "laugh" to "\uD83D\uDE02", "laughing" to "\uD83D\uDE02",
        "party" to "\uD83C\uDF89", "star" to "\u2B50", "stars" to "\u2B50",
        "rose" to "\uD83C\uDF39", "roses" to "\uD83C\uDF39",
        "smile" to "\uD83D\uDE0A", "wink" to "\uD83D\uDE09",
        "hug" to "\uD83E\uDD17", "hugs" to "\uD83E\uDD17", "muscle" to "\uD83D\uDCAA",
    )

    /**
     * When a whole body is a decoration request — "a bunch of
     * kisses and hearts emojis" — render the decorations
     * themselves. Returns null for anything else, so ordinary
     * prose passes through untouched (a listed word must be the
     * whole body or a whole "and"-separated part: "I love you"
     * never trips this). The review prompt shows the exact
     * result before anything fires. "A bunch of" repeats the
     * sequence three times.
     */
    fun renderDecorations(body: String): String? {
        val original = body.lowercase()
        var s = original.trim().trimEnd('.', '!', ' ')
        s = s.removeSuffix(" too").removeSuffix(" also").trim()
        s = s.replace(Regex("\\s+(emojis?|emoticons?)\\s*$"), "")
        s = s.replace(Regex("^(a\\s+)?(bunch|lot|few|couple|ton|tons|some)\\s+of\\s+"), "")
        val parts = s.split(Regex("\\s+and\\s+|\\s*,\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return null
        val emojis = ArrayList<String>()
        for (part in parts) {
            emojis.add(EMOJI_WORDS[part] ?: return null)
        }
        val seq = emojis.joinToString("")
        return if (Regex("\\b(bunch|lot|tons)\\b").containsMatchIn(original)) seq.repeat(3) else seq
    }

    fun reframe(body: String): String {
        renderDecorations(body)?.let { return it }
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
