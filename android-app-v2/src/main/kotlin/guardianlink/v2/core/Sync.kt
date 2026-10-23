package guardianlink.v2.core

/** Port of TS `SyncBundle` in `src/sync.ts`. */
data class SyncBundle(
    val format: String,
    val deviceId: String,
    val exportedAt: String,
    val events: List<EvidenceEvent>,
    /** root commitment over the sorted event hashes in this bundle */
    val root: String,
) {
    /**
     * Canonical JSON rendering of the bundle (write side of the file
     * transport the TS core uses). Event fields are emitted in the TS
     * shape; [importBundle] in this port consumes the bundle object —
     * a full JSON parser for foreign bundles is deliberately NOT in
     * this cut (see module README): importing untrusted text requires
     * the strict-parser treatment, not a lenient one.
     */
    fun toCanonicalJson(): String {
        val eventMaps = events.map { e ->
            val m = LinkedHashMap<String, Any?>(e.bodyMap())
            m["hash"] = e.hash
            m
        }
        return Canonical.stringify(
            mapOf(
                "format" to format,
                "deviceId" to deviceId,
                "exportedAt" to exportedAt,
                "events" to eventMaps,
                "root" to root,
            ),
        )
    }
}

/** Port of TS `ImportResult`. [conflicts]: same event id, different content hash — BOTH versions are preserved. */
data class SyncConflict(val id: String, val localHash: String, val foreignHash: String)

data class ImportResult(
    val added: Int,
    val duplicates: Int,
    val conflicts: List<SyncConflict>,
    val rootVerified: Boolean,
    val chainVerified: Boolean,
)

/**
 * Sync (spec §3.9) over a replaceable transport. Port of TS `src/sync.ts`.
 * Export produces a hash-committed bundle; import verifies root and
 * chain before merging anything. Merge is idempotent (re-import adds
 * nothing) and conflict-preserving (nothing is silently overwritten).
 */
class SyncService(
    private val ledger: Ledger,
    private val audit: AuditLog,
    private val deviceId: String,
) {
    fun exportBundle(): SyncBundle {
        val events = ledger.events()
        val bundle = SyncBundle(
            format = "gl-sync-1",
            deviceId = deviceId,
            exportedAt = IsoTime.nowIso(),
            events = events,
            root = Ledger.rootOf(events),
        )
        audit.record("sync", "sync_export", mapOf("deviceId" to deviceId, "count" to events.size, "root" to bundle.root))
        return bundle
    }

    fun importBundle(bundle: SyncBundle): ImportResult {
        if (bundle.format != "gl-sync-1") {
            throw IllegalStateException("unsupported sync bundle format \"${bundle.format}\"")
        }
        val rootVerified = Ledger.rootOf(bundle.events) == bundle.root
        val chainVerified = Ledger.verifyForeignChain(bundle.events.sortedBy { it.seq })
        if (!rootVerified || !chainVerified) {
            audit.record(
                "sync",
                "sync_import_rejected",
                mapOf("from" to bundle.deviceId, "rootVerified" to rootVerified, "chainVerified" to chainVerified),
            )
            throw IllegalStateException(
                "sync bundle from ${bundle.deviceId} failed verification (root=$rootVerified, chain=$chainVerified); nothing imported",
            )
        }
        var added = 0
        var duplicates = 0
        val conflicts = mutableListOf<SyncConflict>()
        val localById = ledger.events().associateBy { it.id }
        for (e in bundle.events) {
            if (ledger.hasHash(e.hash)) {
                duplicates += 1
                continue
            }
            val local = localById[e.id]
            if (local != null && local.hash != e.hash) {
                conflicts.add(SyncConflict(e.id, local.hash, e.hash))
            }
            if (ledger.addForeign(e)) added += 1 else duplicates += 1
        }
        if (added > 0 || conflicts.isNotEmpty()) {
            ledger.admit(
                AdmitInput(
                    kind = EventKind.SYSTEM,
                    topic = "sync",
                    claim = "Sync import from ${bundle.deviceId}: $added new events, $duplicates duplicates, ${conflicts.size} conflicts preserved.",
                    source = "sync",
                    confidence = 1.0,
                ),
            )
        }
        audit.record(
            "sync",
            "sync_import",
            mapOf(
                "from" to bundle.deviceId,
                "added" to added,
                "duplicates" to duplicates,
                "conflicts" to conflicts.size,
                "rootVerified" to rootVerified,
                "chainVerified" to chainVerified,
            ),
        )
        return ImportResult(added, duplicates, conflicts, rootVerified, chainVerified)
    }
}
