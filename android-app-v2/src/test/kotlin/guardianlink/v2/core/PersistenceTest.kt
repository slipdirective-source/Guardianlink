package guardianlink.v2.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Persistence (StateStore): the full core state survives a save/load
 * round trip byte-for-byte, both hash chains verify after reload, and
 * every damaged-store shape fails closed with a named error — never a
 * partial or silent load.
 */
class PersistenceTest {

    private class RichState(
        val h: GuardianLink,
        val retractedEventId: String,
        val proposalId: String,
    )

    /**
     * A harness with something in every drawer: a live conflict, a
     * superseded statement, a bound retraction, a foreign (synced)
     * event, a rail plus an executed proposal under it, a revoked plug
     * that submitted evidence, a disconnected account that contributed
     * evidence, a paused collector and a denied collector.
     */
    private fun richState(): RichState {
        val (h, _) = makeHarness()
        h.say("I took my evening dose.")
        h.say("Actually, I skipped it.") // live conflict on medication.dose
        h.say("The cabinet job is on schedule.")
        h.say("Correct that: the cabinet job is behind schedule.") // supersedes

        val note = h.admit(
            AdmitInput(kind = EventKind.USER_REPORT, topic = "note.temp", claim = "Temporary note", source = "sovereign"),
        )
        h.admit(
            AdmitInput(
                kind = EventKind.USER_REPORT,
                topic = "note.temp",
                claim = "Retracting the temporary note",
                source = "sovereign",
                relations = listOf(Relation(RelationType.RETRACTS, note.id)),
            ),
        )

        // A foreign event from another device copy: same first id, different body.
        val (other, _) = makeHarness()
        other.admit(AdmitInput(kind = EventKind.USER_REPORT, topic = "note.b", claim = "B-only note", source = "sovereign"))
        h.syncService.importBundle(other.syncService.exportBundle())

        h.actions.addRail("pause_collector", "location", mapOf("durationMin" to 60.0), "sovereign")
        val proposal = h.actions.propose(
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
        assertTrue(h.actions.execute(proposal.id).executed)

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
        h.plugs.submitEvidence("analyst-1", "project.cabinet_job", "Agent analysis: schedule risk rising", 0.6)
        h.plugs.revoke("analyst-1")

        h.accounts.register(Account(id = "gmail-biz", label = "Business Gmail", role = "business", scopes = listOf("read_email")))
        h.admitAccountEvidence("gmail-biz", "read_email", "account.email", "Client confirmed Friday install")
        h.accounts.disconnect("gmail-biz")

        h.denyCollector("motion", "OS permission denied in test")
        return RichState(h, note.id, proposal.id)
    }

    @Test
    fun `round trip preserves the full state exactly and both chains verify after reload`() {
        val rs = richState()
        val storage = InMemoryStateStorage()
        StateStore.save(rs.h, storage, "state")

        val restored = StateStore.load(storage, "state")!!

        assertTrue(restored.ledger.verifyChain(), "ledger chain verifies after reload")
        assertTrue(restored.audit.verify(), "audit chain verifies after reload")
        assertEquals(rs.h.ledger.headHash(), restored.ledger.headHash())
        assertEquals(rs.h.audit.rootHash(), restored.audit.rootHash())
        assertEquals(rs.h.ledger.allEvents().size, restored.ledger.allEvents().size)
        assertEquals(rs.h.audit.all().size, restored.audit.all().size)
        // The strongest statement of "exactly": re-serializing the restored
        // harness reproduces the original document byte for byte.
        assertEquals(StateStore.serialize(rs.h), StateStore.serialize(restored))
        // Standing-question state, including versions, survived as state.
        val posBefore = rs.h.questions.get("position")!!.current
        val posAfter = restored.questions.get("position")!!.current
        assertEquals(posBefore.version, posAfter.version)
        assertEquals(posBefore.text, posAfter.text)
        assertEquals(
            rs.h.questions.get("position")!!.history.size,
            restored.questions.get("position")!!.history.size,
            "the answer version history survived in full",
        )
    }

    @Test
    fun `conflicts retractions revocations and rails survive persistence`() {
        val rs = richState()
        val text = StateStore.serialize(rs.h)
        val restored = StateStore.deserialize(text)

        val pos = restored.questions.get("position")!!.current
        assertEquals(1, pos.conflicts.size, "the live medication conflict is still live after reload")
        assertEquals(Delta.UNKNOWN, pos.delta)
        assertTrue(restored.ledger.isRetracted(rs.retractedEventId), "the bound retraction still binds")
        assertEquals(1, restored.ledger.foreignEvents().size, "the foreign event survived")
        assertTrue(
            restored.ledger.allEvents().any { it.claim.contains("B-only note") },
            "the conflicting foreign claim is preserved, not overwritten",
        )

        assertEquals(CollectorStatus.PAUSED, restored.collectors.get("location")!!.status)
        assertEquals(CollectorStatus.DENIED, restored.collectors.get("motion")!!.status)

        val plugEx = assertThrows(IllegalStateException::class.java) { restored.plugs.assertActive("analyst-1") }
        assertTrue(plugEx.message!!.contains("revoked"), "plug revocation survived")
        val acctEx = assertThrows(IllegalStateException::class.java) { restored.accounts.assertUsable("gmail-biz", "read_email") }
        assertTrue(acctEx.message!!.contains("disconnected"), "account disconnection survived")

        assertEquals(ProposalStatus.EXECUTED, restored.actions.getProposal(rs.proposalId)!!.status)
        val (outcome, _) = restored.actions.evaluate(
            ProposalInput(
                actionType = "pause_collector",
                target = "location",
                parameters = mapOf("durationMin" to 45),
                expectedEffect = "Pause again.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL,
                requiredAuthority = RequiredAuthority.RAIL,
                requestedBy = "sovereign",
            ),
        )
        assertEquals(PolicyOutcome.EXECUTABLE_UNDER_RAIL, outcome, "the restored rail still authorizes in-envelope actions")
    }

    @Test
    fun `tampered ledger content is rejected with a named error`() {
        val rs = richState()
        val tampered = StateStore.serialize(rs.h).replace("evening dose", "evening doze")
        val ex = assertThrows(StateStoreException::class.java) { StateStore.deserialize(tampered) }
        assertEquals(StateStoreError.LEDGER_CHAIN_INVALID, ex.kind)
    }

    @Test
    fun `tampered audit content is rejected with a named error`() {
        val rs = richState()
        val tampered = StateStore.serialize(rs.h).replace("policy_decision", "policy_decisioo")
        val ex = assertThrows(StateStoreException::class.java) { StateStore.deserialize(tampered) }
        assertEquals(StateStoreError.AUDIT_CHAIN_INVALID, ex.kind)
    }

    @Test
    fun `unsupported format is rejected with a named error`() {
        val ex = assertThrows(StateStoreException::class.java) {
            StateStore.deserialize("{\"format\":\"something-else\",\"version\":1}")
        }
        assertEquals(StateStoreError.UNSUPPORTED_FORMAT, ex.kind)
    }

    @Test
    fun `interrupted write leaves the prior good state loadable and a truncated document is rejected`(@TempDir dir: File) {
        val rs = richState()
        val storage = FileStateStorage(dir)
        StateStore.save(rs.h, storage, "state")
        val goodBytes = File(dir, "state").readBytes()

        // The remnant an interrupted write leaves behind: a torn temp
        // file. It is never read, never listed, and the good document
        // remains the state.
        File(dir, "state.tmp").writeBytes(goodBytes.copyOf(goodBytes.size / 2))
        val loaded = StateStore.load(storage, "state")!!
        assertEquals(rs.h.ledger.headHash(), loaded.ledger.headHash())
        assertEquals(listOf("state"), storage.list())

        // Damage the document itself (a torn write that bypassed the
        // atomic path): the load fails closed with a named error and
        // does not rewrite or reset anything.
        val truncated = goodBytes.copyOf(goodBytes.size / 2)
        File(dir, "state").writeBytes(truncated)
        val ex = assertThrows(StateStoreException::class.java) { StateStore.load(storage, "state") }
        assertEquals(StateStoreError.PARSE_ERROR, ex.kind)
        assertTrue(File(dir, "state").readBytes().contentEquals(truncated), "the failed load left the stored bytes untouched")

        // The store is not wedged: a later good save loads fine.
        StateStore.save(rs.h, storage, "state")
        assertEquals(rs.h.ledger.headHash(), StateStore.load(storage, "state")!!.ledger.headHash())
    }

    @Test
    fun `absent store loads as null and an untouched harness round trips`() {
        val storage = InMemoryStateStorage()
        assertNull(StateStore.load(storage, "state"))
        val (h, _) = makeHarness()
        StateStore.save(h, storage, "state")
        val restored = StateStore.load(storage, "state")!!
        assertTrue(restored.ledger.verifyChain())
        assertTrue(restored.audit.verify())
        assertEquals(StateStore.serialize(h), StateStore.serialize(restored))
    }
}
