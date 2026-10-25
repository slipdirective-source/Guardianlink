package guardianlink.v2.app

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import guardianlink.v2.core.LanguagePort
import guardianlink.v2.core.StructuredIntent

/**
 * Our own brain (his call, 2026-10-25): Qwen2.5-1.5B-Instruct
 * running ON the phone via MediaPipe. No key, no account, no
 * network — the utterance never leaves the device, and the
 * reading carries exactly the same (lack of) authority as the
 * cloud lane's: structured data only, validated by the core
 * against the registry before it can become a proposal.
 *
 * The engine is built once and kept; a session is opened per
 * reading and closed after. Any failure — model absent, engine
 * won't start, output unparseable — reads as "no reading" and
 * the deterministic floor carries on, same contract as cloud.
 */
class LocalBrainPort(context: Context) : LanguagePort {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var engine: LlmInference? = null
    @Volatile private var engineFailed = false
    @Volatile private var breakerChecked = false

    /**
     * First touch each process: a surviving attempt marker means
     * the last engine start killed the process (native crashes
     * don't throw — they just end you). Trip the breaker instead
     * of looping the crash: brain off, everything else fine, and
     * Settings says what happened with a way back.
     */
    private fun checkBreaker() {
        if (breakerChecked) return
        breakerChecked = true
        val marker = BrainStore.attemptMarker(appContext)
        if (marker.exists()) {
            marker.delete()
            BrainPrefs.setTripped(appContext, true)
            engineFailed = true
        }
    }

    fun isReady(): Boolean {
        checkBreaker()
        return BrainPrefs.preferLocal(appContext) && BrainStore.isPresent(appContext) &&
            !BrainPrefs.tripped(appContext)
    }

    override fun parse(utterance: String): StructuredIntent? {
        if (!isReady()) return null
        return try {
            val text = generate(utterance) ?: return null
            IntentJson.decode(text)
        } catch (e: Exception) {
            null
        } catch (t: Throwable) {
            // Native-side failures (OOM, engine errors) surface as
            // Errors on some builds — a brain crash must degrade
            // to the floor, never take the conversation down.
            null
        }
    }

    /** Test hook for Settings: reads a fixed sentence with the local brain and reports. */
    fun testReading(): String {
        if (!BrainStore.isPresent(appContext)) return "The on-device brain isn't downloaded yet."
        val intent = parse("send Shannon a text telling her I love her")
            ?: return "The brain answered, but not in a readable intent shape — or it failed to start."
        return "The on-device brain reads fine: that sentence parses as ${intent.actionType} " +
            "(to=${intent.params["to"]}, body=\"${intent.params["body"]}\", confidence ${intent.confidence})."
    }

    private fun generate(utterance: String): String? {
        synchronized(lock) {
            val eng = engine ?: run {
                if (engineFailed) return null
                val marker = BrainStore.attemptMarker(appContext)
                try { marker.createNewFile() } catch (e: Exception) { /* marker is best-effort */ }
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(BrainStore.modelFile(appContext).absolutePath)
                        .setMaxTokens(2048)
                        .build()
                    LlmInference.createFromOptions(appContext, options).also {
                        engine = it
                        marker.delete()
                    }
                } catch (t: Throwable) {
                    engineFailed = true
                    marker.delete()
                    return null
                }
            }
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(1)
                .build()
            val session = LlmInferenceSession.createFromOptions(eng, sessionOptions)
            try {
                session.addQueryChunk(prompt(utterance))
                return session.generateResponse()
            } finally {
                try { session.close() } catch (e: Exception) { /* closing is best-effort */ }
            }
        }
    }

    /**
     * ChatML framing (Qwen's native template) with two pinned
     * examples — a 1.5B reader holds the JSON shape far better
     * shown than told.
     */
    private fun prompt(utterance: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(IntentJson.SYSTEM_PROMPT).append("<|im_end|>\n")
        sb.append("<|im_start|>user\ntext my brother and tell him dinner is at 7<|im_end|>\n")
        sb.append("<|im_start|>assistant\n{\"kind\":\"act\",\"actionType\":\"send_text\",\"params\":{\"to\":\"brother\",\"body\":\"dinner is at 7\"},\"confidence\":0.95}<|im_end|>\n")
        sb.append("<|im_start|>user\ntell Shannon I love her, with hearts and kisses emojis<|im_end|>\n")
        sb.append("<|im_start|>assistant\n{\"kind\":\"act\",\"actionType\":\"send_text\",\"params\":{\"to\":\"Shannon\",\"body\":\"I love you ❤️😘\"},\"confidence\":0.95}<|im_end|>\n")
        sb.append("<|im_start|>user\nwhere am i right now<|im_end|>\n")
        sb.append("<|im_start|>assistant\n{\"kind\":\"ask\",\"actionType\":null,\"params\":{},\"confidence\":0.9}<|im_end|>\n")
        sb.append("<|im_start|>user\n").append(utterance).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

/**
 * Brain first, cloud second (his architecture): the on-device
 * brain reads when it's downloaded and preferred; the cloud lane
 * answers when the brain can't. Either way the core sees one
 * [LanguagePort] and one contract.
 */
class CompositeLanguagePort(
    private val local: LocalBrainPort,
    private val cloud: CloudLanguagePort,
) : LanguagePort {

    override fun parse(utterance: String): StructuredIntent? =
        local.parse(utterance) ?: cloud.parse(utterance)

    fun testReading(): String {
        if (local.isReady()) return local.testReading()
        return cloud.testReading()
    }
}
