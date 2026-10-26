package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Follow-up messages (2026-10-26, from his on-device session):
 * "send her a bunch of kisses and hearts emojis too" — said right
 * after a text to Shan — got "send_message to unspecified" and a
 * policy dump. A conversation carries its recipient, renders a
 * whole-body decoration request as the decorations themselves,
 * and asks in plain words when it cannot bind a send.
 */
class FollowUpMessageTest {

    @Test
    fun `a follow-up pronoun text goes to the person just messaged, decorations rendered`() {
        var seen: ActionProposal? = null
        val h = GuardianLink(deviceActionHandler = { p ->
            seen = p
            DeviceOutcome("Text sent.")
        })
        val first = h.say("send Shan a text telling her I love her")
        assertTrue(first.reply.startsWith("Send this text?"), "first prompt is the review: ${first.reply}")
        val yes1 = h.say("yes")
        assertEquals(true, yes1.execution?.executed, "first text executes: ${yes1.reply}")

        val follow = h.say("send her a bunch of kisses and hearts emojis too")
        assertTrue(follow.reply.contains("To: Shan"), "the follow-up carries the recipient: ${follow.reply}")
        assertTrue(follow.reply.contains("😘❤️😘❤️😘❤️"), "the decorations are the body: ${follow.reply}")
        val yes2 = h.say("yes")
        assertEquals(true, yes2.execution?.executed, "follow-up executes: ${yes2.reply}")
        assertEquals("send_text", seen?.actionType)
        assertEquals("Shan", seen?.parameters?.get("to"))
        assertEquals("😘❤️😘❤️😘❤️", seen?.parameters?.get("body"))
    }

    @Test
    fun `a follow-up with no one carried asks instead of guessing`() {
        val h = GuardianLink(deviceActionHandler = { DeviceOutcome("Text sent.") })
        val r = h.say("send her a bunch of kisses and hearts emojis too")
        assertTrue(r.reply.contains("Who should I send it to"), "plain clarify, no proposal: ${r.reply}")
        assertEquals(null, r.proposal, "no proposal is made without a recipient")
    }

    @Test
    fun `a send with no bindable recipient clarifies in plain words`() {
        val h = GuardianLink()
        val r = h.say("send a message about the thing")
        assertTrue(r.reply.contains("I can send that"), "plain words, no policy vocabulary: ${r.reply}")
        assertTrue((r.clarifyQuestion ?: "").contains("Who should I send it to"), "the clarify question names the gap: ${r.clarifyQuestion}")
    }

    @Test
    fun `asking to talk to an outside mind gets the plugs answer`() {
        val h = GuardianLink()
        val r = h.say("can you talk to copilot for me?")
        assertTrue(r.reply.contains("aren't plugged in yet"), "the honest plugs answer: ${r.reply}")
    }
}

/** The decoration half of message framing: closed word list, whole-body only. */
class DecorationFramingTest {

    @Test
    fun `a bunch of kisses and hearts renders tripled`() {
        assertEquals("😘❤️😘❤️😘❤️", MessageFraming.renderDecorations("a bunch of kisses and hearts emojis"))
        assertEquals("😘❤️😘❤️😘❤️", MessageFraming.reframe("a bunch of kisses and hearts emojis too"))
    }

    @Test
    fun `a plain decoration list renders once`() {
        assertEquals("❤️😘", MessageFraming.renderDecorations("hearts and kisses"))
    }

    @Test
    fun `ordinary prose is never decoration`() {
        assertEquals(null, MessageFraming.renderDecorations("I love her"))
        assertEquals(null, MessageFraming.renderDecorations("tell her the hearts are on the table"))
        assertEquals("I love you", MessageFraming.reframe("I love her"))
    }
}
