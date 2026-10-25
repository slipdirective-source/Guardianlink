package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Device actions through the governed path: a command with NO
 * prior evidence anywhere in the system still proposes, assents,
 * and executes (the no-evidence-no-freeze doctrine, 2026-10-25).
 * The core owns proposal/policy/assent/record; the injected
 * handler owns only the effect and reports the observed outcome.
 */
class DeviceActionTest {

    private fun harnessWith(handler: ((ActionProposal) -> DeviceOutcome)?): GuardianLink =
        GuardianLink(deviceActionHandler = handler)

    @Test
    fun `call with zero history proposes, assents, executes`() {
        var seen: ActionProposal? = null
        val h = harnessWith { p ->
            seen = p
            DeviceOutcome("Calling ${p.target} — the call screen is up.")
        }
        val proposal = h.say("call 8125551234")
        assertEquals(Intent.ACT, proposal.intent)
        assertTrue(proposal.reply.contains("say-so"), "fresh assent is requested: ${proposal.reply}")

        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed, "assented command executes: ${yes.reply}")
        assertTrue(yes.reply.contains("call screen is up"), "handler's observed outcome is the reply: ${yes.reply}")
        assertEquals("phone_call", seen?.actionType)
    }

    @Test
    fun `text parses recipient and body`() {
        var seen: ActionProposal? = null
        val h = harnessWith { p ->
            seen = p
            DeviceOutcome("Text sent.")
        }
        h.say("text Maria saying I'll be there at 7")
        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed)
        assertEquals("Maria", seen?.parameters?.get("to"))
        assertEquals("I'll be there at 7", seen?.parameters?.get("body"))
    }

    @Test
    fun `email parses address and body`() {
        var seen: ActionProposal? = null
        val h = harnessWith { p ->
            seen = p
            DeviceOutcome("Email sent.")
        }
        h.say("email maria@example.com saying the invoice is ready")
        h.say("yes")
        assertEquals("send_email", seen?.actionType)
        assertEquals("maria@example.com", seen?.parameters?.get("to"))
    }

    @Test
    fun `handler failure is an observed failure, not a fake success`() {
        val h = harnessWith { throw IllegalStateException("the text-messages grant is off") }
        h.say("text Maria saying hello")
        val yes = h.say("yes")
        assertEquals(false, yes.execution?.executed)
        assertTrue(yes.reply.contains("Execution failed"), "failure is named: ${yes.reply}")
    }

    @Test
    fun `no handler attached - proposal drafts but cannot execute`() {
        val h = harnessWith(null)
        h.say("call 8125551234")
        val yes = h.say("yes")
        assertEquals(false, yes.execution?.executed)
        assertTrue(
            yes.reply.contains("cannot execute") || yes.reply.contains("can't run"),
            "absence of an adapter is stated, not hidden: ${yes.reply}",
        )
    }

    @Test
    fun `account status answers from the record`() {
        val h = harnessWith(null)
        val r = h.say("check my email")
        assertEquals(Intent.QUERY, r.intent)
        assertTrue(r.reply.contains("Account status"), "status header present: ${r.reply}")
        assertTrue(r.reply.contains("Mailbox:"), "mailbox state named: ${r.reply}")
    }

    @Test
    fun `what can you do answers capabilities, not evidence search`() {
        val h = harnessWith { DeviceOutcome("ok") }
        val r = h.say("what can u do?")
        assertEquals(Intent.QUERY, r.intent)
        assertTrue(r.reply.contains("Here's what I can do"), "capability answer: ${r.reply}")
        assertTrue(r.reply.contains("place a call"), "device actions named when a handler is attached: ${r.reply}")
        assertTrue(!r.reply.contains("nothing on record about"), "never an evidence-search shrug: ${r.reply}")
    }

    @Test
    fun `bare text command clarifies instead of guessing`() {
        val h = harnessWith(null)
        val r = h.say("send a text")
        assertEquals(Intent.CLARIFY, r.intent)
    }
}
