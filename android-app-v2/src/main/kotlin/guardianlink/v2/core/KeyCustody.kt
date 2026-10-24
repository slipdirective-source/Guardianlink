package guardianlink.v2.core

import guardianlink.crypto.Ed25519
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64

/** Named failure kinds for key-custody loads. A custody failure is one of these — never an unnamed surprise. */
enum class KeyCustodyError {
    /** Nothing is stored under the custody name. Distinct from corruption: this is first run. */
    ABSENT,

    /** The stored bytes are not well-formed JSON, or end mid-document. */
    PARSE_ERROR,

    /** The document parsed but is not a format/version this build can load. */
    UNSUPPORTED_FORMAT,

    /** A stored key id does not equal the id recomputed from the stored public key. */
    KEY_ID_MISMATCH,

    /** The stored private key does not correspond to the stored public key (probe sign/verify failed). */
    PAIR_MISMATCH,

    /** Loaded keys do not match the key ids the harness state was recorded under. */
    CONTINUITY_MISMATCH,

    /** The storage layer itself failed to deliver bytes. */
    STORAGE_READ_FAILED,
}

/** Fail-closed key-custody error: [kind] names exactly what was wrong. */
class KeyCustodyException(val kind: KeyCustodyError, message: String) : Exception(message)

/**
 * One custodied Ed25519 keypair, in portable encoded form.
 *
 * [keyId] is the SHA-256 hex of the X.509 DER public-key encoding — a
 * fingerprint anyone can recompute from the public half alone. The
 * private half is the PKCS#8 DER encoding, Base64'd for the JSON
 * document. WHO holds the document is the custody decision: in the app
 * this document lives inside [guardianlink.v2.app.EncryptedStateStorage],
 * i.e. sealed by the Android Keystore master key (see the custody
 * design note in KeyCustody's doc and the module README).
 */
data class StoredKeyPair(
    val keyId: String,
    val publicKeyB64: String,
    val privateKeyB64: String,
) {
    fun toKeyPair(): KeyPair {
        val pub = Ed25519.decodePublicKey(Base64.getDecoder().decode(publicKeyB64))
            ?: throw KeyCustodyException(KeyCustodyError.PAIR_MISMATCH, "stored public key does not decode")
        val priv = Ed25519.decodePrivateKey(Base64.getDecoder().decode(privateKeyB64))
            ?: throw KeyCustodyException(KeyCustodyError.PAIR_MISMATCH, "stored private key does not decode")
        return KeyPair(pub, priv)
    }
}

/**
 * Persistent identity-key custody for the governed engine (EVIDENCE-PATH
 * §"Key custody requirements"; build step 1).
 *
 * Two SEPARATE keypairs, two separate jobs:
 *  - OPERATOR: signs Gate 1 origin messages and Gate 7 assent triples.
 *    The sovereign's hand.
 *  - REVOCATION AUTHORITY: signs revocation-list entries (Gate 8).
 *    Doctrine: the hand that assents must not be the hand that revokes,
 *    so this is a different key with its own identity, generated and
 *    stored under the same document but never used for assent.
 *
 * WHY THE PRIVATE KEYS ARE NOT ANDROID-KEYSTORE-RESIDENT: Android
 * Keystore on API 34 cannot hold an Ed25519 keypair usable through the
 * JCA `Signature.getInstance("Ed25519")` path the engine's
 * [guardianlink.crypto.Ed25519] relies on (Keystore Ed25519 support is
 * not exposed for general signing on this API level). So the pairs are
 * generated in software and their encoded private keys are stored
 * SEALED inside the app's encrypted state storage — the
 * MasterKey/EncryptedFile pattern already established for the harness
 * state and the scaffold's voice template. The sealing key never leaves
 * the Keystore; the Ed25519 private keys never exist on disk
 * unsealed. That is the exact custody statement; anything stronger
 * would be a claim this platform cannot back.
 *
 * LOSS POSTURE: if the sealed document is unreadable (Keystore key
 * lost, ciphertext corrupt), custody is UNAVAILABLE and governed engine
 * execution is unavailable with it — surfaced honestly by the app.
 * This object NEVER regenerates keys to paper over a load failure:
 * regeneration would orphan every signature the lost keys ever made
 * and silently re-identify the sovereign. First-run creation happens
 * only when the store is verifiably ABSENT (read returns null).
 *
 * CONTINUITY: the public key ids are also recorded in the harness
 * state document (StateStore "identity" section). On startup the app
 * checks the loaded custody against those ids; a mismatch is a
 * [KeyCustodyError.CONTINUITY_MISMATCH] — the keys were swapped or the
 * state belongs to different keys — and execution stays closed rather
 * than adopting the new keys silently.
 *
 * Pure Kotlin (java.security only): JVM-testable like the rest of the
 * core. Storage is the core's [StateStorage] interface, so tests use
 * the in-memory implementation and the app uses the encrypted one.
 */
object KeyCustody {

    const val FORMAT = "gl-keys-1"
    const val VERSION = 1
    const val ROLE_OPERATOR = "operator"
    const val ROLE_REVOCATION = "revocation-authority"

    private const val PROBE = "guardianlink-key-continuity-probe"

    /** The key id of a public key: SHA-256 hex over its X.509 DER encoding. */
    fun keyIdFor(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Generate a fresh software Ed25519 keypair in stored form. */
    fun generate(): StoredKeyPair {
        val kp = Ed25519.generateKeyPair()
        val pubDer = Ed25519.encodePublicKey(kp.public)
        return StoredKeyPair(
            keyId = keyIdFor(pubDer),
            publicKeyB64 = Base64.getEncoder().encodeToString(pubDer),
            privateKeyB64 = Base64.getEncoder().encodeToString(Ed25519.encodePrivateKey(kp.private)),
        )
    }

    /** Serialize both pairs to the canonical custody document. */
    fun serialize(operator: StoredKeyPair, revocation: StoredKeyPair): String {
        val doc = mapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "operator" to pairToMap(operator),
            "revocation" to pairToMap(revocation),
        )
        return Canonical.stringify(doc)
    }

    /** Persist both pairs under [name] in [storage] (one atomic write). */
    fun save(storage: StateStorage, name: String, operator: StoredKeyPair, revocation: StoredKeyPair) {
        storage.write(name, serialize(operator, revocation).toByteArray(Charsets.UTF_8))
    }

    /**
     * Load and fully verify the custody document under [name].
     * Returns null only when nothing is stored there (first run).
     * Any present-but-invalid document throws [KeyCustodyException]
     * with a named kind and loads nothing.
     */
    fun load(storage: StateStorage, name: String): Pair<StoredKeyPair, StoredKeyPair>? {
        val bytes = try {
            storage.read(name)
        } catch (e: Exception) {
            throw KeyCustodyException(KeyCustodyError.STORAGE_READ_FAILED, "could not read key custody \"$name\": ${e.message}")
        } ?: return null
        return deserialize(String(bytes, Charsets.UTF_8))
    }

    fun deserialize(text: String): Pair<StoredKeyPair, StoredKeyPair> {
        val root = try {
            JsonParser.parse(text)
        } catch (e: Exception) {
            throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "key custody document is not well-formed JSON: ${e.message}")
        }
        if (root !is Map<*, *>) {
            throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "key custody document is not a JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        val doc = root as Map<String, Any?>
        val format = doc["format"] as? String
            ?: throw KeyCustodyException(KeyCustodyError.UNSUPPORTED_FORMAT, "key custody document has no format field")
        if (format != FORMAT) {
            throw KeyCustodyException(KeyCustodyError.UNSUPPORTED_FORMAT, "unsupported key custody format \"$format\" (this build reads \"$FORMAT\")")
        }
        val version = (doc["version"] as? Number)?.toInt()
            ?: throw KeyCustodyException(KeyCustodyError.UNSUPPORTED_FORMAT, "key custody document has no version field")
        if (version != VERSION) {
            throw KeyCustodyException(KeyCustodyError.UNSUPPORTED_FORMAT, "unsupported key custody version $version (this build reads $VERSION)")
        }
        val operator = pairFromMap(doc["operator"], ROLE_OPERATOR)
        val revocation = pairFromMap(doc["revocation"], ROLE_REVOCATION)
        if (operator.keyId == revocation.keyId) {
            throw KeyCustodyException(
                KeyCustodyError.PAIR_MISMATCH,
                "operator and revocation-authority keys are identical; the hand that assents must not be the hand that revokes",
            )
        }
        return Pair(operator, revocation)
    }

    /**
     * Continuity check: the loaded custody must be the custody the
     * harness state was recorded under. A null expected id means the
     * state predates identity recording — that binds nothing and is
     * reported by the caller, not treated as a match failure here.
     */
    fun checkContinuity(expectedOperatorKeyId: String?, expectedRevocationKeyId: String?, operator: StoredKeyPair, revocation: StoredKeyPair) {
        if (expectedOperatorKeyId != null && expectedOperatorKeyId != operator.keyId) {
            throw KeyCustodyException(
                KeyCustodyError.CONTINUITY_MISMATCH,
                "operator key id ${operator.keyId} does not match the id recorded in the harness state ($expectedOperatorKeyId); keys were swapped or the state belongs to different keys",
            )
        }
        if (expectedRevocationKeyId != null && expectedRevocationKeyId != revocation.keyId) {
            throw KeyCustodyException(
                KeyCustodyError.CONTINUITY_MISMATCH,
                "revocation-authority key id ${revocation.keyId} does not match the id recorded in the harness state ($expectedRevocationKeyId)",
            )
        }
    }

    private fun pairToMap(p: StoredKeyPair): Map<String, Any?> = mapOf(
        "keyId" to p.keyId,
        "publicKey" to p.publicKeyB64,
        "privateKey" to p.privateKeyB64,
    )

    private fun pairFromMap(raw: Any?, role: String): StoredKeyPair {
        if (raw !is Map<*, *>) {
            throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "key custody document has no \"$role\" keypair object")
        }
        @Suppress("UNCHECKED_CAST")
        val m = raw as Map<String, Any?>
        val keyId = m["keyId"] as? String
            ?: throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "\"$role\" keypair has no keyId")
        val pubB64 = m["publicKey"] as? String
            ?: throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "\"$role\" keypair has no publicKey")
        val privB64 = m["privateKey"] as? String
            ?: throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "\"$role\" keypair has no privateKey")
        val pubDer = try {
            Base64.getDecoder().decode(pubB64)
        } catch (e: IllegalArgumentException) {
            throw KeyCustodyException(KeyCustodyError.PARSE_ERROR, "\"$role\" publicKey is not valid Base64")
        }
        if (keyIdFor(pubDer) != keyId) {
            throw KeyCustodyException(
                KeyCustodyError.KEY_ID_MISMATCH,
                "\"$role\" key id does not match the id recomputed from its stored public key; the custody document was tampered with or corrupted",
            )
        }
        val pair = StoredKeyPair(keyId, pubB64, privB64)
        // Pair correspondence: the stored private half must actually
        // belong to the stored public half. Proven by a probe
        // sign/verify, not by trusting the document's say-so.
        val kp = pair.toKeyPair()
        val probeSig = try {
            Ed25519.sign(kp.private, PROBE.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw KeyCustodyException(KeyCustodyError.PAIR_MISMATCH, "\"$role\" private key cannot sign: ${e.message}")
        }
        if (!Ed25519.verify(kp.public, PROBE.toByteArray(Charsets.UTF_8), probeSig)) {
            throw KeyCustodyException(
                KeyCustodyError.PAIR_MISMATCH,
                "\"$role\" private key does not correspond to its stored public key",
            )
        }
        return pair
    }
}
