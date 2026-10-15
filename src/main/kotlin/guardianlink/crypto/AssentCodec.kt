package guardianlink.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Canonical byte encoding of the assent triple
 * (rendering, assentedAtMs, actionId) — the exact bytes the human's
 * signature must cover, per the [guardianlink.gates.Verifiers.verifyAssent]
 * binding contract.
 *
 * Every variable-length field is 4-byte big-endian length-prefixed. This
 * defeats concatenation ambiguity: without framing, rendering="ab" +
 * actionId="c" and rendering="a" + actionId="bc" would sign identical
 * bytes, letting one assent authorize a different action than the human
 * saw. With framing they can never collide.
 *
 * Layout: len(rendering) | rendering UTF-8 | assentedAtMs (8-byte BE) |
 *          len(actionId) | actionId UTF-8
 */
object AssentCodec {

    fun encode(rendering: String, assentedAtMs: Long, actionId: String): ByteArray {
        val r = rendering.toByteArray(StandardCharsets.UTF_8)
        val a = actionId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + r.size + 8 + 4 + a.size)
            .putInt(r.size).put(r)
            .putLong(assentedAtMs)
            .putInt(a.size).put(a)
            .array()
    }

    /**
     * Signs the triple. The private key belongs to the human operator's
     * assent ceremony — whoever holds it can assent, so protect it like
     * the human's hand.
     */
    fun sign(
        privateKey: PrivateKey,
        rendering: String,
        assentedAtMs: Long,
        actionId: String,
    ): ByteArray = Ed25519.sign(privateKey, encode(rendering, assentedAtMs, actionId))

    /**
     * Verifies the triple binding. A signature over the rendering alone —
     * or over any differently-framed triple — does NOT verify here. Never
     * throws.
     */
    fun verify(
        publicKey: PublicKey,
        rendering: String,
        assentedAtMs: Long,
        actionId: String,
        signature: ByteArray,
    ): Boolean = Ed25519.verify(publicKey, encode(rendering, assentedAtMs, actionId), signature)

    /**
     * Canonical byte encoding of a revocation claim (actionId,
     * revokedAtMs), signed by the revocation authority. Same framing
     * discipline as the assent triple.
     */
    fun encodeRevocation(actionId: String, revokedAtMs: Long): ByteArray {
        val a = actionId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + a.size + 8)
            .putInt(a.size).put(a)
            .putLong(revokedAtMs)
            .array()
    }
}
