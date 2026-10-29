package guardianlink.v2.core

import java.util.Locale

/** Port of TS `ClusterId` in `src/questions.ts`. */
enum class ClusterId(val wire: String) {
    POSITION("position"),
    HISTORY("history"),
    RELATIONSHIPS("relationships"),
    TRAJECTORY("trajectory"),
    AGENCY("agency"),
    CUSTOM("custom"),
}

data class Conflict(
    val topic: String,
    val eventIds: List<String>,
    val claims: List<String>,
)

/** How an answer is grounded. An inference is never silently promoted to observation. */
enum class Basis(val wire: String) {
    OBSERVED("observed"),
    INFERRED("inferred"),
    MIXED("mixed"),
    NONE("none"),
}

/** Port of TS `AnswerSnapshot`. */
data class AnswerSnapshot(
    val version: Int,
    val text: String,
    val delta: Delta,
    val basis: Basis,
    val evidenceIds: List<String>,
    val conflicts: List<Conflict>,
    val retractions: List<String>,
    val evaluatedAt: String,
    val changeNote: String,
)

/** Port of TS `StandingQuestion`. */
class StandingQuestion(
    val id: String,
    val text: String,
    val cluster: ClusterId,
    /** dependency topic prefixes; irrelevant admissions do not touch this question */
    val topicPrefixes: List<String>,
    /** for custom questions: terms matched against event topic/claim */
    val matchTerms: List<String>,
    val priority: Int,
    var current: AnswerSnapshot,
) {
    val history: MutableList<AnswerSnapshot> = mutableListOf()
    var stale: Boolean = true
    var recomputeCount: Int = 0
}

private val DRIFT_TERMS = listOf("drift", "behind", "delay", "over budget", "missed", "problem", "stuck")

private data class TopicState(
    val topic: String,
    val live: List<EvidenceEvent>,
    val latest: EvidenceEvent?,
    val conflict: Conflict?,
)

private data class EvalResult(
    val text: String,
    val delta: Delta,
    val basis: Basis,
    val evidenceIds: List<String>,
    val conflicts: List<Conflict>,
    val retractions: List<String>,
)

/**
 * Standing-question engine (spec §3.3). Port of TS `src/questions.ts`.
 *
 * The five core questions are standing computations over the ledger, not
 * answers to prompts: dependency tracking marks a question stale when a
 * relevant event is admitted and recomputes it immediately. Every answer
 * carries a version, an evidence set, a four-valued delta, conflicts
 * (surfaced, never silently resolved), and a change history.
 */
class QuestionEngine(
    private val ledger: Ledger,
    private val now: V2Clock = V2Clock { java.time.Instant.now() },
) {
    private val questions = LinkedHashMap<String, StandingQuestion>()

    /** set by the harness to audit material answer changes */
    var onVersionChange: ((StandingQuestion, AnswerSnapshot) -> Unit)? = null

    /**
     * Set by the harness to the action layer's open options (rails
     * in force, recent counterparties) — the Agency answer's raw
     * material (SMOOTHNESS-ADDENDUM §4). The engine cannot see the
     * action layer directly (the layer depends on the engine), so
     * the harness wires this after construction, like
     * [onVersionChange]. Null = no options source; Agency then
     * speaks only from evidence, as before.
     */
    var agencyOptionsProvider: (() -> List<String>)? = null

    init {
        define("position", "Where am I?", ClusterId.POSITION, listOf("location", "medication", "state", "collector"), emptyList(), 100)
        define("history", "How did I get here?", ClusterId.HISTORY, emptyList(), emptyList(), 90)
        define("relationships", "What is interacting with what?", ClusterId.RELATIONSHIPS, listOf("project", "relationship", "account", "person"), emptyList(), 80)
        define("trajectory", "Where am I going?", ClusterId.TRAJECTORY, listOf("location", "project", "plan"), emptyList(), 70)
        define("agency", "What can I do about it?", ClusterId.AGENCY, listOf("medication", "action", "rail", "collector"), emptyList(), 60)
        ledger.onAdmit { e -> handleAdmission(e) }
    }

    private fun define(
        id: String,
        text: String,
        cluster: ClusterId,
        prefixes: List<String>,
        terms: List<String>,
        priority: Int,
    ): StandingQuestion {
        val q = StandingQuestion(
            id = id,
            text = text,
            cluster = cluster,
            topicPrefixes = prefixes,
            matchTerms = terms,
            priority = priority,
            current = AnswerSnapshot(
                version = 0,
                text = "(not yet evaluated)",
                delta = Delta.UNKNOWN,
                basis = Basis.NONE,
                evidenceIds = emptyList(),
                conflicts = emptyList(),
                retractions = emptyList(),
                evaluatedAt = IsoTime.format(now.now()),
                changeNote = "created",
            ),
        )
        questions[id] = q
        recompute(q)
        return q
    }

    fun addCustomQuestion(text: String, createdBy: String): StandingQuestion {
        val terms = extractTerms(text)
        val slug = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        val id = "q-$slug-${questions.size}"
        return define(id, text, ClusterId.CUSTOM, emptyList(), terms, 50)
    }

    fun get(id: String): StandingQuestion? = questions[id]

    fun all(): List<StandingQuestion> = questions.values.sortedByDescending { it.priority }

    fun findCustomByTerm(term: String): StandingQuestion? =
        all().firstOrNull { q ->
            q.cluster == ClusterId.CUSTOM && q.matchTerms.any { t -> term.lowercase().contains(t) || t.contains(term.lowercase()) }
        }

    /** Dependency tracking: an admission only touches questions it is relevant to. */
    private fun relevant(q: StandingQuestion, e: EvidenceEvent): Boolean {
        if (q.cluster == ClusterId.HISTORY) return true
        if (q.topicPrefixes.any { p -> e.topic == p || e.topic.startsWith(p + ".") || e.topic.startsWith(p) }) return true
        if (q.matchTerms.isNotEmpty()) {
            val hay = (e.topic + " " + e.claim).lowercase()
            if (q.matchTerms.any { t -> hay.contains(t) }) return true
        }
        // an event that retracts/supersedes/conflicts evidence this answer rests on is always relevant
        if (e.relations.any { r -> q.current.evidenceIds.contains(r.targetId) }) return true
        return false
    }

    private fun handleAdmission(e: EvidenceEvent) {
        for (q in questions.values.toList()) {
            if (relevant(q, e)) {
                q.stale = true
                recompute(q)
            }
        }
    }

    private fun topicStates(
        events: List<EvidenceEvent>,
        prefixes: List<String>,
        terms: List<String>,
    ): Pair<List<TopicState>, List<String>> {
        val live = ledger.liveEvents()
        val matched = live.filter { e ->
            if (events.isNotEmpty() && !events.contains(e)) return@filter false
            if (prefixes.isNotEmpty() && prefixes.any { p -> e.topic.startsWith(p) }) return@filter true
            if (terms.isNotEmpty()) {
                val hay = (e.topic + " " + e.claim).lowercase()
                if (terms.any { t -> hay.contains(t) }) return@filter true
            }
            prefixes.isEmpty() && terms.isEmpty()
        }
        val byTopic = LinkedHashMap<String, MutableList<EvidenceEvent>>()
        for (e in matched) byTopic.getOrPut(e.topic) { mutableListOf() }.add(e)
        val states = mutableListOf<TopicState>()
        for ((topic, evs) in byTopic) {
            val sorted = evs.sortedBy { it.seq }
            val affirms = sorted.filter { it.polarity == Polarity.AFFIRM }
            val negates = sorted.filter { it.polarity == Polarity.NEGATE }
            val conflict: Conflict? =
                if (affirms.isNotEmpty() && negates.isNotEmpty()) {
                    Conflict(topic, sorted.map { it.id }, sorted.map { it.claim })
                } else {
                    null
                }
            states.add(TopicState(topic, sorted, sorted.lastOrNull(), conflict))
        }
        val retractedIds = ledger.retractedIds()
        val retracted = ledger.allEvents().filter { e -> retractedIds.contains(e.id) }.map { it.id }
        return Pair(states, retracted)
    }

    /**
     * Surface prose for a claim. The ledger keeps the claim verbatim;
     * what the sovereign reads is a sentence, not a terminal line: a
     * trailing parenthetical becomes an aside ("Battery at 67% (not
     * charging)" → "Battery at 67% — not charging"), and no topic
     * names, source tags, or confidence values ever reach the text.
     * The machinery stays underground — in the ledger, the vault, and
     * the audit chain — where it belongs.
     */
    private fun proseClaim(claim: String): String {
        val m = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$").find(claim)
        val body = if (m != null) "${m.groupValues[1]} — ${m.groupValues[2]}" else claim
        return if (body.endsWith(".") || body.endsWith("!") || body.endsWith("?")) body else "$body."
    }

    private fun renderStateLine(s: TopicState): String {
        val e = s.latest!!
        var line = proseClaim(e.claim)
        if (e.agentDerived) {
            // Provenance is never laundered: a claim an agent derived
            // is labeled as one, in the sentence itself — the surface
            // rule from the product model, kept in prose form.
            line += " That's agent-derived — an inference, not an observation."
        }
        if (s.conflict != null) {
            val others = s.conflict.claims.filter { c -> c != e.claim }
            if (others.isNotEmpty()) {
                line += " In CONFLICT with: ${others.joinToString(" / ") { proseClaim(it).removeSuffix(".") }}."
            }
        }
        return line
    }

    /** A collector's status, as a sentence a person would say. */
    private fun collectorLine(s: TopicState): String {
        val name = s.topic.removePrefix("collector.").replaceFirstChar { it.uppercase() }
        val claim = s.latest!!.claim
        return if (claim == "collecting") {
            "$name is being collected."
        } else {
            "$name collector — ${proseClaim(claim)}"
        }
    }

    private fun evaluate(q: StandingQuestion): EvalResult {
        val all = ledger.allEvents()
        val live = ledger.liveEvents()

        // inapplicable is a DECLARED state: a system marker event on every dependency
        val markerTopics = if (q.topicPrefixes.isNotEmpty()) q.topicPrefixes else q.matchTerms
        if (markerTopics.isNotEmpty()) {
            val markers = live.filter { e -> e.kind == EventKind.SYSTEM && e.claim == "__not_applicable__" }
            val allMarked = markerTopics.all { t -> markers.any { m -> m.topic == t || m.topic.startsWith(t) } }
            if (allMarked && markers.isNotEmpty()) {
                return EvalResult(
                    text = "Nothing about that applies right now.",
                    delta = Delta.INAPPLICABLE,
                    basis = Basis.NONE,
                    evidenceIds = markers.map { it.id },
                    conflicts = emptyList(),
                    retractions = emptyList(),
                )
            }
        }

        if (q.cluster == ClusterId.HISTORY) {
            // One pass for the retracted set: asking the ledger per
            // event here made every History recompute O(n^2) — the
            // same arithmetic class as the Vault freeze, one layer down.
            val retractedList = ledger.retractedIds().let { ids -> all.filter { e -> ids.contains(e.id) }.map { it.id } }
            if (all.isEmpty()) {
                return EvalResult(
                    text = "Nothing yet — nothing has happened since tracking started, so there's no path to report.",
                    delta = Delta.UNKNOWN,
                    basis = Basis.NONE,
                    evidenceIds = emptyList(),
                    conflicts = emptyList(),
                    retractions = emptyList(),
                )
            }
            // History is a PATH: where the sovereign has been, plus
            // what materially executed along the way. It is not a
            // dump of the last six ledger entries — governance
            // chatter, collector status, and filed notes are not a
            // trajectory (2026-10-25: a recorded sentence about
            // Shannon narrated as "how you got here").
            val path = all.filter { e -> e.topic.startsWith("location.") }.sortedBy { it.seq }.takeLast(6)
            val outcomes = all.filter { e -> e.kind == EventKind.OUTCOME }.sortedBy { it.seq }.takeLast(3)
            if (path.isEmpty() && outcomes.isEmpty()) {
                return EvalResult(
                    text = "No path recorded yet — nothing has moved and nothing has executed since tracking started.",
                    delta = Delta.UNKNOWN,
                    basis = Basis.NONE,
                    evidenceIds = emptyList(),
                    conflicts = emptyList(),
                    retractions = retractedList,
                )
            }
            val rawLines = (path + outcomes).sortedBy { it.seq }.map { e -> proseClaim(e.claim) }
            val lines = rawLines.filterIndexed { i, line ->
                i == 0 || !line.trim().trimEnd('.').equals(rawLines[i - 1].trim().trimEnd('.'), ignoreCase = true)
            }
            val shown = (path + outcomes).sortedBy { it.seq }
            return EvalResult(
                text = "Here's how you got here:\n" + lines.joinToString("\n"),
                delta = Delta.DISTINGUISHED,
                basis = Basis.MIXED,
                evidenceIds = shown.map { it.id },
                conflicts = emptyList(),
                retractions = retractedList,
            )
        }

        // Custom questions track the world, not the governance chatter about themselves:
        // proposals/assents/executions/outcomes and the creation notice are excluded from term matching.
        val evalEvents =
            if (q.cluster == ClusterId.CUSTOM) {
                all.filter { e ->
                    e.kind !in setOf(EventKind.ACTION_PROPOSAL, EventKind.ASSENT, EventKind.EXECUTION, EventKind.OUTCOME) &&
                        e.topic != "standing_question"
                }
            } else {
                all
            }
        val (states, retracted) = topicStates(evalEvents, q.topicPrefixes, q.matchTerms)
        val conflicts = states.mapNotNull { it.conflict }
        val evidenceIds = states.flatMap { s -> s.live.map { it.id } }

        if (states.isEmpty()) {
            // Each question reads quiet in its OWN type (§4/§5):
            // trajectory with no evidence says there is no movement
            // on record; agency with no evidence still owes the
            // sovereign his open options, because a rail in force
            // lives in the action layer, not in evidence topics.
            if (q.cluster == ClusterId.TRAJECTORY) {
                return EvalResult(
                    text = "No movement on record today — nothing shows you going anywhere new yet.",
                    delta = Delta.UNKNOWN,
                    basis = Basis.NONE,
                    evidenceIds = emptyList(),
                    conflicts = emptyList(),
                    retractions = retracted,
                )
            }
            if (q.cluster == ClusterId.AGENCY) {
                val options = agencyOptionsProvider?.invoke() ?: emptyList()
                return EvalResult(
                    text = if (options.isNotEmpty()) {
                        options.joinToString("\n")
                    } else {
                        "Nothing is waiting on you, and no rail is in force right now."
                    },
                    delta = Delta.UNKNOWN,
                    basis = Basis.NONE,
                    evidenceIds = emptyList(),
                    conflicts = emptyList(),
                    retractions = retracted,
                )
            }
            return EvalResult(
                text = "I don't know yet — nothing on record bears on that.",
                delta = Delta.UNKNOWN,
                basis = Basis.NONE,
                evidenceIds = emptyList(),
                conflicts = emptyList(),
                retractions = retracted,
            )
        }

        // Compose the answer as prose. The composition differs by
        // question because the questions mean different things: the
        // geographic answer leads with the place and keeps metrics
        // (location.fix readouts) and other collectors' statuses out
        // of it entirely — those are the vault's material, not an
        // answer's. Everywhere, the lines are sentences; the topic
        // names, sources, and confidences that produced them stay in
        // the ledger where they can be audited.
        val lines = mutableListOf<String>()
        if (q.cluster == ClusterId.POSITION) {
            val location = states.firstOrNull { it.topic == "location" }
            if (location?.latest != null) lines.add(renderStateLine(location))
            val locCollector = states.firstOrNull { it.topic == "collector.location" }
            if (locCollector?.latest != null && (location?.latest == null || locCollector.latest!!.claim != "collecting")) {
                lines.add(collectorLine(locCollector))
            }
            for (s in states) {
                if (s.topic == "location" || s.topic == "location.fix" || s.topic.startsWith("collector.")) continue
                lines.add(renderStateLine(s))
            }
            if (lines.isEmpty()) lines.add("I don't know yet — no position evidence has come in.")
        } else {
            for (s in states) {
                if (s.topic == "location.fix") continue
                // Collector status transitions (granted/denied/paused)
                // are availability facts — they belong to Position
                // and the self-report, not to a narrative answer.
                // "How did I get here?" must never read as a list of
                // permission states.
                if (s.topic.startsWith("collector.")) continue
                // Trajectory answers in its own type (§4): the place
                // you are AT is Position's sentence. Restating it
                // here — worse, restating it three times — is the
                // parroting the sovereign named nonsense. Trajectory
                // gets a movement delta below, or the honest quiet.
                if (q.cluster == ClusterId.TRAJECTORY && s.topic == "location") continue
                // Agency answers in its own type (§4): the action
                // log (proposals, executions, outcomes) is History's
                // material. Listing what already ran as "what you
                // can do" was the 2026-10-27 defect, verbatim.
                if (q.cluster == ClusterId.AGENCY && s.topic.startsWith("action.")) continue
                lines.add(renderStateLine(s))
            }
        }

        if (q.cluster == ClusterId.TRAJECTORY) {
            val loc = states.firstOrNull { it.topic == "location" }
            if (loc != null && loc.live.size > 1) {
                val firstPlace = proseClaim(loc.live[0].claim).removeSuffix(".")
                val lastPlace = proseClaim(loc.latest!!.claim).removeSuffix(".")
                // A delta is a DIFFERENT place. Same place at both
                // ends is not movement, and printing it as
                // "Movement: X → X" is a position wearing
                // trajectory's clothes (§4).
                if (!firstPlace.equals(lastPlace, ignoreCase = true)) {
                    lines.add("Movement: $firstPlace → ${proseClaim(loc.latest!!.claim)}")
                }
            }
            for (s in states) {
                if (s.latest != null && DRIFT_TERMS.any { t -> s.latest.claim.lowercase().contains(t) }) {
                    lines.add("Something in the latest ${s.topic.substringAfterLast('.').replace('_', ' ')} reading suggests drift.")
                }
            }
            if (lines.isEmpty()) {
                lines.add("No movement on record today — nothing shows you going anywhere new yet.")
            }
        }
        if (q.cluster == ClusterId.AGENCY) {
            val proposals = live.filter { it.kind == EventKind.ACTION_PROPOSAL }
            val executed = live.filter { it.kind == EventKind.EXECUTION }
                .flatMap { e -> e.relations.filter { r -> r.type == RelationType.EXECUTES }.map { r -> r.targetId } }
                .toSet()
            val pending = proposals.filter { p -> !executed.contains(p.id) }
            for (p in pending) lines.add("Waiting on your say-so: ${proseClaim(p.claim)}")
            // Open options: rails in force and people he can reach —
            // what he CAN do, sourced from the action layer (§4).
            agencyOptionsProvider?.invoke()?.let { options ->
                for (option in options) {
                    if (lines.none { it.equals(option, ignoreCase = true) }) lines.add(option)
                }
            }
            if (conflicts.isNotEmpty()) lines.add("Some options are blocked by a conflict that hasn't been resolved.")
            if (lines.isEmpty()) lines.add("Nothing is waiting on you, and no rail is in force right now.")
        }
        if (q.cluster == ClusterId.CUSTOM) {
            val drift = states.any { s -> s.latest != null && DRIFT_TERMS.any { t -> s.latest.claim.lowercase().contains(t) } }
            lines.add(if (drift) "DRIFT FLAGGED: matching evidence carries drift signals." else "No drift signals in matching live evidence.")
        }

        val kinds = states.flatMap { s -> s.live.map { it.kind } }.toSet()
        val basis: Basis =
            if (kinds.contains(EventKind.SENSOR_OBSERVATION)) {
                if (kinds.size > 1) Basis.MIXED else Basis.OBSERVED
            } else if (kinds.contains(EventKind.DERIVED_INFERENCE) || kinds.contains(EventKind.AGENT_DERIVED)) {
                Basis.INFERRED
            } else {
                Basis.MIXED
            }

        val delta: Delta =
            if (conflicts.isNotEmpty()) {
                Delta.UNKNOWN
            } else {
                val corroborated = states.any { s ->
                    val sources = s.live
                        .filter { e -> e.polarity == (s.latest?.polarity ?: Polarity.AFFIRM) }
                        .map { it.source }
                        .toSet()
                    sources.size >= 2
                }
                if (corroborated) Delta.EQUIVALENT else Delta.DISTINGUISHED
            }

        // One fact, once (§2): identical sentences — the same claim
        // admitted by two paths, the same place resolved twice —
        // merge before rendering. Comparison is on the sentence,
        // case- and punctuation-insensitive; the first (freshest
        // composed) form is the one kept.
        val seenSentences = HashSet<String>()
        val dedupedLines = lines.filter { line ->
            seenSentences.add(line.trim().trimEnd('.').lowercase())
        }
        return EvalResult(dedupedLines.joinToString("\n"), delta, basis, evidenceIds, conflicts, retracted)
    }

    fun recompute(q: StandingQuestion): AnswerSnapshot {
        val result = evaluate(q)
        q.recomputeCount += 1
        q.stale = false
        val cur = q.current
        val changed =
            cur.version == 0 ||
                cur.text != result.text ||
                cur.delta != result.delta ||
                cur.conflicts != result.conflicts
        if (!changed) {
            q.current = cur.copy(evaluatedAt = IsoTime.format(now.now()))
            return q.current
        }
        val snap = AnswerSnapshot(
            version = cur.version + 1,
            text = result.text,
            delta = result.delta,
            basis = result.basis,
            evidenceIds = result.evidenceIds,
            conflicts = result.conflicts,
            retractions = result.retractions,
            evaluatedAt = IsoTime.format(now.now()),
            changeNote = if (cur.version == 0) "initial evaluation" else "updated: ${result.text.split("\n")[0].take(120)}",
        )
        q.current = snap
        q.history.add(snap)
        onVersionChange?.invoke(q, snap)
        return snap
    }

    /** Force re-evaluation of every question (e.g. after sync import). */
    fun recomputeAll() {
        for (q in questions.values.toList()) recompute(q)
    }

    /**
     * Persistence hook for [StateStore]: insert or replace one question
     * with its persisted state — current snapshot, full version history,
     * staleness, and recompute count. No recomputation happens here and
     * [onVersionChange] does not fire: a restore reproduces the saved
     * state, it does not re-derive it.
     */
    fun restoreQuestionState(
        id: String,
        text: String,
        cluster: ClusterId,
        topicPrefixes: List<String>,
        matchTerms: List<String>,
        priority: Int,
        current: AnswerSnapshot,
        history: List<AnswerSnapshot>,
        stale: Boolean,
        recomputeCount: Int,
    ): StandingQuestion {
        val q = StandingQuestion(id, text, cluster, topicPrefixes, matchTerms, priority, current)
        q.history.addAll(history)
        q.stale = stale
        q.recomputeCount = recomputeCount
        questions[id] = q
        return q
    }
}

/** Port of TS `extractTerms` in `src/questions.ts`. */
fun extractTerms(text: String): List<String> {
    val stop = setOf(
        "whether", "the", "a", "an", "is", "are", "keep", "track", "of", "my", "me", "if", "it",
        "this", "that", "still", "going", "to", "do", "does", "did", "what", "how", "am", "i",
    )
    val words = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { w -> w.length > 2 && !stop.contains(w) }
    val terms = LinkedHashSet<String>(words)
    for (i in 0 until words.size - 1) terms.add(words[i] + " " + words[i + 1])
    return terms.toList().take(8)
}
