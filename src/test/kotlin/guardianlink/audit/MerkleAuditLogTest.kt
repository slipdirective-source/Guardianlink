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
}
