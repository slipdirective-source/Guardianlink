package guardianlink.v2.core

import guardianlink.v2.core.zk.RailZkParam
import guardianlink.v2.core.zk.RailZkRecord
import guardianlink.v2.core.zk.UnavailableZkBridge
import guardianlink.v2.core.zk.ZkParamDescriptor
import guardianlink.v2.core.zk.ZkProverBridge
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Named failure kinds for state-store loads. A corrupt store is one of these — never an unnamed surprise. */
enum class StateStoreError {
    /** The stored bytes are not well-formed JSON, or end mid-document (the interrupted-write shape). */
    PARSE_ERROR,

    /** The document parsed but is not a format/version this build can load. */
    UNSUPPORTED_FORMAT,

    /** The evidence ledger's hash chain (links or recomputed hashes) does not verify. */
    LEDGER_CHAIN_INVALID,

    /** A foreign (synced) event's stored hash does not equal its recomputed hash. */
    EVENT_HASH_MISMATCH,

    /** The audit log's hash chain does not verify. */
    AUDIT_CHAIN_INVALID,

    /** The document is well-formed and its chains verify, but its parts contradict each other. */
    INCONSISTENT_STATE,

    /** The storage layer itself failed to deliver bytes. */
    STORAGE_READ_FAILED,
}

/** Fail-closed state-store error: [kind] names exactly what was wrong. */
class StateStoreException(val kind: StateStoreError, message: String) : Exception(message)

/**
 * Storage interface for the persisted harness state (spec §3.7/§3.10;
 * ANDROID-PORT-MAP §f risks 2 and 6 — the durable store this cut chose).
 *
 * Bytes at this interface are always the PLAINTEXT state document.
 * Encryption, where wanted, lives in the implementation (the Android
 * layer's EncryptedStateStorage) — the store logic never changes.
 *
 * Write contract — atomic replacement: a completed [write] either
 * replaces the stored bytes wholly or leaves the previous bytes
 * untouched. A crash mid-write must never produce a torn document under
 * [name]; implementations achieve this with write-to-temp-then-rename
 * (file store) or an equivalent slot mechanism (encrypted store, whose
 * ciphertext is bound to its file name and cannot be renamed).
 */
interface StateStorage {
    /** The stored bytes for [name], or null when nothing is stored under it. */
    fun read(name: String): ByteArray?

    /** Atomically replace the bytes stored under [name]. */
    fun write(name: String, bytes: ByteArray)

    /** Names with stored content (never temp/partial artifacts). */
    fun list(): List<String>
}

/** In-memory [StateStorage] for tests. A write is a single map put — atomic by construction. */
class InMemoryStateStorage : StateStorage {
    private val blobs = LinkedHashMap<String, ByteArray>()

    override fun read(name: String): ByteArray? = blobs[name]?.copyOf()

    override fun write(name: String, bytes: ByteArray) {
        blobs[name] = bytes.copyOf()
    }

    override fun list(): List<String> = blobs.keys.toList()
}

/**
 * File [StateStorage] over plain `java.io` (still no `android.*`
 * imports — the core stays JVM-testable). Each name is one file in
 * [directory]; writes go to `<name>.tmp`, are flushed and fsynced, then
 * renamed over the target (atomic on the filesystems Android and the
 * JVM tests run on), and the directory is fsynced best-effort so the
 * rename itself is durable. A leftover `.tmp` file is the visible
 * remnant of an interrupted write: it is never read and never listed —
 * the previous good document remains the state.
 */
class FileStateStorage(private val directory: File) : StateStorage {

    init {
        directory.mkdirs()
    }

    private fun target(name: String): File {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
            "invalid state name \"$name\""
        }
        return File(directory, name)
    }

    override fun read(name: String): ByteArray? {
        val f = target(name)
        if (!f.isFile) return null
        return try {
            f.readBytes()
        } catch (e: IOException) {
            throw StateStoreException(StateStoreError.STORAGE_READ_FAILED, "could not read state \"$name\": ${e.message}")
        }
    }

    override fun write(name: String, bytes: ByteArray) {
        val dest = target(name)
        val tmp = File(directory, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Best-effort directory fsync so the rename survives a power cut.
        runCatching {
            java.io.RandomAccessFile(directory, "r").use { it.channel.force(true) }
        }
    }

    override fun list(): List<String> =
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.name }
            .sorted()
}

/**
 * Harness persistence (spec §3.2/§3.3/§3.7/§3.10): serialize the FULL
 * core state — ledger events (local chain + foreign), audit entries,
 * standing-question state including every answer version in history,
 * collector/plug/account registries, rails and proposals — to one
 * canonical-JSON document, and restore it with both hash chains
 * verified before any of it is trusted.
 *
 * Format `gl-state-1`, and how it relates to [SyncBundle]: the EVENT
 * encoding is shared — an event serializes as its hashed body map plus
 * its `hash`, byte-for-byte the shape [SyncBundle.toCanonicalJson]
 * emits, and the document carries the same root commitment
 * ([Ledger.rootOf]) over the local chain. The DIFFERENCE is scope, and
 * it is deliberate: a sync bundle exports EVIDENCE for another device
 * to merge; a state document additionally carries harness-internal
 * state (audit chain, question snapshots, registries, rails, proposals)
 * that must be restored exactly on THIS device and never merged into
 * another device's internals. Same evidence encoding, different trust
 * job — one format for events, two documents.
 *
 * Crash-recovery semantics:
 *  - save: serialize fully in memory, then one atomic [StateStorage]
 *    write. There is no incremental write path.
 *  - load: parse (strict), check format, verify the ledger chain,
 *    verify every foreign event's hash, verify the audit chain, check
 *    cross-references (roots, sequence contiguity) — and only then
 *    build the harness. Any failure throws [StateStoreException] with a
 *    named kind and loads NOTHING: no partial state, no silent reset.
 *    A failed load never writes to the storage.
 */
object StateStore {

    const val FORMAT = "gl-state-1"
    const val VERSION = 1

    /* ---------------- save ---------------- */

    fun serialize(gl: GuardianLink): String {
        val doc = mapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "deviceId" to gl.ledger.deviceId,
            "ledgerRoot" to Ledger.rootOf(gl.ledger.events()),
            "auditRoot" to gl.audit.rootHash(),
            "ledger" to mapOf(
                "events" to gl.ledger.events().map { eventToMap(it) },
                "foreign" to gl.ledger.foreignEvents().map { eventToMap(it) },
            ),
            "audit" to mapOf(
                "entries" to gl.audit.all().map { entryToMap(it) },
            ),
            "questions" to gl.questions.all().map { questionToMap(it) },
            "collectors" to gl.collectors.all().map { collectorToMap(it) },
            "plugs" to gl.plugs.all().map { plugToMap(it) },
            "accounts" to gl.accounts.all().map { accountToMap(it) },
            "actions" to mapOf(
                "railSeq" to gl.actions.railSeqValue(),
                "rails" to gl.actions.listRails().map { railToMap(it) },
                "proposals" to gl.actions.allProposals().map { proposalToMap(it) },
            ),
        )
        // Identity continuity (EVIDENCE-PATH key custody): the engine
        // key ids this state binds to. Additive and OPTIONAL — emitted
        // only once custody exists, so pre-custody documents serialize
        // byte-identically to before, and old documents load with null
        // ids (binds nothing; the app records the ids at first bind).
        val identity = LinkedHashMap<String, Any?>()
        if (gl.operatorKeyId != null || gl.revocationAuthorityKeyId != null) {
            identity["operatorKeyId"] = gl.operatorKeyId
            identity["revocationAuthorityKeyId"] = gl.revocationAuthorityKeyId
        }
        val full = if (identity.isEmpty()) doc else doc + ("identity" to identity)
        return Canonical.stringify(full)
    }

    fun save(gl: GuardianLink, storage: StateStorage, name: String) {
        storage.write(name, serialize(gl).toByteArray(Charsets.UTF_8))
    }

    /* ---------------- load ---------------- */

    /** Load and verify the state stored under [name]; null when nothing is stored there. */
    fun load(
        storage: StateStorage,
        name: String,
        now: V2Clock = V2Clock { java.time.Instant.now() },
        zkBridge: ZkProverBridge = UnavailableZkBridge,
    ): GuardianLink? {
        val bytes = try {
            storage.read(name)
        } catch (e: StateStoreException) {
            throw e
        } catch (e: Exception) {
            throw StateStoreException(StateStoreError.STORAGE_READ_FAILED, "could not read state \"$name\": ${e.message}")
        }
        if (bytes == null) return null
        return deserialize(String(bytes, Charsets.UTF_8), now, zkBridge)
    }

    fun deserialize(
        text: String,
        now: V2Clock = V2Clock { java.time.Instant.now() },
        zkBridge: ZkProverBridge = UnavailableZkBridge,
    ): GuardianLink {
        val root = JsonParser.parse(text)
        val doc = asObject(root, "state document")

        val format = reqString(doc, "format")
        if (format != FORMAT) {
            throw StateStoreException(StateStoreError.UNSUPPORTED_FORMAT, "unsupported state format \"$format\" (this build reads \"$FORMAT\")")
        }
        val version = reqInt(doc, "version")
        if (version != VERSION) {
            throw StateStoreException(StateStoreError.UNSUPPORTED_FORMAT, "unsupported state version $version (this build reads $VERSION)")
        }
        val deviceId = reqString(doc, "deviceId")

        /* --- ledger: verify BEFORE anything is built --- */
        val ledgerDoc = reqObject(doc, "ledger")
        val chainEvents = reqList(ledgerDoc, "events").mapIndexed { i, raw -> eventFromMap(asObject(raw, "ledger event $i")) }
        val foreignEvents = reqList(ledgerDoc, "foreign").mapIndexed { i, raw -> eventFromMap(asObject(raw, "foreign event $i")) }
        for ((index, e) in chainEvents.withIndex()) {
            if (e.seq != index + 1) {
                throw StateStoreException(
                    StateStoreError.INCONSISTENT_STATE,
                    "ledger event sequence is not contiguous: position ${index + 1} holds seq ${e.seq}",
                )
            }
        }
        if (!Ledger.verifyChainOf(chainEvents)) {
            throw StateStoreException(StateStoreError.LEDGER_CHAIN_INVALID, "ledger hash chain failed verification; state not loaded")
        }
        for (e in foreignEvents) {
            if (EvidenceEvent.computeHash(e.prevHash, e.bodyMap()) != e.hash) {
                throw StateStoreException(
                    StateStoreError.EVENT_HASH_MISMATCH,
                    "foreign event ${e.id} failed hash self-consistency; state not loaded",
                )
            }
        }
        val storedRoot = reqString(doc, "ledgerRoot")
        if (Ledger.rootOf(chainEvents) != storedRoot) {
            throw StateStoreException(StateStoreError.INCONSISTENT_STATE, "ledger root commitment does not match the stored events")
        }

        /* --- audit: verify on a scratch log first --- */
        val auditDoc = reqObject(doc, "audit")
        val entries = reqList(auditDoc, "entries").mapIndexed { i, raw -> entryFromMap(asObject(raw, "audit entry $i")) }
        for ((index, e) in entries.withIndex()) {
            if (e.seq != index + 1) {
                throw StateStoreException(StateStoreError.INCONSISTENT_STATE, "audit entry sequence is not contiguous at position ${index + 1}")
            }
        }
        val scratch = AuditLog(now)
        try {
            scratch.loadPersistedEntries(entries)
        } catch (e: IllegalArgumentException) {
            throw StateStoreException(StateStoreError.AUDIT_CHAIN_INVALID, "audit hash chain failed verification: ${e.message}")
        }
        if (scratch.rootHash() != reqString(doc, "auditRoot")) {
            throw StateStoreException(StateStoreError.INCONSISTENT_STATE, "audit root commitment does not match the stored entries")
        }

        /* --- registries, rails, proposals, questions: shape-checked, then injected --- */
        val collectors = reqList(doc, "collectors").mapIndexed { i, raw -> collectorFromMap(asObject(raw, "collector $i")) }
        val plugs = reqList(doc, "plugs").mapIndexed { i, raw -> plugFromMap(asObject(raw, "plug $i")) }
        val accounts = reqList(doc, "accounts").mapIndexed { i, raw -> accountFromMap(asObject(raw, "account $i")) }
        val actionsDoc = reqObject(doc, "actions")
        val railSeq = reqInt(actionsDoc, "railSeq")
        val rails = reqList(actionsDoc, "rails").mapIndexed { i, raw -> railFromMap(asObject(raw, "rail $i")) }
        val proposals = reqList(actionsDoc, "proposals").mapIndexed { i, raw -> proposalFromMap(asObject(raw, "proposal $i")) }
        val questionStates = reqList(doc, "questions").mapIndexed { i, raw -> questionFromMap(asObject(raw, "question $i")) }

        // Identity section is optional (pre-custody documents lack it).
        val identityDoc = doc["identity"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val restoredOperatorKeyId = (identityDoc as? Map<String, Any?>)?.get("operatorKeyId") as? String
        @Suppress("UNCHECKED_CAST")
        val restoredRevocationKeyId = (identityDoc as? Map<String, Any?>)?.get("revocationAuthorityKeyId") as? String

        val gl = GuardianLink(now = now, deviceId = deviceId, zkBridge = zkBridge)
        gl.operatorKeyId = restoredOperatorKeyId
        gl.revocationAuthorityKeyId = restoredRevocationKeyId
        try {
            gl.ledger.loadPersistedState(chainEvents, foreignEvents)
            gl.audit.loadPersistedEntries(entries)
            gl.collectors.loadPersistedState(collectors)
            gl.plugs.loadPersistedState(plugs)
            gl.accounts.loadPersistedState(accounts)
            gl.actions.loadPersistedState(rails, proposals, railSeq)
            for (qs in questionStates) {
                gl.questions.restoreQuestionState(
                    id = qs.id,
                    text = qs.text,
                    cluster = qs.cluster,
                    topicPrefixes = qs.topicPrefixes,
                    matchTerms = qs.matchTerms,
                    priority = qs.priority,
                    current = qs.current,
                    history = qs.history,
                    stale = qs.stale,
                    recomputeCount = qs.recomputeCount,
                )
            }
        } catch (e: IllegalArgumentException) {
            throw StateStoreException(StateStoreError.INCONSISTENT_STATE, "verified state could not be restored: ${e.message}")
        }

        // Trust, but verify the assembled harness end to end.
        if (!gl.ledger.verifyChain() || !gl.audit.verify()) {
            throw StateStoreException(StateStoreError.INCONSISTENT_STATE, "restored harness failed post-load integrity verification")
        }
        return gl
    }

    /* ---------------- event / entry encoding (shared with SyncBundle's shape) ---------------- */

    private fun eventToMap(e: EvidenceEvent): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(e.bodyMap())
        m["hash"] = e.hash
        return m
    }

    private fun entryToMap(e: AuditEntry): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(e.bodyMap())
        m["hash"] = e.hash
        return m
    }

    private fun eventFromMap(m: Map<String, Any?>): EvidenceEvent = EvidenceEvent(
        id = reqString(m, "id"),
        seq = reqInt(m, "seq"),
        kind = EventKind.fromWire(reqString(m, "kind")),
        topic = reqString(m, "topic"),
        claim = reqString(m, "claim"),
        polarity = Polarity.fromWire(reqString(m, "polarity")),
        source = reqString(m, "source"),
        assertedAt = reqString(m, "assertedAt"),
        observedAt = reqString(m, "observedAt"),
        confidence = reqDouble(m, "confidence"),
        relations = reqList(m, "relations").map { raw ->
            val r = asObject(raw, "relation")
            Relation(RelationType.fromWire(reqString(r, "type")), reqString(r, "targetId"))
        },
        agentDerived = reqBool(m, "agentDerived"),
        prevHash = reqString(m, "prevHash"),
        hash = reqString(m, "hash"),
    )

    private fun entryFromMap(m: Map<String, Any?>): AuditEntry = AuditEntry(
        seq = reqInt(m, "seq"),
        at = reqString(m, "at"),
        actor = reqString(m, "actor"),
        action = reqString(m, "action"),
        details = reqObject(m, "details"),
        prevHash = reqString(m, "prevHash"),
        hash = reqString(m, "hash"),
    )

    /* ---------------- questions ---------------- */

    private data class QuestionState(
        val id: String,
        val text: String,
        val cluster: ClusterId,
        val topicPrefixes: List<String>,
        val matchTerms: List<String>,
        val priority: Int,
        val stale: Boolean,
        val recomputeCount: Int,
        val current: AnswerSnapshot,
        val history: List<AnswerSnapshot>,
    )

    private fun snapshotToMap(s: AnswerSnapshot): Map<String, Any?> = mapOf(
        "version" to s.version,
        "text" to s.text,
        "delta" to s.delta.wire,
        "basis" to s.basis.wire,
        "evidenceIds" to s.evidenceIds,
        "conflicts" to s.conflicts.map { c ->
            mapOf<String, Any?>("topic" to c.topic, "eventIds" to c.eventIds, "claims" to c.claims)
        },
        "retractions" to s.retractions,
        "evaluatedAt" to s.evaluatedAt,
        "changeNote" to s.changeNote,
    )

    private fun snapshotFromMap(m: Map<String, Any?>): AnswerSnapshot = AnswerSnapshot(
        version = reqInt(m, "version"),
        text = reqString(m, "text"),
        delta = Delta.fromWire(reqString(m, "delta")),
        basis = Basis.entries.first { it.wire == reqString(m, "basis") },
        evidenceIds = reqStringList(m, "evidenceIds"),
        conflicts = reqList(m, "conflicts").map { raw ->
            val c = asObject(raw, "conflict")
            Conflict(reqString(c, "topic"), reqStringList(c, "eventIds"), reqStringList(c, "claims"))
        },
        retractions = reqStringList(m, "retractions"),
        evaluatedAt = reqString(m, "evaluatedAt"),
        changeNote = reqString(m, "changeNote"),
    )

    private fun questionToMap(q: StandingQuestion): Map<String, Any?> = mapOf(
        "id" to q.id,
        "text" to q.text,
        "cluster" to q.cluster.wire,
        "topicPrefixes" to q.topicPrefixes,
        "matchTerms" to q.matchTerms,
        "priority" to q.priority,
        "stale" to q.stale,
        "recomputeCount" to q.recomputeCount,
        "current" to snapshotToMap(q.current),
        "history" to q.history.map { snapshotToMap(it) },
    )

    private fun questionFromMap(m: Map<String, Any?>): QuestionState = QuestionState(
        id = reqString(m, "id"),
        text = reqString(m, "text"),
        cluster = ClusterId.entries.first { it.wire == reqString(m, "cluster") },
        topicPrefixes = reqStringList(m, "topicPrefixes"),
        matchTerms = reqStringList(m, "matchTerms"),
        priority = reqInt(m, "priority"),
        stale = reqBool(m, "stale"),
        recomputeCount = reqInt(m, "recomputeCount"),
        current = snapshotFromMap(reqObject(m, "current")),
        history = reqList(m, "history").map { snapshotFromMap(asObject(it, "history snapshot")) },
    )

    /* ---------------- registries / actions ---------------- */

    private fun collectorToMap(c: Collector): Map<String, Any?> = mapOf(
        "name" to c.name,
        "purpose" to c.purpose,
        "status" to c.status.wire,
        "statusReason" to c.statusReason,
    )

    private fun collectorFromMap(m: Map<String, Any?>): Collector = Collector(
        name = reqString(m, "name"),
        purpose = reqString(m, "purpose"),
        status = CollectorStatus.entries.first { it.wire == reqString(m, "status") },
        statusReason = m["statusReason"] as? String,
    )

    private fun plugToMap(p: AgentPlug): Map<String, Any?> = mapOf(
        "id" to p.id,
        "name" to p.name,
        "roles" to p.roles.map { it.wire },
        "contextScopes" to p.contextScopes,
        "capabilityScopes" to p.capabilityScopes,
        "expiresAt" to p.expiresAt,
        "revoked" to p.revoked,
    )

    private fun plugFromMap(m: Map<String, Any?>): AgentPlug = AgentPlug(
        id = reqString(m, "id"),
        name = reqString(m, "name"),
        roles = reqStringList(m, "roles").map { w -> AgentRole.entries.first { it.wire == w } },
        contextScopes = reqStringList(m, "contextScopes"),
        capabilityScopes = reqStringList(m, "capabilityScopes"),
        expiresAt = reqString(m, "expiresAt"),
        revoked = reqBool(m, "revoked"),
    )

    private fun accountToMap(a: Account): Map<String, Any?> = mapOf(
        "id" to a.id,
        "label" to a.label,
        "role" to a.role,
        "scopes" to a.scopes,
        "connected" to a.connected,
    )

    private fun accountFromMap(m: Map<String, Any?>): Account = Account(
        id = reqString(m, "id"),
        label = reqString(m, "label"),
        role = reqString(m, "role"),
        scopes = reqStringList(m, "scopes"),
        connected = reqBool(m, "connected"),
    )

    private fun railToMap(r: Rail): Map<String, Any?> {
        val base = LinkedHashMap<String, Any?>()
        base["id"] = r.id
        base["actionType"] = r.actionType
        base["target"] = r.target
        base["envelope"] = r.envelope
        base["active"] = r.active
        base["createdBy"] = r.createdBy
        base["createdAt"] = r.createdAt
        // Gate 3 record (ZK-GATE3-DESIGN section 5): additive and
        // OPTIONAL, emitted only when the rail carries commitments —
        // so a pre-ZK rail serializes byte-identically to before. The
        // blinding lives ONLY here, inside the device-local state
        // document (sealed at rest by the app's encrypted store); it
        // is never part of a sync bundle or a ledger record.
        val zk = r.zk
        if (zk != null) {
            base["zk"] = mapOf(
                "params" to zk.params.map { p ->
                    mapOf<String, Any?>(
                        "name" to p.descriptor.name,
                        "scale" to p.descriptor.scale,
                        "bits" to p.descriptor.bits,
                        "offset" to p.descriptor.offset,
                        "ceilingQ" to p.ceilingQ,
                        "commitment" to java.util.Base64.getEncoder().encodeToString(p.envelopeCommitment),
                        "blinding" to java.util.Base64.getEncoder().encodeToString(p.envelopeBlinding),
                    )
                },
            )
        }
        return base
    }

    private fun railFromMap(m: Map<String, Any?>): Rail {
        val zkDoc = m["zk"] as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val zk = (zkDoc as? Map<String, Any?>)?.let { doc ->
            val params = reqList(doc, "params").map { raw ->
                val pm = asObject(raw, "rail zk param")
                RailZkParam(
                    descriptor = ZkParamDescriptor(
                        name = reqString(pm, "name"),
                        scale = reqInt(pm, "scale"),
                        bits = reqInt(pm, "bits"),
                        offset = reqLong(pm, "offset"),
                    ),
                    ceilingQ = reqLong(pm, "ceilingQ"),
                    envelopeCommitment = java.util.Base64.getDecoder().decode(reqString(pm, "commitment")),
                    envelopeBlinding = java.util.Base64.getDecoder().decode(reqString(pm, "blinding")),
                )
            }
            RailZkRecord(railId = reqString(m, "id"), params = params)
        }
        return Rail(
            id = reqString(m, "id"),
            actionType = reqString(m, "actionType"),
            target = reqString(m, "target"),
            envelope = reqObject(m, "envelope").mapValues { (_, v) -> reqNumberValue(v, "rail envelope value") },
            active = reqBool(m, "active"),
            createdBy = reqString(m, "createdBy"),
            createdAt = reqString(m, "createdAt"),
            zk = zk,
        )
    }

    private fun proposalToMap(p: ActionProposal): Map<String, Any?> = mapOf(
        "id" to p.id,
        "actionType" to p.actionType,
        "target" to p.target,
        "parameters" to p.parameters,
        "expectedEffect" to p.expectedEffect,
        "reversibility" to p.reversibility.wire,
        "consequenceClass" to p.consequenceClass.wire,
        "requiredAuthority" to p.requiredAuthority.wire,
        "policy" to p.policy.wire,
        "policyReason" to p.policyReason,
        "status" to p.status.wire,
        "requestedBy" to p.requestedBy,
    )

    private fun proposalFromMap(m: Map<String, Any?>): ActionProposal = ActionProposal(
        id = reqString(m, "id"),
        actionType = reqString(m, "actionType"),
        target = reqString(m, "target"),
        parameters = reqObject(m, "parameters"),
        expectedEffect = reqString(m, "expectedEffect"),
        reversibility = Reversibility.entries.first { it.wire == reqString(m, "reversibility") },
        consequenceClass = ConsequenceClass.entries.first { it.wire == reqString(m, "consequenceClass") },
        requiredAuthority = RequiredAuthority.entries.first { it.wire == reqString(m, "requiredAuthority") },
        policy = PolicyOutcome.entries.first { it.wire == reqString(m, "policy") },
        policyReason = reqString(m, "policyReason"),
        status = ProposalStatus.entries.first { it.wire == reqString(m, "status") },
        requestedBy = reqString(m, "requestedBy"),
    )

    /* ---------------- typed field access: every miss is a named INCONSISTENT_STATE --- */

    private fun inconsistent(message: String): Nothing =
        throw StateStoreException(StateStoreError.INCONSISTENT_STATE, message)

    private fun asObject(value: Any?, what: String): Map<String, Any?> {
        if (value !is Map<*, *>) inconsistent("$what is not a JSON object")
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun reqObject(m: Map<String, Any?>, key: String): Map<String, Any?> =
        asObject(m[key] ?: inconsistent("missing object field \"$key\""), "field \"$key\"")

    private fun reqList(m: Map<String, Any?>, key: String): List<Any?> =
        m[key] as? List<*> ?: inconsistent("missing or non-array field \"$key\"")

    private fun reqString(m: Map<String, Any?>, key: String): String =
        m[key] as? String ?: inconsistent("missing or non-string field \"$key\"")

    private fun reqBool(m: Map<String, Any?>, key: String): Boolean =
        m[key] as? Boolean ?: inconsistent("missing or non-boolean field \"$key\"")

    private fun reqInt(m: Map<String, Any?>, key: String): Int =
        (m[key] as? Number)?.toInt() ?: inconsistent("missing or non-numeric field \"$key\"")

    private fun reqLong(m: Map<String, Any?>, key: String): Long =
        (m[key] as? Number)?.toLong() ?: inconsistent("missing or non-numeric field \"$key\"")

    private fun reqDouble(m: Map<String, Any?>, key: String): Double =
        (m[key] as? Number)?.toDouble() ?: inconsistent("missing or non-numeric field \"$key\"")

    private fun reqNumberValue(value: Any?, what: String): Double =
        (value as? Number)?.toDouble() ?: inconsistent("$what is not a number")

    private fun reqStringList(m: Map<String, Any?>, key: String): List<String> =
        reqList(m, key).map { it as? String ?: inconsistent("field \"$key\" contains a non-string") }
}
