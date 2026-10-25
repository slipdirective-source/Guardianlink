package guardianlink.v2.app

import android.content.Context
import java.io.File

/**
 * Where the on-device brain lives. The weights are Qwen2.5-1.5B-
 * Instruct (Apache 2.0, Alibaba) in MediaPipe .task form from
 * the LiteRT community — no gate, no account, no license wall,
 * which is exactly why this model and not a gated one: the app
 * can fetch it directly, and the sovereign's copy is theirs.
 *
 * 1.6 GB on internal storage, fetched once from Settings. The
 * file's byte size is pinned — a truncated or substituted
 * download fails the size check and is discarded, never loaded.
 */
object BrainStore {

    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val EXPECTED_BYTES = 1_598_556_720L
    const val MODEL_LABEL = "Qwen2.5-1.5B"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "brain").apply { mkdirs() }, "qwen2.5-1.5b.task")

    fun partFile(context: Context): File =
        File(File(context.filesDir, "brain").apply { mkdirs() }, "qwen2.5-1.5b.task.part")

    fun isPresent(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() == EXPECTED_BYTES
    }

    fun remove(context: Context) {
        modelFile(context).delete()
        partFile(context).delete()
    }
}

/** The sovereign's brain preferences: on-device first, unless they say otherwise. */
object BrainPrefs {
    private const val PREFS = "gl-brain"
    private const val KEY_PREFER_LOCAL = "prefer_local"

    fun preferLocal(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFER_LOCAL, true)

    fun setPreferLocal(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFER_LOCAL, value).apply()
    }
}
