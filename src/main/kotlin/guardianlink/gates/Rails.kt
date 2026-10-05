package guardianlink.gates

/**
 * Fixed rails (Codex v2.2, §I.3 — Fixed Rail Invariance).
 *
 * Immutable specification constants. No adaptive loop, trajectory estimate,
 * or evidence record may modify them or grant authority. Construct once and
 * share freely; the engine never mutates them.
 *
 * delta_cool is a fixed *schedule* (a function of computed impact), not a
 * threshold constant — see [coolingWindowMs].
 */
data class Rails(
    val gammaNoise: Double = 10.0,        // Gate 0: minimum signal SNR (theta_snr)
    val epsilonBio: Double = 0.15,        // Gate 1: biometric distance bound (theta_bio)
    val hMax: Double = 4.0,              // Gate 4: context entropy bound, bits (theta_ent)
    val epsilonDrift: Double = 0.05,     // Gate 5: intent-drift cosine bound (theta_drift)
    val epsilonGovernance: Double = 0.1, // Gate 6: governance divergence bound (psi_align)
    val deltaT: Long = 60_000L,          // Gate 3: proof freshness window, ms (theta_time)
    val baseCoolingMs: Long = 5_000L,    // Gate 7: cooling window base, ms
    val coolingPerImpactStep: Long = 5_000L, // Gate 7: added per impact level above READ_ONLY
    /**
     * Gate 7: assent idle-max-age, ms (theta_assent_idle).
     *
     * An outstanding assent dies only when BOTH hold: (a) the assent itself
     * is older than this, AND (b) the audit ledger has been silent (no
     * appends) longer than this. Rationale: a fresh assent on a quiet
     * system still authorizes (the person is present); an old assent on a
     * continuously-logging system rode along under observation; but an old
     * assent plus a dark ledger means the world moved unobserved — fail
     * closed. A null/empty ledger counts as silent since the assent time.
     */
    val maxAssentIdleMs: Long = 86_400_000L, // 24h
    val maxActionNodes: Int = 64,        // Gate 0: A_total action-node bound (theta_ast)
    val maxActionDepth: Int = 8,         // Gate 0: A_total action-depth bound (theta_ast)
    val maxConditionNodes: Int = 64,    // Gate 0: condition-node bound (theta_ast)
    val maxConditionDepth: Int = 8,      // Gate 0: condition-depth bound (theta_ast)
    val maxStringBytes: Int = 256,      // Gate 0: per-atom UTF-8 byte bound (theta_ast)
    val maxReadFields: Int = 32,        // Gate 0: Read field-count bound (theta_ast)
    val maxWriteFields: Int = 32,       // Gate 0: Write map-cardinality bound (theta_ast)
    val maxPayloadBytes: Long = 65_536L,// Gate 0: total payload byte bound (theta_ast)
    val maxMemoryBytes: Long = 1_048_576L, // Gate 7: allocation bound (theta_mem)
    val maxCycles: Long = 1_000_000L,    // Gate 7: cycle bound (theta_mem)
    val bytesPerNode: Long = 1_024L,     // Gate 7: static memory estimate per AST node
    val cyclesPerNode: Long = 1_000L,    // Gate 7: static cycle estimate per AST node
) {
    /**
     * Fixed-rail cooling schedule (Codex v2.2, Gate 7, theta_cool).
     * Scales with COMPUTED impact — never self-declared.
     */
    fun coolingWindowMs(impact: Impact): Long =
        baseCoolingMs + impact.ordinal * coolingPerImpactStep
}
