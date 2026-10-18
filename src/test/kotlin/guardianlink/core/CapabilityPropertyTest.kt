package guardianlink.core

import guardianlink.capability.CapabilityIssuer
import guardianlink.capability.CapabilityPredicate
import guardianlink.capability.CheckResult
import guardianlink.capability.EpochEffect
import guardianlink.capability.sha256Hex
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Property-based orthogonal angle for the capability mechanism.
 *
 * The falsification battery (CapabilityFalsificationTest) checks hand-picked
 * examples. These tests check INVARIANTS across generated inputs: they do
 * not depend on anyone's idea of what the test case should be, only on the
 * spec's claims holding for all inputs. This is the orthogonal angle for
 * the "test wrong" row of the verification coverage matrix — a wrong
 * mental model of an example cannot survive here because there are no
 * examples, only properties.
 *
 * Seeds are fixed for reproducibility. If a trial fails, the seed and
 * trial number identify the failing input.
 */
class CapabilityPropertyTest {

    @Test
    fun `expired mutation capability always refuses regardless of other fields`() {
        val random = Random(42)
        repeat(300) { trial ->
            var now = random.nextLong(0, 10_000_000L)
            val iss = CapabilityIssuer(clockMs = { now })
            val ttl = random.nextLong(1, 100_000L)
            val rendering = "rendering-${random.nextInt()}"
            val cap = iss.mintMutation(sha256Hex(rendering), ttlMs = ttl)
            // Advance past brutal expiry by 1ms up to ~11 days.
            now = cap.notAfterMs + random.nextLong(1, 1_000_000_000L)
            val r = iss.checkAndConsumeMutation(cap, rendering, now)
            assertIs<CheckResult.Refused>(
                r, "expired capability accepted (trial $trial, seed 42)"
            )
        }
    }

    @Test
    fun `not-yet-valid mutation capability always refuses`() {
        val random = Random(43)
        repeat(300) { trial ->
            var now = random.nextLong(1_000_000L, 10_000_000L)
            val iss = CapabilityIssuer(clockMs = { now })
            val rendering = "rendering-${random.nextInt()}"
            val cap = iss.mintMutation(sha256Hex(rendering))
            // Check at a time before notBeforeMs.
            val early = cap.notBeforeMs - random.nextLong(1, 1_000_000L)
            val r = iss.checkAndConsumeMutation(cap, rendering, early)
            assertIs<CheckResult.Refused>(
                r, "not-yet-valid capability accepted (trial $trial, seed 43)"
            )
        }
    }

    @Test
    fun `consumed mutation capability never commits again for any time or rendering`() {
        val random = Random(44)
        repeat(200) { trial ->
            var now = random.nextLong(0, 10_000_000L)
            val iss = CapabilityIssuer(clockMs = { now })
            val rendering = "rendering-${random.nextInt()}"
            val cap = iss.mintMutation(sha256Hex(rendering))
            assertIs<CheckResult.Ok>(
                iss.checkAndConsumeMutation(cap, rendering, now),
                "first commit refused (trial $trial, seed 44)"
            )
            // Every subsequent attempt refuses, no matter the time or rendering.
            repeat(5) {
                val t2 = random.nextLong(0, 20_000_000L)
                val r2 = "rendering-${random.nextInt()}"
                val r = iss.checkAndConsumeMutation(cap, r2, t2)
                assertIs<CheckResult.Refused>(
                    r, "consumed capability accepted (trial $trial, seed 44)"
                )
            }
        }
    }

    @Test
    fun `wrong rendering always refuses across generated renderings`() {
        val random = Random(45)
        repeat(300) { trial ->
            var now = random.nextLong(0, 10_000_000L)
            val iss = CapabilityIssuer(clockMs = { now })
            val rendering = "rendering-${random.nextInt()}"
            val cap = iss.mintMutation(sha256Hex(rendering))
            // A different rendering (overwhelmingly likely a different hash).
            val other = "rendering-${random.nextInt()}"
            if (other != rendering) {
                val r = iss.checkAndConsumeMutation(cap, other, now)
                assertIs<CheckResult.Refused>(
                    r, "wrong-rendering capability accepted (trial $trial, seed 45)"
                )
            }
        }
    }

    @Test
    fun `epoch usage never exceeds bounds across random effect sequences`() {
        val random = Random(46)
        repeat(150) { trial ->
            var now = 1_000_000L
            val iss = CapabilityIssuer(clockMs = { now })
            val maxMut = random.nextLong(1, 20L)
            val maxBytes = random.nextLong(100, 2000L)
            val cap = iss.mintEpoch(
                predicate = CapabilityPredicate("net/", setOf("tun-packet")),
                maxMutations = maxMut,
                maxBytes = maxBytes,
            )
            var committedBytes = 0L
            var commits = 0
            repeat(random.nextInt(1, 40)) {
                val bytes = random.nextLong(1, 500L)
                val r = iss.checkAndRecordEpoch(
                    cap, EpochEffect("net/1", "tun-packet", bytes), now
                )
                if (r is CheckResult.Ok) {
                    commits++
                    committedBytes += bytes
                }
            }
            assertTrue(
                commits <= maxMut,
                "mutation bound exceeded: $commits > $maxMut (trial $trial, seed 46)"
            )
            assertTrue(
                committedBytes <= maxBytes,
                "byte bound exceeded: $committedBytes > $maxBytes (trial $trial, seed 46)"
            )
            // The recorded usage matches the observed commits exactly.
            assertEquals(commits.toLong(), iss.usageOf(cap.id)?.first)
            assertEquals(committedBytes, iss.usageOf(cap.id)?.second)
        }
    }

    @Test
    fun `predicate decision matches across generated targets and types`() {
        val random = Random(47)
        val prefixes = listOf(null, "", "net/", "disk/", "net/10.")
        val typeSets = listOf(null, setOf("tun-packet"), setOf("write"), setOf("a", "b"))
        val targets = listOf("net/1", "net/10.0.0.1", "disk/1", "", "net", "other", "net/10.x")
        val types = listOf("tun-packet", "raw-socket", "", "write", "a")
        repeat(400) { trial ->
            var now = 1_000_000L
            val iss = CapabilityIssuer(clockMs = { now })
            val pred = CapabilityPredicate(
                targetPrefix = prefixes.random(random),
                allowedTypes = typeSets.random(random),
            )
            val cap = iss.mintEpoch(pred, maxMutations = 10_000L, maxBytes = null)
            val target = targets.random(random)
            val type = types.random(random)
            // The spec's predicate, evaluated independently of the commit path.
            val expected = pred.allows(target, type)
            val r = iss.checkAndRecordEpoch(cap, EpochEffect(target, type, 10L), now)
            if (expected) {
                assertIs<CheckResult.Ok>(
                    r, "in-predicate effect refused: '$target'/'$type' vs $pred (trial $trial)"
                )
            } else {
                assertIs<CheckResult.Refused>(
                    r, "out-of-predicate effect accepted: '$target'/'$type' vs $pred (trial $trial)"
                )
            }
        }
    }

    @Test
    fun `attenuation validity is decided correctly across generated narrowings`() {
        val random = Random(48)
        val prefixes = listOf(null, "", "net/", "net/10.", "disk/")
        val typeSets = listOf(null, setOf("tun-packet"), setOf("tun-packet", "x"))
        repeat(400) { trial ->
            var now = 1_000_000L
            val iss = CapabilityIssuer(clockMs = { now })
            val parentPred = CapabilityPredicate(
                targetPrefix = prefixes.random(random),
                allowedTypes = typeSets.random(random),
            )
            val parent = iss.mintEpoch(parentPred, maxMutations = 100L, maxBytes = 10_000L)
            val childPred = CapabilityPredicate(
                targetPrefix = prefixes.random(random),
                allowedTypes = typeSets.random(random),
            )
            // notAfter: sometimes shorter, sometimes equal, sometimes longer.
            val childNotAfter = parent.notAfterMs + random.nextLong(-5_000, 5_001)
            val child = parent.attenuateTo(childPred, notAfterMs = childNotAfter)
            // Independently compute whether the narrowing is valid.
            val predOk = parentPred.isNarrowerOrEqual(childPred)
            val timeOk = childNotAfter <= parent.notAfterMs
            val valid = predOk && timeOk
            if (valid) {
                assertTrue(
                    child != null,
                    "valid narrowing rejected: $parentPred -> $childPred (trial $trial, seed 48)"
                )
            } else {
                assertTrue(
                    child == null,
                    "invalid narrowing accepted: $parentPred -> $childPred, " +
                        "timeOk=$timeOk (trial $trial, seed 48)"
                )
            }
        }
    }
}
