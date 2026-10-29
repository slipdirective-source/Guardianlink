package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SMOOTHNESS-ADDENDUM (2026-10-27) acceptance fixtures.
 *
 * The bar is not "tests pass" — it is that the five standing answers
 * read like a briefing: composed prose, one fact once, places not
 * coordinates, each question answering in its own type. These tests
 * pin the golden text of the five answers in the states the
 * sovereign actually hits (no data, position only, moved), the two
 * chat capability fixtures from his own screenshots, conversational
 * rail authoring (§§14–17), and the Vault ANR's arithmetic: a
 * ledger holding weeks of collector data must stay cheap to read.
 */
class SmoothnessTest {

    private fun assertNoRepeatedSentence(text: String) {
        val sentences = text.split("\n").map { it.trim().trimEnd('.').lowercase() }.filter { it.isNotEmpty() }
        assertEquals(sentences.size, sentences.toSet().size, "no sentence repeats in: $text")
    }

    private fun assertNoRawCoordinates(text: String) {
        assertFalse(Regex("-?\\d{2,3}\\.\\d{3,}").containsMatchIn(text), "no raw coordinates in: $text")
    }

    // ---------------------------------------------------------------
    // Golden answers: no-data state
    // ---------------------------------------------------------------

    @Test
    fun `golden answers - no data state reads like a briefing`() {
        val (h, _) = makeHarness()
        val byId = h.questions.all().associateBy { it.id }
        assertEquals(
            "I don't know yet — nothing on record bears on that.",
            byId["position"]!!.current.text,
        )
        assertTrue(byId["trajectory"]!!.current.text.startsWith("No movement on record today"), "trajectory quiet in its own type: ${byId["trajectory"]!!.current.text}")
        assertEquals(
            "Nothing is waiting on you, and no rail is in force right now.",
            byId["agency"]!!.current.text,
        )
        for (q in byId.values) {
            assertNoRepeatedSentence(q.current.text)
            assertNoRawCoordinates(q.current.text)
        }
    }

    // ---------------------------------------------------------------
    // Golden answers: position-only state (the 2026-10-27 screenshot)
    // ---------------------------------------------------------------

    @Test
    fun `golden answers - position only - trajectory does not parrot position`() {
        val (h, _) = makeHarness()
        // The same place, resolved twice (two fixes, one place):
        // exactly the input that produced the screenshot's nonsense.
        h.admitSensor("location", "location", "You are near 344 W Leota, Scottsburg, Scott County, Indiana")
        h.admitSensor("location", "location", "You are near 344 W Leota, Scottsburg, Scott County, Indiana")
        h.admitSensor("location", "location.fix", "Location fix: 38.68632, -85.77766 (gps, accuracy ±4m)")
        h.admitSensor("location", "location.fix", "Location fix: 38.68631, -85.77765 (gps, accuracy ±3m)")

        val position = h.questions.get("position")!!.current.text
        assertTrue(position.startsWith("You are near 344 W Leota"), "position leads with the place: $position")
        assertNoRepeatedSentence(position)
        assertNoRawCoordinates(position)

        val trajectory = h.questions.get("trajectory")!!.current.text
        assertTrue(trajectory.startsWith("No movement on record today"), "same place at both ends is not movement: $trajectory")
        assertFalse(trajectory.contains("Movement:"), "no fabricated delta: $trajectory")
        assertNoRepeatedSentence(trajectory)
        assertNoRawCoordinates(trajectory)
    }

    // ---------------------------------------------------------------
    // Golden answers: moved state
    // ---------------------------------------------------------------

    @Test
    fun `golden answers - moved state - trajectory carries the real delta only`() {
        val (h, _) = makeHarness()
        h.admitSensor("location", "location", "You are near Home, Scottsburg")
        h.admitSensor("location", "location", "You are near Louisville, Kentucky")
        val trajectory = h.questions.get("trajectory")!!.current.text
        assertTrue(
            trajectory.contains("Movement: You are near Home, Scottsburg → You are near Louisville, Kentucky."),
            "the delta, once: $trajectory",
        )
        assertNoRepeatedSentence(trajectory)
        assertNoRawCoordinates(trajectory)
    }

    // ---------------------------------------------------------------
    // Agency: open options, never the action log
    // ---------------------------------------------------------------

    @Test
    fun `agency lists open options and rails - never past actions as the answer`() {
        val (h, _) = makeHarness()
        // The record after a completed text to Shan: execution and
        // outcome events on action.* topics — History's material.
        h.admit(
            AdmitInput(
                kind = EventKind.EXECUTION,
                topic = "action.send_text",
                claim = "Executed send_text on Shan.",
                source = "sovereign",
            ),
        )
        h.admit(
            AdmitInput(
                kind = EventKind.OUTCOME,
                topic = "action.send_text",
                claim = "Text sent to Shan: \"I love you\"",
                source = "system",
            ),
        )
        val agency = h.questions.get("agency")!!.current.text
        assertFalse(agency.contains("Executed send_text"), "the action log is not an answer: $agency")
        assertFalse(agency.contains("Text sent to Shan"), "a past outcome is not an open option: $agency")
    }

    // ---------------------------------------------------------------
    // Chat capability fixtures (his screenshots, 2026-10-27)
    // ---------------------------------------------------------------

    @Test
    fun `chat fixture - how can u use you finger routes to the device-control capability answer`() {
        val (h, _) = makeHarness()
        val r = h.say("how can u use you finger?")
        assertTrue(r.reply.contains("accessibility service"), "what the fingers are: ${r.reply}")
        assertTrue(r.reply.contains("Settings"), "the restoring step is named: ${r.reply}")
        assertFalse(r.reply.contains("don't have anything on record"), "never the record deflection: ${r.reply}")
        assertFalse(r.reply.contains("won't guess"), "never the record deflection: ${r.reply}")
    }

    @Test
    fun `chat fixture - can you search on Google routes to the plugs answer`() {
        val (h, _) = makeHarness()
        val r = h.say("can you search on Google for me?")
        assertTrue(r.reply.contains("isn't plugged in yet"), "plainly unplugged: ${r.reply}")
        assertFalse(r.reply.contains("don't have anything on record"), "never the record deflection: ${r.reply}")
        assertFalse(r.reply.contains("won't guess"), "never the record deflection: ${r.reply}")
    }

    @Test
    fun `record deflection still serves genuine record questions`() {
        val (h, _) = makeHarness()
        val r = h.say("what do you have about the cabinet job?")
        assertTrue(r.reply.contains("on record"), "a genuine record question still searches the record: ${r.reply}")
    }

    // ---------------------------------------------------------------
    // Conversational rails (§§14–17 acceptance fixture)
    // ---------------------------------------------------------------

    @Test
    fun `rail fixture - set a rail renders one rail for assent, and assent sets it`() {
        // 10:00 UTC is morning in the CI zone and in Indianapolis.
        val (h, _) = makeHarness("2026-10-23T10:00:00.000Z")
        val r = h.say("Set a rail — texts to Shan in the morning can go without asking me each time")
        assertNotNull(r.proposal, "a rail is authored as a proposal, not a settings screen")
        assertEquals("set_rail", r.proposal!!.actionType)
        assertTrue(r.reply.contains("Texts to Shan send without asking you first"), "scope rendered: ${r.reply}")
        assertTrue(r.reply.contains("in the morning"), "bound rendered: ${r.reply}")
        assertTrue(r.reply.contains("kill that rail"), "revocation rendered: ${r.reply}")
        assertTrue(r.reply.contains("drifts"), "suspension terms rendered: ${r.reply}")
        assertEquals(0, h.actions.listRails().size, "no rail exists before assent")

        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed, "assent executes the rail commit: ${yes.reply}")
        val rails = h.actions.listRails()
        assertEquals(1, rails.size)
        val rail = rails[0]
        assertEquals("send_text", rail.actionType)
        assertEquals("Shan", rail.target)
        assertEquals("morning", rail.timeBound)
        assertNotNull(rail.terms, "the assented rendering is kept with the rail")
        assertTrue(rail.terms!!.contains("Texts to Shan"), "terms carry the rendering: ${rail.terms}")

        // Inside the bound, a text to Shan is covered by the rail;
        // outside it (a different recipient), it asks.
        val morning = h.say("text Shan saying good morning")
        assertEquals(PolicyOutcome.EXECUTABLE_UNDER_RAIL, morning.proposal?.policy, "covered action runs under the rail: ${morning.reply}")
        val other = h.say("text Brandon saying good morning")
        assertEquals(PolicyOutcome.NEEDS_FRESH_ASSENT, other.proposal?.policy, "a different recipient is outside the rail: ${other.reply}")
    }

    @Test
    fun `rail fixture - kill that rail revokes it conversationally`() {
        val (h, _) = makeHarness("2026-10-23T10:00:00.000Z")
        h.say("Set a rail — texts to Shan in the morning can go without asking me each time")
        h.say("yes")
        assertEquals(1, h.actions.listRails().count { it.active })
        val r = h.say("kill that rail")
        assertTrue(r.reply.contains("Rail killed"), "the revocation is rendered back: ${r.reply}")
        assertEquals(0, h.actions.listRails().count { it.active }, "the rail no longer covers")
        assertEquals(1, h.actions.listRails().size, "the rail stays on record as revoked")
    }

    @Test
    fun `rail fixture - ambiguous scope earns one clarifying question, never a guessed rail`() {
        val (h, _) = makeHarness()
        val r = h.say("Set a rail — stuff can just go")
        assertEquals(Intent.CLARIFY, r.intent)
        assertEquals(null, r.proposal)
        assertEquals(0, h.actions.listRails().size)
    }

    // ---------------------------------------------------------------
    // The Vault ANR's arithmetic: weeks of data stay cheap to read
    // ---------------------------------------------------------------

    @Test
    fun `large ledger - standing answers and liveness stay correct at weeks-of-data scale`() {
        val (h, clock) = makeHarness()
        // ~3 weeks of fixes at the collector's cadence shape: the
        // same place, fix after fix, with raw fix rows interleaved —
        // the state file the Vault acceptance is judged against.
        repeat(1500) { i ->
            clock.set("2026-10-0${1 + (i % 9)}T1${i % 10}:00:00.000Z")
            h.admitSensor("location", "location", "You are near 344 W Leota, Scottsburg, Scott County, Indiana")
            h.admitSensor("location", "location.fix", "Location fix: 38.68632, -85.77766 (gps, accuracy ±4m)")
        }
        val position = h.questions.get("position")!!.current.text
        assertTrue(position.startsWith("You are near 344 W Leota"), "position still reads the place: $position")
        assertNoRawCoordinates(position)
        val trajectory = h.questions.get("trajectory")!!.current.text
        assertTrue(trajectory.startsWith("No movement on record today"), "3000 events in, still no parroting: $trajectory")
        val live = h.ledger.liveEvents()
        assertEquals(3000, live.size, "liveness is computed over the whole record, correctly")
        assertTrue(h.ledger.verifyChain(), "the chain still verifies end to end")
    }

    // ---------------------------------------------------------------
    // Language pass (2026-10-28): no engine-room vocabulary on the
    // surface — replies speak to the sovereign, not to a terminal.
    // ---------------------------------------------------------------

    private fun assertForwardFacing(reply: String) {
        for (term in listOf("Policy:", "adapter", "insufficiently_specified", "needs_fresh_assent", "executable_under_rail", "drift_flagged", "hard constraint", "harness")) {
            assertFalse(reply.contains(term), "engine-room term \"$term\" leaked into: $reply")
        }
    }

    @Test
    fun `language - the no-way-to-send reply is plain words, honestly stated`() {
        val (h, _) = makeHarness()
        val r = h.say("Send an email to the client about the cabinets")
        assertTrue(r.reply.contains("cannot execute"), "the limit is stated, not hidden: ${r.reply}")
        assertTrue(r.reply.contains("Nothing has been sent"), "the outcome is stated: ${r.reply}")
        assertForwardFacing(r.reply)
        val yes = h.say("yes")
        assertForwardFacing(yes.reply)
    }

    @Test
    fun `language - an unbuilt imperative is plain words too`() {
        val (h, _) = makeHarness()
        val r = h.say("unlock the door")
        assertTrue(r.reply.contains("cannot execute"), "the limit is stated: ${r.reply}")
        assertForwardFacing(r.reply)
    }

    @Test
    fun `language - the hard refusal names the rule in plain words`() {
        val (h, _) = makeHarness()
        val r = h.say("delete the record of my texts from today")
        assertTrue(r.reply.startsWith("Denied."), "a refusal, plainly: ${r.reply}")
        assertForwardFacing(r.reply)
    }

    @Test
    fun `language - a drift refusal speaks of the limit he set, not an envelope`() {
        val (h, _) = makeHarness()
        h.actions.addRail("send_tip", "creators", mapOf("amount" to 100.0), "sovereign")
        val p = h.actions.propose(
            ProposalInput(
                actionType = "send_tip",
                target = "creators",
                parameters = mapOf("amount" to 500.0),
                expectedEffect = "Send a tip.",
                reversibility = Reversibility.IRREVERSIBLE,
                consequenceClass = ConsequenceClass.EXTERNAL,
                requiredAuthority = RequiredAuthority.RAIL,
                requestedBy = "sovereign",
            ),
        )
        assertEquals(PolicyOutcome.DRIFT_FLAGGED, p.policy)
        assertTrue(p.policyReason.contains("past the limit you set"), "his limit, in his terms: ${p.policyReason}")
        assertForwardFacing(p.policyReason)
        val exec = h.actions.execute(p.id)
        assertForwardFacing(exec.message)
    }

    @Test
    fun `language - the review prompt shows exactly what will be sent`() {
        val (h, _) = makeHarness()
        val r = h.say("text Shan saying I'll be home by supper")
        assertTrue(r.reply.contains("Send this text?"), "one prompt, reviewing the send: ${r.reply}")
        assertTrue(r.reply.contains("To: Shan"), "the recipient, exactly: ${r.reply}")
        assertTrue(r.reply.contains("I'll be home by supper"), "the words she will read: ${r.reply}")
        assertForwardFacing(r.reply)
    }

    @Test
    fun `retraction and supersession still resolve at scale`() {
        val (h, _) = makeHarness()
        val first = h.admit(AdmitInput(kind = EventKind.USER_REPORT, topic = "note.x", claim = "first version", source = "sovereign"))
        repeat(500) { i ->
            h.admit(AdmitInput(kind = EventKind.USER_REPORT, topic = "note.filler$i", claim = "filler $i", source = "sovereign"))
        }
        h.admit(
            AdmitInput(
                kind = EventKind.USER_REPORT,
                topic = "note.x",
                claim = "second version",
                source = "sovereign",
                relations = listOf(Relation(RelationType.SUPERSEDES, first.id)),
            ),
        )
        val live = h.ledger.liveEvents()
        assertFalse(live.any { it.id == first.id }, "the superseded event is not live")
        assertTrue(h.ledger.isSuperseded(first.id))
    }
}
