package guardianlink.v2.app

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The language-model connection, sealed — same posture as the
 * mailbox credential. The API key lives only inside an
 * [EncryptedFile] (Keystore AES256-GCM) in the app's private
 * directory; it never enters core state, the ledger, or a log.
 * Base URL and model name ride in the same file (they're config,
 * not secrets, but one file is one thing to delete).
 */
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

class LlmConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "gl-llm.enc")

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

    fun load(): LlmConfig? {
        if (!file.exists()) return null
        return try {
            val text = encryptedFile().openFileInput().bufferedReader(StandardCharsets.UTF_8).readText()
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size < 3) null else LlmConfig(baseUrl = lines[0], model = lines[1], apiKey = lines[2])
        } catch (e: Exception) {
            null
        }
    }

    fun save(config: LlmConfig) {
        if (file.exists()) file.delete()
        val body = listOf(config.baseUrl, config.model, config.apiKey).joinToString("\n")
        encryptedFile().openFileOutput().use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }
}
