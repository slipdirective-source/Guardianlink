package guardianlink.v2.core

/** Port of TS `AuditEntry` in `src/audit.ts`. */
data class AuditEntry(
    val seq: Int,
    val at: String,
    val actor: String,
    val action: String,
    val details: Map<String, Any?>,
    val prevHash: String,
    val hash: String,
) {
    fun bodyMap(): Map<String, Any?> = mapOf(
        "seq" to seq,
        "at" to at,
        "actor" to actor,
        "action" to action,
        "details" to details,
        "prevHash" to prevHash,
    )
}

/**
 * Tamper-evident audit log (spec §3.10). Port of TS `src/audit.ts`.
 * Separate hash chain from the evidence ledger: the ledger records what
 * is claimed; the audit records what the harness did — admissions,
 * proposals, policy decisions, assent, execution, outcomes,
 * plug/account/sync/collector changes.
 */
class AuditLog(
    private val now: V2Clock = V2Clock { java.time.Instant.now() },
) {
    private val entries = mutableListOf<AuditEntry>()

    fun record(actor: String, action: String, details: Map<String, Any?> = emptyMap()): AuditEntry {
        val prevHash = if (entries.isNotEmpty()) entries[entries.size - 1].hash else GENESIS
        val body = mapOf<String, Any?>(
            "seq" to entries.size + 1,
            "at" to IsoTime.format(now.now()),
            "actor" to actor,
            "action" to action,
            "details" to details,
            "prevHash" to prevHash,
        )
        val entry = AuditEntry(
            seq = entries.size + 1,
            at = body["at"] as String,
            actor = actor,
            action = action,
            details = details,
            prevHash = prevHash,
            hash = Canonical.sha256Hex(prevHash + "|" + Canonical.stringify(body)),
        )
        entries.add(entry)
        return entry
    }

    fun all(): List<AuditEntry> = entries.toList()

    fun query(actor: String? = null, action: String? = null, since: String? = null): List<AuditEntry> =
        entries.filter { e ->
            (actor == null || e.actor == actor) &&
                (action == null || e.action == action) &&
                (since == null || e.at >= since)
        }

    fun verify(): Boolean {
        var prev = GENESIS
        for (e in entries) {
            if (e.prevHash != prev) return false
            if (Canonical.sha256Hex(e.prevHash + "|" + Canonical.stringify(e.bodyMap())) != e.hash) return false
            prev = e.hash
        }
        return true
    }

    fun rootHash(): String = if (entries.isNotEmpty()) entries[entries.size - 1].hash else GENESIS

    /**
     * Persistence hook for [StateStore]: replace the log's entries with
     * a previously serialized set. The chain is verified BEFORE anything
     * is replaced — a set that does not verify is refused whole, so a
     * failed restore leaves the current log untouched.
     */
    fun loadPersistedEntries(restored: List<AuditEntry>) {
        var prev = GENESIS
        for (e in restored) {
            require(e.prevHash == prev) { "persisted audit chain broken at seq ${e.seq}" }
            require(Canonical.sha256Hex(e.prevHash + "|" + Canonical.stringify(e.bodyMap())) == e.hash) {
                "persisted audit entry seq ${e.seq} failed hash verification"
            }
            prev = e.hash
        }
        entries.clear()
        entries.addAll(restored)
    }

    companion object {
        val GENESIS: String = "0".repeat(64)
    }
}
