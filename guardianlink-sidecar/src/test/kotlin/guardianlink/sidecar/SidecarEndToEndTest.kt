package guardianlink.sidecar

import guardianlink.voice.SyntheticVoice
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests through the real HTTP layer against a running
 * sidecar — now including the voice ceremony. These prove the gates
 * still enforce across the wire:
 *
 * - valid flow executes (submit → challenge → speak → sign → assent → receipt)
 * - tampered rendering is rejected at Gate 7 (theta_render)
 * - wrong signature is rejected at Gate 7 (theta_assent)
 * - a signature replayed across actionIds is rejected at Gate 7
 * - wrong-speaker ceremony audio is rejected at Gate 7 (theta_voice)
 * - wrong-phrase ceremony audio fails liveness at Gate 7 (theta_voice)
 * - assent without a challenge → 409; double-assent → 409; unknown → 404
 *
 * Ceremony audio is synthesized with the engine's own SyntheticVoice
 * fixture speaking as the enrolled demo person — the documented demo
 * enrollment the service wires. The "person" here is simulated by
 * backdating assentedAtMs past the cooling window — the HMAC binds the
 * exact triple, so this is a faithful simulation of a signing ceremony
 * that happened seconds ago, not a bypass: every gate predicate still
 * evaluates for real.
 */
class SidecarEndToEndTest {

    private val testSecret: ByteArray = "test-secret-0123456789abcdef".toByteArray(Charsets.UTF_8)

    /** Must match the enrolled demo speaker in GuardianLinkEngine.buildVoiceAnchor. */
    private val demoSpeaker = GuardianLinkEngine.demoSpeaker
    private val impostor = SyntheticVoice.Speaker(f0Hz = 165.0, formantScale = 1.18, seed = 99L)

    private lateinit var server: SidecarServer
    private lateinit var engine: GuardianLinkEngine
    private lateinit var base: String
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun startServer() {
        val dataDir = Files.createTempDirectory("sidecar-e2e")
        engine = GuardianLinkEngine(dataDir, testSecret)
        server = SidecarServer(engine, 0)
        server.start()
        base = "http://localhost:${server.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    // ---- HTTP helpers ----

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to JSONObject(res.body())
    }

    private fun get(path: String): Pair<Int, JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$base$path")).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to JSONObject(res.body())
    }

    private fun submitWrite(name: String = "Neo"): JSONObject {
        val (status, body) = post("/v1/actions/submit", JSONObject().put("action", JSONObject()
            .put("type", "write")
            .put("recordId", "profile")
            .put("fields", JSONObject().put("name", name))))
        assertEquals(200, status, "submit failed: $body")
        return body
    }

    /** Faithful signing ceremony: HMAC over the exact binding triple. */
    private fun sign(actionId: String, assentedAtMs: Long, rendering: String, secret: ByteArray = testSecret): String =
        hmacSha256Hex(secret, assentMessage(actionId, assentedAtMs, rendering))

    private fun callerSig(actionId: String, secret: ByteArray = testSecret): String =
        hmacSha256Hex(secret, callerMessage(actionId.toByteArray(Charsets.UTF_8)))

    /**
     * Voice ceremony step 1: fetch the challenge, then synthesize the
     * challenged phrase as [speaker]. Returns (challengeId, base64 PCM).
     */
    private fun ceremony(actionId: String, speaker: SyntheticVoice.Speaker = demoSpeaker): Pair<String, String> {
        val (cs, cb) = get("/v1/actions/$actionId/challenge")
        assertEquals(200, cs, "challenge failed: $cb")
        assertTrue(cb.getString("phrase").isNotEmpty())
        val phraseId = cb.getString("challengeId")
        return phraseId to pcmToB64(SyntheticVoice.speak(speaker, phraseId).pcm)
    }

    /** float32-LE base64, the wire format the sidecar decodes. */
    private fun pcmToB64(pcm: DoubleArray): String {
        val buf = java.nio.ByteBuffer.allocate(pcm.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) buf.putFloat(x.toFloat())
        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** Assent as-if the person signed 11s ago (past WRITE's 10s cooling window). */
    private fun assentNow(
        submitted: JSONObject,
        audioB64: String,
        rendering: String? = null,
        assentedAtMs: Long? = null,
        signature: String? = null,
        keySignature: String? = null,
    ): Pair<Int, JSONObject> {
        val actionId = submitted.getString("actionId")
        val r = rendering ?: submitted.getString("rendering")
        val t = assentedAtMs ?: (submitted.getLong("serverTimeMs") - 11_000L)
        return post("/v1/actions/$actionId/assent", JSONObject()
            .put("rendering", r)
            .put("assentedAtMs", t)
            .put("assentSignature", signature ?: sign(actionId, t, r))
            .put("keySignature", keySignature ?: callerSig(actionId))
            .put("assentAudio", audioB64))
    }

    // ---- tests ----

    @Test
    fun validFlowExecutesEndToEnd() {
        val submitted = submitWrite()
        val (_, audioB64) = ceremony(submitted.getString("actionId"))
        val (status, body) = assentNow(submitted, audioB64)

        assertEquals(200, status, "assent failed: $body")
        assertEquals("executed", body.getString("status"))
        assertTrue(body.getString("sealRoot").isNotEmpty())
        val receipt = body.getJSONObject("anchorReceipt")
        assertEquals(body.getString("sealRoot"), receipt.getString("root"))
        assertEquals("Neo", body.getJSONObject("substrate")
            .getJSONObject("profile").getString("name"))

        // Anchor chain intact, ledger grew.
        val (vs, vb) = get("/v1/ledger/verify")
        assertEquals(200, vs)
        assertTrue(vb.getJSONObject("merkle").getBoolean("ok"))
        assertTrue(vb.getJSONObject("anchor").getBoolean("ok"))
        assertTrue(vb.getJSONObject("merkle").getInt("entries") > 0)
    }

    @Test
    fun challengeEndpointIssuesSingleUseChallenge() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")

        val (cs, cb) = get("/v1/actions/$actionId/challenge")
        assertEquals(200, cs)
        assertTrue(cb.getString("challengeId").startsWith("p"))
        assertTrue(cb.getString("phrase").startsWith("Please say:"))

        // A second ceremony while one is live is refused: the engine
        // holds exactly one active challenge.
        val (cs2, _) = get("/v1/actions/$actionId/challenge")
        assertEquals(200, cs2, "re-issue for the SAME action replaces the challenge")

        // Unknown action → 404.
        val (cs3, _) = get("/v1/actions/act_nope/challenge")
        assertEquals(404, cs3)
    }

    @Test
    fun secondActionCeremonyWhileBusyIsRejected() {
        val a = submitWrite("Neo")
        val b = submitWrite("Neo")
        val (cs, _) = get("/v1/actions/${a.getString("actionId")}/challenge")
        assertEquals(200, cs)

        val (bs, bb) = get("/v1/actions/${b.getString("actionId")}/challenge")
        assertEquals(409, bs, "concurrent ceremony must be refused: $bb")
    }

    @Test
    fun tamperedRenderingRejectedAtGate7() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")
        val (_, audioB64) = ceremony(actionId)
        val t = submitted.getLong("serverTimeMs") - 11_000L
        // Attacker holds the key but swaps what was shown: recompute the
        // MAC over the tampered rendering so verifyAssent passes and the
        // engine's own theta_render must catch it.
        val tampered = "WRITE \"profile\" {\"name\"=\"Attacker\"}"
        val (status, body) = post("/v1/actions/$actionId/assent", JSONObject()
            .put("rendering", tampered)
            .put("assentedAtMs", t)
            .put("assentSignature", sign(actionId, t, tampered))
            .put("keySignature", callerSig(actionId))
            .put("assentAudio", audioB64))

        assertEquals(200, status)
        assertEquals("rejected", body.getString("status"))
        assertEquals(7, body.getInt("atGate"))
        assertTrue(body.getString("reason").contains("theta_render"), body.getString("reason"))
    }

    @Test
    fun wrongSignatureRejectedAtGate7() {
        val submitted = submitWrite()
        val (_, audioB64) = ceremony(submitted.getString("actionId"))
        val actionId = submitted.getString("actionId")
        val t = submitted.getLong("serverTimeMs") - 11_000L
        val wrongSecret = "wrong-secret-0000000000000000".toByteArray(Charsets.UTF_8)
        val (status, body) = assentNow(
            submitted,
            audioB64,
            assentedAtMs = t,
            signature = sign(actionId, t, submitted.getString("rendering"), wrongSecret),
        )

        assertEquals(200, status)
        assertEquals("rejected", body.getString("status"))
        assertEquals(7, body.getInt("atGate"))
        assertTrue(body.getString("reason").contains("theta_assent"), body.getString("reason"))
    }

    @Test
    fun replayedSignatureAcrossActionsRejected() {
        // Action A: full valid flow (consumes A's pending record + ceremony).
        val a = submitWrite("Neo")
        val aId = a.getString("actionId")
        val aT = a.getLong("serverTimeMs") - 11_000L
        val aRendering = a.getString("rendering")
        val aSig = sign(aId, aT, aRendering)
        val (_, aAudio) = ceremony(aId)
        val (s1, b1) = assentNow(a, aAudio, assentedAtMs = aT, signature = aSig)
        assertEquals("executed", b1.getString("status"))

        // Action B: identical AST, different actionId. Replay A's triple.
        val b = submitWrite("Neo")
        val bId = b.getString("actionId")
        val (_, bAudio) = ceremony(bId)
        val (status, body) = post("/v1/actions/$bId/assent", JSONObject()
            .put("rendering", b.getString("rendering"))
            .put("assentedAtMs", aT)
            .put("assentSignature", aSig) // A's signature, bound to A's actionId
            .put("keySignature", callerSig(bId))
            .put("assentAudio", bAudio))

        assertEquals(200, status, "replay should be decided by the gates, not the transport")
        assertEquals("rejected", body.getString("status"))
        assertEquals(7, body.getInt("atGate"))
        assertTrue(body.getString("reason").contains("theta_assent"), body.getString("reason"))
        assertEquals(200, s1) // sanity: keeps the compiler honest about s1
    }

    @Test
    fun wrongSpeakerRejectedAtGate7() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")
        val (cs, cb) = get("/v1/actions/$actionId/challenge")
        assertEquals(200, cs)
        // The impostor speaks the challenged phrase: content matches, but
        // the voice doesn't.
        val audioB64 = pcmToB64(SyntheticVoice.speak(impostor, cb.getString("challengeId")).pcm)

        val (status, body) = assentNow(submitted, audioB64)
        assertEquals(200, status)
        assertEquals("rejected", body.getString("status"))
        assertEquals(7, body.getInt("atGate"))
        assertTrue(body.getString("reason").contains("theta_voice"), body.getString("reason"))
    }

    @Test
    fun wrongPhraseRejectedAtGate7() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")
        val (cs, cb) = get("/v1/actions/$actionId/challenge")
        assertEquals(200, cs)
        val challenged = cb.getString("challengeId")
        // The enrolled person speaks a DIFFERENT phrase: speaker matches,
        // liveness content check must fail.
        val other = listOf("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8")
            .first { it != challenged }
        val audioB64 = pcmToB64(SyntheticVoice.speak(demoSpeaker, other).pcm)

        val (status, body) = assentNow(submitted, audioB64)
        assertEquals(200, status)
        assertEquals("rejected", body.getString("status"))
        assertEquals(7, body.getInt("atGate"))
        assertTrue(body.getString("reason").contains("theta_voice"), body.getString("reason"))
    }

    @Test
    fun assentWithoutChallengeFailsClosed() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")
        val t = submitted.getLong("serverTimeMs") - 11_000L
        // Valid signature, valid audio shape — but no ceremony was issued.
        val audioB64 = pcmToB64(SyntheticVoice.speak(demoSpeaker, "p1").pcm)
        val (status, body) = post("/v1/actions/$actionId/assent", JSONObject()
            .put("rendering", submitted.getString("rendering"))
            .put("assentedAtMs", t)
            .put("assentSignature", sign(actionId, t, submitted.getString("rendering")))
            .put("keySignature", callerSig(actionId))
            .put("assentAudio", audioB64))

        assertEquals(409, status, "assent without a ceremony must fail closed: $body")
    }

    @Test
    fun missingAudioIsBadRequest() {
        val submitted = submitWrite()
        val actionId = submitted.getString("actionId")
        ceremony(actionId)
        val t = submitted.getLong("serverTimeMs") - 11_000L
        val (status, _) = post("/v1/actions/$actionId/assent", JSONObject()
            .put("rendering", submitted.getString("rendering"))
            .put("assentedAtMs", t)
            .put("assentSignature", sign(actionId, t, submitted.getString("rendering")))
            .put("keySignature", callerSig(actionId)))
        assertEquals(400, status)
    }

    @Test
    fun doubleAssentFailsClosed() {
        val submitted = submitWrite()
        val (_, audioB64) = ceremony(submitted.getString("actionId"))
        val (s1, b1) = assentNow(submitted, audioB64)
        assertEquals("executed", b1.getString("status"))

        // Second assent: the pending action and the ceremony are both
        // consumed — no double execution. The ceremony check fires first.
        val (s2, b2) = assentNow(submitted, audioB64)
        assertEquals(409, s2, "second assent must fail closed: $b2")
        assertEquals(200, s1)
    }

    @Test
    fun unknownActionFailsClosed() {
        val (status, body) = post("/v1/actions/act_nonexistent/assent", JSONObject()
            .put("rendering", "x")
            .put("assentedAtMs", 0L)
            .put("assentSignature", "00")
            .put("keySignature", "00")
            .put("assentAudio", pcmToB64(doubleArrayOf(0.01, -0.01))))
        // No ceremony exists for this id either — 409 either way is fail-closed.
        assertTrue(status == 404 || status == 409, "unexpected: $status $body")
    }

    @Test
    fun malformedActionIsBadRequest() {
        val (status, body) = post("/v1/actions/submit", JSONObject()
            .put("action", JSONObject().put("type", "drop-database")))
        assertEquals(400, status)
        assertTrue(body.getString("error").contains("unknown action type"))
    }

    @Test
    fun substrateReadIsConsistent() {
        val (s0, b0) = get("/v1/substrate")
        assertEquals(200, s0)
        assertEquals("Caleb", b0.getJSONObject("records").getJSONObject("profile").getString("name"))

        val submitted = submitWrite("Neo")
        val (_, audioB64) = ceremony(submitted.getString("actionId"))
        assentNow(submitted, audioB64)

        val (s1, b1) = get("/v1/substrate")
        assertEquals(200, s1)
        assertEquals("Neo", b1.getJSONObject("records").getJSONObject("profile").getString("name"))
    }
}
