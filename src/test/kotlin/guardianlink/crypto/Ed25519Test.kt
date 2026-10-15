package guardianlink.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ed25519 adapter and assent-triple binding contract.
 * Every failure case must return false, never throw.
 */
class Ed25519Test {

    @Test
    fun `sign and verify roundtrip`() {
        val kp = Ed25519.generateKeyPair()
        val msg = "hello guardianlink".toByteArray()
        val sig = Ed25519.sign(kp.private, msg)
        assertTrue(Ed25519.verify(kp.public, msg, sig))
    }

    @Test
    fun `tampered message fails verification`() {
        val kp = Ed25519.generateKeyPair()
        val sig = Ed25519.sign(kp.private, "original".toByteArray())
        assertFalse(Ed25519.verify(kp.public, "original!".toByteArray(), sig))
    }

    @Test
    fun `wrong key fails verification`() {
        val a = Ed25519.generateKeyPair()
        val b = Ed25519.generateKeyPair()
        val sig = Ed25519.sign(a.private, "msg".toByteArray())
        assertFalse(Ed25519.verify(b.public, "msg".toByteArray(), sig))
    }

    @Test
    fun `degenerate inputs fail closed without throwing`() {
        val kp = Ed25519.generateKeyPair()
        val sig = Ed25519.sign(kp.private, "msg".toByteArray())
        assertFalse(Ed25519.verify(kp.public, "msg".toByteArray(), ByteArray(0)))
        assertFalse(Ed25519.verify(kp.public, "msg".toByteArray(), ByteArray(64) { 0 }))
        // Flipped-bit signature must not verify.
        val bad = sig.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(Ed25519.verify(kp.public, "msg".toByteArray(), bad))
    }

    @Test
    fun `key encode decode roundtrip, malformed bytes decode to null`() {
        val kp = Ed25519.generateKeyPair()
        val pubBytes = Ed25519.encodePublicKey(kp.public)
        val decoded = Ed25519.decodePublicKey(pubBytes)
        assertNotNull(decoded)
        assertTrue(Ed25519.verify(decoded, "m".toByteArray(), Ed25519.sign(kp.private, "m".toByteArray())))
        assertNull(Ed25519.decodePublicKey(ByteArray(10) { 1 }))
        assertNull(Ed25519.decodePublicKey(ByteArray(0)))
    }

    @Test
    fun `assent triple signs and verifies`() {
        val kp = Ed25519.generateKeyPair()
        val sig = AssentCodec.sign(kp.private, "WRITE profile {name=Neo}", 1_700_000_000_000L, "act-1")
        assertTrue(AssentCodec.verify(kp.public, "WRITE profile {name=Neo}", 1_700_000_000_000L, "act-1", sig))
    }

    @Test
    fun `assent binding rejects wrong actionId, time, or rendering`() {
        val kp = Ed25519.generateKeyPair()
        val sig = AssentCodec.sign(kp.private, "WRITE profile {name=Neo}", 1_700_000_000_000L, "act-1")
        // Backdating: same rendering, older timestamp.
        assertFalse(AssentCodec.verify(kp.public, "WRITE profile {name=Neo}", 1_699_999_000_000L, "act-1", sig))
        // Replay onto a different action.
        assertFalse(AssentCodec.verify(kp.public, "WRITE profile {name=Neo}", 1_700_000_000_000L, "act-2", sig))
        // Tampered rendering.
        assertFalse(AssentCodec.verify(kp.public, "WRITE profile {name=Attacker}", 1_700_000_000_000L, "act-1", sig))
    }

    @Test
    fun `signature over rendering alone does not verify as assent`() {
        // The binding contract's core guarantee: a verifier that only
        // checks the rendering honors the letter of the port and defeats
        // its purpose. The codec makes that confusion impossible.
        val kp = Ed25519.generateKeyPair()
        val renderingOnly = Ed25519.sign(kp.private, "WRITE profile {name=Neo}".toByteArray())
        assertFalse(AssentCodec.verify(kp.public, "WRITE profile {name=Neo}", 1_700_000_000_000L, "act-1", renderingOnly))
    }

    @Test
    fun `length framing defeats concatenation ambiguity`() {
        // Without framing, ("ab","c") and ("a","bc") sign identical bytes.
        val e1 = AssentCodec.encode("ab", 100L, "c")
        val e2 = AssentCodec.encode("a", 100L, "bc")
        assertFalse(e1.contentEquals(e2))
        // And a signature for one never verifies for the other.
        val kp = Ed25519.generateKeyPair()
        val sig = AssentCodec.sign(kp.private, "ab", 100L, "c")
        assertFalse(AssentCodec.verify(kp.public, "a", 100L, "bc", sig))
    }

    @Test
    fun `revocation encoding is unambiguous and distinct from assent encoding`() {
        val kp = Ed25519.generateKeyPair()
        val r1 = AssentCodec.encodeRevocation("act-1", 200L)
        val r2 = AssentCodec.encodeRevocation("act-1", 201L)
        assertFalse(r1.contentEquals(r2))
        // A revocation signature is not an assent signature and vice versa.
        val revSig = Ed25519.sign(kp.private, r1)
        assertFalse(AssentCodec.verify(kp.public, "act-1", 200L, "act-1", revSig))
        assertEquals(4 + 5 + 8, r1.size) // len + "act-1" + long
    }
}
