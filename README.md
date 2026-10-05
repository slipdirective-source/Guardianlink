# GuardianLink — v1 Kotlin Scaffold

Sovereign personal data platform: zero-authority concierge interface, fail-closed
tiered PolicyEngine, Merkle tamper-evident audit log, Shamir's Secret Sharing MFA,
MAD-based trajectory estimation, and a friction/cooling-off pre-commitment rail
system.

## Build

Push this repo to GitHub as-is — .github/workflows/build.yml will build and test
it automatically via GitHub Actions (free runners, full JDK/Gradle support).

Locally: ./gradlew build test (requires JDK 17+).

## Verification status

The algorithmic core of every bug-prone module (Merkle checkpointing, MAD shift
detection, CAS concurrency, Shamir reconstruction, PolicyEngine fail-closed
invariant) was verified via adversarial Python-ported test batches before this
Kotlin code was written, confirming the logic is sound. The Kotlin itself has been
manually audited (brace/paren balance, import/package consistency) but not yet
compiler-verified — first CI run will confirm.

Not included (expansion-path, not v1 core): MinorProfile adapter,
HierarchicalOversight adapter, hardware Keystore/AES-GCM bindings (Android-target
specific), aggregate research/consent-bundle layer.

## Nine Gates policy core (`guardianlink.gates`)

Executable model of the Global Master Codex v2.2 Nine Gates FSM
(Sovereign Information Dynamics): `Action.kt` (A_total bounded AST),
`Rails.kt` (fixed-rail thresholds), `GateContext.kt` (sigma vector),
`Render.kt` (canonical rendering the person signs), `NineGates.kt`
(the engine + `Verifiers` ports).

- Deterministic, fail-closed: any failing predicate drops to `S_HALT`,
  and every halt — including revocations — is appended to the Merkle ledger.
- Crypto/governance plug in through `Verifiers`; the engine itself is pure.
- Gate 7: assent over `Render(a)` (never the payload), fixed-rail cooling
  window scaling with AST-computed impact, signed revocation until seal.
- Gate 8: prepare-then-commit — `applyAction` is pure, the caller commits
  by adopting `GateOutcome.Integrated.newSubstrate`. The seal is never post-hoc.
- 20 tests in `NineGatesTest` cover every gate's halt path, cooling
  scaling, revocation, and ledger appends. Demo in `Main.kt`.