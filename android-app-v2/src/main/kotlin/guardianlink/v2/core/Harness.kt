package guardianlink.v2.core

private val DEFAULT_COLLECTORS: List<Pair<String, String>> = listOf(
    "location" to "Where the phone is: position, arrival, movement",
    "motion" to "Movement/activity state of the device and its carrier",
    "microphone" to "Voice input and authorized sound events",
    "camera" to "User-invoked captures and authorized visual events",
    "bluetooth" to "Nearby devices and connected peripherals",
    "wifi" to "Network context",
    "notifications" to "Incoming interaction signals",
)

/**
 * GuardianLink v0.2 core facade. Port of TS `src/harness.ts`.
 *
 * One object, the whole rig (spec §3.7): ledger, standing questions,
 * conversation, governed actions, collectors, agent plugs, accounts,
 * sync, audit. Local-first and network-free by construction — the core
 * paths never touch a socket (spec §6.10).
 *
 * Not thread-safe, matching the TS core: the Android layer serializes
 * access on an app-level lock.
 */
class GuardianLink(
    now: V2Clock = V2Clock { java.time.Instant.now() },
    deviceId: String = "sovereign-device",
) {
    val ledger: Ledger = Ledger(deviceId, now)
    val audit: AuditLog = AuditLog(now)
    val questions: QuestionEngine = QuestionEngine(ledger, now)
    val collectors: CollectorRegistry = CollectorRegistry(ledger, audit)
    val plugs: AgentPlugRegistry = AgentPlugRegistry(ledger, audit, now)
    val accounts: AccountRegistry = AccountRegistry(audit)
    val syncService: SyncService
    val actions: ActionLayer
    private val conversation: Conversation

    init {
        questions.onVersionChange = { q, snap ->
            audit.record(
                "engine",
                "answer_version_changed",
                mapOf("questionId" to q.id, "version" to snap.version, "delta" to snap.delta.wire),
            )
        }
        for ((name, purpose) in DEFAULT_COLLECTORS) collectors.register(name, purpose)
        syncService = SyncService(ledger, audit, deviceId)
        actions = ActionLayer(
            ActionDeps(
                ledger = ledger,
                audit = audit,
                questions = questions,
                pauseCollector = { name -> collectors.pause(name) },
                resumeCollector = { name -> collectors.resume(name) },
                exportLedger = { syncService.exportBundle() },
                now = now,
            ),
        )
        conversation = Conversation(
            ConversationDeps(
                ledger = ledger,
                questions = questions,
                actions = actions,
                collectors = collectors,
                now = now,
            ),
        )
        // Every admission — whoever caused it — lands in the audit.
        ledger.onAdmit { e ->
            audit.record(e.source, "evidence_admitted", mapOf("eventId" to e.id, "kind" to e.kind.wire, "topic" to e.topic))
        }
    }

    /** Admit evidence directly (sensors in tests, adapters later). Always audited. */
    fun admit(input: AdmitInput): EvidenceEvent = ledger.admit(input)

    /** Sensor path: a non-active collector cannot contribute evidence. */
    fun admitSensor(
        collector: String,
        topic: String,
        claim: String,
        confidence: Double = 0.9,
        polarity: Polarity = Polarity.AFFIRM,
    ): EvidenceEvent {
        collectors.assertCollecting(collector)
        return ledger.admit(
            AdmitInput(
                kind = EventKind.SENSOR_OBSERVATION,
                topic = topic,
                claim = claim,
                polarity = polarity,
                source = "sensor:$collector",
                confidence = confidence,
            ),
        )
    }

    /** Account evidence path: disconnected or out-of-scope accounts are blocked at the boundary. */
    fun admitAccountEvidence(
        accountId: String,
        scope: String,
        topic: String,
        claim: String,
        confidence: Double = 0.9,
    ): EvidenceEvent {
        accounts.assertUsable(accountId, scope)
        return ledger.admit(
            AdmitInput(
                kind = EventKind.EXTERNAL_REPORT,
                topic = topic,
                claim = claim,
                source = "account:$accountId",
                confidence = confidence,
            ),
        )
    }

    fun denyCollector(name: String, reason: String = "permission denied") {
        collectors.deny(name, reason)
    }

    /** The conversational control surface: say it naturally. */
    fun say(text: String): ConversationResult = conversation.handle(text)

    /** Current standing answers (the "Live Now" state). */
    fun snapshot(): List<StandingQuestion> = questions.all()

    fun verifyIntegrity(): Map<String, Any?> = mapOf(
        "ledgerChain" to ledger.verifyChain(),
        "auditChain" to audit.verify(),
        "events" to ledger.allEvents().size,
        "auditEntries" to audit.all().size,
    )
}
