package guardianlink.v2.core

import guardianlink.adapters.ProductionVerifiers
import guardianlink.adapters.RevocationFeed
import guardianlink.audit.ExternalAnchor
import guardianlink.audit.MerkleAuditLog
import guardianlink.core.ConciergeInterface
import guardianlink.crypto.AssentCodec
import guardianlink.crypto.Ed25519
import guardianlink.gates.Action
import guardianlink.gates.Assent
import guardianlink.gates.FakeClock
import guardianlink.gates.GateContext
import guardianlink.gates.GateOutcome
import guardianlink.gates.KeyMaterial
import guardianlink.gates.NineGates
import guardianlink.gates.Proofs
import guardianlink.gates.Rails
import guardianlink.gates.Revocation
import guardianlink.gates.Signal
import guardianlink.gates.SubstrateState
import guardianlink.gates.render
import guardianlink.policy.PolicyEngine
import guardianlink.policy.RailAuthorizer
import guardianlink.voice.AudioSample
import guardianlink.voice.BiometricVerifier
import guardianlink.voice.SpeakerEmbedding
import guardianlink.voice.VerificationResult
import guardianlink.voice.VoiceTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine evidence path (EVIDENCE-PATH build steps 1–5): key
 * custody, policy ports from core state, substrate/action translation,
 * and the assent ceremony through ConciergeInterface.
 *
 * TEST-ONLY ZK STUB: rigs built with zkStub = true inject
 * `zkVerifier = { _, _ -> true }` into ProductionVerifiers so the
 * bundle can be exercised past Gate 3. That stub exists ONLY in this
 * test file's wiring. Production wiring (GuardianLinkV2App) never
 * passes a zkVerifier — Gate 3 stays at the deny default — and the
 * productionWiringHaltsAtGate3 test below pins exactly that posture
 * using a ProductionVerifiers constructed with NO zk argument.
 *
 * The voice port in these rigs is likewise a test double
 * (AcceptingVoice) standing in for the not-yet-enrolled production
 * port; it accepts only non-empty audio, so the absent-voice halt is
 * exercised by passing an empty sample.
 */
class EngineEvidenceTest {

    /** Test-only voice double: accepts a non-empty sample, rejects the absent-voice path. */
    private class AcceptingVoice : BiometricVerifier {
        override fun enroll(samples: List<AudioSample>): VoiceTemplate =
            throw UnsupportedOperationException("test-only verifier does not enroll")

        override fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult =
            if (sample.pcm.isNotEmpty()) {
                VerificationResult(1.0, VerificationResult.Decision.ACCEPT, VerificationResult.Liveness.LIVE)
            } else {
                VerificationResult(0.0, VerificationResult.Decision.REJECT, VerificationResult.Liveness.NOT_CHECKED)
            }
    }

    private class NoopAnchor : ExternalAnchor {
        override fun anchor(root: String, entryIndex: Int, timestampMs: Long): ExternalAnchor.Receipt =
            ExternalAnchor.Receipt(root, entryIndex, timestampMs, "test-anchor")
    }

    private class Rig(
        zkStub: Boolean = true,
        productionWiring: Boolean = false,
    ) {
        val coreClock = TestClock("2026-10-23T14:00:00.000Z")
        val core = GuardianLink(now = coreClock)
        val tau = FakeClock(1_000_000L)
        val operator: StoredKeyPair = KeyCustody.generate()
        val revocation: StoredKeyPair = KeyCustody.generate()
        val revocationList = mutableListOf<Revocation>()
        val ports = CorePolicyPorts { core }
        val verifiers: ProductionVerifiers
        val gates: NineGates
        val concierge: ConciergeInterface
        val ceremony: AssentCeremony
        val audio = AudioSample(doubleArrayOf(0.25, 0.5, 0.25))

        init {
            val feed = object : RevocationFeed {
                override fun revocationsFor(actionId: String): List<Revocation> =
                    revocationList.filter { it.actionId == actionId }
            }
            verifiers = if (productionWiring) {
                // Exactly the production shape: NO zkVerifier argument,
                // so Gate 3 keeps ProductionVerifiers' deny default.
                ProductionVerifiers(
                    operatorKey = operator.toKeyPair().public,
                    revocationAuthorityKey = revocation.toKeyPair().public,
                    revocationFeed = feed,
                    boundaryCheck = ports::boundaryCheck,
                    policyCheck = ports::policyCheck,
                    divergenceFn = ports::divergence,
                    loopCheck = ports::loopCheck,
                )
            } else {
                ProductionVerifiers(
                    operatorKey = operator.toKeyPair().public,
                    revocationAuthorityKey = revocation.toKeyPair().public,
                    revocationFeed = feed,
                    // TEST-ONLY ZK PROOF STUB — test wiring only, never production.
                    zkVerifier = { _, _ -> zkStub },
                    boundaryCheck = ports::boundaryCheck,
                    policyCheck = ports::policyCheck,
                    divergenceFn = ports::divergence,
                    loopCheck = ports::loopCheck,
                )
            }
            gates = NineGates(
                rails = Rails(),
                verifiers = verifiers,
                biometricVerifier = AcceptingVoice(),
                enrolledVoice = VoiceTemplate(SpeakerEmbedding.Vector(DoubleArray(0)), emptyMap()),
                clock = tau,
                ledger = MerkleAuditLog(),
            )
            concierge = ConciergeInterface(
                policyEngine = PolicyEngine(authorizer = RailAuthorizer.DENY_ALL),
                auditLog = MerkleAuditLog(),
                gates = gates,
                anchor = NoopAnchor(),
                initialSubstrate = EngineTranslation.substrateFrom(core),
            )
            ceremony = AssentCeremony(core, concierge, tau, operator.toKeyPair().private)
        }

        fun pauseProposal(assent: Boolean = true, parameters: Map<String, Any?> = emptyMap()): ActionProposal {
            val p = core.actions.propose(
                ProposalInput(
                    actionType = "pause_collector",
                    target = "location",
                    parameters = parameters,
                    expectedEffect = "The location collector pauses.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL_LOW,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            if (assent) core.actions.recordAssent(p.id)
            return p
        }

        fun request(p: ActionProposal, parseTrees: Int = 1): CeremonyRequest {
            val v = DoubleArray(Intent.entries.size)
            v[Intent.ACT.ordinal] = 1.0
            return CeremonyRequest(
                proposalId = p.id,
                requestText = "Stop collecting location",
                parseTrees = parseTrees,
                intentVector = v,
                currentVector = v.copyOf(),
                measurements = CeremonyMeasurements(signalSnr = 20.0, contextEntropyBits = 1.0),
            )
        }

        /** begin + confirm + ride out the cooling window + submit. */
        fun runCeremony(p: ActionProposal, audioSample: AudioSample = audio): CeremonyOutcome {
            val prepared = when (val b = ceremony.begin(request(p))) {
                is CeremonyBegin.Refused -> throw AssertionError("ceremony refused to start: ${b.reason}")
                is CeremonyBegin.Prepared -> b.ceremony
            }
            ceremony.confirm(prepared)
            tau.advance(20_000L)
            return ceremony.submit(prepared, audioSample)
        }

        /**
         * A hand-built GateContext with REAL cryptographic evidence
         * (AssentCodec-bound assent, Ed25519 origin signature) for
         * gate-level halt tests — the same construction the engine's
         * own suites use, with this rig's keys.
         */
        fun directContext(
            action: Action,
            actionId: String = "direct-1",
            assentedAtMs: Long = tau.nowMs() - 20_000L,
            assentActionId: String = actionId,
            substrate: SubstrateState = concierge.currentSubstrate(),
        ): GateContext {
            val rendering = render(action)
            val message = "direct".toByteArray(Charsets.UTF_8)
            return GateContext(
                signal = Signal("direct".toByteArray(Charsets.UTF_8), snr = 20.0, wellFormed = true),
                keys = KeyMaterial(message, Ed25519.sign(operator.toKeyPair().private, message)),
                proofs = Proofs("zk".toByteArray(Charsets.UTF_8), "pub".toByteArray(Charsets.UTF_8), issuedAtMs = tau.nowMs()),
                contextEntropyBits = 1.0,
                contextParseTrees = 1,
                action = action,
                actionId = actionId,
                substrate = substrate,
                rendering = rendering,
                assent = Assent(AssentCodec.sign(operator.toKeyPair().private, rendering, assentedAtMs, assentActionId)),
                assentedAtMs = assentedAtMs,
                assentAudio = audio,
                intentVector = doubleArrayOf(1.0, 0.0),
                currentVector = doubleArrayOf(1.0, 0.0),
            )
        }

        /** The engine action the pause proposal translates to, against the live substrate. */
        fun translatedPause(p: ActionProposal): Action =
            when (val t = EngineTranslation.translate(p, concierge.currentSubstrate())) {
                is TranslationResult.Ok -> t.action
                is TranslationResult.Refused -> throw AssertionError("translation refused: ${t.reason}")
            }
    }

    /* ---------- the full ceremony, Integrated ---------- */

    @Test
    fun ceremonyIntegratesRepresentableLocalAction() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val outcome = rig.runCeremony(p)
        val submitted = outcome as CeremonyOutcome.Submitted
        val executed = submitted.result as ConciergeInterface.ExecutionResult.Executed
        assertEquals("paused", executed.newSubstrate.records["collector/location"]?.get("status"))
        // The sealed effect is reconciled into the core registry.
        assertEquals("paused", rig.core.collectors.get("location")!!.status.wire)
        // And written back into the v2 ledger as execution evidence with the seal root.
        val writeBack = rig.core.ledger.allEvents().first { it.id == submitted.writeBackEventId }
        assertEquals(EventKind.EXECUTION, writeBack.kind)
        assertTrue(writeBack.claim.contains("INTEGRATED"), "write-back names the seal: ${writeBack.claim}")
        assertTrue(rig.core.audit.all().any { it.action == "engine_execution_integrated" })
    }

    @Test
    fun productionWiringHaltsAtGate3() {
        val rig = Rig(productionWiring = true)
        val p = rig.pauseProposal()
        val outcome = rig.runCeremony(p)
        val submitted = outcome as CeremonyOutcome.Submitted
        val rejected = submitted.result as ConciergeInterface.ExecutionResult.Rejected
        assertEquals(3, rejected.atGate)
        assertTrue(rejected.reason.contains("theta_zk"), "engine reason is verbatim theta_zk: ${rejected.reason}")
        assertTrue(
            submitted.userMessage.startsWith("engine execution halts at Gate 3 (ZK proof system not yet deployed)"),
            "user message is the exact Gate 3 sentence: ${submitted.userMessage}",
        )
        val writeBack = rig.core.ledger.allEvents().first { it.id == submitted.writeBackEventId }
        assertEquals(EventKind.OUTCOME, writeBack.kind)
        assertTrue(writeBack.claim.contains("HALTED at gate 3"), "halt written back verbatim: ${writeBack.claim}")
    }

    /* ---------- per-gate halt paths ---------- */

    @Test
    fun badOriginSignatureHaltsGate1() {
        val rig = Rig()
        val rogue = KeyCustody.generate()
        val rogueCeremony = AssentCeremony(rig.core, rig.concierge, rig.tau, rogue.toKeyPair().private)
        val p = rig.pauseProposal()
        val prepared = when (val b = rogueCeremony.begin(rig.request(p))) {
            is CeremonyBegin.Refused -> throw AssertionError("refused: ${b.reason}")
            is CeremonyBegin.Prepared -> b.ceremony
        }
        rogueCeremony.confirm(prepared)
        rig.tau.advance(20_000L)
        val outcome = rogueCeremony.submit(prepared, rig.audio)
        val rejected = (outcome as CeremonyOutcome.Submitted).result as ConciergeInterface.ExecutionResult.Rejected
        assertEquals(1, rejected.atGate)
        assertTrue(rejected.reason.contains("theta_sig"), rejected.reason)
    }

    @Test
    fun boundaryDenyHaltsGate2() {
        val rig = Rig()
        rig.pauseProposal()
        // A Write to harness/meta with a field the translator never
        // emits: inside the substrate, outside the deployment boundary.
        val action = Action.Write(EngineTranslation.META_RECORD, mapOf("foreignField" to "x"))
        val outcome = rig.gates.evaluate(rig.directContext(action))
        val halted = outcome as GateOutcome.Halted
        assertEquals(2, halted.atGate)
        assertTrue(halted.reason.contains("phi_perm"), halted.reason)
    }

    @Test
    fun policyDenyHaltsGate6() {
        val rig = Rig()
        // A rail with an envelope the proposal then exceeds: the core's
        // policy decision for this proposal is DRIFT_FLAGGED.
        rig.core.actions.addRail("pause_collector", "location", mapOf("level" to 5.0), "sovereign")
        val p = rig.pauseProposal(parameters = mapOf("level" to 9.0))
        assertEquals(PolicyOutcome.DRIFT_FLAGGED, p.policy)
        val outcome = rig.runCeremony(p)
        val rejected = (outcome as CeremonyOutcome.Submitted).result as ConciergeInterface.ExecutionResult.Rejected
        assertEquals(6, rejected.atGate)
        assertTrue(rejected.reason.contains("psi_rules"), rejected.reason)
    }

    @Test
    fun parseTreesNotOneRefusesCeremony() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val begin = rig.ceremony.begin(rig.request(p, parseTrees = 2))
        val refused = begin as CeremonyBegin.Refused
        assertTrue(refused.reason.contains("parse trees"), refused.reason)
    }

    @Test
    fun assentReplayHaltsGate7() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val ctx = rig.directContext(rig.translatedPause(p))
        val first = rig.gates.evaluate(ctx)
        assertTrue(first is GateOutcome.Integrated, "first evaluation seals: $first")
        val second = rig.gates.evaluate(ctx)
        val halted = second as GateOutcome.Halted
        assertEquals(7, halted.atGate)
        assertTrue(halted.reason.contains("assent already consumed"), halted.reason)
    }

    @Test
    fun backdatedAssentHaltsGate7() {
        val rig = Rig()
        val p = rig.pauseProposal()
        // Assent from 25h ago against a fresh, silent ledger: the idle
        // death condition (mirrors the engine's adversarial cases).
        val ctx = rig.directContext(rig.translatedPause(p), assentedAtMs = rig.tau.nowMs() - 90_000_000L)
        val halted = rig.gates.evaluate(ctx) as GateOutcome.Halted
        assertEquals(7, halted.atGate)
        assertTrue(halted.reason.contains("theta_assent_idle"), halted.reason)
    }

    @Test
    fun assentForDifferentActionIdHaltsGate7() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val ctx = rig.directContext(
            rig.translatedPause(p),
            actionId = "this-action",
            assentActionId = "a-different-action",
        )
        val halted = rig.gates.evaluate(ctx) as GateOutcome.Halted
        assertEquals(7, halted.atGate)
        assertTrue(halted.reason.contains("theta_assent"), halted.reason)
    }

    @Test
    fun validRevocationHaltsGate8() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val revokedAt = rig.tau.nowMs() - 1_000L
        val sig = Ed25519.sign(
            rig.revocation.toKeyPair().private,
            AssentCodec.encodeRevocation("revoked-1", revokedAt),
        )
        rig.revocationList.add(Revocation("revoked-1", sig, revokedAt))
        val ctx = rig.directContext(rig.translatedPause(p), actionId = "revoked-1")
        val halted = rig.gates.evaluate(ctx) as GateOutcome.Halted
        assertEquals(8, halted.atGate)
        assertTrue(halted.reason.contains("theta_norev"), halted.reason)
    }

    @Test
    fun absentVoiceEvidenceHaltsGate7() {
        val rig = Rig()
        val p = rig.pauseProposal()
        // Empty audio = voice evidence absent (the production posture
        // until enrollment exists): Gate 7 halts on theta_voice.
        val outcome = rig.runCeremony(p, AudioSample(DoubleArray(0)))
        val rejected = (outcome as CeremonyOutcome.Submitted).result as ConciergeInterface.ExecutionResult.Rejected
        assertEquals(7, rejected.atGate)
        assertTrue(rejected.reason.contains("theta_voice"), rejected.reason)
    }

    @Test
    fun coolingWindowIsRespectedBeforeSubmit() {
        val rig = Rig()
        val p = rig.pauseProposal()
        val prepared = when (val b = rig.ceremony.begin(rig.request(p))) {
            is CeremonyBegin.Refused -> throw AssertionError("refused: ${b.reason}")
            is CeremonyBegin.Prepared -> b.ceremony
        }
        rig.ceremony.confirm(prepared)
        // No clock advance: the fixed-rail cooling window has not elapsed.
        val outcome = rig.ceremony.submit(prepared, rig.audio)
        val refused = outcome as CeremonyOutcome.Refused
        assertTrue(refused.reason.contains("cooling window"), refused.reason)
    }

    /* ---------- translation refusals ---------- */

    @Test
    fun translationRefusesUnrepresentableActions() {
        val rig = Rig()
        val substrate = rig.concierge.currentSubstrate()

        val email = rig.core.actions.propose(
            ProposalInput(
                actionType = "send_email",
                target = "client",
                expectedEffect = "An email is sent.",
                reversibility = Reversibility.IRREVERSIBLE,
                consequenceClass = ConsequenceClass.EXTERNAL,
                requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                requestedBy = "sovereign",
            ),
        )
        val emailResult = EngineTranslation.translate(email, substrate)
        assertTrue(emailResult is TranslationResult.Refused, "send_email has no adapter and no translation")
        assertTrue((emailResult as TranslationResult.Refused).reason.contains("no engine translation"))

        val delete = rig.core.actions.propose(
            ProposalInput(
                actionType = "delete_ledger",
                target = "ledger",
                expectedEffect = "The ledger is deleted.",
                reversibility = Reversibility.IRREVERSIBLE,
                consequenceClass = ConsequenceClass.HIGH,
                requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                requestedBy = "sovereign",
            ),
        )
        val deleteResult = EngineTranslation.translate(delete, substrate)
        assertTrue(deleteResult is TranslationResult.Refused)
        assertTrue((deleteResult as TranslationResult.Refused).reason.contains("hard constraint"))

        val lidar = rig.core.actions.propose(
            ProposalInput(
                actionType = "pause_collector",
                target = "lidar",
                expectedEffect = "The lidar collector pauses.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL_LOW,
                requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                requestedBy = "sovereign",
            ),
        )
        val lidarResult = EngineTranslation.translate(lidar, substrate)
        assertTrue(lidarResult is TranslationResult.Refused)
        assertTrue((lidarResult as TranslationResult.Refused).reason.contains("not a projected substrate record"))
    }

    /* ---------- ports derive from core state, not constants ---------- */

    @Test
    fun divergenceIsDerivedFromCoreSignals() {
        val rig = Rig()
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        assertEquals(0.0, rig.ports.divergence(action), 1e-9)
        // A live conflict in the maintained answers moves the number,
        // by exactly the documented mapping over the core's signals:
        // 0.05 per conflicted standing question (capped at 2), plus
        // 0.05 when the Trajectory answer carries a drift flag.
        rig.core.say("I took my evening dose.")
        rig.core.say("Actually, I skipped it.")
        val questions = rig.core.questions.all()
        val conflicted = questions.count { q -> q.current.conflicts.isNotEmpty() }.coerceAtMost(2)
        val drift = questions.any { q ->
            q.cluster == ClusterId.TRAJECTORY && q.current.text.contains("DRIFT FLAGGED")
        }
        assertTrue(conflicted > 0, "the contradiction registered as a conflict in the standing answers")
        val expected = (0.05 * conflicted + if (drift) 0.05 else 0.0).coerceAtMost(0.15)
        assertEquals(expected, rig.ports.divergence(action), 1e-9)
        assertTrue(rig.ports.divergence(action) > 0.0, "divergence moved off zero with the conflict — not a constant")
    }

    /* ---------- key custody ---------- */

    @Test
    fun keyCustodyRoundTripPreservesIdsAndStateStoreBindsThem() {
        val storage = InMemoryStateStorage()
        val operator = KeyCustody.generate()
        val revocation = KeyCustody.generate()
        KeyCustody.save(storage, "identity-keys", operator, revocation)
        val loaded = KeyCustody.load(storage, "identity-keys")!!
        assertEquals(operator.keyId, loaded.first.keyId)
        assertEquals(revocation.keyId, loaded.second.keyId)

        // The ids bind into the harness state document and survive its round trip.
        val (core, _) = makeHarness()
        core.operatorKeyId = operator.keyId
        core.revocationAuthorityKeyId = revocation.keyId
        val stateStorage = InMemoryStateStorage()
        StateStore.save(core, stateStorage, "harness-state")
        val restored = StateStore.load(stateStorage, "harness-state")!!
        assertEquals(operator.keyId, restored.operatorKeyId)
        assertEquals(revocation.keyId, restored.revocationAuthorityKeyId)
        // Re-serialization of the restored state is byte-identical.
        val again = InMemoryStateStorage()
        StateStore.save(restored, again, "harness-state")
        assertEquals(
            stateStorage.read("harness-state")!!.toString(Charsets.UTF_8),
            again.read("harness-state")!!.toString(Charsets.UTF_8),
        )
        // And continuity against the recorded ids passes for the same keys.
        KeyCustody.checkContinuity(restored.operatorKeyId, restored.revocationAuthorityKeyId, loaded.first, loaded.second)
    }

    @Test
    fun swappedOperatorKeyFailsContinuityAndTamperedPairFailsLoad() {
        val operator = KeyCustody.generate()
        val revocation = KeyCustody.generate()
        val swapped = KeyCustody.generate()
        val err = assertThrows(KeyCustodyException::class.java) {
            KeyCustody.checkContinuity(operator.keyId, revocation.keyId, swapped, revocation)
        }
        assertEquals(KeyCustodyError.CONTINUITY_MISMATCH, err.kind)

        // A custody document whose private half belongs to a different
        // keypair fails verification at load — it is not adopted.
        val storage = InMemoryStateStorage()
        val tampered = StoredKeyPair(
            keyId = operator.keyId,
            publicKeyB64 = operator.publicKeyB64,
            privateKeyB64 = swapped.privateKeyB64,
        )
        KeyCustody.save(storage, "identity-keys", tampered, revocation)
        val loadErr = assertThrows(KeyCustodyException::class.java) {
            KeyCustody.load(storage, "identity-keys")
        }
        assertEquals(KeyCustodyError.PAIR_MISMATCH, loadErr.kind)
    }
}
