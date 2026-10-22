package guardianlink.sidecar

import guardianlink.durability.DurableCapabilityStore
import guardianlink.durability.StoreResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GET /v1/capabilities — the read-only capability-state endpoint.
 *
 * The state under test is produced by the REAL durable store (the same
 * public commit calls the R-U1-wired issuer makes) writing its WAL in
 * the engine's data dir before the engine starts; the engine recovers
 * it, and the endpoint must then report exactly what the store knows:
 *
 * - consumed permits, consumed/revoked capabilities, fencing epoch,
 *   recorded usage, and the composed rails — nothing invented;
 * - the same truth after an engine restart (WAL replay);
 * - zero mutation: the WAL bytes and the response are identical across
 *   any number of reads;
 * - malformed traffic fails closed in the standard error shape:
 *   wrong method 405, sub-paths and look-alike prefixes 404, and a
 *   query string selects nothing (the true state is returned as-is);
 * - concurrent reads are consistent snapshots, never torn.
 */
class SidecarCapabilityStateTest {

    private val testSecret: ByteArray = "test-secret-0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val http: HttpClient = HttpClient.newHttpClient()

    private var server: SidecarServer? = null
    private lateinit var base: String
    private lateinit var dataDir: Path

    @AfterEach
    fun tearDown() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private fun start(dir: Path) {
        dataDir = dir
        val engine = GuardianLinkEngine(dir, testSecret)
        val s = SidecarServer(engine, 0)
        s.start()
        server = s
        base = "http://localhost:${s.port}"
    }

    /** Write real capability state into the dir's WAL via the store's public commit API. */
    private fun seedStore(dir: Path) {
        val store = DurableCapabilityStore(dir.toFile(), clockMs = { 60_000L })
        assertEquals(StoreResult.Committed, store.recordPermitConsumption("permit-seeded-1"))
        assertEquals(StoreResult.Committed, store.commitMutation("cap-consumed-1", Long.MAX_VALUE))
        assertEquals(StoreResult.Committed, store.recordUsage("cap-consumed-1", 42L, null, null, Long.MAX_VALUE))
        store.revoke("cap-revoked-1") // fencing epoch -> 1
    }

    // ---- HTTP helpers ----

    private fun request(method: String, path: String): Pair<Int, JSONObject> {
        val builder = HttpRequest.newBuilder(URI.create("$base$path"))
        when (method) {
            "GET" -> builder.GET()
            else -> builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to JSONObject(res.body())
    }

    private fun get(path: String): Pair<Int, JSONObject> = request("GET", path)

    /** Key-order-independent canonical rendering, for equality across responses. */
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { k -> "\"$k\":${canonical(v.get(k))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        else -> v.toString()
    }

    private fun walHash(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(dataDir.resolve("capability.log")))
            .joinToString("") { "%02x".format(it) }

    // ---- C1: happy path + exact shape ----

    @Test
    fun `reports exactly what the store knows`() {
        val dir = Files.createTempDirectory("sidecar-cap-state")
        seedStore(dir)
        start(dir)

        val (status, body) = get("/v1/capabilities")
        assertEquals(200, status)
        assertEquals(setOf("permits", "capabilities", "rails"), body.keySet())

        val permits = body.getJSONObject("permits")
        assertEquals(setOf("consumedCount", "consumedIds"), permits.keySet())
        assertEquals(1, permits.getInt("consumedCount"))
        assertEquals(
            listOf("permit-seeded-1"),
            (0 until permits.getJSONArray("consumedIds").length())
                .map { permits.getJSONArray("consumedIds").getString(it) },
        )

        val caps = body.getJSONObject("capabilities")
        assertEquals(
            setOf("fencingEpoch", "consumedIds", "revokedIds", "revokedEpochs", "usage"),
            caps.keySet(),
        )
        assertEquals(1L, caps.getLong("fencingEpoch"))
        assertEquals("cap-consumed-1", caps.getJSONArray("consumedIds").getString(0))
        assertEquals("cap-revoked-1", caps.getJSONArray("revokedIds").getString(0))
        assertEquals(0, caps.getJSONArray("revokedEpochs").length())
        val usage = caps.getJSONObject("usage").getJSONObject("cap-consumed-1")
        assertEquals(1L, usage.getLong("mutations"))
        assertEquals(42L, usage.getLong("bytes"))

        // The rails are the engine's composed Rails() — reported, not invented.
        val rails = body.getJSONObject("rails")
        assertEquals(20, rails.length())
        assertEquals(10.0, rails.getDouble("gammaNoise"))
        assertEquals(4.0, rails.getDouble("hMax"))
        assertEquals(86_400_000L, rails.getLong("maxAssentIdleMs"))
        assertEquals(1_000_000L, rails.getLong("maxCycles"))
    }

    @Test
    fun `fresh engine reports empty capability state`() {
        start(Files.createTempDirectory("sidecar-cap-empty"))

        val (status, body) = get("/v1/capabilities")
        assertEquals(200, status)
        val permits = body.getJSONObject("permits")
        assertEquals(0, permits.getInt("consumedCount"))
        assertEquals(0, permits.getJSONArray("consumedIds").length())
        val caps = body.getJSONObject("capabilities")
        assertEquals(0L, caps.getLong("fencingEpoch"))
        assertEquals(0, caps.getJSONArray("consumedIds").length())
        assertEquals(0, caps.getJSONArray("revokedIds").length())
        assertEquals(0, caps.getJSONObject("usage").length())
    }

    // ---- C2: restart truthfulness ----

    @Test
    fun `capability state survives engine restart`() {
        val dir = Files.createTempDirectory("sidecar-cap-restart")
        seedStore(dir)
        start(dir)
        val (_, first) = get("/v1/capabilities")

        tearDown()
        start(dir) // new engine, same data dir: the store replays the WAL
        val (status, second) = get("/v1/capabilities")

        assertEquals(200, status)
        assertEquals(canonical(first), canonical(second))
        assertEquals(
            "permit-seeded-1",
            second.getJSONObject("permits").getJSONArray("consumedIds").getString(0),
        )
    }

    // ---- C3: no mutation + malformed battery ----

    @Test
    fun `endpoint mutates nothing`() {
        val dir = Files.createTempDirectory("sidecar-cap-nomutation")
        seedStore(dir)
        start(dir)

        val hashBefore = walHash()
        val (_, first) = get("/v1/capabilities")
        val expected = canonical(first)
        repeat(25) {
            val (status, body) = get("/v1/capabilities")
            assertEquals(200, status)
            assertEquals(expected, canonical(body))
        }
        assertEquals(hashBefore, walHash())
    }

    @Test
    fun `malformed requests fail closed`() {
        val dir = Files.createTempDirectory("sidecar-cap-malformed")
        seedStore(dir)
        start(dir)

        val (postStatus, postBody) = request("POST", "/v1/capabilities")
        assertEquals(405, postStatus)
        assertTrue(postBody.has("error"))

        val (deleteStatus, _) = request("DELETE", "/v1/capabilities")
        assertEquals(405, deleteStatus)

        for (path in listOf("/v1/capabilities/", "/v1/capabilities/extra", "/v1/capabilitiesXYZ")) {
            val (status, body) = get(path)
            assertEquals(404, status, "expected 404 for $path")
            assertTrue(body.has("error"), "expected error shape for $path")
        }

        // A query string selects nothing and changes nothing: the true
        // state comes back exactly as without one.
        val (qStatus, qBody) = get("/v1/capabilities?consumedCount=99&fencingEpoch=99")
        assertEquals(200, qStatus)
        assertEquals(1, qBody.getJSONObject("permits").getInt("consumedCount"))
        assertEquals(1L, qBody.getJSONObject("capabilities").getLong("fencingEpoch"))
    }

    // ---- C4: concurrent reads ----

    @Test
    fun `concurrent reads are consistent`() {
        val dir = Files.createTempDirectory("sidecar-cap-concurrent")
        seedStore(dir)
        start(dir)

        val pool = Executors.newFixedThreadPool(32)
        try {
            val futures = (1..64).map {
                pool.submit<Pair<Int, JSONObject>> { get("/v1/capabilities") }
            }
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            val expected = canonical(results.first().second)
            for ((status, body) in results) {
                assertEquals(200, status)
                // Internally consistent: the count always matches the ids.
                val permits = body.getJSONObject("permits")
                assertEquals(
                    permits.getInt("consumedCount"),
                    permits.getJSONArray("consumedIds").length(),
                )
                assertEquals(expected, canonical(body))
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
