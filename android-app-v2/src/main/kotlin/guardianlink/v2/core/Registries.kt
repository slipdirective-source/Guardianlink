package guardianlink.v2.core

/* ---------------- Collectors (spec §3.1, §4) ---------------- */

enum class CollectorStatus(val wire: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    DENIED("denied"),
}

data class Collector(
    val name: String,
    val purpose: String,
    var status: CollectorStatus,
    var statusReason: String? = null,
)

/** Port of TS `CollectorRegistry` in `src/registries.ts`. */
class CollectorRegistry(
    private val ledger: Ledger,
    private val audit: AuditLog,
) {
    private val collectors = LinkedHashMap<String, Collector>()

    fun register(name: String, purpose: String): Collector {
        val c = Collector(name, purpose, CollectorStatus.ACTIVE)
        collectors[name] = c
        return c
    }

    fun get(name: String): Collector? = collectors[name]

    fun all(): List<Collector> = collectors.values.toList()

    private fun setStatus(name: String, status: CollectorStatus, reason: String, actor: String): Collector {
        val c = collectors[name] ?: throw IllegalStateException("unknown collector \"$name\"")
        c.status = status
        c.statusReason = reason
        ledger.admit(
            AdmitInput(
                kind = EventKind.SYSTEM,
                topic = "collector.$name",
                claim = when (status) {
                    CollectorStatus.ACTIVE -> "collecting"
                    CollectorStatus.PAUSED -> "collection paused ($reason)"
                    CollectorStatus.DENIED -> "permission denied ($reason)"
                },
                source = actor,
                confidence = 1.0,
            ),
        )
        audit.record(actor, "collector_changed", mapOf("collector" to name, "status" to status.wire, "reason" to reason))
        return c
    }

    fun pause(name: String, actor: String = "sovereign"): Collector =
        setStatus(name, CollectorStatus.PAUSED, "paused by sovereign action", actor)

    fun resume(name: String, actor: String = "sovereign"): Collector =
        setStatus(name, CollectorStatus.ACTIVE, "resumed", actor)

    /** OS permission denial: the harness names the missing capability and never fabricates its evidence. */
    fun deny(name: String, reason: String = "permission denied"): Collector =
        setStatus(name, CollectorStatus.DENIED, reason, "system")

    /** A collector that is not active cannot contribute evidence. */
    fun assertCollecting(name: String) {
        val c = collectors[name] ?: throw IllegalStateException("unknown collector \"$name\"")
        if (c.status != CollectorStatus.ACTIVE) {
            throw IllegalStateException("collector \"$name\" is ${c.status.wire}: ${c.statusReason ?: ""}".trim())
        }
    }
}

/* ---------------- Agent plugs (spec §3.11) ---------------- */

enum class AgentRole(val wire: String) {
    OBSERVER("observer"),
    ANALYST("analyst"),
    RESEARCHER("researcher"),
    PLANNER("planner"),
    CLASSIFIER("classifier"),
    PROPOSER("proposer"),
    EXECUTOR("executor"),
    VERIFIER("verifier"),
}

/** Port of TS `AgentPlug`. */
class AgentPlug(
    val id: String,
    val name: String,
    val roles: List<AgentRole>,
    /** topics the plug may read context from and submit evidence about — nothing else exists to it */
    val contextScopes: List<String>,
    /** action types the plug may request through the governed path — never approve */
    val capabilityScopes: List<String>,
    val expiresAt: String,
    var revoked: Boolean = false,
)

/**
 * Agent plug registry (spec §3.11). Port of TS `AgentPlugRegistry`.
 * A plug is a governed socket, not a back door: least-context reads,
 * agent-derived submissions only, capability checks at the boundary,
 * immediate revocation.
 */
class AgentPlugRegistry(
    private val ledger: Ledger,
    private val audit: AuditLog,
    private val now: V2Clock = V2Clock { java.time.Instant.now() },
) {
    private val plugs = LinkedHashMap<String, AgentPlug>()

    fun register(p: AgentPlug): AgentPlug {
        plugs[p.id] = p
        audit.record(
            "sovereign",
            "plug_registered",
            mapOf(
                "plugId" to p.id,
                "roles" to p.roles.map { it.wire },
                "contextScopes" to p.contextScopes,
                "capabilityScopes" to p.capabilityScopes,
                "expiresAt" to p.expiresAt,
            ),
        )
        return p
    }

    fun get(id: String): AgentPlug? = plugs[id]

    fun revoke(id: String) {
        val plug = plugs[id] ?: throw IllegalStateException("unknown plug \"$id\"")
        plug.revoked = true
        audit.record("sovereign", "plug_revoked", mapOf("plugId" to id))
    }

    /** A revoked or expired plug cannot read, submit, or invoke — checked at the harness boundary on every call. */
    fun assertActive(id: String): AgentPlug {
        val plug = plugs[id] ?: throw IllegalStateException("unknown plug \"$id\"")
        if (plug.revoked) throw IllegalStateException("plug \"$id\" is revoked; the socket is closed")
        if (IsoTime.format(now.now()) > plug.expiresAt) throw IllegalStateException("plug \"$id\" expired at ${plug.expiresAt}")
        return plug
    }

    private fun inScope(plug: AgentPlug, topic: String): Boolean =
        plug.contextScopes.any { s -> topic == s || topic.startsWith(s) }

    /** Least-context read: only events inside the plug's context scopes are visible. */
    fun readContext(id: String, topic: String? = null): List<EvidenceEvent> {
        val plug = assertActive(id)
        val events = ledger.liveEvents().filter { e -> inScope(plug, e.topic) && (topic == null || e.topic == topic) }
        audit.record("agent:$id", "plug_context_read", mapOf("plugId" to id, "topic" to (topic ?: "*"), "count" to events.size))
        return events
    }

    /** Agent submissions enter as agent-derived evidence — never as direct observation. */
    fun submitEvidence(id: String, topic: String, claim: String, confidence: Double = 0.5): EvidenceEvent {
        val plug = assertActive(id)
        if (!inScope(plug, topic)) {
            audit.record("agent:$id", "plug_submission_refused_out_of_scope", mapOf("plugId" to id, "topic" to topic))
            throw IllegalStateException("plug \"$id\" cannot submit on topic \"$topic\": outside its context scopes [${plug.contextScopes.joinToString(", ")}]")
        }
        val ev = ledger.admit(
            AdmitInput(
                kind = EventKind.AGENT_DERIVED,
                topic = topic,
                claim = claim,
                source = "agent:$id",
                confidence = confidence,
                agentDerived = true,
            ),
        )
        audit.record("agent:$id", "plug_submission", mapOf("plugId" to id, "eventId" to ev.id, "topic" to topic))
        return ev
    }

    /** Capability check only — actual invocation goes through the ActionLayer and still needs policy + assent. */
    fun assertCapability(id: String, actionType: String): AgentPlug {
        val plug = assertActive(id)
        if (!plug.capabilityScopes.contains(actionType)) {
            audit.record("agent:$id", "plug_capability_refused", mapOf("plugId" to id, "actionType" to actionType))
            throw IllegalStateException("plug \"$id\" holds no capability for action \"$actionType\"")
        }
        return plug
    }
}

/* ---------------- Accounts (spec §3.8) ---------------- */

data class Account(
    val id: String,
    val label: String,
    /** personal | business | project | sovereign-defined */
    val role: String,
    val scopes: List<String>,
    var connected: Boolean = true,
)

/** Port of TS `AccountRegistry` in `src/registries.ts`. */
class AccountRegistry(private val audit: AuditLog) {

    private val accounts = LinkedHashMap<String, Account>()

    fun register(a: Account): Account {
        a.connected = true
        accounts[a.id] = a
        audit.record("sovereign", "account_registered", mapOf("accountId" to a.id, "label" to a.label, "role" to a.role, "scopes" to a.scopes))
        return a
    }

    fun get(id: String): Account? = accounts[id]

    fun disconnect(id: String) {
        val acct = accounts[id] ?: throw IllegalStateException("unknown account \"$id\"")
        acct.connected = false
        audit.record("sovereign", "account_disconnected", mapOf("accountId" to id))
    }

    /** Any use of an account — read or act — goes through here. Disconnection or missing scope blocks it immediately. */
    fun assertUsable(id: String, scope: String): Account {
        val acct = accounts[id] ?: throw IllegalStateException("unknown account \"$id\"")
        if (!acct.connected) throw IllegalStateException("account \"$id\" is disconnected; it cannot be used")
        if (!acct.scopes.contains(scope)) {
            throw IllegalStateException("account \"$id\" does not hold scope \"$scope\" (holds: ${acct.scopes.joinToString(", ")})")
        }
        audit.record("account:$id", "account_used", mapOf("accountId" to id, "scope" to scope))
        return acct
    }
}
