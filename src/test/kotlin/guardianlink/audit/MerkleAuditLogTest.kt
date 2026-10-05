package guardianlink.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MerkleAuditLogTest {
    private val log = MerkleAuditLog()

    @Test
    fun testAppendEntry() {
        val entry = log.append("test data".toByteArray())
        assertEquals(0, entry.index)
        assertEquals(1, log.size)
        assertTrue(entry.payloadHash.isNotEmpty())
    }

    @Test
    fun testLogSize() {
        assertEquals(0, log.size)
        log.append("entry 1".toByteArray())
        assertEquals(1, log.size)
        log.append("entry 2".toByteArray())
        assertEquals(2, log.size)
    }

    @Test
    fun testRootChangesOnAppend() {
        val root1 = log.root
        log.append("first".toByteArray())
        val root2 = log.root
        assertNotEquals(root1, root2)
        
        log.append("second".toByteArray())
        val root3 = log.root
        assertNotEquals(root2, root3)
    }

    @Test
    fun testIntegrityCheck() {
        log.append("entry 1".toByteArray())
        log.append("entry 2".toByteArray())
        log.append("entry 3".toByteArray())
        
        val failIndex = log.verifyIntegrity()
        assertEquals(-1, failIndex)  // -1 means all good
    }

    @Test
    fun testRootAtCheckpoint() {
        log.append("a".toByteArray())
        val root0 = log.rootAtCheckpoint(0)
        
        log.append("b".toByteArray())
        val root1 = log.rootAtCheckpoint(1)
        
        assertNotEquals(root0, root1)
    }

    @Test
    fun testConcurrentAppends() {
        val threads = (0..9).map { i ->
            Thread {
                log.append("thread-$i".toByteArray())
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        assertEquals(10, log.size)
    }

    @Test
    fun testExternalAnchorCallback() {
        val anchors = mutableListOf<Pair<String, Int>>()
        val logWithAnchor = MerkleAuditLog { root, index ->
            anchors.add(Pair(root, index))
        }

        logWithAnchor.append("first".toByteArray())
        logWithAnchor.append("second".toByteArray())

        assertEquals(2, anchors.size)
        assertEquals(0, anchors[0].second)
        assertEquals(1, anchors[1].second)
    }

    // ------------------------------------------------------------------
    // Hardening regressions (adversarial review).
    // ------------------------------------------------------------------

    @Test
    fun testVerifyIntegrityDetectsGenesisLinkageBreak() {
        log.append("a".toByteArray())
        tamperEntry(0) { it.copy(previousRoot = "00".repeat(32)) }
        assertEquals(0, log.verifyIntegrity(), "entry 0 genesis linkage break not detected")
    }

    @Test
    fun testVerifyIntegrityDetectsPayloadTampering() {
        log.append("a".toByteArray())
        log.append("b".toByteArray())
        log.append("c".toByteArray())
        tamperEntry(1) { it.copy(payloadHash = "ff".repeat(32)) }
        assertEquals(1, log.verifyIntegrity(), "payload tampering at index 1 not detected")
    }

    @Test
    fun testVerifyIntegrityDetectsChainingBreak() {
        log.append("a".toByteArray())
        log.append("b".toByteArray())
        // Rewire entry 1 to point at genesis instead of checkpoint 0.
        val genesis = MerkleAuditLog().root
        tamperEntry(1) { it.copy(previousRoot = genesis) }
        assertEquals(1, log.verifyIntegrity(), "chain rewiring not detected")
    }

    @Test
    fun testVerifyIntegrityDetectsCurrentRootTampering() {
        log.append("a".toByteArray())
        log.append("b".toByteArray())
        setCurrentRoot("00".repeat(32))
        // All entries well-formed, but the in-memory root was swapped.
        assertEquals(log.size, log.verifyIntegrity(), "currentRoot tampering not detected")
    }

    @Test
    fun testAnchorFiresOncePerAppendInIndexOrderWithTrueRoots() {
        val seen = mutableListOf<Pair<Int, String>>()
        lateinit var reentrant: MerkleAuditLog
        var nested = false
        reentrant = MerkleAuditLog { root, index ->
            seen.add(index to root)
            if (!nested) {
                nested = true
                reentrant.append("nested".toByteArray()) // reentrant append from the callback
            }
        }
        reentrant.append("outer".toByteArray())
        assertEquals(listOf(0, 1), seen.map { it.first }, "anchor calls out of order or duplicated")
        for ((index, root) in seen) {
            assertEquals(reentrant.rootAtCheckpoint(index), root, "anchor($index) got a stale root")
        }
    }

    @Test
    fun testAnchorExceptionPropagatesButEntryIsRecorded() {
        val anchored = MerkleAuditLog { _, _ -> throw RuntimeException("anchor down") }
        try {
            anchored.append("x".toByteArray())
            kotlin.test.fail("anchor exception should propagate")
        } catch (_: RuntimeException) {
        }
        assertEquals(1, anchored.size, "entry lost when anchor threw")
        assertEquals(-1, anchored.verifyIntegrity())
    }

    @Test
    fun testGenesisRootIsStableGolden() {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest("GUARDIANLINK-MERKLE/GENESIS/v1".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MerkleAuditLog().root)
    }

    @Test
    fun testRandomAppendsAlwaysVerify() {
        val rng = kotlin.random.Random(0x5EED)
        repeat(50) { log.append(ByteArray(rng.nextInt(1, 64)) { rng.nextInt(256).toByte() }) }
        assertEquals(-1, log.verifyIntegrity())
        assertEquals(50, log.size)
    }

    private fun tamperEntry(i: Int, f: (MerkleAuditLog.Entry) -> MerkleAuditLog.Entry) {
        val field = MerkleAuditLog::class.java.getDeclaredField("entries")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val list = field.get(log) as MutableList<MerkleAuditLog.Entry>
        list[i] = f(list[i])
    }

    private fun setCurrentRoot(v: String) {
        val field = MerkleAuditLog::class.java.getDeclaredField("currentRoot")
        field.isAccessible = true
        field.set(log, v)
    }
}
