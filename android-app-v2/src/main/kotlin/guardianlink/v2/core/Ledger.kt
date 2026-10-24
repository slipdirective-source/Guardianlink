package guardianlink.v2.core

/**
 * Append-only evidence ledger (spec §3.2). Port of TS `src/ledger.ts`.
 *
 * Every event commits to the previous event's SHA-256 hash. There is no
 * update or delete API: corrections and retractions are new events bound
 * to prior events by relations. Foreign (synced) events keep their origin
 * chain and are verified against their bundle root; they are visible to
 * readers but never rewrite the local chain.
 *
 * Not thread-safe (same contract as the TS core): callers serialize —
 * the Android layer does so on an app-level lock.
 */
class Ledger(
    val deviceId: String = "sovereign-device",
    private val now: V2Clock = V2Clock { java.time.Instant.now() },
) {
    private val chain = mutableListOf<EvidenceEvent>()
    private val foreign = LinkedHashMap<String, EvidenceEvent>() // keyed by hash
    private var seq = 0
    private val listeners = mutableListOf<(EvidenceEvent) -> Unit>()

    fun onAdmit(fn: (EvidenceEvent) -> Unit) {
        listeners.add(fn)
    }

    fun admit(input: AdmitInput): EvidenceEvent {
        seq += 1
        val prevHash = if (chain.isNotEmpty()) chain[chain.size - 1].hash else GENESIS_HASH
        val assertedAt = input.assertedAt ?: IsoTime.format(now.now())
        val body = mapOf<String, Any?>(
            "id" to "$deviceId-evt-${seq.toString().padStart(6, '0')}",
            "seq" to seq,
            "kind" to input.kind.wire,
            "topic" to input.topic,
            "claim" to input.claim,
            "polarity" to input.polarity.wire,
            "source" to input.source,
            "assertedAt" to assertedAt,
            "observedAt" to (input.observedAt ?: assertedAt),
            "confidence" to input.confidence,
            "relations" to input.relations.map { it.toCanonicalMap() },
            "agentDerived" to (input.agentDerived ?: (input.kind == EventKind.AGENT_DERIVED)),
            "prevHash" to prevHash,
        )
        val event = EvidenceEvent(
            id = body["id"] as String,
            seq = seq,
            kind = input.kind,
            topic = input.topic,
            claim = input.claim,
            polarity = input.polarity,
            source = input.source,
            assertedAt = assertedAt,
            observedAt = input.observedAt ?: assertedAt,
            confidence = input.confidence,
            relations = input.relations,
            agentDerived = input.agentDerived ?: (input.kind == EventKind.AGENT_DERIVED),
            prevHash = prevHash,
            hash = EvidenceEvent.computeHash(prevHash, body),
        )
        chain.add(event)
        for (fn in listeners) fn(event)
        return event
    }

    /** Local chain events, in order. */
    fun events(): List<EvidenceEvent> = chain.toList()

    /** Local chain + imported foreign events (deduped by hash). This is what evaluators read. */
    fun allEvents(): List<EvidenceEvent> {
        val byId = LinkedHashMap<String, MutableList<EvidenceEvent>>()
        for (e in chain + foreign.values) {
            byId.getOrPut(e.id) { mutableListOf() }.add(e)
        }
        val out = mutableListOf<EvidenceEvent>()
        for (list in byId.values) out.addAll(list)
        return out.sortedWith(compareBy<EvidenceEvent> { it.seq }.thenBy { it.hash })
    }

    fun get(id: String): EvidenceEvent? = allEvents().firstOrNull { it.id == id }

    /** True when some later event retracts the given event. Retracted events stay in the ledger. */
    fun isRetracted(id: String): Boolean =
        allEvents().any { e -> e.relations.any { r -> r.type == RelationType.RETRACTS && r.targetId == id } }

    fun isSuperseded(id: String): Boolean =
        allEvents().any { e -> e.relations.any { r -> r.type == RelationType.SUPERSEDES && r.targetId == id } }

    /** Events still in force: not retracted, not superseded. */
    fun liveEvents(): List<EvidenceEvent> =
        allEvents().filter { e -> !isRetracted(e.id) && !isSuperseded(e.id) }

    fun headHash(): String = if (chain.isNotEmpty()) chain[chain.size - 1].hash else GENESIS_HASH

    fun addForeign(e: EvidenceEvent): Boolean {
        if (foreign.containsKey(e.hash)) return false
        if (chain.any { c -> c.hash == e.hash }) return false
        foreign[e.hash] = e
        for (fn in listeners) fn(e)
        return true
    }

    fun foreignEvents(): List<EvidenceEvent> = foreign.values.toList()

    /**
     * Persistence hook for [StateStore]: replace the ledger's contents
     * with a previously serialized state. This does NOT fire admission
     * listeners — a restore is not a series of new admissions, and it
     * must not mint audit entries or recompute answers as a side effect
     * (the persisted question state is restored alongside, by StateStore).
     *
     * The caller (StateStore) verifies before calling; this function
     * re-verifies anyway and refuses rather than loading a broken chain:
     * the local chain must verify end-to-end, and every foreign event
     * must be self-consistent (its stored hash equals its recomputed
     * hash — foreign events arrive from multiple origin chains, so no
     * single-chain check applies to the set).
     */
    fun loadPersistedState(chainEvents: List<EvidenceEvent>, foreignEvents: List<EvidenceEvent>) {
        require(verifyChainOf(chainEvents)) { "persisted ledger chain failed verification" }
        for (e in foreignEvents) {
            require(EvidenceEvent.computeHash(e.prevHash, e.bodyMap()) == e.hash) {
                "persisted foreign event ${e.id} failed hash self-consistency"
            }
        }
        chain.clear()
        chain.addAll(chainEvents)
        foreign.clear()
        for (e in foreignEvents) foreign[e.hash] = e
        seq = if (chainEvents.isNotEmpty()) chainEvents[chainEvents.size - 1].seq else 0
    }

    fun hasHash(hash: String): Boolean = foreign.containsKey(hash) || chain.any { e -> e.hash == hash }

    /** Recompute every local hash and link. Detects any mutation of stored events. */
    fun verifyChain(events: List<EvidenceEvent> = chain): Boolean = verifyChainOf(events)

    companion object {
        val GENESIS_HASH: String = "0".repeat(64)

        /** Root commitment over a set of event hashes (used for sync bundles). */
        fun rootOf(events: List<EvidenceEvent>): String {
            val hashes = events.map { it.hash }.sorted()
            return Canonical.sha256Hex(hashes.joinToString("|"))
        }

        fun verifyChainOf(events: List<EvidenceEvent>): Boolean {
            var prev = GENESIS_HASH
            for (e in events) {
                if (e.prevHash != prev) return false
                if (EvidenceEvent.computeHash(e.prevHash, e.bodyMap()) != e.hash) return false
                prev = e.hash
            }
            return true
        }

        /** Verify a foreign event list as its own chain (for sync import). */
        fun verifyForeignChain(events: List<EvidenceEvent>): Boolean = verifyChainOf(events)
    }
}
