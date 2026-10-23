package guardianlink.v2.core

import java.time.Instant

/** Controllable clock for tests (mirrors the TS tests' makeHarness clock). */
class TestClock(startIso: String) : V2Clock {
    var instant: Instant = Instant.parse(startIso)
    override fun now(): Instant = instant
    fun set(iso: String) {
        instant = Instant.parse(iso)
    }
}

/** A harness on a fixed clock, starting 2026-10-23T14:00:00Z unless stated. */
fun makeHarness(startIso: String = "2026-10-23T14:00:00.000Z"): Pair<GuardianLink, TestClock> {
    val clock = TestClock(startIso)
    return Pair(GuardianLink(now = clock), clock)
}
