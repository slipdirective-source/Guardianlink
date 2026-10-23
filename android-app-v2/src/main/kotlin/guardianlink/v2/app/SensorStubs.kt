package guardianlink.v2.app

import guardianlink.v2.core.AdmitInput
import guardianlink.v2.core.EventKind
import guardianlink.v2.core.GuardianLink

/**
 * Location / motion collectors: DECLARED, NOT IMPLEMENTED in this cut.
 *
 * Honesty contract (spec §4, §6.11): an unavailable source is NAMED as
 * unavailable in the standing answers. It never emits synthetic fixes,
 * never infers movement, and never lets Position present a completeness
 * it does not have. Permissions for both are already declared in the
 * manifest (ANDROID-PORT-MAP §d) so the real collectors can land behind
 * the guided setup flow without manifest churn.
 *
 * When a real collector lands, it replaces the marker below with live
 * sensor events through `core.admitSensor("location" | "motion", ...)`
 * — which also means a sovereign pause/deny of the collector takes
 * effect at that same boundary.
 */
object SensorStubs {

    /** Availability text shown in the UI; the same facts are in the ledger. */
    val unavailableCollectors: List<Pair<String, String>> = listOf(
        "location" to "location collector not implemented in the v0.2 first cut — no location data is collected",
        "motion" to "motion/activity collector not implemented in the v0.2 first cut — no motion data is collected",
    )

    /**
     * Admits one system marker event per unavailable collector, so the
     * standing answers (Position renders collector lines) state the
     * limitation where the user can see it.
     */
    fun markUnavailable(core: GuardianLink) {
        for ((name, reason) in unavailableCollectors) {
            core.admit(
                AdmitInput(
                    kind = EventKind.SYSTEM,
                    topic = "collector.$name",
                    claim = "unavailable in this build: $reason",
                    source = "system",
                    confidence = 1.0,
                ),
            )
        }
    }
}
