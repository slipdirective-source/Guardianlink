package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The self-report: "what are you tracking?" must be answered from
 * the system's own books — the collector registry and the ledger —
 * not fall through to the evidence search and report a miss. These
 * tests pin that behavior: registry truth before evidence exists,
 * ledger truth after it does.
 */
class SelfReportTest {

    @Test
    fun `what are you tracking lists the registered collectors and the empty record`() {
        val (h, _) = makeHarness()
        val r = h.say("What are you tracking?")
        assertEquals(Intent.QUERY, r.intent)
        assertNull(r.proposal, "asking what is tracked must never propose creating a standing question")
        assertTrue(r.reply.contains("location"), "registry names the location collector: ${r.reply}")
        assertTrue(r.reply.contains("motion"), "registry names the motion collector: ${r.reply}")
        assertTrue(r.reply.contains("nothing yet"), "an empty ledger is named as empty: ${r.reply}")
        assertTrue(r.reply.contains("Data vault"), "the reply routes to where the readings live: ${r.reply}")
    }

    @Test
    fun `self-report counts the record and names the latest position`() {
        val (h, _) = makeHarness()
        h.admitSensor("location", "location", "You are near Scottsburg, Scott County, Indiana")
        h.admitSensor("location", "location.fix", "Location fix: 38.68632, -85.77766 (gps, accuracy ±8m)")
        val r = h.say("what do you collect?")
        assertEquals(Intent.QUERY, r.intent)
        assertTrue(r.reply.contains("2 location"), "both location-family events counted: ${r.reply}")
        assertTrue(
            r.reply.contains("Latest position: You are near Scottsburg, Scott County, Indiana"),
            "the latest place claim leads the position line: ${r.reply}",
        )
    }

    @Test
    fun `a denied collector is reported off, with its reason`() {
        val (h, _) = makeHarness()
        h.denyCollector("location", "location permission not granted")
        val r = h.say("What are you tracking?")
        assertTrue(r.reply.contains("[off — location permission not granted]"), "denial and reason surface: ${r.reply}")
    }
}
