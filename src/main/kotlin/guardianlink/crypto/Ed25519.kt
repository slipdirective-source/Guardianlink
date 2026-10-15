package guardianlink.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

/**
 * Deployment-grade Ed25519 signatures (RFC 8032) via the JDK's built-in
 * EdDSA provider — no third-party dependency.
 *
 * This is the real adapter behind the [guardianlink.gates.Verifiers]
 * signature ports. The demo's permissive verifiers (`= true`) must never
 * reach production; wire this instead.
 *
 * Fail-closed contract: [verify] returns false on ANY failure (bad
 * signature, wrong key, malformed input) and never throws — matching the
 * engine's requirement that verification may fail but never throw.
 */
object Ed25519 {

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(message)
        return sig.sign()
    }

    /**
     * Returns true iff [signature] is a valid Ed25519 signature of
     * [message] under [publicKey]. Never throws.
     */
    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean {
        // Reject degenerate inputs before touching the provider: an
        // empty signature must not reach verification.
        if (signature.isEmpty()) return false
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** X.509 DER encoding of a public key — the distributable form. */
    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** PKCS#8 DER encoding of a private key — protect at rest. */
    fun encodePrivateKey(privateKey: PrivateKey): ByteArray = privateKey.encoded

    /** Never throws: malformed bytes yield null, not an exception. */
    fun decodePublicKey(bytes: ByteArray): PublicKey? =
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
        } catch (_: Exception) {
            null
        }

    fun decodePrivateKey(bytes: ByteArray): PrivateKey? =
        try {
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) {
            null
        }
}
