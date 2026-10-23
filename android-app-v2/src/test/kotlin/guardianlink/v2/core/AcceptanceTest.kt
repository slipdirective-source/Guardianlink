package guardianlink.v2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Port of the TS core's `test/acceptance.test.ts` — spec §6 acceptance
 * scenarios 1–16 plus the conversation clarify case, with identical
 * assertions. Sensor scenarios (§6.4) run through admitted sensor-kind
 * events, as in the TS suite. The TS suite's file round-trip for sync
 * (§6.10/§6.14) is replaced by the bundle object itself: the merge
 * semantics under test are transport-independent (see module README).
 */
class AcceptanceTest {

    /* §6.1 Conversational report — evidence admitted; Position/Agency update without a form */
    @Test
    fun `6-1 conversational report updates standing answers`() {
        val (h, _) = makeHarness()
        val posBefore = h.questions.get("position")!!.current.version
        val r = h.say("I took my evening dose.")
        assertEquals(Intent.REPORT, r.intent)
        assertEquals(1, r.admittedEventIds.size)
        val changed = r.answerChanges.map { it.questionId }
        assertTrue(changed.contains("position"), "position updated")
        assertTrue(changed.contains("agency"), "agency updated")
        assertTrue(h.questions.get("position")!!.current.version > posBefore)
        assertTrue(h.questions.get("position")!!.current.text.contains("evening dose"))
    }

    /* §6.2 Conflict stays live — both statements remain; the answer surfaces the conflict */
    @Test
    fun `6-2 contradiction surfaces a live conflict, never silently resolved`() {
        val (h, _) = makeHarness()
        h.say("I took my evening dose.")
        val r = h.say("Actually, I skipped it.")
        assertEquals(Intent.REPORT, r.intent)
        val pos = h.questions.get("position")!!.current
        assertEquals(1, pos.conflicts.size)
        assertEquals("medication.dose", pos.conflicts[0].topic)
        assertEquals(Delta.UNKNOWN, pos.delta)
        assertTrue(pos.text.contains("CONFLICT"))
        val claims = h.ledger.allEvents().map { it.claim }
        assertTrue(claims.any { it.contains("took my evening dose") }, "original statement remains")
        assertTrue(claims.any { it.contains("skipped it") }, "contradiction remains")
    }

    /* §6.3 Correction binds by reference and supersedes */
    @Test
    fun `6-3 correction binds and supersedes the intended prior statement`() {
        val (h, _) = makeHarness()
        val r1 = h.say("The cabinet job is on schedule.")
        val originalId = r1.admittedEventIds[0]
        val r2 = h.say("Correct that: the cabinet job is behind schedule.")
        assertEquals(Intent.CORRECT, r2.intent)
        assertTrue(h.ledger.isSuperseded(originalId))
        val rel = h.questions.get("relationships")!!.current
        assertTrue(rel.text.contains("behind schedule"))
        assertEquals(0, rel.conflicts.size, "superseded statement no longer creates a live conflict")
    }

    /* §6.3 Ambiguous retraction clarifies and retracts nothing */
    @Test
    fun `6-3 ambiguous retraction returns a clarify request and retracts nothing`() {
        val (h, _) = makeHarness()
        h.say("I took my morning dose.")
        h.say("I took my evening dose.")
        val countBefore = h.ledger.allEvents().size
        val r = h.say("Retract what I said about the dose")
        assertEquals(Intent.CLARIFY, r.intent)
        assertNotNull(r.clarifyQuestion)
        assertEquals(countBefore, h.ledger.allEvents().size, "nothing was admitted on an ambiguous binding")
        assertEquals(2, h.ledger.liveEvents().count { it.kind == EventKind.USER_REPORT })
    }

    /* §6.4 Arrival (simulated sensor events) — Position and Trajectory update with provenance */
    @Test
    fun `6-4 sensor arrival updates Position and Trajectory`() {
        val (h, _) = makeHarness()
        h.admitSensor("location", "location", "Arrived at Home", confidence = 0.95)
        val pos = h.questions.get("position")!!.current
        assertTrue(pos.text.contains("Arrived at Home"))
        assertEquals(Basis.OBSERVED, pos.basis)
        h.admitSensor("location", "location", "Arrived at the shop", confidence = 0.93)
        val traj = h.questions.get("trajectory")!!.current
        assertTrue(traj.text.contains("movement: Arrived at Home -> Arrived at the shop"))
    }

    /* §6.5 Change query composes deltas over the interval */
    @Test
    fun `6-5 what changed since this morning composes evidence and answer deltas`() {
        val (h, clock) = makeHarness("2026-10-23T05:00:00.000Z")
        h.admit(AdmitInput(kind = EventKind.EXTERNAL_REPORT, topic = "note.early", claim = "Early note before the morning window", source = "test"))
        clock.set("2026-10-23T14:00:00.000Z")
        h.say("I took my evening dose.")
        val r = h.say("What changed since this morning?")
        assertEquals(Intent.QUERY, r.intent)
        assertTrue(r.reply.contains("evening dose"))
        assertTrue(!r.reply.contains("Early note"), "events before the window are excluded")
        assertTrue(r.reply.contains("Answer changes:"))
    }

    /* §6.6 New standing question: proposed explicitly, committed on assent, then flags drift */
    @Test
    fun `6-6 standing question lifecycle with drift flag`() {
        val (h, _) = makeHarness()
        val r = h.say("Keep track of whether the cabinet job is drifting")
        assertEquals(Intent.ACT, r.intent)
        assertEquals(PolicyOutcome.NEEDS_FRESH_ASSENT, r.proposal?.policy)
        assertEquals(0, h.questions.all().count { it.cluster == ClusterId.CUSTOM }, "not created before assent")
        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed)
        val custom = h.questions.all().firstOrNull { it.cluster == ClusterId.CUSTOM }
        assertNotNull(custom, "standing question exists after assent")
        h.say("The cabinet job is behind schedule after the client changed the finish.")
        assertTrue(custom!!.current.text.contains("DRIFT FLAGGED"))
        assertTrue(custom.history.size >= 2, "answer has a change history")
    }

    /* §6.7 Governed action: no rail -> proposal, fresh assent required, nothing executes early */
    @Test
    fun `6-7 action without a rail does not execute before assent`() {
        val (h, _) = makeHarness()
        val r = h.say("Stop collecting location")
        assertEquals(PolicyOutcome.NEEDS_FRESH_ASSENT, r.proposal?.policy)
        assertEquals(0, h.ledger.allEvents().count { it.kind == EventKind.EXECUTION })
        assertEquals(CollectorStatus.ACTIVE, h.collectors.get("location")!!.status)
    }

    /* §6.8 Drift flag: parameters outside a committed rail envelope suspend execution */
    @Test
    fun `6-8 drift-flagged proposal is refused; in-envelope proposal runs under the rail`() {
        val (h, _) = makeHarness()
        h.actions.addRail("pause_collector", "location", mapOf("durationMin" to 60.0), "sovereign")
        val drift = h.actions.propose(
            ProposalInput(
                actionType = "pause_collector",
                target = "location",
                parameters = mapOf("durationMin" to 120),
                expectedEffect = "Location collector pauses for two hours.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL,
                requiredAuthority = RequiredAuthority.RAIL,
                requestedBy = "sovereign",
            ),
        )
        assertEquals(PolicyOutcome.DRIFT_FLAGGED, drift.policy)
        assertEquals(false, h.actions.execute(drift.id).executed)
        assertEquals(CollectorStatus.ACTIVE, h.collectors.get("location")!!.status, "drift-flagged action did not run")

        val ok = h.actions.propose(
            ProposalInput(
                actionType = "pause_collector",
                target = "location",
                parameters = mapOf("durationMin" to 30),
                expectedEffect = "Location collector pauses for thirty minutes.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL,
                requiredAuthority = RequiredAuthority.RAIL,
                requestedBy = "sovereign",
            ),
        )
        assertEquals(PolicyOutcome.EXECUTABLE_UNDER_RAIL, ok.policy)
        assertEquals(true, h.actions.execute(ok.id).executed, "rail-covered action executes without fresh assent")
        assertEquals(CollectorStatus.PAUSED, h.collectors.get("location")!!.status)
    }

    /* §6.9 Collection command: collector stops, surface reflects it, command is audited */
    @Test
    fun `6-9 stop-collecting command takes effect and is audited`() {
        val (h, _) = makeHarness()
        h.say("Stop collecting location")
        val yes = h.say("yes")
        assertEquals(true, yes.execution?.executed)
        assertEquals(CollectorStatus.PAUSED, h.collectors.get("location")!!.status)
        assertTrue(h.questions.get("position")!!.current.text.contains("collection paused"))
        assertTrue(h.audit.query(action = "collector_changed").isNotEmpty())
        assertTrue(h.audit.query(action = "assent_recorded").isNotEmpty())
    }

    /* §6.10 Offline continuity: the whole loop, including sync, runs with no network */
    @Test
    fun `6-10 offline continuity - full loop and sync are local`() {
        val (h, _) = makeHarness()
        assertEquals(Intent.REPORT, h.say("I took my evening dose.").intent)
        assertEquals(Intent.ASK, h.say("Where am I?").intent)
        h.say("Stop collecting location")
        assertEquals(true, h.say("yes").execution?.executed)
        val bundle = h.syncService.exportBundle()
        val (other, _) = makeHarness()
        val result = other.syncService.importBundle(bundle)
        assertTrue(result.added > 0)
    }

    /* §6.11 Permission denial: named capability, honest degradation, no fabricated evidence */
    @Test
    fun `6-11 permission denial is named and blocks collection`() {
        val (h, _) = makeHarness()
        h.admitSensor("location", "location", "Arrived at Home", confidence = 0.9)
        h.denyCollector("location", "OS permission denied")
        assertEquals(CollectorStatus.DENIED, h.collectors.get("location")!!.status)
        val ex = assertThrows(IllegalStateException::class.java) {
            h.admitSensor("location", "location", "Arrived at work")
        }
        assertTrue(ex.message!!.contains("denied"))
        assertTrue(h.questions.get("position")!!.current.text.contains("permission denied"))
    }

    /* §6.12 No fake action: unimplemented adapter can be proposed/drafted, never executed */
    @Test
    fun `6-12 no fake execution for actions without an adapter`() {
        val (h, _) = makeHarness()
        val r = h.say("Send an email to the client about the cabinets")
        assertEquals(Intent.ACT, r.intent)
        assertTrue(r.reply.contains("cannot execute"))
        val yes = h.say("yes")
        assertEquals(false, yes.execution?.executed)
        assertEquals(OutcomeStatus.CANNOT_EXECUTE, yes.execution?.outcomeStatus)
        assertEquals(0, h.ledger.allEvents().count { it.kind == EventKind.EXECUTION }, "no execution event was fabricated")
    }

    /* §6.13 Account use under authority: scope checked; disconnection blocks use */
    @Test
    fun `6-13 account evidence under authority; disconnect blocks use`() {
        val (h, _) = makeHarness()
        h.accounts.register(Account(id = "gmail-biz", label = "Business Gmail", role = "business", scopes = listOf("read_email")))
        val ev = h.admitAccountEvidence("gmail-biz", "read_email", "account.email", "Client confirmed Friday install")
        assertEquals(EventKind.EXTERNAL_REPORT, ev.kind)
        assertEquals("account:gmail-biz", ev.source)
        val scopeEx = assertThrows(IllegalStateException::class.java) {
            h.admitAccountEvidence("gmail-biz", "send_email", "account.email", "x")
        }
        assertTrue(scopeEx.message!!.contains("scope"))
        h.accounts.disconnect("gmail-biz")
        val discEx = assertThrows(IllegalStateException::class.java) {
            h.admitAccountEvidence("gmail-biz", "read_email", "account.email", "x")
        }
        assertTrue(discEx.message!!.contains("disconnected"))
    }

    /* §6.14 Sync: verifiable, idempotent, conflict-preserving */
    @Test
    fun `6-14 sync import verifies, merges idempotently, preserves conflicts`() {
        val (a, _) = makeHarness()
        a.say("I took my evening dose.")
        val (b, _) = makeHarness()
        b.admit(AdmitInput(kind = EventKind.USER_REPORT, topic = "note.b", claim = "B-only note", source = "sovereign"))

        val bundle = a.syncService.exportBundle()

        val first = b.syncService.importBundle(bundle)
        assertTrue(first.rootVerified)
        assertTrue(first.chainVerified)
        assertEquals(1, first.conflicts.size, "same id, different content: conflict preserved")
        val claimsForId = b.ledger.allEvents().filter { it.id == first.conflicts[0].id }.map { it.claim }
        assertTrue(claimsForId.any { it.contains("evening dose") })
        assertTrue(claimsForId.any { it.contains("B-only note") })

        val second = b.syncService.importBundle(bundle)
        assertEquals(0, second.added, "re-import is idempotent")
    }

    /* §6.15 Audit reconstruction: the full story of one action is recoverable */
    @Test
    fun `6-15 audit reconstruction of a governed action`() {
        val (h, _) = makeHarness()
        val r = h.say("Stop collecting location")
        val proposalId = r.proposal!!.id
        h.say("yes")
        for (action in listOf("proposal_created", "policy_decision", "assent_recorded", "execution", "outcome_recorded")) {
            assertTrue(h.audit.query(action = action).isNotEmpty(), "audit contains $action")
        }
        val assent = h.ledger.allEvents().first { it.kind == EventKind.ASSENT }
        assertTrue(assent.relations.any { rel -> rel.type == RelationType.AUTHORIZES && rel.targetId == proposalId })
        val execution = h.ledger.allEvents().first { it.kind == EventKind.EXECUTION }
        assertTrue(execution.relations.any { rel -> rel.type == RelationType.EXECUTES && rel.targetId == proposalId })
        val outcome = h.ledger.allEvents().first { it.kind == EventKind.OUTCOME }
        assertTrue(outcome.relations.any { rel -> rel.type == RelationType.DERIVES_FROM && rel.targetId == execution.id })
        val v = h.verifyIntegrity()
        assertEquals(true, v["ledgerChain"])
        assertEquals(true, v["auditChain"])
    }

    /* §6.16 Outside-agent plug bounds: scoped context, agent-derived labels, no self-approval, revocation */
    @Test
    fun `6-16 agent plug bounds are enforced at the boundary`() {
        val (h, _) = makeHarness()
        h.say("I took my evening dose.")
        h.plugs.register(
            AgentPlug(
                id = "analyst-1",
                name = "Analyst",
                roles = listOf(AgentRole.ANALYST),
                contextScopes = listOf("project.cabinet_job"),
                capabilityScopes = emptyList(),
                expiresAt = "2999-01-01T00:00:00.000Z",
            ),
        )
        val ev = h.plugs.submitEvidence("analyst-1", topic = "project.cabinet_job", claim = "Agent analysis: schedule risk rising", confidence = 0.6)
        assertEquals(EventKind.AGENT_DERIVED, ev.kind)
        assertTrue(ev.agentDerived)
        assertTrue(h.questions.get("relationships")!!.current.text.contains("agent-derived"))

        val ctx = h.plugs.readContext("analyst-1")
        assertTrue(ctx.isNotEmpty())
        assertTrue(ctx.all { it.topic.startsWith("project.cabinet_job") }, "plug sees only its scope — the dose event is invisible to it")

        val scopeEx = assertThrows(IllegalStateException::class.java) {
            h.plugs.submitEvidence("analyst-1", topic = "medication.dose", claim = "out of scope")
        }
        assertTrue(scopeEx.message!!.contains("outside its context scopes"))
        val capEx = assertThrows(IllegalStateException::class.java) {
            h.plugs.assertCapability("analyst-1", "pause_collector")
        }
        assertTrue(capEx.message!!.contains("no capability"))

        h.plugs.revoke("analyst-1")
        val revEx = assertThrows(IllegalStateException::class.java) {
            h.plugs.submitEvidence("analyst-1", topic = "project.cabinet_job", claim = "after revoke")
        }
        assertTrue(revEx.message!!.contains("revoked"))
        assertThrows(IllegalStateException::class.java) { h.plugs.readContext("analyst-1") }
        assertTrue(h.audit.query(action = "plug_submission").isNotEmpty(), "plug exchange is reconstructible from audit")
        assertTrue(h.audit.query(action = "plug_revoked").isNotEmpty())
    }

    /* Conversation: ambiguous input clarifies instead of guessing */
    @Test
    fun `conversation - unclassifiable input returns clarify, never a guess`() {
        val (h, _) = makeHarness()
        val r = h.say("Hmm.")
        assertEquals(Intent.CLARIFY, r.intent)
        assertNotNull(r.clarifyQuestion)
        val r2 = h.say("Do it")
        assertEquals(Intent.CLARIFY, r2.intent)
    }
}
