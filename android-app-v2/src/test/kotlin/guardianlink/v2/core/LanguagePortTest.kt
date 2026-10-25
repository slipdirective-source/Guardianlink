package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The language port contract: an LLM may READ an utterance the
 * deterministic forms missed, but the registry decides what can
 * become a proposal. Off-registry, low-confidence, or absent —
 * the floor's behavior stands untouched.
 */
class LanguagePortTest {

    private class FakePort(val intent: StructuredIntent?) : LanguagePort {
        var calls = 0
        override fun parse(utterance: String): StructuredIntent? {
            calls++
            return intent
        }
    }

    private fun harnessWith(port: LanguagePort?): GuardianLink {
        val clock = TestClock("2026-10-23T14:00:00.000Z")
        return GuardianLink(now = clock, languagePort = port)
    }

    @Test
    fun `an LLM-read command becomes the same review-prompt proposal`() {
        val port = FakePort(
            StructuredIntent(
                kind = "act",
                actionType = "send_text",
                params = mapOf("to" to "Shannon", "body" to "I love her"),
                confidence = 0.93,
            ),
        )
        val h = harnessWith(port)
        // No deterministic form matches this phrasing.
        val r = h.say("ping Shannon and let her know I love her")
        assertTrue(r.reply.startsWith("Send this text?"), "review prompt from validated intent: ${r.reply}")
        assertTrue(r.reply.contains("To: Shannon") && r.reply.contains("\"I love you\""), "exact content: ${r.reply}")
        assertEquals("send_text", r.proposal?.actionType)
        // And the parse is on the record as agent-derived.
        val parse = h.ledger.liveEvents().firstOrNull { it.topic == "language.parse" }
        assertTrue(parse != null && parse.agentDerived, "parse admitted as agent-derived evidence")
    }

    @Test
    fun `off-registry and low-confidence intents change nothing`() {
        val offRegistry = harnessWith(
            FakePort(StructuredIntent("act", "order_pizza", mapOf("to" to "Dominos"), 0.99)),
        )
        val r1 = offRegistry.say("order a pizza for me")
        assertEquals(Intent.CLARIFY, r1.intent, "falls to the command guard: ${r1.reply}")
        assertTrue(r1.proposal == null, "no proposal from an off-registry intent")

        val lowConfidence = harnessWith(
            FakePort(StructuredIntent("act", "send_text", mapOf("to" to "Shannon", "body" to "hi"), 0.30)),
        )
        val r2 = lowConfidence.say("frobulate the Shannon situation somehow")
        assertTrue(r2.proposal == null, "below the confidence floor: no proposal")
        assertEquals(
            0,
            lowConfidence.ledger.liveEvents().count { it.topic == "language.parse" },
            "a rejected parse is not admitted as a reading",
        )
    }

    @Test
    fun `a recognized action missing its content gets a targeted clarify`() {
        val h = harnessWith(
            FakePort(StructuredIntent("act", "send_text", mapOf("to" to "Shannon"), 0.9)),
        )
        val r = h.say("reach out to Shannon for me")
        assertEquals(Intent.CLARIFY, r.intent)
        assertTrue(r.reply.contains("saying what"), "asks for the missing piece: ${r.reply}")
    }

    @Test
    fun `no port — the floor is the floor`() {
        val h = harnessWith(null)
        val r = h.say("ping Shannon and let her know I love her")
        assertTrue(r.proposal == null, "nothing proposed without a reading: ${r.reply}")
    }
}
