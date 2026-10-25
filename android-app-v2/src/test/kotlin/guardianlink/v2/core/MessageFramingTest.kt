package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Message framing (his correction, 2026-10-25, from Shannon's
 * phone showing "I love her" delivered as-is): dictated reported
 * speech must reach the recipient as a message from the
 * sovereign, addressed to them — and the review prompt must show
 * the reframed text, because assent binds to what will be sent.
 */
class MessageFramingTest {

    @Test
    fun `object pronouns shift to second person`() {
        assertEquals("I love you", MessageFraming.reframe("I love her"))
        assertEquals("I'll see you at 7", MessageFraming.reframe("I'll see her at 7"))
        assertEquals("give you the keys", MessageFraming.reframe("give him the keys"))
        assertEquals("I miss you", MessageFraming.reframe("I miss them"))
    }

    @Test
    fun `subject pairs keep verb agreement`() {
        assertEquals("you are the best", MessageFraming.reframe("she is the best"))
        assertEquals("You're late", MessageFraming.reframe("She's late"))
        assertEquals("your house is ready", MessageFraming.reframe("their house is ready"))
    }

    @Test
    fun `bodies without recipient pronouns pass through untouched`() {
        assertEquals("dinner is at 7", MessageFraming.reframe("dinner is at 7"))
        assertEquals("I love you", MessageFraming.reframe("I love you"))
        assertEquals("Running late, see you soon", MessageFraming.reframe("Running late, see you soon"))
    }

    @Test
    fun `tell form proposes the reframed body and the review shows it`() {
        var seen: ActionProposal? = null
        val h = GuardianLink(deviceActionHandler = { p ->
            seen = p
            DeviceOutcome("Text sent.")
        })
        val prompt = h.say("tell Shannon I love her")
        assertTrue(prompt.reply.contains("\"I love you\""), "review prompt shows the reframed text: ${prompt.reply}")
        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed)
        assertEquals("I love you", seen?.parameters?.get("body"))
    }
}
