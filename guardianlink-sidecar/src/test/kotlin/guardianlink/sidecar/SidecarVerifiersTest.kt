package guardianlink.sidecar

import guardianlink.gates.Action
import guardianlink.gates.Revocation
import guardianlink.voice.SyntheticVoice
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security pass over the sidecar's verifier posture:
 *
 * - Assent-key separation (review #2): with GUARDIANLINK_ASSENT_SECRET
 *   set, assent signed with the caller key FAILS and assent signed with
 *   the assent secret PASSES; caller authentication keeps using the
 *   shared key. Without the separate secret, the legacy shared-key
 *   fallback verifies assent as before.
 * - Halt-by-default (review #1, #3, #5): outside demo mode verifyZk is
 *   false, an unwired revocation feed throws (→ engine halts at
 *   Gate 8), and an engine assent with no demo evidence halts at
 *   Gate 0. Demo mode is the explicit opt-in to the old posture.
 */
class SidecarVerifiersTest {

    private val callerKey = "caller-key-0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val assentKey = "assent-key-0123456789abcdef".toByteArray(Charsets.UTF_8)

    private fun signAssent(secret: ByteArray, actionId: String, atMs: Long, rendering: String): ByteArray =
        hmacSha256Hex(secret, assentMessage(actionId, atMs, rendering)).hexToBytes()!!

    private fun signCaller(secret: ByteArray, message: ByteArray): ByteArray =
        hmacSha256Hex(secret, callerMessage(message)).hexToBytes()!!

    // ---- #2: assent-key separation ----

    @Test
    fun assentSecretSet_callerKeyCannotSignAssent() {
        val v = SidecarVerifiers(callerKey, assentSecret = assentKey)
        val sig = signAssent(callerKey, "act_1", 1_000L, "rendering")
        assertFalse(v.verifyAssent("rendering", 1_000L, "act_1", sig))
    }

    @Test
    fun assentSecretSet_assentKeySignsAssent() {
        val v = SidecarVerifiers(callerKey, assentSecret = assentKey)
        val sig = signAssent(assentKey, "act_1", 1_000L, "rendering")
        assertTrue(v.verifyAssent("rendering", 1_000L, "act_1", sig))
    }

    @Test
    fun assentSecretSet_callerAuthStillUsesSharedKey() {
        val v = SidecarVerifiers(callerKey, assentSecret = assentKey)
        val msg = "act_1".toByteArray(Charsets.UTF_8)
        assertTrue(v.verifySignature(msg, signCaller(callerKey, msg)))
        assertFalse(v.verifySignature(msg, signCaller(assentKey, msg)))
    }

    @Test
    fun assentSecretUnset_sharedKeyFallbackVerifiesAssent() {
        val v = SidecarVerifiers(callerKey)
        val sig = signAssent(callerKey, "act_1", 1_000L, "rendering")
        assertTrue(v.verifyAssent("rendering", 1_000L, "act_1", sig))
    }

    // ---- #1/#3: halt-by-default verifiers ----

    @Test
    fun verifyZk_haltsOutsideDemo_passesInDemo() {
        assertFalse(SidecarVerifiers(callerKey).verifyZk(byteArrayOf(1), byteArrayOf(2)))
        assertTrue(SidecarVerifiers(callerKey, demoMode = true).verifyZk(byteArrayOf(1), byteArrayOf(2)))
    }

    @Test
    fun revocationsFor_noFeedOutsideDemo_throwsFailClosed() {
        assertFailsWith<IllegalStateException> {
            SidecarVerifiers(callerKey).revocationsFor("act_1")
        }
    }

    @Test
    fun revocationsFor_noFeedInDemo_empty() {
        assertTrue(SidecarVerifiers(callerKey, demoMode = true).revocationsFor("act_1").isEmpty())
    }

    @Test
    fun revocationsFor_wiredFeed_isUsed() {
        val rev = Revocation("act_1", byteArrayOf(9), 5L)
        val v = SidecarVerifiers(callerKey, SidecarPolicy(revocationFeed = { listOf(rev) }))
        assertEquals(listOf(rev), v.revocationsFor("act_1"))
    }

    // ---- engine-level: the split and the halt, through the real gates ----

    /** Full ceremony against a directly-constructed engine, signing assent with [signSecret]. */
    private fun attempt(engine: GuardianLinkEngine, signSecret: ByteArray): GuardianLinkEngine.AssentResult {
        val submitted = engine.submit(Action.Write("profile", mapOf("name" to "Neo")))
        val issued = engine.issueChallenge(submitted.actionId)
        assertTrue(issued is GuardianLinkEngine.ChallengeResult.Issued, "challenge refused: $issued")
        val audio = SyntheticVoice.speak(GuardianLinkEngine.demoSpeaker, issued.challengeId)
        val t = submitted.serverTimeMs - 11_000L // past WRITE's 10s cooling window, engine clock domain
        val assentSig = hmacSha256Hex(signSecret, assentMessage(submitted.actionId, t, submitted.rendering))
        val keyMessage = submitted.actionId.toByteArray(Charsets.UTF_8)
        val keySig = hmacSha256Hex(callerKey, callerMessage(keyMessage))
        return engine.assent(submitted.actionId, submitted.rendering, t, assentSig, keyMessage, keySig, audio)
    }

    @Test
    fun engineDemo_assentSignedWithCallerKeyRejectedAtGate7() {
        val dir = Files.createTempDirectory("sidecar-split")
        val engine = GuardianLinkEngine(dir, callerKey, assentSecret = assentKey, demoMode = true)
        val r = attempt(engine, callerKey)
        assertTrue(r is GuardianLinkEngine.AssentResult.Rejected, "expected rejection, got $r")
        assertEquals(7, r.atGate)
    }

    @Test
    fun engineDemo_assentSignedWithAssentKeyExecutes() {
        val dir = Files.createTempDirectory("sidecar-split")
        val engine = GuardianLinkEngine(dir, callerKey, assentSecret = assentKey, demoMode = true)
        val r = attempt(engine, assentKey)
        assertTrue(r is GuardianLinkEngine.AssentResult.Executed, "expected execution, got $r")
    }

    @Test
    fun engineNonDemo_assentHaltsAtGate0() {
        val dir = Files.createTempDirectory("sidecar-nondemo")
        val engine = GuardianLinkEngine(dir, callerKey) // demo OFF, shared-key fallback
        val r = attempt(engine, callerKey)
        assertTrue(r is GuardianLinkEngine.AssentResult.Rejected, "expected rejection, got $r")
        assertEquals(0, r.atGate)
    }
}
