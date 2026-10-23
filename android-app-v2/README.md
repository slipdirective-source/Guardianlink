# GuardianLink v0.2 — android-app-v2 (first cut, staging)

First cut of the GuardianLink Surface v0.2 native Android module, built
to [ANDROID-PORT-MAP.md](../ANDROID-PORT-MAP.md) (§c module plan, §d
permission/FGS matrix, §e CI steps) and the v0.2 correction spec §3/§5/§6.

**Status: NOT COMPILED.** There is no Android SDK or kotlinc in the
authoring environment. Every engine signature used here was read from
the Guardianlink sources at `main` @
`50ae09dfbf25107b967ed154d6a0af45f255db6c` (fresh shallow clone), and
the code was statically checked (imports, package names, manifest
references, delimiter balance), but **compilation is unverified until
the first CI run** — see MODULE-INTEGRATION.md §5 for the exact steps
and the CI-verify-first list.

This tree is staging: paths are relative to the future `android-app-v2/`
module root in the Guardianlink repo. The existing `android-app/`
scaffold is not touched by anything here.

## What is real vs stubbed — explicit

| Piece | State |
|---|---|
| Pure-Kotlin core (`guardianlink.v2.core`): ledger, standing questions, conversation, governed actions, registries, audit, sync | **Real.** Faithful port of the verified TS core (`../src/*.ts`), semantics matched file-by-file. Hash compatibility is pinned by known-answer tests generated from the TS core itself (see Tests). |
| JUnit 5 tests (26) | **Real.** Port of the TS suites with identical assertions; run in CI via `:android-app-v2:testDebugUnitTest`. |
| Engine wiring (`GuardianLinkV2App`) | **Real, production posture.** `ProductionVerifiers` (real Ed25519) with all deployment ports at their deny defaults; `RailAuthorizer.DENY_ALL`; `ConciergeInterface` + `NineGates` + `DurableCapabilityStore` + `MerkleAuditLog` + file anchor, mirroring the scaffold's graph shape. |
| Governed engine EXECUTION | **Unavailable — fails closed, by design.** `FailClosedEvidenceProvider` throws on every evidence request (see the fail-closed note below). Nothing is fabricated; the UI surfaces the refusal verbatim. |
| Voice biometric port | **Denying.** `DenyBiometricVerifier` rejects every ceremony; enrollment throws. No synthetic-voice bootstrap (the scaffold's demo approach is not inherited). |
| Identity keys | **Ephemeral in this cut.** Operator/revocation Ed25519 keypairs are generated at engine init and logged as such. Keystore-wrapped persistence is required before any real assent can bind to them — and is moot until evidence exists. |
| Revocation feed | **Empty unless deployed.** If `filesDir/revocations.jsonl` exists it is loaded via the engine's `SignedRevocationListFeed` (a bad file aborts engine init — fail-closed); otherwise an empty feed is wired and the fact is logged. |
| Live Now UI + conversation input | **Real.** Programmatic views; answers re-render on version change, including background admissions from the sensing service. |
| Device-state collector | **Real.** Screen on/off/user-present, battery level + power connect/disconnect, network availability/transport. Events enter through `core.admitSensor("device_state", …)`, so core collector governance applies. |
| SensingService | **Real skeleton + working collector.** `specialUse` FGS with the manifest justification property; typed `startForeground` guarded to API 34+. Boot restart via `BootReceiver` only if sensing was running. |
| Location / motion collectors | **Stubs — honest.** Declared in the manifest (permissions, §d), named as unavailable in the standing answers via marker events (`SensorStubs`), emit **no data**. No fake fixes, no inferred movement. |
| Sync transport in the Kotlin core | **Object-level.** Bundles export/import with the TS merge semantics (verify root+chain, idempotent, conflict-preserving) plus a canonical-JSON write side (`SyncBundle.toCanonicalJson`). A JSON *parser* for foreign bundles is deliberately not in this cut: importing untrusted text needs a strict parser, not a lenient one. |
| Accounts / agent plugs on Android | **Core-only in this cut.** The registries and their boundary checks are ported and tested in `guardianlink.v2.core`; no Android UI or OAuth flow yet (Phase D per the map). |

## The fail-closed wiring decision

The scaffold's only working Android wiring runs `ScaffoldVerifiers`
(every check returns true) and `RailAuthorizer.PERMISSIVE` — a posture
that LOOKS governed while passing everything (PORT-MAP §f risk 1). This
module takes the opposite branch at every seam:

1. `ProductionVerifiers` is wired with real keys, and every injected
   port is left at the class's own fail-closed default: ZK denies,
   boundary denies, policy denies, divergence is +Inf, meta-loop denies.
2. `PolicyEngine` gets `RailAuthorizer.DENY_ALL`: no hard rail can be
   armed until a real authorizer exists.
3. Evidence cannot be constructed at all: `FailClosedEvidenceProvider`
   throws `EvidenceUnavailableException` before any `ActionEvidence`
   exists. Gate 1 (Ed25519 origin signature), Gate 3 (ZK proof — no
   prover exists), and Gate 7 (AssentCodec triple-bound signature +
   voice ceremony against an enrolled template) are all unimplemented,
   and placeholder bytes would be a lie with a structure.
4. The voice port can never ACCEPT (`DenyBiometricVerifier`), so the
   inert placeholder template NineGates requires cannot authorize.

Consequence, stated plainly: **governed engine actions cannot execute
in this cut.** The bridge, gates, policy engine, capability store, and
audit anchor are all real and wired; the missing piece is the evidence
ceremony, and until it exists the system refuses — visibly — rather
than performing governance. The v0.2 core's own governed LOCAL actions
(standing questions, rails, collector pause, export) execute through
the core's ActionLayer and are unaffected.

## File inventory

Core — `src/main/kotlin/guardianlink/v2/core/` (no `android.*` imports):

| File | Bytes | Contents |
|---|---|---|
| Canonical.kt | 3,753 | Canonical JSON serialization + SHA-256 (TS-compatible; see vectors in tests) |
| Types.kt | 5,404 | Delta (4-valued), event/relation/polarity enums, EvidenceEvent, AdmitInput, IsoTime, V2Clock |
| Ledger.kt | 5,589 | Append-only hash-chained evidence ledger, foreign-event merge views, chain verification |
| Audit.kt | 2,632 | Tamper-evident audit chain (separate from the ledger) |
| Questions.kt | 16,243 | Standing-question engine: five core questions, dependency tracking, versioned answers, conflicts |
| Actions.kt | 15,159 | ActionProposal, policy outcomes, rails, assent binding, local adapters, no-fake-execution |
| Registries.kt | 9,070 | Collectors, agent plugs (scoped, revocable), accounts |
| Sync.kt | 4,797 | Sync bundles: export/import, verification, idempotent conflict-preserving merge |
| Conversation.kt | 26,069 | Deterministic conversational classifier (report/ask/query/correct/retract/configure/act/clarify) |
| Harness.kt | 4,941 | `GuardianLink` facade wiring the whole core |

Android layer — `src/main/kotlin/guardianlink/v2/app/`:

| File | Bytes | Contents |
|---|---|---|
| GuardianLinkV2App.kt | 11,268 | Application: core creation + engine graph with production wiring (see above) |
| GovernedActionBridge.kt | 5,493 | Sole-writer engine bridge (scaffold ActionDispatcher shape) + the fail-closed evidence provider |
| MainActivity.kt | 7,875 | Live Now surface, conversational input, sensing controls, engine-posture demo |
| SensingService.kt | 5,774 | Foreground sensing service + BootReceiver |
| DeviceStateCollector.kt | 6,685 | The working collector: screen/battery/network |
| SensorStubs.kt | 1,946 | Location/motion honest-unavailability markers |

Build/config/docs:

| File | Bytes | Contents |
|---|---|---|
| build.gradle.kts | 2,445 | namespace `guardianlink.app`, applicationId `guardianlink.app.v2`, compile/targetSdk 34, minSdk 26, deps per map §e step 2 (engine project, core-ktx 1.13.1, appcompat 1.7.0, security-crypto 1.1.0-alpha06, JUnit 5.9.2) |
| src/main/AndroidManifest.xml | 4,777 | Launcher activity, Application class, SensingService (`specialUse` + justification property), BootReceiver; permissions per §d. Component names are fully qualified: namespace (`guardianlink.app`) ≠ class package (`guardianlink.v2.app`). `allowBackup="false"`. |
| MODULE-INTEGRATION.md | 4,758 | Exact settings include line, workflow install, push sequence, CI-verify-first list |
| .github-draft/apk-v0.2.yml | 1,728 | Draft workflow (installs to `.github/workflows/apk-v0.2.yml`): unit tests, then `:android-app-v2:assembleDebug`, artifact `guardianlink-v0.2-debug-apk` |

Tests — `src/test/kotlin/guardianlink/v2/core/` (JUnit 5, matching the
engine's test stack): TestSupport.kt (611 B), LedgerTest.kt (6,016 B),
AcceptanceTest.kt (17,412 B).

## Tests: inventory mapped to spec §6

They are **local unit tests of this Android module** and run on the JVM
in CI via `:android-app-v2:testDebugUnitTest` (map §e step 5). The core
under test has no Android dependencies, so the suites exercise the full
port. 26 tests:

- `LedgerTest` (8): hash chain verifies + tamper detection; append-only
  retraction; four-valued Delta never collapsed; dependency tracking;
  inference never promoted to observation; **+ 3 known-answer vectors**
  (canonical bytes, first-event ledger hash, first-entry audit hash)
  generated by running the TS core — these pin cross-implementation
  hash compatibility, not just internal consistency.
- `AcceptanceTest` (18): §6.1 report updates answers · §6.2 live
  conflict · §6.3 correction supersedes · §6.3 ambiguous retraction
  clarifies · §6.4 sensor arrival (admitted sensor-kind events, as in
  the TS suite) · §6.5 change query · §6.6 standing-question lifecycle
  + drift · §6.7 no execution before assent · §6.8 drift-flag refusal /
  rail execution · §6.9 stop-collecting takes effect + audited ·
  §6.10 offline continuity · §6.11 permission denial · §6.12 no fake
  execution · §6.13 account authority · §6.14 sync merge · §6.15 audit
  reconstruction · §6.16 agent-plug bounds · clarify-never-guesses.

Divergence from the TS suite, deliberate: §6.10/§6.14 pass the sync
bundle as an object instead of a JSON file round-trip (see the sync row
above). The semantics under test — verification, idempotence, conflict
preservation — are transport-independent.

## Remaining steps to CI (parent executes, on Caleb's word)

Full detail in MODULE-INTEGRATION.md. Summary:

1. Copy this tree into the repo as `android-app-v2/` (layout preserved).
2. Add `include("android-app-v2")` to `settings.gradle.kts` — the only
   repo file that changes. Root build and gradle.properties need nothing.
3. Install `.github-draft/apk-v0.2.yml` as `.github/workflows/apk-v0.2.yml`.
4. Push via the PAT `gh-put-file` route (can write workflows).
5. First CI run = first compilation. Watch, in order: JUnit platform
   uptake (the log must show 26 tests, not 0), engine named-argument
   construction, the API-34-guarded `startForeground`, receiver flags.
6. Download the APK artifact; record its SHA-256 with the commit hash.
7. On-device smoke on the Galaxy A36 (not CI-able): permission trips,
   FGS survival across screen-off and reboot, honest degradation with
   each permission denied (spec §6.10/§6.11).

## Guessed / isolated engine touchpoints (flagged list)

Everything else was read directly from the engine sources at 50ae09df.
The following are the only places judgment replaced a read signature:

1. **`EngineEvidenceProvider`** (GovernedActionBridge.kt) is an
   app-side seam modeled on the scaffold's `EvidenceSupplier`
   fun-interface — it is not an engine API, and its only implementation
   throws. When the evidence ceremony is built, this is the single
   place it plugs in.
2. **Inert `VoiceTemplate`** construction (`SpeakerEmbedding.Vector`
   with an empty array + empty phrase map): the constructor shape is
   real (read from BiometricVerifier.kt); the *choice* of an inert
   instance is ours, and it is safe only because the paired verifier
   rejects unconditionally.
3. **Empty `RevocationFeed`** when no signed list file is deployed:
   the port interface and its "empty means none known" contract are
   real (read from RevocationFeed.kt); shipping without a deployed
   list is a posture decision, logged at init.
4. **Sync JSON parsing** is not ported (object-level bundles only) —
   a scope cut, documented above, not a signature guess.
