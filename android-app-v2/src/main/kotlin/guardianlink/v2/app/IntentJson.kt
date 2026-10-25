package guardianlink.v2.app

import guardianlink.v2.core.JsonParser
import guardianlink.v2.core.StructuredIntent

/**
 * The shared intent-shape decoder: both language ports (cloud
 * and on-device brain) speak the same strict JSON intent, and
 * this is the only door from raw model text to a [StructuredIntent].
 * The core still validates the result against the registry —
 * a model can only ever produce data here, never authority.
 */
object IntentJson {

    fun decode(raw: String): StructuredIntent? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = JsonParser.parse(raw.substring(start, end + 1)) as? Map<*, *> ?: return null
        val kind = obj["kind"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val paramsRaw = obj["params"] as? Map<String, Any?>
        val params = paramsRaw?.mapValues { (_, v) -> v?.toString() ?: "" } ?: emptyMap()
        val confidence = (obj["confidence"] as? Number)?.toDouble() ?: 0.0
        return StructuredIntent(
            kind = kind,
            actionType = obj["actionType"] as? String,
            params = params,
            confidence = confidence,
        )
    }

    /** The reader's instructions — one text for every brain, so behavior doesn't drift by lane. */
    val SYSTEM_PROMPT: String = """
        You are the intent reader for GuardianLink, a governed personal assistant.
        Read the user's utterance and return ONLY a JSON object, no other text:
        {"kind":"act"|"ask"|"report"|"none","actionType":<one of the registry or null>,"params":{...},"confidence":<0.0-1.0>}
        Registry (the ONLY actions that exist):
        - send_text: params to (person name or number), body (exact message text)
        - phone_call: params to
        - send_email: params to (address or name), subject (may be ""), body
        - device_open_app: params app
        - device_tap: params text (the on-screen label to tap)
        - device_type: params text (text to type into the focused field)
        - device_press: params key (back|home|recents|notifications)
        - read_screen: no params
        Rules: the body is a message FROM the user TO the recipient — frame it that way.
        Convert reported speech to direct address: pronouns referring to the recipient become second person
        ("tell Shannon I love her" → body "I love you"; "tell her I'll see her at 7" → "I'll see you at 7").
        Keep the user's wording otherwise. If the user asks for decoration (hearts, kisses, emojis),
        render it as actual emoji characters in the body, never as words describing them.
        If the utterance is not a command, kind is "ask" or "report" and actionType is null.
        If a command names an action outside the registry, kind is "none".
        Confidence reflects how certain the reading is.
    """.trimIndent()
}
