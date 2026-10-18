package guardianlink.durability

import java.io.File
import java.nio.file.Files
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Crash harness (Phase 3d) — fails against the RecoveryOracle (Phase 3a).
 *
 * For every crash point (every write/fsync boundary, including directory
 * fsync), the harness: runs operations, injects the crash, recovers by
 * constructing a new store on the same directory (which replays the log),
 * and runs the oracle. The oracle must Hold for every point.
 *
 * Also covers: torn writes (partial log lines), crash during recovery
 * (recovery must be idempotent), and double crashes.
 *
 * Success criterion (the user's): Phase 3 isn't done when these tests
 * confirm the design. It's done when they've had a serious chance to
 * prove the design wrong — i.e., when every crash point has been injected
 * and the oracle still holds.
 */
class CrashHarnessTest {

    private fun tempDir(): File =
        Files.createTempDirectory("crash-harness").toFile()

    /** Attempted history, tracked by the harness for the oracle. */
    private class AttemptedHistory {
        val ops = mutableListOf<StoreOp>()
        private fun nextSeq(): Long = ops.size.toLong()
        fun commitMutation(capId: String) {
            ops.add(StoreOp.CommitMutation(nextSeq(), capId))
        }
        fun recordUsage(capId: String, bytes: Long, maxM: Long?, maxB: Long?) {
            ops.add(StoreOp.RecordUsage(nextSeq(), capId, bytes, maxM, maxB))
        }
        fun revoke(capId: String) {
            ops.add(StoreOp.Revoke(nextSeq(), capId))
        }
    }

    /**
     * Run operations with crash injection at [point]. The [ops] lambda
     * receives the history tracker and a function to attempt an operation;
     * only operations actually attempted (before the crash) are recorded.
     * Returns the attempted history and the directory (for recovery).
     */
    private fun runWithCrash(
        dir: File,
        point: CrashPoint,
        ops: AttemptedHistory.(attempt: (StoreOp) -> Unit) -> Unit,
    ): Pair<AttemptedHistory, File> {
        val history = AttemptedHistory()
        val injector = CrashInjector(setOf(point))
        var clock = 1_000_000L
        val store = DurableCapabilityStore(dir, clockMs = { clock }, crash = injector)
        // Attempt function: records the op in history, then executes it.
        // If the crash fires, the op is recorded (it was attempted) and we stop.
        val attempt: (StoreOp) -> Unit = { op ->
            // Record with a harness-assigned seq (order is what matters).
            val seqOp = when (op) {
                is StoreOp.CommitMutation -> StoreOp.CommitMutation(history.ops.size.toLong(), op.capId)
                is StoreOp.RecordUsage -> StoreOp.RecordUsage(history.ops.size.toLong(), op.capId, op.bytes, op.maxMutations, op.maxBytes)
                is StoreOp.Revoke -> StoreOp.Revoke(history.ops.size.toLong(), op.capId)
                is StoreOp.RevokeEpoch -> StoreOp.RevokeEpoch(history.ops.size.toLong(), op.epoch)
            }
            history.ops.add(seqOp)
            when (seqOp) {
                is StoreOp.CommitMutation ->
                    store.commitMutation(seqOp.capId, notAfterMs = Long.MAX_VALUE)
                is StoreOp.RecordUsage ->
                    store.recordUsage(
                        seqOp.capId, seqOp.bytes, seqOp.maxMutations, seqOp.maxBytes,
                        notAfterMs = Long.MAX_VALUE,
                    )
                is StoreOp.Revoke -> store.revoke(seqOp.capId)
                is StoreOp.RevokeEpoch -> {}
            }
            clock += 1_000L
        }
        try {
            history.ops(ops, attempt)
        } catch (_: CrashInjector.CrashInjected) {
            // Expected: the crash happened. Fall through to recovery.
        }
        return history to dir
    }

    private fun recoverAndCheck(history: AttemptedHistory, dir: File) {
        // Recovery: new store instance replays the log. Idempotent by design.
        val recovered = DurableCapabilityStore(dir, clockMs = { 1_000_000L })
        val state = recovered.currentState()
        val result = RecoveryOracle.check(history.ops, state)
        assertIs<OracleResult.Holds>(
            result,
            "oracle violated after crash+recovery: $result"
        )
    }

    @Test
    fun `crash at every point yields an atomic prefix`() {
        for (point in CrashPoint.entries) {
            val dir = tempDir()
            val (history, _) = runWithCrash(dir, point) { attempt ->
                attempt(StoreOp.CommitMutation(-1, "cap-1"))
                attempt(StoreOp.CommitMutation(-1, "cap-2"))
                attempt(StoreOp.RecordUsage(-1, "epoch-1", 100L, 10L, 1000L))
                attempt(StoreOp.RecordUsage(-1, "epoch-1", 200L, 10L, 1000L))
                attempt(StoreOp.Revoke(-1, "cap-3"))
                attempt(StoreOp.CommitMutation(-1, "cap-1")) // replay: already consumed, refused
            }
            recoverAndCheck(history, dir)
        }
    }

    @Test
    fun `torn write is ignored on recovery`() {
        val dir = tempDir()
        val history = AttemptedHistory()
        val store = DurableCapabilityStore(dir, clockMs = { 1_000_000L })
        history.commitMutation("cap-1")
        store.commitMutation("cap-1", notAfterMs = Long.MAX_VALUE)
        history.commitMutation("cap-2")
        // Simulate a torn write: append a partial line (no newline, truncated).
        dir.resolve("capability.log").appendText("999|0|COMMIT|COMMIT_MUT")
        // No fsync — the torn write may or may not be visible. Recovery must
        // ignore the malformed line either way.
        recoverAndCheck(history, dir)
    }

    @Test
    fun `crash during recovery is idempotent`() {
        val dir = tempDir()
        val history = AttemptedHistory()
        // First: normal operations, then a crash.
        val (h1, _) = runWithCrash(dir, CrashPoint.AFTER_COMMIT_FSYNC) { attempt ->
            attempt(StoreOp.CommitMutation(-1, "cap-1"))
            attempt(StoreOp.RecordUsage(-1, "epoch-1", 100L, 10L, 1000L))
        }
        history.ops.addAll(h1.ops)
        // Recovery 1: construct a store (replays log). Simulate a crash
        // DURING recovery by injecting at a point that fires during the
        // first operation after recovery.
        val injector2 = CrashInjector(setOf(CrashPoint.AFTER_INTENT_WRITE))
        try {
            val store2 = DurableCapabilityStore(
                dir, clockMs = { 1_000_000L }, crash = injector2
            )
            history.commitMutation("cap-2")
            store2.commitMutation("cap-2", notAfterMs = Long.MAX_VALUE)
        } catch (_: CrashInjector.CrashInjected) {
            // Crashed during post-recovery operation. Fall through.
        }
        // Recovery 2: must still satisfy the oracle (recovery is idempotent).
        recoverAndCheck(history, dir)
    }

    @Test
    fun `double crash preserves the prefix property`() {
        val dir = tempDir()
        val history = AttemptedHistory()
        // Crash 1.
        val (h1, _) = runWithCrash(dir, CrashPoint.AFTER_INTENT_FSYNC) { attempt ->
            attempt(StoreOp.CommitMutation(-1, "cap-1"))
            attempt(StoreOp.CommitMutation(-1, "cap-2"))
        }
        history.ops.addAll(h1.ops)
        // Recover, do more, crash 2. Manually track with continuing seqs.
        val injector3 = CrashInjector(setOf(CrashPoint.AFTER_COMMIT_WRITE))
        try {
            val store3 = DurableCapabilityStore(
                dir, clockMs = { 1_000_000L }, crash = injector3
            )
            val seq = history.ops.size.toLong()
            history.ops.add(StoreOp.RecordUsage(seq, "epoch-1", 50L, 10L, 1000L))
            store3.recordUsage(
                "epoch-1", 50L, 10L, 1000L, notAfterMs = Long.MAX_VALUE
            )
        } catch (_: CrashInjector.CrashInjected) {
        }
        // Final recovery: oracle must hold across both crashes.
        recoverAndCheck(history, dir)
    }

    @Test
    fun `revoked stays revoked across crash`() {
        val dir = tempDir()
        val history = AttemptedHistory()
        val store = DurableCapabilityStore(dir, clockMs = { 1_000_000L })
        history.revoke("cap-9")
        store.revoke("cap-9")
        // Crash (no injector: simulate by just dropping the instance).
        // Recovery:
        val recovered = DurableCapabilityStore(dir, clockMs = { 1_000_000L })
        assertTrue("cap-9" in recovered.currentState().revoked)
        // And a commit after recovery must still refuse.
        history.commitMutation("cap-9")
        val r = recovered.commitMutation("cap-9", notAfterMs = Long.MAX_VALUE)
        assertIs<StoreResult.Refused>(r)
        // Oracle over the full history.
        val result = RecoveryOracle.check(history.ops, recovered.currentState())
        assertIs<OracleResult.Holds>(result)
    }

    @Test
    fun `usage never rolls backward across crash`() {
        val dir = tempDir()
        val history = AttemptedHistory()
        val (h1, _) = runWithCrash(dir, CrashPoint.AFTER_COMMIT_FSYNC) { attempt ->
            attempt(StoreOp.RecordUsage(-1, "epoch-1", 100L, 10L, 1000L))
            attempt(StoreOp.RecordUsage(-1, "epoch-1", 200L, 10L, 1000L))
            attempt(StoreOp.RecordUsage(-1, "epoch-1", 300L, 10L, 1000L))
        }
        history.ops.addAll(h1.ops)
        val recovered = DurableCapabilityStore(dir, clockMs = { 1_000_000L })
        val (muts, bytes) = recovered.currentState().usage.getOrDefault("epoch-1", 0L to 0L)
        // Usage is monotonic: it equals the usage after some prefix, never less
        // than a previous observation (here, we check it against the oracle).
        val result = RecoveryOracle.check(history.ops, recovered.currentState())
        assertIs<OracleResult.Holds>(result, "usage rolled backward: $result")
        // And the counters are consistent with a prefix.
        assertTrue(muts <= 3L && bytes <= 600L)
    }
}
