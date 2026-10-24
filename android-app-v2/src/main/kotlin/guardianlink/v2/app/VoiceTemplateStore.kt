package guardianlink.v2.app

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import guardianlink.voice.Gmm
import guardianlink.voice.SpeakerEmbedding
import guardianlink.voice.VoiceTemplate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * On-device storage for the enrolled [VoiceTemplate], ported from the
 * scaffold's `TemplateStore` (same binary format, same sealing): the
 * template is serialized to a compact binary form and sealed with a
 * Keystore-held AES key via EncryptedFile (AES256_GCM_HKDF_4KB).
 *
 * In this build the store is expected to be EMPTY: enrollment is not
 * offered until a real voice verifier (UBM) is deployed — see
 * [VoiceBiometricPort]. The store exists now so that when enrollment
 * lands, its custody pattern is the established one, and so a template
 * enrolled by a future build loads here without a format break.
 *
 * Fail-closed load: a corrupt or tampered template yields null (the
 * caller wires the inert template + denying port), never a partial or
 * silently replaced template.
 */
class VoiceTemplateStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "voice-template.bin")

    private fun encryptedFile(): EncryptedFile {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    fun save(template: VoiceTemplate) {
        DataOutputStream(encryptedFile().openFileOutput()).use { out ->
            out.writeBytes(MAGIC)
            out.writeInt(VERSION)
            when (val emb = template.speakerEmbedding) {
                is SpeakerEmbedding.GmmModel -> {
                    out.writeByte(TYPE_GMM)
                    writeGmm(out, emb.model)
                }
                is SpeakerEmbedding.Vector -> {
                    out.writeByte(TYPE_VECTOR)
                    out.writeInt(emb.values.size)
                    emb.values.forEach(out::writeDouble)
                }
            }
            out.writeInt(template.phraseReferences.size)
            for ((phraseId, frames) in template.phraseReferences) {
                val idBytes = phraseId.toByteArray(Charsets.UTF_8)
                out.writeInt(idBytes.size)
                out.write(idBytes)
                out.writeInt(frames.size)
                for (frame in frames) {
                    out.writeInt(frame.size)
                    frame.forEach(out::writeDouble)
                }
            }
            out.writeInt(template.sampleRateHz)
            out.flush()
        }
        Log.i(TAG, "voice template saved (${file.length()} bytes, encrypted)")
    }

    fun load(): VoiceTemplate? {
        if (!file.exists()) return null
        return try {
            DataInputStream(encryptedFile().openFileInput()).use { inp ->
                val magic = ByteArray(4)
                inp.readFully(magic)
                require(magic.toString(Charsets.UTF_8) == MAGIC) { "bad magic" }
                require(inp.readInt() == VERSION) { "unsupported version" }
                val embedding: SpeakerEmbedding = when (inp.readByte().toInt()) {
                    TYPE_GMM -> SpeakerEmbedding.GmmModel(readGmm(inp))
                    TYPE_VECTOR -> {
                        val n = inp.readInt()
                        SpeakerEmbedding.Vector(DoubleArray(n) { inp.readDouble() })
                    }
                    else -> throw IllegalArgumentException("unknown embedding type")
                }
                val refCount = inp.readInt()
                val refs = LinkedHashMap<String, List<DoubleArray>>(refCount)
                repeat(refCount) {
                    val idLen = inp.readInt()
                    val idBytes = ByteArray(idLen)
                    inp.readFully(idBytes)
                    val frameCount = inp.readInt()
                    val frames = ArrayList<DoubleArray>(frameCount)
                    repeat(frameCount) {
                        val dim = inp.readInt()
                        frames.add(DoubleArray(dim) { inp.readDouble() })
                    }
                    refs[idBytes.toString(Charsets.UTF_8)] = frames
                }
                VoiceTemplate(embedding, refs, inp.readInt())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "voice template load failed; treating as not enrolled", t)
            null
        }
    }

    private fun writeGmm(out: DataOutputStream, gmm: Gmm) {
        out.writeInt(gmm.components)
        out.writeInt(gmm.dim)
        gmm.weights.forEach(out::writeDouble)
        for (k in 0 until gmm.components) {
            gmm.means[k].forEach(out::writeDouble)
            gmm.variances[k].forEach(out::writeDouble)
        }
    }

    private fun readGmm(inp: DataInputStream): Gmm {
        val components = inp.readInt()
        val dim = inp.readInt()
        val weights = DoubleArray(components) { inp.readDouble() }
        val means = Array(components) { DoubleArray(dim) { inp.readDouble() } }
        val variances = Array(components) { DoubleArray(dim) { inp.readDouble() } }
        return Gmm(weights, means, variances)
    }

    companion object {
        private const val TAG = "GuardianLinkV2Voice"
        private const val MAGIC = "GLVT"
        private const val VERSION = 1
        private const val TYPE_GMM = 0
        private const val TYPE_VECTOR = 1
    }
}
