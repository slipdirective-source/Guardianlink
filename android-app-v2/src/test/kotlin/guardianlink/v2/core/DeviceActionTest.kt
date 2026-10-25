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
        assertTrue(proposal.reply.startsWith("Call 8125551234?"), "the prompt is a one-line review: ${proposal.reply}")

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
    fun `natural send-a-message phrasing parses to a text proposal`() {
        var seen: ActionProposal? = null
        val h = harnessWith { p ->
            seen = p
            DeviceOutcome("Text sent.")
        }
        val r = h.say("so I want you to send Shannon a message telling her I love her")
        assertEquals(Intent.ACT, r.intent, "a command, not a note: ${r.reply}")
        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed, "executes on assent: ${yes.reply}")
        assertEquals("Shannon", seen?.parameters?.get("to"))
        assertEquals("I love her", seen?.parameters?.get("body"))
    }

    @Test
    fun `tell a person parses, tell me does not`() {
        var seen: ActionProposal? = null
        val h = harnessWith { p ->
            seen = p
            DeviceOutcome("Text sent.")
        }
        h.say("tell Shannon I love her")
        h.say("yes")
        assertEquals("Shannon", seen?.parameters?.get("to"))
        val ask = h.say("tell me about Shannon")
        assertTrue(ask.intent != Intent.ACT || ask.proposal == null, "tell me stays a question: ${ask.reply}")
    }

    @Test
    fun `the send prompt is a review of exactly what will be sent`() {
        val h = harnessWith { DeviceOutcome("Text sent.") }
        val r = h.say("send Shannon a text telling her I love her")
        assertTrue(r.reply.startsWith("Send this text?"), "the prompt IS the review: ${r.reply}")
        assertTrue(r.reply.contains("To: Shannon"), "recipient shown: ${r.reply}")
        assertTrue(r.reply.contains("\"I love her\""), "exact body shown: ${r.reply}")
        assertTrue(!r.reply.contains("policy") && !r.reply.contains("rail"), "no boilerplate in the prompt: ${r.reply}")
        val no = h.say("no")
        assertTrue(no.reply.contains("Left unexecuted"), "no drops it cleanly: ${no.reply}")
    }

    @Test
    fun `device control verbs propose and execute through the handler`() {
        val seen = ArrayList<String>()
        val h = harnessWith { p ->
            seen += p.actionType
            DeviceOutcome("Did ${p.actionType} on ${p.target}.")
        }
        h.say("open Gmail"); h.say("yes")
        h.say("tap Send"); h.say("yes")
        h.say("type Running late, see you at 7"); h.say("yes")
        h.say("go back"); h.say("yes")
        val read = h.say("what's on my screen?")
        assertEquals(Intent.ACT, read.intent, "screen read is an action, not a search: ${read.reply}")
        h.say("yes")
        assertEquals(
            listOf("device_open_app", "device_tap", "device_type", "device_press", "read_screen"),
            seen,
            "every finger verb reached the handler in order",
        )
    }

    @Test
    fun `conjunction forms — text Shan and tell her`() {
        val h = harnessWith { DeviceOutcome("Text sent.") }
        val r = h.say("text Shan in my contacts and tell her I love her.")
        assertTrue(r.reply.startsWith("Send this text?"), "review prompt: ${r.reply}")
        assertTrue(r.reply.contains("To: Shan"), "qualifier stripped, short name kept: ${r.reply}")
        assertTrue(r.reply.contains("\"I love her.\""), "body intact: ${r.reply}")
        val r2 = h.say("send Shannon a text and tell her the cabinets are done")
        assertTrue(r2.reply.startsWith("Send this text?"), "send-side conjunction: ${r2.reply}")
    }

    @Test
    fun `the third screenshot's sentences all land`() {
        val h = harnessWith { DeviceOutcome("Text sent to Shannon.") }
        // Leading discourse word + contacts qualifier.
        val r = h.say("now send a text to Shannon in my contacts telling her I love her")
        assertTrue(r.reply.startsWith("Send this text?"), "review prompt: ${r.reply}")
        assertTrue(r.reply.contains("To: Shannon"), "qualifier stripped from the name: ${r.reply}")
        // His assent wording.
        val done = h.say("execute the order")
        assertTrue(done.reply.startsWith("Done"), "\"execute the order\" assents: ${done.reply}")
        // "tell me" questions are questions.
        val where = h.say("tell me where I am")
        assertEquals(Intent.ASK, where.intent, "tell-me-where is an ask: ${where.reply}")
    }

    @Test
    fun `connect-email and meta-commands get pathways, not notes`() {
        val h = harnessWith { DeviceOutcome("unused") }
        val connect = h.say("let's connect my email so you can read it for context")
        assertTrue(connect.reply.contains("Connected email"), "pathway given: ${connect.reply}")
        val want = h.say("I want to use you to send an email")
        assertEquals(Intent.CLARIFY, want.intent, "an under-specified command halts as a command: ${want.reply}")
        val complaint = h.say("don't just record, you are supposed to actually do it")
        assertTrue(complaint.reply.contains("pipeline"), "the complaint is heard: ${complaint.reply}")
        assertEquals(
            0,
            h.ledger.liveEvents().count { it.kind == EventKind.USER_REPORT },
            "none of the three became a filed note",
        )
    }

    @Test
    fun `an unrecognized command is never filed as a note`() {
        val h = harnessWith { DeviceOutcome("ok") }
        val r = h.say("order a pizza for delivery")
        assertEquals(Intent.CLARIFY, r.intent, "a command halts visibly, naming the missing form: ${r.reply}")
        assertTrue(r.reply.contains("don't have a form"), "the halt is honest about inaction: ${r.reply}")
        assertTrue(
            h.ledger.allEvents().none { it.kind == guardianlink.v2.core.EventKind.USER_REPORT },
            "no user-report event was fabricated from a command",
        )
        val past = h.say("I ordered a pizza yesterday and it was late")
        assertEquals(Intent.REPORT, past.intent, "a past-tense report still records normally: ${past.reply}")
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
