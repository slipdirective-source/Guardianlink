package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The surface register: standing answers and conversation replies
 * are composed prose — sentences a person would say — never the
 * framework's underground format. These tests pin the absences as
 * much as the content: no topic prefixes, no source/confidence
 * tags, no wire values, no raw metric readouts in an answer.
 */
class ProseSurfaceTest {

    @Test
    fun `position answer leads with the place and keeps machinery out`() {
        val (h, _) = makeHarness()
        h.admit(
            AdmitInput(
                kind = EventKind.SENSOR_OBSERVATION,
                topic = "state.battery",
                claim = "Battery at 67% (not charging)",
                source = "sensor:device_state",
                confidence = 0.95,
            ),
        )
        h.admitSensor("location", "location", "You are near Scottsburg, Scott County, Indiana")
        h.admitSensor("location", "location.fix", "Location fix: 38.68632, -85.77766 (gps, accuracy ±8m)")
        val text = h.questions.get("position")!!.current.text
        assertTrue(text.startsWith("You are near Scottsburg, Scott County, Indiana."), "the place leads, as a sentence: $text")
        assertTrue(text.contains("Battery at 67% — not charging."), "other evidence follows as prose: $text")
        assertFalse(text.contains("38.68632"), "raw fixes are vault material, never answer text: $text")
        assertFalse(text.contains("[sensor"), "no source tags: $text")
        assertFalse(text.contains("conf "), "no confidence values: $text")
        assertFalse(text.contains("state.battery:"), "no topic prefixes: $text")
        assertFalse(text.contains("collector location"), "no collector dump in the geographic answer: $text")
    }

    @Test
    fun `position with no fix yet names the collector state in prose`() {
        val (h, _) = makeHarness()
        h.denyCollector("location", "location permission not granted")
        val text = h.questions.get("position")!!.current.text
        assertTrue(
            text.contains("Location collector — permission denied — location permission not granted."),
            "the status is the answer, in words: $text",
        )
    }

    @Test
    fun `asking where am i returns the prose answer with no machinery suffix`() {
        val (h, _) = makeHarness()
        h.admitSensor("location", "location", "You are near Scottsburg, Scott County, Indiana")
        val r = h.say("so where am I?")
        assertEquals(Intent.ASK, r.intent)
        assertTrue(r.reply.contains("You are near Scottsburg"), "the answer is the reply: ${r.reply}")
        assertFalse(r.reply.contains("uncertainty:"), "no wire suffix: ${r.reply}")
        assertFalse(r.reply.contains("basis:"), "no wire suffix: ${r.reply}")
    }

    @Test
    fun `a fragment after track words clarifies instead of proposing`() {
        val (h, _) = makeHarness()
        val r = h.say("keep track of?")
        assertEquals(Intent.CLARIFY, r.intent)
        assertNull(r.proposal, "a fragment must never become a proposal")
    }

    @Test
    fun `what will you keep track of is a self-report question, not a proposal`() {
        val (h, _) = makeHarness()
        val r = h.say("what will you keep track of?")
        assertEquals(Intent.QUERY, r.intent)
        assertNull(r.proposal)
        assertTrue(r.reply.contains("collectors registered"), "answered from the registry: ${r.reply}")
    }
}
