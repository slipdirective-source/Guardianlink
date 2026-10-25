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
                    "Done — ${exec.message}"
                } else {
                    "Your assent is recorded, but it didn't complete: ${exec.message}"
                }
            if (Regex("\\balways\\b").containsMatchIn(lower) && exec.executed) {
                deps.actions.addRail(pending.actionType, pending.target, emptyMap(), "sovereign")
                reply += " And from now on, that runs under a standing rail — I won't ask again unless something drifts."
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
                    "Correction recorded — it replaces \"${target.claim}\". Both versions stay on record."
                } else if (relationType == RelationType.CONFLICTS) {
                    "That contradicts your earlier statement \"${target.claim}\". Both stay live and the conflict is on the table — it won't be quietly resolved."
                } else {
                    "Noted against \"${target.claim}\": \"$text\"."
                }
            return base(if (isCorrection) Intent.CORRECT else Intent.REPORT, reply, before)
                .copy(admittedEventIds = listOf(ev.id))
        }

        // --- change query ---
        if (Regex("\\bwhat changed\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val since = parseSince(lower)
            val events = deps.ledger.allEvents().filter { e -> e.assertedAt >= since }
            val snaps = deps.questions.all().flatMap { q ->
                q.history.filter { s -> s.evaluatedAt >= since }.map { s -> "${q.text} ${s.changeNote}" }
            }
            val lines = events.map { e -> "• ${e.claim}" }
            val reply =
                "Since $since:\n" +
                    (if (lines.isNotEmpty()) lines.joinToString("\n") else "Nothing new has come in.") +
                    (if (snaps.isNotEmpty()) "\nAnswer changes:\n${snaps.joinToString("\n")}" else "\nNo standing answers changed.")
            return base(Intent.QUERY, reply, before)
        }

        // --- self-report: what is being tracked ---
        // The sovereign asking what the system tracks is owed an
        // answer from the system's own books — the collector registry
        // (what CAN gather, and its state) and the ledger (what HAS
        // been gathered) — never a shrug or a search miss. This branch
        // sits before the generic ask path on purpose: the ask path
        // would otherwise throw the question at the evidence search
        // and report nothing matching.
        if (Regex("\\bwhat\\b[^?]*\\b(track|collect|monitor|watch|record|gather|sens)(ing|ion|e|s)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("\\bwhat data\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            Regex("\\b(list|show|tell me)\\b[^?]*\\bcollectors\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        ) {
            val collectors = deps.collectors.all()
            val live = deps.ledger.liveEvents()
            fun family(topic: String): String = when {
                topic.startsWith("location") -> "location"
                topic == "state.motion" -> "motion"
                topic.startsWith("state.") -> "device state"
                topic.startsWith("email") || topic.startsWith("inbox") -> "inbox and email"
                topic.startsWith("calendar") -> "calendar"
                topic.startsWith("finance") -> "finances"
                topic.startsWith("health") -> "health"
                else -> "other records"
            }
            val counts = live.groupingBy { family(it.topic) }.eachCount()
            val latestPlace = live.filter { it.topic == "location" }.maxByOrNull { it.seq }?.claim
            val sb = StringBuilder("What I'm tracking — the collectors registered on this device:\n")
            for (c in collectors) {
                val state = when (c.status) {
                    CollectorStatus.ACTIVE -> "active"
                    CollectorStatus.PAUSED -> "paused" + (c.statusReason?.let { " — $it" } ?: "")
                    CollectorStatus.DENIED -> "off" + (c.statusReason?.let { " — $it" } ?: "")
                }
                sb.append("• ${c.name} — ${c.purpose} [$state]\n")
            }
            if (live.isEmpty()) {
                sb.append("On record: nothing yet — no evidence has been admitted.\n")
            } else {
                val parts = counts.entries.joinToString(", ") { "${it.value} ${it.key}" }
                sb.append("On record: ${live.size} events — $parts.\n")
                latestPlace?.let { sb.append("Latest position: $it\n") }
            }
            sb.append("Every reading is readable in the Data vault, tabbed by kind. To stop one, say \"pause location\" — it goes through the same assent ceremony as any governed action.")
            return base(Intent.QUERY, sb.toString(), before)
        }

        // --- capabilities: "what can you do?" ----------------------
        // A capability question is about the system, not the record.
        // Answering it from evidence search ("nothing on record
        // about that") is a category error — this branch composes
        // the answer from the live registry and the action layer.
        if (Regex("\\bwhat can (you|u) do\\b|\\bwhat do you do\\b|\\byour (capabilities|abilities|features)\\b|^help\\s*\\.?$|\\bwhat are you able to\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val collectors = deps.collectors.all()
            val active = collectors.filter { it.status == CollectorStatus.ACTIVE }.map { it.name }
            val off = collectors.filter { it.status != CollectorStatus.ACTIVE }
            val sb = StringBuilder("Here's what I can do, as I stand right now:\n")
            sb.append("• Keep your standing answers current — where you are, how you got here, what's moving, what's next — from what this phone senses.\n")
            if (active.isNotEmpty()) sb.append("• Collecting now: ${active.joinToString(", ")}.\n")
            for (c in off) {
                sb.append("• ${c.name} is ${if (c.status == CollectorStatus.PAUSED) "paused" else "off"}${c.statusReason?.let { " — $it" } ?: ""}. Its pathway is in Settings → Permissions.\n")
            }
            sb.append("• Answer from your record — ask me things like \"check my email\", \"what are you tracking?\", or \"search\" anything I've recorded.\n")
            sb.append("• Act when you command it, under your assent: pause or resume a collector, create a standing question, export the record")
            if (deps.actions.deviceActionsAvailable()) {
                sb.append(", place a call (\"call Maria\"), send a text (\"text Maria saying…\"), send an email from your mailbox (\"email maria@example.com saying…\"), and work the phone itself — open apps (\"open Gmail\"), tap and type inside them (\"tap Send\", \"type …\"), go back or home, and read the screen (\"read my screen\")")
            }
            sb.append(". Every action is proposed first, runs only on your yes or a rail you set, and its outcome lands in the record either way.\n")
            sb.append("• Keep the whole record in the Data vault — every reading, and the chain that proves it wasn't rewritten.")
            return base(Intent.QUERY, sb.toString(), before)
        }

        // --- create a standing question (governed act) ---
        val trackMatch = Regex("\\b(?:keep track of|track|watch)\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (trackMatch != null && Regex("\\b(whether|if|of)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trackMatch.groupValues[1])) {
            val questionText = trackMatch.groupValues[1].replace(Regex("^whether\\s+", RegexOption.IGNORE_CASE), "").trim()
            // A fragment is not a question. "keep track of?" parses to
            // the word "of" — proposing a standing question out of it
            // would manufacture a nonsense commitment and then hold
            // the sovereign's "yes" hostage to it. Clarify instead.
            val contentWords = questionText.lowercase()
                .replace(Regex("[^a-z ]"), " ")
                .split(Regex("\\s+"))
                .filter { w -> w.length > 2 && w !in setOf("the", "and", "for", "whether", "what", "that", "this") }
            if (questionText.length < 8 || contentWords.isEmpty()) {
                return clarify(
                    Intent.CLARIFY,
                    "That came through as a fragment — I can't tell what you'd want tracked.",
                    before,
                    "What should I keep track of? Say it in a few words — like \"whether the cabinet job is paid\".",
                )
            }
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

        // --- read the screen (before ask: "what's on my screen?" is
        // a device action wearing question grammar; the answer is
        // the screen's contents, not an evidence search) ---
        if (Regex("^what'?s on (?:my )?screen\\b|^read (?:my )?screen\\b|^look at (?:my )?screen\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "read_screen",
                    target = "screen",
                    parameters = emptyMap(),
                    expectedEffect = "Read the current screen and report what's on it.",
                    reversibility = Reversibility.REVERSIBLE,
                    consequenceClass = ConsequenceClass.LOCAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: read the screen.")
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
                // The answer is the reply — composed prose, no
                // machinery suffix. Settled-ness is woven in only
                // when it adds something the prose doesn't already
                // say: independent confirmation is worth naming.
                val confirmation =
                    if (q.current.delta == Delta.EQUIVALENT) "\nThat's confirmed by more than one source." else ""
                return base(Intent.ASK, q.current.text + confirmation, before)
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

        // --- query: account status --------------------------------
        // "Check an account status" means: what does the record
        // hold for the mailbox and the money — answered from the
        // collectors' state and the ledger, never invented.
        if (Regex("\\b(account status|check my (email|mail|account|inbox)|how many (new )?(emails|messages)|any new (email|mail)|unread)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            val live = deps.ledger.liveEvents()
            val emails = live.filter { it.topic == "email.message" }
            val finance = live.filter { it.topic.startsWith("finance") }
            val emailCollector = deps.collectors.get("email")
            val emailState = when (emailCollector?.status) {
                CollectorStatus.ACTIVE -> "collecting"
                CollectorStatus.PAUSED -> "paused" + (emailCollector.statusReason?.let { " — $it" } ?: "")
                CollectorStatus.DENIED -> "off" + (emailCollector.statusReason?.let { " — $it" } ?: "")
                null -> "not registered"
            }
            val sb = StringBuilder("Account status, from your record:\n")
            sb.append("• Mailbox: $emailState. ${emails.size} message(s) on record")
            emails.maxByOrNull { it.seq }?.let { sb.append("; latest — ${it.claim}") }
            sb.append(".\n")
            sb.append("• Finances: ${finance.size} alert(s) on record")
            finance.maxByOrNull { it.seq }?.let { sb.append("; latest — ${it.claim}") }
            sb.append(".\n")
            sb.append("Sync runs while sensing is on (about every 15 minutes), or on demand in Settings → Connected email → Fetch now.")
            return base(Intent.QUERY, sb.toString(), before)
        }

        // --- act: device actions (the phone, operated as him) ----
        // A request becomes a proposal; policy, assent, and the
        // record apply exactly as for any governed action. The
        // platform handler executes only after assent (or a rail).
        val callMatch = Regex("^(?:call|phone|dial)\\s+(.+?)\\s*\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (callMatch != null && !lower.contains("collector")) {
            val who = callMatch.groupValues[1].trim()
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "phone_call",
                    target = who,
                    parameters = mapOf("to" to who),
                    expectedEffect = "Place a phone call to $who.",
                    reversibility = Reversibility.IRREVERSIBLE,
                    consequenceClass = ConsequenceClass.EXTERNAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: call $who.")
        }
        val textMatch = Regex("^(?:so\\s+)?(?:i want you to\\s+|please\\s+)?send\\s+(.+?)\\s+a\\s+(?:message|text|sms)\\s+(?:saying|telling\\s+(?:her|him|them)(?:\\s+that)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^(?:please\\s+)?send (?:a )?(?:text|message|sms) to\\s+(.+?)\\s+(?:saying|that says|telling\\s+(?:her|him|them)(?:\\s+that)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^text\\s+(.+?)\\s+(?:saying|that says)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^message\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^text\\s+(.+?)\\s*[:—]\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (textMatch != null) {
            val who = textMatch.groupValues[1].trim()
            val body = textMatch.groupValues[2].trim()
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "send_text",
                    target = who,
                    parameters = mapOf("to" to who, "body" to body),
                    expectedEffect = "Send a text to $who: \"$body\"",
                    reversibility = Reversibility.IRREVERSIBLE,
                    consequenceClass = ConsequenceClass.EXTERNAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: text $who.")
        }
        // "tell <person> <message>" — the most natural form of all.
        // Parsed in code, not regex: the name is one word, or two
        // when the second is a capitalized name-part and not a
        // pronoun/verb ("tell Shannon I love her" vs "tell Mary
        // Jane I'll call"). "tell me…" never lands here.
        val tellRest = Regex("^tell\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        if (tellRest != null && !lower.startsWith("tell me")) {
            val words = tellRest.split(Regex("\\s+"))
            val nonNameWords = setOf("i", "you", "we", "he", "she", "they", "it", "the", "a", "that", "to", "about", "whether", "if", "why", "when", "how")
            if (words.size >= 2 && words[1].lowercase() !in setOf("about", "whether", "if", "why", "when", "how")) {
                val twoWordName = words.size >= 3 && words[1][0].isUpperCase() && words[1].lowercase() !in nonNameWords
                val nameWordCount = if (twoWordName) 2 else 1
                val who = words.take(nameWordCount).joinToString(" ")
                val body = words.drop(nameWordCount).joinToString(" ")
                if (body.isNotBlank() && who.lowercase() != "me") {
                    val proposal = deps.actions.propose(
                        ProposalInput(
                            actionType = "send_text",
                            target = who,
                            parameters = mapOf("to" to who, "body" to body),
                            expectedEffect = "Send a text to $who: \"$body\"",
                            reversibility = Reversibility.IRREVERSIBLE,
                            consequenceClass = ConsequenceClass.EXTERNAL,
                            requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                            requestedBy = "sovereign",
                        ),
                    )
                    return finishProposal(proposal, before, "Action proposed: text $who.")
                }
            }
        }
        if (Regex("^send (?:a )?text\\s*\\.?$|^text\\s+\\S+\\s*\\.?$", RegexOption.IGNORE_CASE).containsMatchIn(lower) &&
            !Regex("\\b(track|collector|question)\\b").containsMatchIn(lower)
        ) {
            return clarify(
                Intent.CLARIFY,
                "I can send it — I need who it's for and what it says, in one line: \"text Maria saying I'll be there at 7\".",
                before,
                "Who should I text, and what should it say?",
            )
        }
        val emailMatch = Regex("^(?:send )?(?:an )?email to\\s+(\\S+@\\S+)\\s+(?:saying|that says)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^(?:send )?(?:an )?email\\s+(\\S+@\\S+)\\s+(?:saying|that says)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("^(?:send )?(?:an )?email to\\s+(\\S+@\\S+)\\s*[:—]\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (emailMatch != null) {
            val to = emailMatch.groupValues[1].trim()
            val body = emailMatch.groupValues[2].trim()
            val subject = body.lineSequence().firstOrNull()?.take(60) ?: "Message from GuardianLink"
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = "send_email",
                    target = to,
                    parameters = mapOf("to" to to, "subject" to subject, "body" to body),
                    expectedEffect = "Send an email to $to from your connected mailbox: \"$subject\"",
                    reversibility = Reversibility.IRREVERSIBLE,
                    consequenceClass = ConsequenceClass.EXTERNAL,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: email $to.")
        }
        if (Regex("^send (?:an )?email\\s*\\.?$|^email\\s*\\.?$", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return clarify(
                Intent.CLARIFY,
                "I can send it from your connected mailbox — I need the address and the message in one line: \"email maria@example.com saying the invoice is attached\".",
                before,
                "Who should I email, and what should it say?",
            )
        }

        // --- act: device control (the fingers) ---------------------
        fun deviceProposal(type: String, target: String, params: Map<String, Any?>, effect: String, reversible: Reversibility = Reversibility.IRREVERSIBLE, cc: ConsequenceClass = ConsequenceClass.EXTERNAL): ConversationResult {
            val proposal = deps.actions.propose(
                ProposalInput(
                    actionType = type,
                    target = target,
                    parameters = params,
                    expectedEffect = effect,
                    reversibility = reversible,
                    consequenceClass = cc,
                    requiredAuthority = RequiredAuthority.FRESH_ASSENT,
                    requestedBy = "sovereign",
                ),
            )
            return finishProposal(proposal, before, "Action proposed: $effect")
        }
        val pressMatch = Regex("^(?:go back|go home|show recents|open notifications)\\s*\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (pressMatch != null) {
            val key = when {
                lower.startsWith("go back") -> "back"
                lower.startsWith("go home") -> "home"
                lower.startsWith("show recents") -> "recents"
                else -> "notifications"
            }
            return deviceProposal("device_press", key, mapOf("key" to key), "Press $key.")
        }
        val openMatch = Regex("^(?:please\\s+)?open\\s+(.+?)\\s*\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (openMatch != null && !lower.contains("collector")) {
            val app = openMatch.groupValues[1].trim()
            return deviceProposal("device_open_app", app, mapOf("app" to app), "Open $app.")
        }
        val tapMatch = Regex("^(?:tap|click)(?:\\s+on)?\\s+(.+?)\\s*\\.?$", RegexOption.IGNORE_CASE).find(text)
        if (tapMatch != null) {
            val what = tapMatch.groupValues[1].trim()
            return deviceProposal("device_tap", what, mapOf("text" to what), "Tap \"$what\" on the current screen.")
        }
        val typeMatch = Regex("^type\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
        if (typeMatch != null) {
            val typed = typeMatch.groupValues[1].trim()
            return deviceProposal("device_type", "text field", mapOf("text" to typed), "Type \"$typed\" into the current field.")
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

        // --- unrecognized command shape: never file a command as a note ---
        // His ruling (2026-10-25, the Shannon message): the agent
        // recorded the request and failed to execute — not because
        // it couldn't, but because it never tried. An utterance
        // shaped like a command that matched no action form must
        // surface exactly that — "no form for it yet, nothing done"
        // — with the forms that DO work. It never silently becomes
        // a record.
        val commandShaped =
            Regex("^(send|call|text|email|message|dial|phone|open|remind|book|order|pay|schedule)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
                Regex("\\b(i want you to|please|can you|could you|will you)\\s+(send|call|text|email|message|tell|open|remind|book|order|pay|schedule)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        if (commandShaped) {
            return clarify(
                Intent.CLARIFY,
                "That reads as a command, and I don't have a form for it yet — so nothing was done with it, and I won't file it away as a note. The command forms I can run today: \"text Shannon saying …\", \"tell Shannon …\", \"call …\", \"email name@host saying …\", \"pause <collector>\". Say it in one of those and it goes proposal → your yes → executed, with the outcome recorded either way.",
                before,
                "Which form should that be?",
            )
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
                "Recorded: \"${ev.claim}\" — your standing answers are updated.$conflictNote",
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
            return base(Intent.ACT, "$lead One of your standing rails already covers it, so it ran without asking again. ${exec.message}", before)
                .copy(proposal = proposal, execution = exec)
        }
        if (proposal.policy == PolicyOutcome.DENIED ||
            proposal.policy == PolicyOutcome.DRIFT_FLAGGED ||
            proposal.policy == PolicyOutcome.INSUFFICIENTLY_SPECIFIED
        ) {
            return base(Intent.ACT, "$lead I can't run that — ${proposal.policyReason} Nothing was executed.", before)
                .copy(proposal = proposal)
        }
        return base(
            Intent.ACT,
            "$lead This one needs your say-so first — ${proposal.policyReason} Say \"yes\" and I'll run it; ignore it and nothing happens.",
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
                "I don't have anything on record about \"$about\" yet — and I won't guess at it.",
                before,
            )
        }
        val lines = matches.map { e -> "• ${e.claim}" }
        return base(intent, "Here's what I have on \"$about\":\n${lines.joinToString("\n")}", before)
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
