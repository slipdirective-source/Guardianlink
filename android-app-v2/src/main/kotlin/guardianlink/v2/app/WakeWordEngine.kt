package guardianlink.v2.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import guardianlink.v2.core.JsonParser
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Wake-phrase spotting, on-device (his ruling, 2026-10-25: the
 * handle is "Hey Guard" by default and whatever the user wants it
 * to be — Android's own hotword can't be claimed, so the phrase is
 * spotted locally instead).
 *
 * Engine: Vosk with a GRAMMAR constrained to the handle phrase —
 * the recognizer can only ever "hear" the phrase or unknown
 * speech, which is what makes an arbitrary user-chosen handle
 * possible without retraining a model. Audio is processed in
 * memory and discarded; nothing is recorded, stored, or sent.
 * The loop runs only while sensing runs, the toggle is on, the
 * mic grant stands, and the offline model (~40 MB, downloaded
 * once at the sovereign's request) is present.
 */
object WakeWordEngine {

    private const val TAG = "WakeWordEngine"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val SAMPLE_RATE = 16000

    @Volatile
    private var running = false
    private var worker: Thread? = null

    /** Live truth for the Settings status line — never prefs-derived. */
    @Volatile
    var isRunning: Boolean = false

    @Volatile
    var lastError: String? = null

    fun modelDir(context: Context): File = File(context.applicationContext.filesDir, "vosk-model")

    fun modelInstalled(context: Context): Boolean =
        File(modelDir(context), "conf/model.conf").exists() || File(modelDir(context), "am/final.mdl").exists()

    fun downloadModel(context: Context, done: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        thread(name = "gl-vosk-download", isDaemon = true) {
            var ok = false
            try {
                val target = modelDir(appContext)
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                ZipInputStream(conn.inputStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        // Strip the archive's top-level folder.
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val out = File(target, rel)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                ok = modelInstalled(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "model download failed", e)
            }
            done(ok)
        }
    }

    fun start(context: Context, onWake: () -> Unit) {
        if (running) return
        val appContext = context.applicationContext
        if (!modelInstalled(appContext)) return
        val phrase = WakePrefs.phrase(appContext).lowercase().trim()
        if (phrase.isBlank()) return
        running = true
        isRunning = true
        lastError = null
        worker = thread(name = "gl-wake-word", isDaemon = true) {
            var model: Model? = null
            var recognizer: Recognizer? = null
            var record: AudioRecord? = null
            try {
                model = Model(modelDir(appContext).absolutePath)
                val grammar = "[\"$phrase\", \"[unk]\"]"
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(8192)
                @Suppress("MissingPermission") // gated by the service before start
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("the microphone didn't open")
                }
                record.startRecording()
                val buffer = ByteArray(4096)
                var deadReads = 0
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        // A mic that never delivers is a failed
                        // ear, not a quiet room — say so.
                        if (++deadReads > 200) {
                            lastError = "the microphone isn't delivering audio"
                            break
                        }
                        continue
                    }
                    deadReads = 0
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        val text = (JsonParser.parse(recognizer.result) as? Map<*, *>)?.get("text") as? String
                        if (text != null && text.trim() == phrase) {
                            Log.i(TAG, "wake phrase heard")
                            onWake()
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "wake loop ended", e)
            } finally {
                isRunning = false
                try { record?.stop() } catch (e: Exception) { /* already stopped */ }
                record?.release()
                recognizer?.close()
                model?.close()
            }
        }
    }

    fun stop() {
        running = false
        isRunning = false
        worker = null
    }
}
