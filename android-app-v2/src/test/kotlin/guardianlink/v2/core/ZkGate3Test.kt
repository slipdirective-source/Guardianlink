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
import guardianlink.v2.app.ZkVerifierWiring
import guardianlink.v2.core.zk.FakeZkBridge
import guardianlink.v2.core.zk.PublicInputsCodec
import guardianlink.v2.core.zk.PublicInputsException
import guardianlink.v2.core.zk.PublicParam
import guardianlink.v2.core.zk.UnavailableZkBridge
import guardianlink.v2.core.zk.ZkBridgeException
import guardianlink.v2.core.zk.ZkCommitResult
import guardianlink.v2.core.zk.ZkParamDescriptor
import guardianlink.v2.core.zk.ZkProofBundle
import guardianlink.v2.core.zk.ZkProveRequest
import guardianlink.v2.core.zk.ZkProverBridge
import guardianlink.v2.core.zk.ZkReason
import guardianlink.v2.core.zk.ZkVerifierBridge
import guardianlink.voice.AudioSample
import guardianlink.voice.BiometricVerifier
import guardianlink.voice.SpeakerEmbedding
import guardianlink.voice.VerificationResult
import guardianlink.voice.VoiceTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate 3 (ZK-GATE3-DESIGN) at the Kotlin layer: the GLZK1 codec, the
 * §4.3 Gate 5 cross-binding, the §5 ceremony revision (statement
 * assembly at begin, prove-at-submit, bridge-unavailable refusal),
 * and the production verifier wiring's totality. Proving itself runs
 * through [FakeZkBridge] — a test fake behind the production seam;
 * proof SOUNDNESS is the Rust crate's attack corpus, not this file's
 * claim (see FakeZkBridge's doc).
 */
class ZkGate3Test {

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

    /** A bridge that can be taken offline mid-test: commits/proofs while online, unavailable after. */
    private class SwitchableBridge : ZkProverBridge, ZkVerifierBridge {
        @Volatile var online = true
        override fun isAvailable(): Boolean = online
        override fun commitCeiling(ceilingQ: Long): ZkCommitResult =
            if (online) FakeZkBridge.commitCeiling(ceilingQ) else throw ZkBridgeException(UnavailableZkBridge.REASON)
        override fun prove(request: ZkProveRequest): ZkProofBundle =
            if (online) FakeZkBridge.prove(request) else throw ZkBridgeException(UnavailableZkBridge.REASON)
        override fun verify(proof: ByteArray, publicInputs: ByteArray, nowMs: Long): Int =
            if (online) FakeZkBridge.verify(proof, publicInputs, nowMs) else ZkReason.INTERNAL
    }

    private class Rig {
        val bridge = SwitchableBridge()
        val coreClock = TestClock("2026-10-23T14:00:00.000Z")
        val core = GuardianLink(now = coreClock, zkBridge = bridge)
        val tau = FakeClock(1_000_000L)
        val operator: StoredKeyPair = KeyCustody.generate()
        val revocation: StoredKeyPair = KeyCustody.generate()
        val ports = CorePolicyPorts { core }
        val wiring = ZkVerifierWiring(bridge) { tau.nowMs() }
        val gates: NineGates
        val concierge: ConciergeInterface
        val ceremony: AssentCeremony
        val audio = AudioSample(doubleArrayOf(0.25, 0.5, 0.25))

        init {
            val feed = object : RevocationFeed {
                override fun revocationsFor(actionId: String): List<Revocation> = emptyList()
            }
            val verifiers = ProductionVerifiers(
                operatorKey = operator.toKeyPair().public,
                revocationAuthorityKey = revocation.toKeyPair().public,
                revocationFeed = feed,
                zkVerifier = wiring.asLambda(),
                boundaryCheck = ports::boundaryCheck,
                policyCheck = ports::policyCheck,
                divergenceFn = ports::divergence,
                loopCheck = ports::loopCheck,
            )
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

        fun railCoveredPause(level: Double = 3.0): ActionProposal {
            core.actions.addRail("pause_collector", "location", mapOf("level" to 5.0), "sovereign")
            val p = core.actions.propose(
                ProposalInput(
                    actionType = "pause_collector",
                    target = "location",
                    parameters = mapOf("level" to level),
                    expectedEffect = "The location collector pauses.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL_LOW,
                    requiredAuthority = RequiredAuthority.RAIL,
                    requestedBy = "sovereign",
                ),
            )
            assertEquals(PolicyOutcome.EXECUTABLE_UNDER_RAIL, p.policy)
            return p
        }

        fun request(p: ActionProposal): CeremonyRequest {
            val v = DoubleArray(Intent.entries.size)
            v[Intent.ACT.ordinal] = 1.0
            return CeremonyRequest(
                proposalId = p.id,
                requestText = "Stop collecting location",
                parseTrees = 1,
                intentVector = v,
                currentVector = v.copyOf(),
                measurements = CeremonyMeasurements(signalSnr = 20.0, contextEntropyBits = 1.0),
            )
        }

        fun begin(p: ActionProposal): PreparedCeremony =
            when (val b = ceremony.begin(request(p))) {
                is CeremonyBegin.Refused -> throw AssertionError("ceremony refused to start: ${b.reason}")
                is CeremonyBegin.Prepared -> b.ceremony
            }

        /** A coherent hand-built context (vacuity block) for gate-level checks. */
        fun directContext(action: Action, actionId: String = "direct-zk"): GateContext {
            val rendering = render(action)
            val message = "direct".toByteArray(Charsets.UTF_8)
            val issued = tau.nowMs()
            val block = PublicInputsCodec.encode(
                actionId,
                PublicInputsCodec.sha256(rendering.toByteArray(Charsets.UTF_8)),
                issued,
                emptyList(),
            )
            return GateContext(
                signal = Signal("direct".toByteArray(Charsets.UTF_8), snr = 20.0, wellFormed = true),
                keys = KeyMaterial(message, Ed25519.sign(operator.toKeyPair().private, message)),
                proofs = Proofs(PublicInputsCodec.VACUITY_PROOF, block, issuedAtMs = issued),
                contextEntropyBits = 1.0,
                contextParseTrees = 1,
                action = action,
                actionId = actionId,
                substrate = concierge.currentSubstrate(),
                rendering = rendering,
                assent = Assent(AssentCodec.sign(operator.toKeyPair().private, rendering, tau.nowMs() - 20_000L, actionId)),
                assentedAtMs = tau.nowMs() - 20_000L,
                assentAudio = audio,
                intentVector = doubleArrayOf(1.0, 0.0),
                currentVector = doubleArrayOf(1.0, 0.0),
            )
        }
    }

    /* ---------------- codec ---------------- */

    @Test
    fun publicInputsRoundTripPreservesAllFields() {
        val v1 = ByteArray(32) { (it * 3 + 1).toByte() }
        val c1 = ByteArray(32) { (it * 5 + 2).toByte() }
        val v2 = ByteArray(32) { (it * 7 + 3).toByte() }
        val c2 = ByteArray(32) { (it * 11 + 4).toByte() }
        val hash = PublicInputsCodec.sha256("render me".toByteArray(Charsets.UTF_8))
        val params = listOf(
            PublicParam(ZkParamDescriptor("amount", scale = 1000, bits = 32, offset = 0L), v1, c1),
            PublicParam(ZkParamDescriptor("level", scale = 100, bits = 32, offset = -5L), v2, c2),
        )
        val bytes = PublicInputsCodec.encode("a-123", hash, 1_700_000_000_000L, params)
        val parsed = PublicInputsCodec.decode(bytes)
        assertEquals("a-123", parsed.actionId)
        assertTrue(parsed.renderingHash.contentEquals(hash))
        assertEquals(1_700_000_000_000L, parsed.issuedAtMs)
        assertEquals(2, parsed.params.size)
        assertEquals(ZkParamDescriptor("amount", 1000, 32, 0L), parsed.params[0].descriptor)
        assertEquals(ZkParamDescriptor("level", 100, 32, -5L), parsed.params[1].descriptor)
        assertTrue(parsed.params[0].valueCommitment.contentEquals(v1))
        assertTrue(parsed.params[0].envelopeCommitment.contentEquals(c1))
        assertTrue(parsed.params[1].valueCommitment.contentEquals(v2))
        assertTrue(parsed.params[1].envelopeCommitment.contentEquals(c2))
        // Re-encoding the parsed block reproduces the canonical bytes.
        val again = PublicInputsCodec.encode(parsed.actionId, parsed.renderingHash, parsed.issuedAtMs, parsed.params)
        assertTrue(bytes.contentEquals(again))
    }

    @Test
    fun publicInputsDecodeRejectsMalformedBlocks() {
        val hash = PublicInputsCodec.sha256("r".toByteArray(Charsets.UTF_8))
        val good = PublicInputsCodec.encode("a-1", hash, 42L, emptyList())
        assertThrows(PublicInputsException::class.java) { PublicInputsCodec.decode(byteArrayOf()) }
        assertThrows(PublicInputsException::class.java) { PublicInputsCodec.decode("garbage".toByteArray(Charsets.UTF_8)) }
        val wrongTag = good.copyOf()
        wrongTag[4] = '2'.code.toByte()
        assertThrows(PublicInputsException::class.java) { PublicInputsCodec.decode(wrongTag) }
        assertThrows(PublicInputsException::class.java) { PublicInputsCodec.decode(good.copyOfRange(0, good.size - 3)) }
        assertThrows(PublicInputsException::class.java) { PublicInputsCodec.decode(good + byteArrayOf(0)) }
    }

    /* ---------------- Gate 5 cross-binding (section 4.3) ---------------- */

    @Test
    fun gate5CrossCheckAcceptsCoherentBlock() {
        val rig = Rig()
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        assertTrue(rig.ports.loopCheck(rig.directContext(action)), "a coherent bundle passes the loop port")
    }

    @Test
    fun gate5CrossCheckRejectsMismatchedActionId() {
        val rig = Rig()
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        val ctx = rig.directContext(action, actionId = "minted-for-A")
        // The same bundle presented under a different actionId: the
        // block still names A (GateContext is a data class — copy
        // rebinds the outer field only, exactly the replay shape).
        val replayed = ctx.copy(actionId = "presented-as-B")
        assertFalse(rig.ports.loopCheck(replayed), "a proof minted for action A must not cohere on action B")
    }

    @Test
    fun gate5CrossCheckRejectsMismatchedRendering() {
        val rig = Rig()
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        val ctx = rig.directContext(action)
        val otherAction = Action.Write("collector/location", mapOf("status" to "paused", "statusReason" to "x"))
        // Block bound to the first rendering, bundle claiming another.
        val replayed = ctx.copy(action = otherAction, rendering = render(otherAction))
        assertFalse(rig.ports.loopCheck(replayed), "a block bound to a different rendering must not cohere")
    }

    @Test
    fun gate5CrossCheckRejectsMismatchedIssuedAt() {
        val rig = Rig()
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        val ctx = rig.directContext(action)
        // Outer timestamp freshened, inner (bound) timestamp stale:
        // the two disagree and coherence refuses (design section 4.4).
        val replayed = ctx.copy(proofs = ctx.proofs.copy(issuedAtMs = ctx.proofs.issuedAtMs + 5_000L))
        assertFalse(rig.ports.loopCheck(replayed), "inner/outer issuedAtMs mismatch must not cohere")
    }

    /* ---------------- ceremony (section 5) ---------------- */

    @Test
    fun ceremonyIntegratesRailCoveredActionWithBridgeProof() {
        val rig = Rig()
        val p = rig.railCoveredPause(level = 3.0)
        val prepared = rig.begin(p)
        assertNotNull(prepared.zkPlan, "a rail-covered numeric action assembles a Gate 3 statement at begin")
        rig.ceremony.confirm(prepared)
        rig.tau.advance(20_000L)
        val outcome = rig.ceremony.submit(prepared, rig.audio)
        val submitted = outcome as CeremonyOutcome.Submitted
        val executed = submitted.result as ConciergeInterface.ExecutionResult.Executed
        assertEquals("paused", executed.newSubstrate.records["collector/location"]?.get("status"))
        assertEquals("paused", rig.core.collectors.get("location")!!.status.wire)
    }

    @Test
    fun ceremonyProvesAtSubmitSoCoolingCannotStaleTheProof() {
        // Section 8 case 16 (regression for the OLD confirm-time
        // stamping): assent at T, submit at T+70s. A proof stamped at
        // confirm would be 70s old — outside the 60s freshness
        // window — and Gate 3 would reject it. Prove-at-submit stamps
        // at submit, so the same ceremony passes Gate 3 and seals.
        val rig = Rig()
        val p = rig.railCoveredPause(level = 2.0)
        val prepared = rig.begin(p)
        rig.ceremony.confirm(prepared)
        rig.tau.advance(70_000L)
        val outcome = rig.ceremony.submit(prepared, rig.audio)
        val submitted = outcome as CeremonyOutcome.Submitted
        assertTrue(
            submitted.result is ConciergeInterface.ExecutionResult.Executed,
            "prove-at-submit keeps the proof fresh across the cooling wait: ${submitted.result}",
        )
    }

    @Test
    fun ceremonyWithZkUnavailableRefusesAtSubmitNamingGate3() {
        val rig = Rig()
        val p = rig.railCoveredPause(level = 1.0)
        val prepared = rig.begin(p)
        rig.ceremony.confirm(prepared)
        // The bridge disappears between confirm and submit (the .so
        // failed to load on this device): submit must refuse, naming
        // Gate 3 and the unavailability — never throw, never fake.
        rig.bridge.online = false
        rig.tau.advance(20_000L)
        val outcome = rig.ceremony.submit(prepared, rig.audio)
        val refused = outcome as CeremonyOutcome.Refused
        assertTrue(refused.reason.contains("Gate 3"), "the refusal names Gate 3: ${refused.reason}")
        assertTrue(refused.reason.contains("unavailable"), "the refusal names the unavailability: ${refused.reason}")
        assertTrue(refused.reason.contains("Nothing was submitted"), "nothing reached the engine: ${refused.reason}")
    }

    @Test
    fun productionVerifierWithUnavailableBridgeHaltsAtGate3() {
        // The deliverable posture end to end at the engine: the real
        // wiring (ZkVerifierWiring over a bridge that is not there)
        // returns false, and Gate 3 halts — the wrapper is total.
        val rig = Rig()
        rig.bridge.online = false
        val action = Action.Read(EngineTranslation.META_RECORD, listOf("ledgerRoot"))
        val outcome = rig.gates.evaluate(rig.directContext(action))
        val halted = outcome as GateOutcome.Halted
        assertEquals(3, halted.atGate)
        assertTrue(halted.reason.contains("theta_zk"), halted.reason)
        assertEquals(ZkReason.INTERNAL, rig.wiring.lastReasonCode)
    }

    @Test
    fun ceremonyRefusesUngovernedParameterAtBegin() {
        val rig = Rig()
        rig.core.actions.addRail("pause_collector", "location", mapOf("level" to 5.0), "sovereign")
        val p = rig.core.actions.propose(
            ProposalInput(
                actionType = "pause_collector",
                target = "location",
                parameters = mapOf("level" to 1.0, "smuggled" to 1.0),
                expectedEffect = "The location collector pauses.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL_LOW,
                requiredAuthority = RequiredAuthority.RAIL,
                requestedBy = "sovereign",
            ),
        )
        val begin = rig.ceremony.begin(rig.request(p))
        val refused = begin as CeremonyBegin.Refused
        assertTrue(refused.reason.contains("does not govern"), "the smuggled parameter is named: ${refused.reason}")
    }

    @Test
    fun ceremonyRefusesNumericParametersWithoutRailAtBegin() {
        val rig = Rig()
        val p = rig.core.actions.propose(
            ProposalInput(
                actionType = "pause_collector",
                target = "location",
                parameters = mapOf("level" to 1.0),
                expectedEffect = "The location collector pauses.",
                reversibility = Reversibility.REVERSIBLE,
                consequenceClass = ConsequenceClass.LOCAL_LOW,
                requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                requestedBy = "sovereign",
            ),
        )
        rig.core.actions.recordAssent(p.id)
        val begin = rig.ceremony.begin(rig.request(p))
        val refused = begin as CeremonyBegin.Refused
        assertTrue(refused.reason.contains("no committed rail envelope"), "the missing envelope is named: ${refused.reason}")
    }

    /* ---------------- rail commit (section 2.3 / section 8 case 13) ---------------- */

    @Test
    fun railCommitRefusesCeilingOutsideQuantizedDomain() {
        val rig = Rig()
        // 5,000,000.0 at scale 1000 quantizes to 5e9 >= 2^32: an
        // unprovable envelope is refused at COMMIT, before any proof.
        val err = assertThrows(IllegalArgumentException::class.java) {
            rig.core.actions.addRail("pause_collector", "location", mapOf("level" to 5_000_000.0), "sovereign")
        }
        assertTrue(err.message!!.contains("rail commit refused"), err.message)
        assertTrue(rig.core.actions.listRails().isEmpty(), "the refused rail was not committed")
    }

    @Test
    fun zkRailRecordSurvivesStateStoreRoundTrip() {
        val rig = Rig()
        rig.core.actions.addRail("pause_collector", "location", mapOf("level" to 5.0), "sovereign")
        val storage = InMemoryStateStorage()
        StateStore.save(rig.core, storage, "harness-state")
        val restored = StateStore.load(storage, "harness-state", zkBridge = rig.bridge)!!
        val rail = restored.actions.listRails().single()
        val zk = rail.zk
        assertNotNull(zk, "the restored rail carries its Gate 3 record")
        assertEquals(1, zk!!.params.size)
        assertEquals(5_000L, zk.params[0].ceilingQ)
        assertEquals(32, zk.params[0].envelopeCommitment.size)
        assertEquals(32, zk.params[0].envelopeBlinding.size)
        // Re-serialization of the restored state is byte-identical.
        val again = InMemoryStateStorage()
        StateStore.save(restored, again, "harness-state")
        assertEquals(
            storage.read("harness-state")!!.toString(Charsets.UTF_8),
            again.read("harness-state")!!.toString(Charsets.UTF_8),
        )
    }
}
