package guardianlink.v2.app

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import guardianlink.v2.core.StateStorage
import java.io.File
import java.io.IOException

/**
 * Encrypted-at-rest [StateStorage] for the harness state document.
 *
 * Pattern: the scaffold's established one (`TemplateStore` in
 * android-app) — a [MasterKey] (AES256_GCM scheme) held by the Android
 * Keystore, and [EncryptedFile] with
 * [EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB] doing the
 * sealing. The key never leaves the Keystore; what this class stores is
 * only ciphertext. The plaintext at the [StateStorage] interface is the
 * `gl-state-1` document from StateStore — the SAME bytes the core's
 * FileStateStorage would write, encrypted.
 *
 * WHY SLOTS INSTEAD OF TEMP+RENAME: EncryptedFile binds its ciphertext
 * to the file's name — a file written under a temp name cannot be
 * renamed into place and still decrypt. So atomicity (the StateStorage
 * contract: a write wholly replaces, or the prior bytes survive) is
 * achieved with two fixed slots per logical name, `<name>.a.enc` and
 * `<name>.b.enc`, plus a tiny plaintext marker `<name>.slot` holding
 * the active slot letter:
 *
 *  1. The new document is encrypted into the INACTIVE slot (its fixed
 *     name, so the name binding holds).
 *  2. The slot is decrypted back and compared byte-for-byte with the
 *     intended document; only a verified slot may become active.
 *  3. The marker is flipped (a small plain file, written via temp +
 *     rename — it carries no secrets, only "a" or "b").
 *
 * A crash before step 3 leaves the marker pointing at the previous
 * good slot: the prior state is untouched. A crash during step 1
 * corrupts only the inactive slot. If the marker-named slot ever fails
 * to decrypt, [read] falls back to the other slot before giving up —
 * both slots hold authentic, chain-verified documents (StateStore
 * still hash-verifies whatever this class returns; a fallback slot is
 * an older authentic state, never a forgery).
 *
 * RECOVERY POSTURE, named honestly: if the Keystore key is lost (key
 * invalidation, credential change on some devices, factory reset), the
 * ciphertext is unreadable and the stored state is GONE — the app
 * surfaces that as a named persistence failure and starts visibly
 * empty; it does not silently "recover" by resetting. There is no
 * plaintext fallback and no key escrow in this build.
 */
class EncryptedStateStorage(context: Context) : StateStorage {

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "gl-state").apply { mkdirs() }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun slotFile(name: String, slot: Char): File = File(directory, "$name.$slot.enc")

    private fun markerFile(name: String): File = File(directory, "$name.slot")

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    private fun activeSlot(name: String): Char? {
        val marker = markerFile(name)
        if (!marker.isFile) return null
        val letter = marker.readText().trim()
        return if (letter == "a" || letter == "b") letter[0] else null
    }

    private fun decryptSlot(name: String, slot: Char): ByteArray? {
        val file = slotFile(name, slot)
        if (!file.isFile) return null
        return try {
            encryptedFile(file).openFileInput().use { it.readBytes() }
        } catch (e: Exception) {
            // AEAD failure, missing key, truncated ciphertext — this slot
            // is unusable. The caller may still have the other slot.
            null
        }
    }

    override fun read(name: String): ByteArray? {
        validateName(name)
        val active = activeSlot(name)
        if (active != null) {
            decryptSlot(name, active)?.let { return it }
            val other = if (active == 'a') 'b' else 'a'
            decryptSlot(name, other)?.let { return it }
            if (slotFile(name, 'a').isFile || slotFile(name, 'b').isFile) {
                throw IOException(
                    "encrypted state \"$name\" exists but neither slot decrypts (Keystore key lost or ciphertext corrupt)",
                )
            }
            return null
        }
        // No marker: either nothing was ever stored, or the marker was
        // lost after a slot write. Try both slots before declaring empty.
        return decryptSlot(name, 'a') ?: decryptSlot(name, 'b')
    }

    override fun write(name: String, bytes: ByteArray) {
        validateName(name)
        val active = activeSlot(name)
        val target = if (active == 'a') 'b' else 'a'
        val file = slotFile(name, target)
        // EncryptedFile writes a fresh stream; remove any stale slot
        // content first so a previous partial slot cannot linger.
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { out ->
            out.write(bytes)
            out.flush()
        }
        // Verify before committing: the slot must decrypt to exactly
        // what we meant to store. A failure here leaves the marker —
        // and therefore the prior state — untouched.
        val back = decryptSlot(name, target)
        if (back == null || !back.contentEquals(bytes)) {
            throw IOException("encrypted state \"$name\" failed post-write verification; prior state left active")
        }
        writeMarker(name, target)
    }

    override fun list(): List<String> =
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".slot") }
            .map { it.name.removeSuffix(".slot") }
            .sorted()

    private fun writeMarker(name: String, slot: Char) {
        val marker = markerFile(name)
        val tmp = File(directory, "$name.slot.tmp")
        tmp.writeText(slot.toString())
        if (!tmp.renameTo(marker)) {
            // renameTo can fail across filesystems; same-directory here,
            // but fall back to a direct write rather than failing the save.
            marker.writeText(slot.toString())
            tmp.delete()
        }
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
            "invalid state name \"$name\""
        }
    }
}
