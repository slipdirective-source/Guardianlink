package guardianlink.v2.app

import android.content.Context
import android.util.Log
import guardianlink.v2.core.KeyCustody
import guardianlink.v2.core.KeyCustodyException
import guardianlink.v2.core.StoredKeyPair

/**
 * Android-side key custody: the core's [KeyCustody] document, stored
 * through [EncryptedStateStorage] — i.e. the Ed25519 keypairs' encoded
 * private keys exist on disk only as ciphertext sealed by the Android
 * Keystore master key (the established pattern for this app; see
 * KeyCustody's doc for why the Ed25519 keys themselves are not
 * Keystore-resident on API 34).
 *
 * Posture, mirroring the state store's:
 *  - ABSENT document (read returns null) = first run: generate both
 *    pairs, persist, report [Ready.createdFresh] so the app can record
 *    the new key ids in the harness state.
 *  - PRESENT but unreadable/invalid (Keystore key lost, ciphertext
 *    corrupt, document tampered) = [Unavailable] with the named kind.
 *    NEVER a silent regeneration: new keys would orphan every prior
 *    signature and silently re-identify the sovereign. The app
 *    surfaces unavailability and governed engine execution stays
 *    closed.
 */
class KeyCustodyProvider(context: Context) {

    private val storage = EncryptedStateStorage(context)

    sealed interface State {
        data class Ready(
            val operator: StoredKeyPair,
            val revocation: StoredKeyPair,
            val createdFresh: Boolean,
        ) : State

        data class Unavailable(val reason: String) : State
    }

    fun loadOrCreate(): State {
        val loaded = try {
            KeyCustody.load(storage, CUSTODY_NAME)
        } catch (e: KeyCustodyException) {
            Log.e(TAG, "key custody load failed (${e.kind}): ${e.message}")
            return State.Unavailable("key custody failed (${e.kind}): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "key custody storage failed", e)
            return State.Unavailable("key custody storage failed: ${e.message}")
        }
        if (loaded != null) {
            return State.Ready(loaded.first, loaded.second, createdFresh = false)
        }
        // Verifiably absent: first run. Create both pairs — operator
        // and a SEPARATE revocation authority — and persist before use.
        val operator = KeyCustody.generate()
        val revocation = KeyCustody.generate()
        return try {
            KeyCustody.save(storage, CUSTODY_NAME, operator, revocation)
            Log.i(TAG, "fresh engine keypairs created and sealed (operator ${operator.keyId.take(12)}…, revocation ${revocation.keyId.take(12)}…)")
            State.Ready(operator, revocation, createdFresh = true)
        } catch (e: Exception) {
            Log.e(TAG, "key custody persist failed", e)
            State.Unavailable("key custody could not be persisted: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GuardianLinkV2Keys"

        /** Logical name of the custody document in the encrypted store. */
        const val CUSTODY_NAME = "identity-keys"
    }
}
