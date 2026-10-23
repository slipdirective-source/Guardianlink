package guardianlink.v2.core

import java.time.ZoneId

/** Port of TS `Intent` in `src/conversation.ts`; [wire] is the TS string. */
enum class Intent(val wire: String) {
    REPORT("report"),
    ASK("ask"),
    QUERY("query"),
    CORRECT("correct"),
    RETRACT("retract"),
    CONFIGURE("configure"),
    ACT("act"),
    CLARIFY("clarify"),
}

data class AnswerChange(
    val questionId: String,
    val version: Int,
    val text: String,
)

/** Port of TS `ConversationResult`. */
data class ConversationResult(
    val intent: Intent,
    val reply: String,
    val admittedEventIds: List<String> = emptyList(),
    val proposal: ActionProposal? = null,
    val execution: ExecuteResult? = null,
    val answerChanges: List<AnswerChange> = emptyList(),
    val clarifyQuestion: String? = null,
)

class ConversationDeps(
    val ledger: Ledger,
    val questions: QuestionEngine,
    val actions: ActionLayer,
    val collectors: CollectorRegistry,
    val now: V2Clock,
)

private val NEGATION = Regex(
    "\\b(skipped|skip|missed|didn'?t|did not|don'?t|not|never|no longer|behind schedule|delayed|cancelled|canceled)\\b",
    RegexOption.IGNORE_CASE,
)

/** Port of TS `inferTopic` in `src/conversation.ts`. */
fun inferTopic(text: String): String {
    val t = text.lowercase()
    if (Regex("\\b(dose|medication|medicine|pill)\\b").containsMatchIn(t)) return "medication.dose"
    if (t.contains("cabinet")) return "project.cabinet_job"
    if (Regex("\\b(arrived|arriving|i'?m at|at home|at work|at the shop|at the garage|heading|left the)\\b").containsMatchIn(t)) return "location"
    if (t.contains("door")) return "state.door"
    val stop = setOf("the", "that", "this", "was", "were", "have", "has")
    val words = t.replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { w -> w.length > 2 && !stop.contains(w) }
    return "note." + (words.take(3).joinToString("_").ifEmpty { "general" })
}

/** Port of TS `inferPolarity`. */
fun inferPolarity(text: String): Polarity =
    if (NEGATION.containsMatchIn(text) || Regex("\\bbehind\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        Polarity.NEGATE
    } else {
        Polarity.AFFIRM
    }

/**
 * Deterministic conversational control layer (spec §3.4). Port of TS
 * `src/conversation.ts`, branch for branch.
 *
 * Ordinary language in; exactly one intent out. Ambiguity returns a
 * clarify request — the parser never guesses a binding it cannot justify.
 */
class Conversation(private val deps: ConversationDeps) {

    private fun versionsBefore(): Map<String, Int> =
        deps.questions.all().associate { q -> q.id to q.current.version }

    private fun changesSince(before: Map<String, Int>): List<AnswerChange> =
        deps.questions.all()
            .filter { q -> q.current.version > (before[q.id] ?: 0) }
            .map { q -> AnswerChange(q.id, q.current.version, q.current.text) }

    private fun base(intent: Intent, reply: String, before: Map<String, Int>): ConversationResult =
        ConversationResult(intent = intent, reply = reply, answerChanges = changesSince(before))

    private fun clarify(
        intent: Intent,
        reply: String,
        before: Map<String, Int>,
        question: String,
        proposal: ActionProposal? = null,
    ): ConversationResult =
        base(intent, reply, before).copy(clarifyQuestion = question, proposal = proposal)

    private fun liveUserReports(): List<EvidenceEvent> =
        deps.ledger.liveEvents().filter { e -> e.kind == EventKind.USER_REPORT }

    fun handle(raw: String): ConversationResult {
        val before = versionsBefore()
        val text = raw.trim()
        if (text.isEmpty()) {
            return clarify(Intent.CLARIFY, "Nothing to work with — say it again?", before, "What did you mean?")
        }
        val lower = text.lowercase()

        // --- assent / refusal for a pending proposal ---
        val pending = pendingProposal()
        if (Regex("^(yes|yeah|approve|approved|go ahead|do it)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            if (pending == null) {
                return clarify(Intent.CLARIFY, "Nothing is awaiting your assent right now.", before, "What should I do?")
            }
            deps.actions.recordAssent(pending.id)
            val exec = deps.actions.execute(pending.id)
            var reply =
                if (exec.executed) {
                    "Assent recorded. Executed: ${pending.actionType} on ${pending.target}. ${exec.message}"
                } else {
                    "Assent recorded, but execution did not complete: ${exec.message}"
                }
            if (Regex("\\balways\\b").containsMatchIn(lower) && exec.executed) {
                deps.actions.addRail(pending.actionType, pending.target, emptyMap(), "sovereign")
                reply += " A rail now covers ${pending.actionType} on ${pending.target} without fresh assent."
            }
            return base(Intent.ACT, reply, before).copy(proposal = pending, execution = exec)
        }
        if (Regex("^(no|cancel|never mind)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) && pending != null) {
            return base(
                Intent.CLARIFY,
                "Left unexecuted: ${pending.actionType} on ${pending.target}. The proposal stays on record; nothing was done.",
                before,
            )
        }

        // --- retract: binds by reference; ambiguity is a clarification, never a guess ---
        if (Regex("\\b(retract|take back|withdraw)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val refMatch = Regex("(?:about|regarding)\\s+(.+?)\\.?$").find(lower)
                ?: Regex("\\b(?:retract|take back|withdraw)\\s+(?:what i said\\s+)?(.+?)\\.?$").find(lower)
            var ref = refMatch?.groupValues?.get(1) ?: ""
            ref = ref.replace(Regex("^(what i said|my statement|that)\\s+"), "")
            val keywords = ref.split(Regex("[^a-z0-9]+")).filter { w -> w.length > 2 }
            val candidates = liveUserReports().filter { e ->
                keywords.any { k -> e.claim.lowercase().contains(k) || e.topic.lowercase().contains(k) }
            }
            if (candidates.isEmpty()) {
                return clarify(
                    Intent.CLARIFY,
                    "I can't find a live statement matching \"$ref\". Nothing was retracted.",
                    before,
                    "Which statement should I retract?",
                )
            }
            if (candidates.size > 1) {
                val list = candidates.joinToString("; ") { c -> "\"${c.claim}\"" }
                return clarify(
                    Intent.CLARIFY,
                    "That reference matches ${candidates.size} live statements: $list. Which one should I retract? Nothing was retracted.",
                    before,
                    "Which statement should I retract?",
                )
            }
            val target = candidates[0]
            val ev = deps.ledger.admit(
                AdmitInput(
                    kind = EventKind.USER_REPORT,
                    topic = target.topic,
                    claim = "Retracted: \"${target.claim}\"",
                    source = "sovereign",
                    relations = listOf(Relation(RelationType.RETRACTS, target.id)),
                ),
            )
            return base(
                Intent.RETRACT,
                "Retracted: \"${target.claim}\". The original stays in the ledger; it no longer counts as live evidence.",
                before,
            ).copy(admittedEventIds = listOf(ev.id))
        }

        // --- correct: binds to the latest live statement on the corrected topic ---
        val correctMatch = Regex("^(?:correct(?:ion)?(?:\\s+that)?|actually)[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (correctMatch != null) {
            val rest = correctMatch.groupValues[1]
            val isCorrection = Regex("^correct", RegexOption.IGNORE_CASE).containsMatchIn(text)
            val topic = inferTopic(rest)
            val candidates = liveUserReports().filter { e -> e.topic == topic }
            var target: EvidenceEvent? = candidates.maxByOrNull { it.seq }
            if (target == null && !isCorrection) {
                target = liveUserReports().maxByOrNull { it.seq }
            }
            if (target == null) {
                return clarify(
                    Intent.CLARIFY,
                    "I have no live statement on $topic to bind that correction to.",
                    before,
                    "Which statement are you correcting?",
                )
            }
            val polarity = inferPolarity(rest)
            val relationType =
                if (isCorrection) {
                    RelationType.SUPERSEDES
                } else if (polarity != target.polarity) {
                    RelationType.CONFLICTS
                } else {
                    RelationType.SUPERSEDES
                }
            val ev = deps.ledger.admit(
                AdmitInput(
                    kind = EventKind.USER_REPORT,
                    topic = target.topic,
                    claim = text,
                    polarity = polarity,
                    source = "sovereign",
                    relations = listOf(Relation(relationType, target.id)),
                ),
            )
            val reply =
                if (isCorrection) {
                    "Correction bound to \"${target.claim}\" and superseding it: \"$text\". Both stay in the ledger."
                } else if (relationType == RelationType.CONFLICTS) {
                    "That contradicts your earlier statement \"${target.claim}\". Both are live and the conflict is now surfaced — it will not be silently resolved."
                } else {
                    "Bound to \"${target.claim}\": \"$text\"."
                }
            return base(if (isCorrection) Intent.CORRECT else Intent.REPORT, reply, before)
                .copy(admittedEventIds = listOf(ev.id))
        }

        // --- change query ---
        if (Regex("\\bwhat changed\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val since = parseSince(lower)
            val events = deps.ledger.allEvents().filter { e -> e.assertedAt >= since }
            val snaps = deps.questions.all().flatMap { q ->
                q.history.filter { s -> s.evaluatedAt >= since }.map { s -> "${q.id} v${s.version}: ${s.changeNote}" }
            }
            val lines = events.map { e -> "#${e.seq} [${e.kind.wire}] ${e.claim} (${e.source})" }
            val reply =
                "Since $since:\n" +
                    (if (lines.isNotEmpty()) lines.joinToString("\n") else "No new evidence admitted.") +
                    (if (snaps.isNotEmpty()) "\nAnswer changes:\n${snaps.joinToString("\n")}" else "\nNo standing answers changed.")
            return base(Intent.QUERY, reply, before)
        }

        // --- create a standing question (governed act) ---
        val trackMatch = Regex("\\b(?:keep track of|track|watch)\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (trackMatch != null && Regex("\\b(whether|if|of)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trackMatch.groupValues[1])) {
            val questionText = trackMatch.groupValues[1].replace(Regex("^whether\\s+", RegexOption.IGNORE_CASE), "").trim()
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "create_standing_question",
                    target = questionText,
                    parameters = mapOf("text" to questionText),
                    expectedEffect = "A standing question \"$questionText\" is maintained continuously and flags drift.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL_LOW,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(
                proposal,
                before,
                "Standing question proposed in explicit terms: \"$questionText\". It will be recomputed continuously as evidence arrives.",
            )
        }

        // --- configure ---
        val configMatch = Regex("^set\\s+([a-z_]+)\\s+to\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (configMatch != null) {
            val ev = deps.ledger.admit(
                AdmitInput(
                    kind = EventKind.SYSTEM,
                    topic = "config.${configMatch.groupValues[1]}",
                    claim = "${configMatch.groupValues[1]} = ${configMatch.groupValues[2]}",
                    source = "sovereign",
                ),
            )
            return base(
                Intent.CONFIGURE,
                "Configuration set: ${configMatch.groupValues[1]} = ${configMatch.groupValues[2]}. Recorded and auditable.",
                before,
            ).copy(admittedEventIds = listOf(ev.id))
        }

        // --- ask ---
        if (text.contains('?') ||
            Regex("^(what|where|how|who|when|why|is|are|do|does|can)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        ) {
            val cluster: String? =
                if (Regex("where am i\\b").containsMatchIn(lower)) {
                    "position"
                } else if (lower.contains("how did i get here")) {
                    "history"
                } else if (lower.contains("interact")) {
                    "relationships"
                } else if (Regex("where am i (going|headed)").containsMatchIn(lower)) {
                    "trajectory"
                } else if (lower.contains("what can i do")) {
                    "agency"
                } else {
                    null
                }
            if (cluster != null) {
                val q = deps.questions.get(cluster)!!
                return base(
                    Intent.ASK,
                    "${q.text}\n${q.current.text}\n(uncertainty: ${q.current.delta.wire}; basis: ${q.current.basis.wire}; v${q.current.version})",
                    before,
                )
            }
            val aboutMatch = Regex("about\\s+(.+?)\\??$").find(lower)
            if (aboutMatch != null) return searchReply(aboutMatch.groupValues[1], Intent.ASK, before)
            return searchReply(lower.replace("?", ""), Intent.ASK, before)
        }

        // --- query (search form) ---
        val searchMatch = Regex("^(?:show me|list|search)(?:\\s+everything)?\\s+(?:about\\s+)?(.+?)\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (searchMatch != null) return searchReply(searchMatch.groupValues[1], Intent.QUERY, before)

        // --- act: pause a collector ---
        val pauseMatch = Regex("^(?:stop|pause)\\s+(?:collecting\\s+)?(?:the\\s+)?([a-z]+)(?:\\s+collector)?\\s*\\.?$").find(lower)
            ?: Regex("^stop collecting\\s+(?:the\\s+)?([a-z]+)").find(lower)
        if (pauseMatch != null || Regex("^stop collecting\\b").containsMatchIn(lower)) {
            val name = (pauseMatch?.groupValues?.get(1) ?: "").replace(Regex("collection$"), "")
            if (name.isEmpty() || deps.collectors.get(name) == null) {
                return clarify(
                    Intent.CLARIFY,
                    "No collector named \"$name\" is registered, so there is nothing to pause. Registered: ${deps.collectors.all().joinToString(", ") { it.name }}.",
                    before,
                    "Which collector should pause?",
                )
            }
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "pause_collector",
                    target = name,
                    parameters = emptyMap(),
                    expectedEffect = "The $name collector pauses; its evidence stops until resumed, and answers depending on it degrade honestly.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: pause the $name collector.")
        }

        // --- act: export ---
        if (Regex("\\bexport\\b").containsMatchIn(lower) && Regex("\\b(ledger|record)\\b").containsMatchIn(lower)) {
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "export_ledger",
                    target = "ledger",
                    parameters = emptyMap(),
                    expectedEffect = "A hash-committed export bundle of the ledger is produced.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL_LOW,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: export the ledger.")
        }

        // --- act: hard-denied ---
        if (Regex("\\bdelete\\b").containsMatchIn(lower) && Regex("\\b(ledger|audit|record)\\b").containsMatchIn(lower)) {
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "delete_ledger",
                    target = "ledger",
                    parameters = emptyMap(),
                    expectedEffect = "The ledger would be destroyed.",
                    reversibility = Reversibility.IRREVERSIBLE,
                    consequenceClass = ConsequenceClass.HIGH,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return base(Intent.ACT, "Denied. ${proposal.policyReason}", before).copy(proposal = proposal)
        }

        // --- act: external send (no adapter in this core) ---
        if (Regex("^(send|email|text|message)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val isEmail = lower.contains("email")
            val toMatch = Regex("\\bto\\s+(?:the\\s+)?([a-z]+(?:\\s[a-z]+)?)(?=\\s+about\\b|\\s*$|\\.)").find(lower)
            val target = toMatch?.groupValues?.get(1)?.trim() ?: "unspecified"
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = if (isEmail) "send_email" else "send_message",
                    target = target,
                    parameters = mapOf("text" to text),
                    expectedEffect = "A ${if (isEmail) "email" else "message"} would be delivered to ${if (toMatch != null) target else "an unspecified recipient"}.",
                    reversibility = Reversibility.IRREVERSIBLE,
                    consequenceClass = ConsequenceClass.EXTERNAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            var reply = "Proposal recorded: ${proposal.actionType} to ${proposal.target}. Policy: ${proposal.policy.wire} — ${proposal.policyReason}"
            if (proposal.policy != PolicyOutcome.INSUFFICIENTLY_SPECIFIED) {
                reply += " Note: no execution adapter for ${proposal.actionType} exists in this core, so even with your assent I can propose/draft this but cannot execute it. Nothing has been sent."
            }
            return base(Intent.ACT, reply, before).copy(proposal = proposal)
        }

        // --- act: other imperatives (no adapters in this core) ---
        val impMatch = Regex("^(unlock|lock|open|turn on|turn off|pay|buy|call|start|shut)\\s+(?:the\\s+)?(.+?)\\.?$").find(lower)
        if (impMatch != null) {
            val actionType = (impMatch.groupValues[1].replace(Regex("\\s+"), "_") + "_" +
                impMatch.groupValues[2].split(Regex("\\s+"))[0]).replace(Regex("[^a-z_]"), "")
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = actionType,
                    target = impMatch.groupValues[2],
                    parameters = mapOf("text" to text),
                    expectedEffect = "The requested external effect on ${impMatch.groupValues[2]} would occur.",
                    reversibility = Reversibility.PARTIALLY_REVERSIBLE,
                    consequenceClass = ConsequenceClass.EXTERNAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return base(
                Intent.ACT,
                "Proposal recorded: $actionType on ${impMatch.groupValues[2]}. Policy: ${proposal.policy.wire} — ${proposal.policyReason} Note: no execution adapter for $actionType exists in this core, so I can propose/draft this but cannot execute it. Nothing was done.",
                before,
            ).copy(proposal = proposal)
        }

        // --- report (default for a declarative statement) ---
        val words = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size >= 3 || Regex("\\b(dose|cabinet|arrived)\\b").containsMatchIn(lower)) {
            val topic = inferTopic(text)
            val polarity = inferPolarity(text)
            val relations = mutableListOf<Relation>()
            val opposite = deps.ledger.liveEvents()
                .filter { e -> e.topic == topic && e.polarity != polarity }
                .maxByOrNull { it.seq }
            if (opposite != null) relations.add(Relation(RelationType.CONFLICTS, opposite.id))
            val ev = deps.ledger.admit(
                AdmitInput(
                    kind = EventKind.USER_REPORT,
                    topic = topic,
                    claim = text.removeSuffix("."),
                    polarity = polarity,
                    source = "sovereign",
                    relations = relations,
                ),
            )
            val conflictNote =
                if (opposite != null) {
                    " This conflicts with your live statement \"${opposite.claim}\" — the conflict is surfaced, not resolved."
                } else {
                    ""
                }
            return base(
                Intent.REPORT,
                "Recorded under $topic: \"${ev.claim}\". Standing answers updated.$conflictNote",
                before,
            ).copy(admittedEventIds = listOf(ev.id))
        }

        // --- clarify: never guess ---
        return clarify(
            Intent.CLARIFY,
            "I can't tell what to do with \"$text\" — is it a report (something that happened), a question, or an action you want taken? I won't guess.",
            before,
            "Is that a report, a question, or an action?",
        )
    }

    private fun pendingProposal(): ActionProposal? {
        val proposals = deps.ledger.allEvents().filter { e -> e.kind == EventKind.ACTION_PROPOSAL }
        for (i in proposals.indices.reversed()) {
            val p = deps.actions.getProposal(proposals[i].id)
            if (p != null && p.status == ProposalStatus.PROPOSED && p.policy == PolicyOutcome.NEEDS_FRESH_ASSENT) return p
        }
        return null
    }

    private fun finishProposal(proposal: ActionProposal, before: Map<String, Int>, lead: String): ConversationResult {
        if (proposal.policy == PolicyOutcome.EXECUTABLE_UNDER_RAIL) {
            val exec = deps.actions.execute(proposal.id)
            return base(Intent.ACT, "$lead Covered by a rail, so it executed without fresh assent. ${exec.message}", before)
                .copy(proposal = proposal, execution = exec)
        }
        if (proposal.policy == PolicyOutcome.DENIED ||
            proposal.policy == PolicyOutcome.DRIFT_FLAGGED ||
            proposal.policy == PolicyOutcome.INSUFFICIENTLY_SPECIFIED
        ) {
            return base(Intent.ACT, "$lead Policy: ${proposal.policy.wire} — ${proposal.policyReason} Nothing was executed.", before)
                .copy(proposal = proposal)
        }
        return base(
            Intent.ACT,
            "$lead Policy: needs fresh assent — ${proposal.policyReason} Say \"yes\" to assent and execute, or ignore it and nothing happens.",
            before,
        ).copy(proposal = proposal)
    }

    private fun searchReply(about: String, intent: Intent, before: Map<String, Int>): ConversationResult {
        val keywords = about.lowercase().split(Regex("[^a-z0-9]+")).filter { w -> w.length > 3 }
        val matches = deps.ledger.liveEvents().filter { e ->
            keywords.any { k -> e.claim.lowercase().contains(k) || e.topic.lowercase().contains(k) }
        }
        if (matches.isEmpty()) {
            return base(
                intent,
                "Unknown: I hold no live evidence about \"$about\" yet. (That is an honest unknown, not a gap I will fill by guessing.)",
                before,
            )
        }
        val lines = matches.map { e -> "#${e.seq} [${e.kind.wire}] ${e.claim} (${e.source})" }
        return base(intent, "Live evidence about \"$about\":\n${lines.joinToString("\n")}", before)
    }

    private fun parseSince(lower: String): String {
        val nowInstant = deps.now.now()
        if (lower.contains("last hour")) return IsoTime.format(nowInstant.minusSeconds(3600_000 / 1000))
        val zone = ZoneId.systemDefault()
        val today = nowInstant.atZone(zone).toLocalDate()
        val zdt =
            if (lower.contains("yesterday")) {
                today.minusDays(1).atStartOfDay(zone)
            } else if (lower.contains("this morning")) {
                today.atTime(6, 0).atZone(zone)
            } else {
                today.atStartOfDay(zone)
            }
        return IsoTime.format(zdt.toInstant())
    }
}
