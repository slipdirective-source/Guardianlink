package guardianlink.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import guardianlink.voice.AudioSample
import kotlin.concurrent.thread

/**
 * On-device voice enrollment.
 *
 * Captures 16 kHz mono PCM from the microphone, enrolls the speaker
 * against the engine's GMM-UBM verifier, and seals the template via
 * [TemplateStore] (Keystore-held key; never leaves the device).
 *
 * Scaffold scope: single free-speech sample, no per-phrase liveness
 * references (liveness DTW needs supervised multi-phrase capture —
 * production scope). Thresholds are synthetic-calibrated; re-calibration
 * on real microphone captures is mandatory before any production claim
 * (see docs/android-integration.md).
 */
class VoiceEnrollmentActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var recordButton: Button

    @Volatile
    private var recording = false
    private var recorder: AudioRecord? = null

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = if (granted) "Mic granted. Tap Enroll and speak." else "Mic permission denied."
            recordButton.isEnabled = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Voice enrollment" }
        recordButton = Button(this).apply {
            text = "Enroll (speak for ~5s)"
            isEnabled = false
            setOnClickListener { toggleEnrollment() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(recordButton)
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            recordButton.isEnabled = true
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleEnrollment() {
        if (recording) stopAndEnroll() else startRecording()
    }

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        ).also { it.startRecording() }
        recording = true
        recordButton.text = "Stop & enroll"
        status.text = "Recording… speak naturally."
    }

    private fun stopAndEnroll() {
        recording = false
        recordButton.isEnabled = false
        status.text = "Enrolling…"
        val rec = recorder
        recorder = null
        thread(name = "gl-enroll", isDaemon = true) {
            try {
                val pcm = captureAll(rec)
                rec?.release()
                val sample = AudioSample(DoubleArray(pcm.size) { pcm[it] / 32768.0 }, SAMPLE_RATE)
                val app = application as GuardianApp
                val template = app.voiceVerifier.enroll(listOf(sample))
                app.setEnrolledTemplate(template)
                runOnUiThread {
                    status.text = "Enrolled. Restart the app to activate the voice ceremony."
                    recordButton.text = "Enroll again"
                    recordButton.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "Enrollment failed: ${t.message}"
                    recordButton.isEnabled = true
                }
            }
        }
    }

    private fun captureAll(rec: AudioRecord?): ShortArray {
        val chunks = ArrayList<ShortArray>()
        var total = 0
        val buf = ShortArray(4096)
        while (recording) {
            val n = rec?.read(buf, 0, buf.size) ?: 0
            if (n > 0) {
                chunks.add(buf.copyOf(n))
                total += n
            }
        }
        // Drain the tail after stop.
        var n = rec?.read(buf, 0, buf.size) ?: 0
        while (n > 0) {
            chunks.add(buf.copyOf(n))
            total += n
            n = rec?.read(buf, 0, buf.size) ?: 0
        }
        val out = ShortArray(total)
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    override fun onDestroy() {
        recording = false
        try { recorder?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
