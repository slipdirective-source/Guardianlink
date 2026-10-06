# GuardianLink — v1 Kotlin Scaffold

Sovereign personal data platform: zero-authority concierge interface, fail-closed
tiered PolicyEngine, Merkle tamper-evident audit log, Shamir's Secret Sharing MFA,
MAD-based trajectory estimation, and a friction/cooling-off pre-commitment rail
system.

## Build

Push this repo to GitHub as-is — .github/workflows/ci.yml will build and test
it automatically via GitHub Actions (free runners, full JDK/Gradle support).

Locally: `gradle build test` (requires JDK 17+; no Gradle wrapper is checked in).

## Verification status

The algorithmic core of every bug-prone module (Merkle checkpointing, MAD shift
detection, CAS concurrency, Shamir reconstruction, PolicyEngine fail-closed
invariant) was verified via adversarial Python-ported test batches before this
Kotlin code was written, confirming the logic is sound. The Kotlin itself is
compiler-verified (kotlinc 1.9.22, JDK 17) with a 155-test suite run
locally plus CI (`.github/workflows/ci.yml`) and CodeQL
(`.github/workflows/codeql.yml`) on every push.

Not included (expansion-path, not v1 core): MinorProfile adapter,
HierarchicalOversight adapter, hardware Keystore/AES-GCM bindings (Android-target
specific), aggregate research/consent-bundle layer.

## Trust boundaries & adapter requirements

This scaffold is **fail-closed against hostile inputs** (fuzz-tested: NaN/Inf
numerics, throwing verifiers, hostile clocks, AST smuggling, share injection).
What it does **not** do yet is verify the *truth* of what callers tell it.
Until the adapters below exist, the engine checks **consistency of caller
claims, not truth**:

| Claim source | What the engine checks | What it does NOT check (adapter required) |
|---|---|---|
| `GateContext` evidence (biometric vectors, SNR, entropy, parse trees, proof bytes) | Internal consistency, bounds, finiteness | That the values are true — a caller can supply self-consistent lies |
| `Verifiers` ports (signatures, ZK, assent, revocation, policy) | Totality (throw → halt), boolean outcomes | Cryptographic validity itself — real signature/ZK/attestation adapters required |
| Shamir share categories | Share math (field ranges, no dup x, threshold, mandatory HW label) | That a share labeled `HARDWARE_BIOMETRIC` really came from hardware — attestation binding + VSS commitments required |
| Trajectory samples | Finiteness, magnitude bounds, self-consistency, windowed anomalies | That samples are authentic — signed/attested sensor streams required |
| Merkle ledger | Tamper-evidence within the process | Immutability — requires a **durable external anchor** (write-once store / timestamping authority); the demo anchor only prints |
| Assent idle-expiry (`theta_assent_idle`) | Assent age + ledger silence vs `maxAssentIdleMs` (24h) — an assent dies only when BOTH are exceeded | Ledger append times are caller-supplied: forged future-dated appends fake liveness the same way they forge the audit trail — the external anchor is the recourse |
| Revocation scan | All revocations known at Gate 8 + seal time | Revocations issued after `evaluate()` returns but before the caller commits — callers MUST re-scan at commit or hold a commit lock |
| `PolicyEngine` rail lifecycle | Authorization hook (`RailAuthorizer`, default deny-all) | Real authentication — capability tokens / signed admin commands required in production |
| `FrictionStateMachine` time | Injected clock + backward-jump high-water mark | The clock being truly monotonic — deployments must inject a monotonic source |
| `DurableMonotonicClock` | Persists at most once per 1000ms of clock advance; resumes at persisted+1000ms (crash-safe, up to 1s forward ratchet on restart) | The state file being writable and un-tampered — persistence is best-effort and crash-monotonicity degrades to the wall clock if the file can't be written; monitor the file in production |

## Nine Gates policy core (`guardianlink.gates`)

Executable model of the Global Master Codex v2.2 Nine Gates FSM
(Sovereign Information Dynamics): `Action.kt` (A_total bounded AST),
`Rails.kt` (fixed-rail thresholds), `GateContext.kt` (sigma vector),
`Render.kt` (canonical rendering the person signs), `NineGates.kt`
(the engine + `Verifiers` ports).

- Deterministic, fail-closed: any failing predicate drops to `S_HALT`,
  and every halt — including revocations — is appended to the Merkle ledger.
- Crypto/governance plug in through `Verifiers`; the engine itself is pure.
- Gate 7: assent over `Render(a)` (never the payload), idle-max-age
  (`maxAssentIdleMs` = 24h — an assent dies only when BOTH the assent is old
  AND the ledger has been silent that long), fixed-rail cooling window
  scaling with AST-computed impact, signed revocation until seal.
- Gate 8: prepare-then-commit — `applyAction` is pure, the caller commits
  by adopting `GateOutcome.Integrated.newSubstrate`. The seal is never post-hoc.
- 155 tests across the suite (`NineGatesTest` / `NineGatesAdversarialTest` hold 73 between them) cover every gate's
  halt path, cooling scaling, revocation, ledger appends, render injectivity,
  and single-evaluation. Demo in `Main.kt`.