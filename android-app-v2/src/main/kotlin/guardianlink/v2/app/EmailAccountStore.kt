package guardianlink.v2.app

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The connected-mailbox credential, sealed.
 *
 * The sovereign connects a mailbox by handing the app an address
 * and an app password; those bytes live ONLY inside an
 * [EncryptedFile] (Keystore-held master key, AES256-GCM) in the
 * app's private directory. The credential never enters the core
 * state document, never enters the ledger, and is never logged.
 * Deleting the account deletes the file.
 *
 * Honest posture, same as the state store: if the Keystore key is
 * lost, the credential is gone and the account must be connected
 * again — the app says so instead of pretending.
 */
data class EmailAccount(
    val address: String,
    val host: String,
    val port: Int,
    val password: String,
)

class EmailAccountStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "gl-email-account.enc")

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    fun load(): EmailAccount? {
        if (!file.exists()) return null
        return try {
            val text = encryptedFile().openFileInput().bufferedReader(StandardCharsets.UTF_8).readText()
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            // Positional format, one field per line; the password is
            // last and may not contain a newline (dialog enforces).
            if (lines.size < 4) null else EmailAccount(
                address = lines[0],
                host = lines[1],
                port = lines[2].toIntOrNull() ?: 993,
                password = lines.subList(3, lines.size).joinToString(""),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(account: EmailAccount) {
        // EncryptedFile refuses to overwrite an existing file.
        if (file.exists()) file.delete()
        val body = listOf(account.address, account.host, account.port.toString(), account.password)
            .joinToString("\n")
        encryptedFile().openFileOutput().use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }
}
