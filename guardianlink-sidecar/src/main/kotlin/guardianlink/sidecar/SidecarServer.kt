package guardianlink.sidecar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Thin HTTP adapter over [GuardianLinkEngine].
 *
 * This layer does transport only: routing, body parsing, status codes.
 * It makes NO policy decisions and performs NO execution of its own —
 * every mutating call funnels into GuardianLinkEngine.assent, the single
 * engine entry point. There is deliberately no endpoint that executes,
 * mutates, or inspects anything except through the engine.
 *
 * Fail-closed transport: malformed JSON, oversized bodies, unknown
 * routes, and wrong methods all get 4xx; nothing in the error paths
 * touches the engine.
 */
class SidecarServer(
    private val engine: GuardianLinkEngine,
    port: Int,
) {
    companion object {
        /**
         * Request bodies larger than this are rejected outright (413).
         * Must exceed the largest ceremony audio: 1 MiB of PCM becomes
         * ~1.4 MiB of base64 plus JSON overhead.
         */
        const val MAX_BODY_BYTES: Int = 2 * 1024 * 1024
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/v1/actions/submit", ::handleSubmit)
        createContext("/v1/actions/", ::handleActionSub) // prefix: /v1/actions/{id}/{challenge|assent}
        createContext("/v1/ledger/verify", ::handleLedgerVerify)
        createContext("/v1/substrate", ::handleSubstrate)
        createContext("/", ::handleNotFound)
        executor = Executors.newFixedThreadPool(8)
    }

    /** Actual bound port (useful when constructed with port 0). */
    val port: Int get() = server.address.port

    fun start() = server.start()
    fun stop() = server.stop(0)

    // ---- handlers ----

    private fun handleSubmit(ex: HttpExchange) {
        if (!requireMethod(ex, "POST")) return
        val body = readBody(ex) ?: return
        val actionJson = try {
            val obj = JSONObject(body)
            obj.optJSONObject("action")
                ?: return sendJson(ex, 400, err("missing 'action' object"))
        } catch (_: Exception) {
            return sendJson(ex, 400, err("malformed JSON"))
        }
        val action = try {
            parseAction(actionJson)
        } catch (e: BadActionException) {
            return sendJson(ex, 400, err("bad action: ${e.message}"))
        }
        val s = engine.submit(action)
        sendJson(ex, 200, JSONObject()
            .put("actionId", s.actionId)
            .put("rendering", s.rendering)
            .put("impact", s.impact)
            .put("coolingWindowMs", s.coolingWindowMs)
            .put("serverTimeMs", s.serverTimeMs))
    }

    /**
     * Voice ceremony, step 1: issue a single-use liveness challenge for a
     * pending action. The person must speak the returned phrase; the PCM
     * goes back with the assent. Ceremonies are serial per sidecar
     * instance (the engine holds one active challenge): 409 while another
     * action's ceremony is live.
     */
    private fun handleChallenge(ex: HttpExchange, actionId: String) {
        if (!requireMethod(ex, "GET")) return
        when (val r = engine.issueChallenge(actionId)) {
            is GuardianLinkEngine.ChallengeResult.Issued -> sendJson(ex, 200, JSONObject()
                .put("challengeId", r.challengeId)
                .put("phrase", r.phrase)
                .put("expiresInMs", GuardianLinkEngine.CHALLENGE_TTL_MS)
                .put("audioFormat", "base64 of little-endian float32 mono PCM at 16000 Hz"))
            is GuardianLinkEngine.ChallengeResult.UnknownAction ->
                sendJson(ex, 404, err("unknown or expired actionId"))
            is GuardianLinkEngine.ChallengeResult.CeremonyBusy ->
                sendJson(ex, 409, err("another voice ceremony is already active; it must complete or lapse first"))
        }
    }

    private fun handleActionSub(ex: HttpExchange) {
        // Route shapes: /v1/actions/{id}/challenge | /v1/actions/{id}/assent
        val parts = ex.requestURI.path.removePrefix("/v1/actions/").split("/")
        if (parts.size != 2 || parts[0].isBlank()) {
            return sendJson(ex, 404, err("not found"))
        }
        when (parts[1]) {
            "challenge" -> handleChallenge(ex, parts[0])
            "assent" -> handleAssent(ex, parts[0])
            else -> sendJson(ex, 404, err("not found"))
        }
    }

    private fun handleAssent(ex: HttpExchange, actionId: String) {
        if (!requireMethod(ex, "POST")) return
        val body = readBody(ex) ?: return
        val obj = try {
            JSONObject(body)
        } catch (_: Exception) {
            return sendJson(ex, 400, err("malformed JSON"))
        }

        fun reqStr(name: String): String? =
            obj.optString(name, null)?.takeIf { it.isNotEmpty() }

        val rendering = reqStr("rendering")
            ?: return sendJson(ex, 400, err("missing 'rendering'"))
        val assentedAtMs = obj.optLong("assentedAtMs", Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
            ?: return sendJson(ex, 400, err("missing 'assentedAtMs'"))
        val assentSignature = reqStr("assentSignature")
            ?: return sendJson(ex, 400, err("missing 'assentSignature' (hex HMAC)"))
        val keySignature = reqStr("keySignature")
            ?: return sendJson(ex, 400, err("missing 'keySignature' (hex HMAC)"))
        val keyMessage = obj.optString("keyMessage", null)
            ?.let { hexOrUtf8(it) }
            ?: actionId.toByteArray(Charsets.UTF_8)

        val intentVector = obj.optJSONArray("intentVector")?.toDoubleArray()
            ?: doubleArrayOf(1.0, 0.0)
        val currentVector = obj.optJSONArray("currentVector")?.toDoubleArray()
            ?: doubleArrayOf(1.0, 0.0)

        // Ceremony PCM: base64 of little-endian float32 mono at 16 kHz.
        // An ABSENT field is a malformed request (400). A present-but-empty
        // string decodes to empty PCM and reaches the engine, which
        // fail-closes at Gate 7 (theta_voice) — silent ceremonies are
        // rejections, not crashes.
        if (!obj.has("assentAudio") || obj.isNull("assentAudio")) {
            return sendJson(ex, 400, err("missing 'assentAudio' (base64 float32-LE PCM, 16 kHz mono)"))
        }
        val assentAudio = try {
            engine.decodeCeremonyAudio(obj.optString("assentAudio", ""))
        } catch (e: BadAudioException) {
            return sendJson(ex, 400, err("bad assentAudio: ${e.message}"))
        }

        when (val r = engine.assent(
            actionId, rendering, assentedAtMs, assentSignature,
            keyMessage, keySignature, assentAudio, intentVector, currentVector,
        )) {
            is GuardianLinkEngine.AssentResult.Executed -> sendJson(ex, 200, JSONObject()
                .put("status", "executed")
                .put("sealRoot", r.sealRoot)
                .put("anchorReceipt", JSONObject()
                    .put("root", r.anchorReceipt.root)
                    .put("entryIndex", r.anchorReceipt.entryIndex)
                    .put("timestampMs", r.anchorReceipt.timestampMs)
                    .put("location", r.anchorReceipt.location))
                .put("substrate", substrateJson(r.newSubstrate)))
            is GuardianLinkEngine.AssentResult.Rejected -> sendJson(ex, 200, JSONObject()
                .put("status", "rejected")
                .put("atGate", r.atGate)
                .put("reason", r.reason))
            is GuardianLinkEngine.AssentResult.UnknownAction ->
                sendJson(ex, 404, err("unknown or expired actionId"))
            is GuardianLinkEngine.AssentResult.NoActiveCeremony ->
                sendJson(ex, 409, err("no active voice ceremony for this action: GET /v1/actions/{id}/challenge first"))
            is GuardianLinkEngine.AssentResult.AnchoringFailed -> sendJson(ex, 502, JSONObject()
                .put("status", "anchoring_failed")
                .put("sealRoot", r.sealRoot)
                .put("reason", r.reason)
                .put("note", "sealed locally but NOT durably anchored; reconcile via /v1/ledger/verify before retrying"))
        }
    }

    private fun handleLedgerVerify(ex: HttpExchange) {
        if (!requireMethod(ex, "GET")) return
        val r = engine.verifyLedger()
        sendJson(ex, 200, JSONObject()
            .put("merkle", JSONObject()
                .put("ok", r.merkleOk)
                .put("entries", r.merkleEntries)
                .put("root", r.merkleRoot))
            .put("anchor", JSONObject()
                .put("ok", r.anchorOk)
                .put("lines", r.anchorLines)))
    }

    private fun handleSubstrate(ex: HttpExchange) {
        if (!requireMethod(ex, "GET")) return
        sendJson(ex, 200, JSONObject().put("records", substrateJson(engine.currentSubstrate())))
    }

    private fun handleNotFound(ex: HttpExchange) {
        sendJson(ex, 404, err("not found"))
    }

    // ---- transport helpers ----

    private fun requireMethod(ex: HttpExchange, method: String): Boolean {
        if (ex.requestMethod != method) {
            sendJson(ex, 405, err("method not allowed"))
            return false
        }
        return true
    }

    /** Reads the body up to MAX_BODY_BYTES; null after sending 413/400. */
    private fun readBody(ex: HttpExchange): String? {
        val bytes = try {
            ex.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
        } catch (_: Exception) {
            sendJson(ex, 400, err("could not read body"))
            return null
        }
        if (bytes.size > MAX_BODY_BYTES) {
            sendJson(ex, 413, err("body too large"))
            return null
        }
        return try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            sendJson(ex, 400, err("body is not UTF-8"))
            return null
        }
    }

    private fun sendJson(ex: HttpExchange, status: Int, obj: JSONObject) {
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun err(message: String): JSONObject =
        JSONObject().put("error", message)

    private fun substrateJson(s: guardianlink.gates.SubstrateState): JSONObject {
        val out = JSONObject()
        for ((recordId, fields) in s.records) {
            val f = JSONObject()
            for ((k, v) in fields) f.put(k, v)
            out.put(recordId, f)
        }
        return out
    }

    /** Accepts hex; falls back to raw UTF-8 bytes for convenience. */
    private fun hexOrUtf8(s: String): ByteArray =
        s.hexToBytes() ?: s.toByteArray(Charsets.UTF_8)

    private fun org.json.JSONArray.toDoubleArray(): DoubleArray? = try {
        DoubleArray(length()) { i -> getDouble(i) }
    } catch (_: Exception) {
        null
    }
}
