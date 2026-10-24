# GuardianLink v0.2 — android-app-v2 (staging)

The GuardianLink Surface v0.2 native Android module, built to
[ANDROID-PORT-MAP.md](../ANDROID-PORT-MAP.md) (§c module plan, §d
permission/FGS matrix, §e CI steps) and the v0.2 correction spec
§3/§5/§6.

**Status:** the first cut of this module is pushed to the Guardianlink
repo and CI-green (26 tests). **This slice — persistence + encryption
at rest, and the location/motion collectors — is staged here and NOT
yet compiled.** There is no Android SDK or kotlinc in the authoring
environment; engine signatures were read from the Guardianlink sources
at `main` @ `50ae09dfbf25107b967ed154d6a0af45f255db6c` (fresh shallow
clone) and the Android APIs used are framework/security-crypto
signatures documented in the code. Compilation is unverified until
the parent's CI push — see the CI-verify list at the bottom.

This tree mirrors the live `android-app-v2/` module in the repo. The
existing `android-app/` scaffold is not touched by anything here.

## What this slice adds

1. **Persistence (pure-Kotlin core).** `StateStore` serializes the full
   core state — ledger events (local chain + foreign), audit entries,
   standing-question state including every answer version in history,
   collector/plug/account registries, rails and proposals — to one
   canonical-JSON document (`gl-state-1`), behind a `StateStorage`
   interface (read/write/list) with an in-memory implementation (tests)
   and a `java.io` file implementation (temp-write + fsync + atomic
   rename). Loads verify BEFORE trust: strict JSON parse (a dedicated
   strict parser, `JsonParser.kt`), format check, ledger chain
   verification, per-event hash checks for foreign events, audit chain
   verification on a scratch log, and root/sequence cross-checks. Any
   failure throws `StateStoreException` with a named kind
   (PARSE_ERROR, UNSUPPORTED_FORMAT, LEDGER_CHAIN_INVALID,
   EVENT_HASH_MISMATCH, AUDIT_CHAIN_INVALID, INCONSISTENT_STATE,
   STORAGE_READ_FAILED) and loads nothing — no partial state, no
   silent reset, and a failed load never writes to the storage.
2. **Encryption at rest (app layer).** `EncryptedStateStorage` runs
   the file store through the scaffold's established
   Keystore/`EncryptedFile` pattern. The Application restores the core
   from it at startup (load-or-initialize, chains verified) and
   surfaces a visible fail-closed persistence state in the UI when
   verification fails; a failed store is never overwritten by the
   session. Format and recovery posture are documented below.
3. **Location + motion collectors (app layer).** Framework APIs only
   (`LocationManager`, `SensorManager`), replacing the honest stubs.
   Permission flows in MainActivity; denials degrade by name in the
   standing answers, as before.

## Persistence format and recovery posture

- **Document:** `gl-state-1`, canonical JSON (the `Canonical.kt`
  writer), one document for the whole core state. The EVENT encoding
  is shared with `SyncBundle.toCanonicalJson` (hashed body map + hash)
  and the document carries the same `Ledger.rootOf` commitment over
  the local chain. The difference from a sync bundle is scope, and it
  is deliberate: sync exports EVIDENCE for another device to merge;
  the state document additionally carries harness-internal state
  (audit chain, question snapshots, registries, rails, proposals)
  that must be restored exactly on this device and never merged into
  another device's internals.
- **Encryption:** the plaintext document above is sealed with
  `EncryptedFile` (AES256_GCM_HKDF_4KB) under a Keystore-held
  `MasterKey` (AES256_GCM scheme) — the scaffold `TemplateStore`
  pattern verbatim. Keys never leave the Keystore.
- **Atomicity:** EncryptedFile binds ciphertext to the file name, so
  temp+rename is unavailable; the encrypted store uses two fixed
  slots per name (`harness-state.a.enc` / `.b.enc`) plus a plaintext
  slot marker. Writes go to the inactive slot, are decrypted back and
  byte-compared, and only then does the marker flip. A crash mid-write
  leaves the prior slot active and untouched; a marker-named slot that
  fails to decrypt falls back to the other slot (an older authentic
  state — StateStore still hash-verifies whatever it is handed).
- **Recovery posture, named honestly:** Keystore key loss
  (invalidation, some credential changes, factory reset) makes the
  ciphertext unreadable and the stored state is GONE. The app surfaces
  that as a named persistence failure and runs visibly on an empty
  in-memory core; there is no plaintext fallback, no silent reset, and
  no key escrow in this build.
- **Save points and their limit:** state is written at explicit save
  points (completed conversation turns, activity pause). This
  protects the store against corruption and torn writes; it does NOT
  make every admission durable — a process kill between save points
  loses the admissions since the last one. Admission-level durability
  (an append-only write-ahead admission log) is future work and is
  the same open question as PORT-MAP §f risk 2 for the engine audit.

## Location/motion: the GMS trade-off (PORT-MAP §f risk 5, decided for this slice)

Framework APIs only — no Google Play services dependency is added.
`LocationManager` and `SensorManager` keep the build dependency-free
beyond androidx and work on devices without Play services. The costs
are real: framework location is less power-optimized than
FusedLocation (no fused batching/adaptive cadence), first fix can be
slower, and still/moving classification is our own accelerometer
classifier instead of the Activity Recognition API's models. Accuracy
is taken from each fix's own reported radius and drives the admitted
confidence — never invented. Revisit if on-device power/accuracy
soaks on the Galaxy A36 say the cost is too high; that would be a
dependency decision for Caleb, not a silent swap.

## What is real vs stubbed — explicit

| Piece | State |
|---|---|
| Pure-Kotlin core (`guardianlink.v2.core`): ledger, standing questions, conversation, governed actions, registries, audit, sync | **Real.** Faithful port of the verified TS core, semantics matched file-by-file. Hash compatibility is pinned by known-answer tests generated from the TS core itself (see Tests). |
| Persistence (`StateStore`, `JsonParser`, storage interface + in-memory/file implementations) | **Real.** Full-state canonical document; atomic file writes; verify-before-trust loads with named errors. Persistence hooks added to Ledger/Audit/Questions/Registries/Actions are surgical and restore-only (no admission events, no audit side effects). |
| Encryption at rest (`EncryptedStateStorage`) | **Real.** Keystore MasterKey + EncryptedFile per the scaffold pattern; two-slot atomicity; verified-before-commit slot flips; wired into Application startup and save points. |
| JUnit 5 tests (38) | **Real.** The 26 from the first cut plus PersistenceTest (7) and SensorLogicTest (5); run in CI via `:android-app-v2:testDebugUnitTest`. |
| Engine wiring (`GuardianLinkV2App`) | **Real, production posture.** `ProductionVerifiers` (real Ed25519) with all deployment ports at their deny defaults; `RailAuthorizer.DENY_ALL`; `ConciergeInterface` + `NineGates` + `DurableCapabilityStore` + `MerkleAuditLog` + file anchor, mirroring the scaffold's graph shape. |
| Governed engine EXECUTION | **Unavailable — fails closed, by design.** `FailClosedEvidenceProvider` throws on every evidence request (see the fail-closed note below). Nothing is fabricated; the UI surfaces the refusal verbatim. |
| Voice biometric port | **Denying.** `DenyBiometricVerifier` rejects every ceremony; enrollment throws. No synthetic-voice bootstrap (the scaffold's demo approach is not inherited). |
| Engine identity keys | **Ephemeral in this cut.** Operator/revocation Ed25519 keypairs are generated at engine init and logged as such. Keystore-wrapped persistence is required before any real assent can bind to them — and is moot until evidence exists. (The CORE state is persisted and encrypted as of this slice; the ENGINE keys are a separate store that does not exist yet.) |
| Revocation feed | **Empty unless deployed.** If `filesDir/revocations.jsonl` exists it is loaded via the engine's `SignedRevocationListFeed` (a bad file aborts engine init — fail-closed); otherwise an empty feed is wired and the fact is logged. |
| Live Now UI + conversation input | **Real.** Programmatic views; answers re-render on version change, including background admissions from the sensing service. The persistence posture (restored / fresh / FAILED with the named error) is rendered on the surface. |
| Device-state collector | **Real.** Screen on/off/user-present, battery level + power connect/disconnect, network availability/transport. Events enter through `core.admitSensor("device_state", …)`, so core collector governance applies. |
| Location collector | **Real (this slice).** `LocationManager` updates under the sensing FGS; provider chosen by permission + enabled state (`LocationLogic`); confidence derived from each fix's reported accuracy; last-known fix admitted and labeled as such. Permission-absent → the collector is DENIED by name in the registry and Position says so. |
| Motion collector | **Real (this slice).** Accelerometer → gravity-removed [MotionClassifier] → still/moving transitions; step counter → [StepDeltaTracker] → accumulated step deltas. Everything it emits is DERIVED_INFERENCE (source `sensor:motion`) through an explicit assertCollecting gate — classifications are never dressed as raw observations. SENSOR_DELAY_NORMAL sampling, listeners bound to the service lifecycle. Gated on ACTIVITY_RECOGNITION (API 29+); absent → named DENIED, registers nothing. |
| SensingService | **Real.** `specialUse|location|health` FGS; typed `startForeground` passes exactly the type subset whose prerequisites are met (API 34+); runs all three collectors, each self-reporting unavailability at the harness boundary. Boot restart via `BootReceiver` only if sensing was running. |
| Runtime permission flow | **Real.** MainActivity requests POST_NOTIFICATIONS (33+), fine+coarse location, and ACTIVITY_RECOGNITION (29+) in one trip before starting sensing. `ACCESS_BACKGROUND_LOCATION` is deliberately NOT bundled (§d: it must be requested separately/incrementally); results reconcile collector states transition-only via `CollectorAvailability` (a sovereign pause is never overridden). |
| Sync transport in the Kotlin core | **Object-level.** Bundles export/import with the TS merge semantics (verify root+chain, idempotent, conflict-preserving) plus a canonical-JSON write side (`SyncBundle.toCanonicalJson`). A JSON *parser* for foreign bundles is still not in the core: `JsonParser` exists now, but it was built and threat-modeled for our own state document; wiring it to untrusted foreign bundles is a separate, deliberate step. |
| Accounts / agent plugs on Android | **Core-only.** The registries and their boundary checks are ported and tested in `guardianlink.v2.core` (and now persist); no Android UI or OAuth flow yet (Phase D per the map). |

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
5. Persistence fails closed the same way: an unverifiable store loads
   nothing, resets nothing, and is never overwritten by the session
   that failed to verify it.

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
| Ledger.kt | 7,018 | Append-only hash-chained evidence ledger, foreign-event merge views, chain verification, persistence restore hook |
| Audit.kt | 3,429 | Tamper-evident audit chain (separate from the ledger), persistence restore hook |
| Questions.kt | 17,185 | Standing-question engine: five core questions, dependency tracking, versioned answers, conflicts, persistence restore hook |
| Actions.kt | 15,961 | ActionProposal, policy outcomes, rails, assent binding, local adapters, no-fake-execution, persistence hooks |
| Registries.kt | 10,104 | Collectors, agent plugs (scoped, revocable), accounts; persistence hooks |
| Sync.kt | 4,797 | Sync bundles: export/import, verification, idempotent conflict-preserving merge |
| StateStore.kt | 24,827 | Full-state persistence: `gl-state-1` document, StateStorage interface, in-memory + atomic file storage, verify-before-trust load with named errors |
| JsonParser.kt | 8,393 | Strict JSON parser (read side of the canonical format; duplicate keys, trailing content, and malformed numbers rejected) |
| Conversation.kt | 26,069 | Deterministic conversational classifier (report/ask/query/correct/retract/configure/act/clarify) |
| Harness.kt | 4,941 | `GuardianLink` facade wiring the whole core |

Android layer — `src/main/kotlin/guardianlink/v2/app/`:

| File | Bytes | Contents |
|---|---|---|
| GuardianLinkV2App.kt | 15,063 | Application: encrypted-store core restore + engine graph with production wiring (see above) |
| GovernedActionBridge.kt | 5,493 | Sole-writer engine bridge (scaffold ActionDispatcher shape) + the fail-closed evidence provider |
| MainActivity.kt | 10,509 | Live Now surface, conversational input, sensing controls + runtime permission flow, persistence status and save points |
| SensingService.kt | 10,604 | Foreground sensing service (specialUse/location/health subset typing) running all three collectors + BootReceiver |
| DeviceStateCollector.kt | 6,685 | The device-state collector: screen/battery/network |
| LocationCollector.kt | 7,637 | LocationManager collector + `LocationLogic` (pure provider/confidence/claim logic) |
| MotionCollector.kt | 8,659 | SensorManager collector + `MotionClassifier` / `StepDeltaTracker` (pure logic); derived-inference emissions only |
| CollectorAvailability.kt | 2,579 | Transition-only reconciliation of collector registry state with OS permission grants |
| EncryptedStateStorage.kt | 6,736 | Keystore/EncryptedFile StateStorage: two-slot atomic encrypted store, recovery posture documented in-file |

Build/config/docs:

| File | Bytes | Contents |
|---|---|---|
| build.gradle.kts | 2,445 | namespace `guardianlink.app`, applicationId `guardianlink.app.v2`, compile/targetSdk 34, minSdk 26, deps per map §e step 2 (engine project, core-ktx 1.13.1, appcompat 1.7.0, security-crypto 1.1.0-alpha06, JUnit 5.9.2) |
| src/main/AndroidManifest.xml | 4,632 | Launcher activity, Application class, SensingService (`specialUse|location|health` + justification property), BootReceiver; permissions per §d. Component names are fully qualified: namespace (`guardianlink.app`) ≠ class package (`guardianlink.v2.app`). `allowBackup="false"`. |
| MODULE-INTEGRATION.md | 4,758 | Exact settings include line, workflow install, push sequence (first-cut integration; the module is live in the repo) |
| .github-draft/apk-v0.2.yml | 2,214 | Draft workflow (already installed in the repo): unit tests, then `:android-app-v2:assembleDebug`, artifact `guardianlink-v0.2-debug-apk` |

Tests — `src/test/kotlin/guardianlink/v2/` (JUnit 5, matching the
engine's test stack): TestSupport.kt (611 B), LedgerTest.kt (6,016 B),
AcceptanceTest.kt (17,418 B), PersistenceTest.kt (10,645 B),
SensorLogicTest.kt (5,260 B).

## Tests: inventory mapped to spec §6

They are **local unit tests of this Android module** and run on the JVM
in CI via `:android-app-v2:testDebugUnitTest` (map §e step 5). The core
under test has no Android dependencies, so the suites exercise the full
port. 38 tests:

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
  bundle as an object instead of a JSON file round-trip (see the sync
  row above). The semantics under test — verification, idempotence,
  conflict preservation — are transport-independent.
- `PersistenceTest` (7, this slice): round trip preserves the full
  state exactly (re-serialization is byte-identical; both chains
  verify after reload) · a state carrying a live conflict, a bound
  retraction, a foreign event, a revoked plug, a disconnected account,
  paused/denied collectors, and an executed proposal keeps all of it
  through persistence, and its rail still authorizes · tampered ledger
  content → LEDGER_CHAIN_INVALID · tampered audit content →
  AUDIT_CHAIN_INVALID · unsupported format → named error · interrupted
  write (torn temp file) leaves the prior good state loadable, and a
  truncated document is rejected (PARSE_ERROR) without the failed load
  modifying the stored bytes · absent store loads as null and an
  untouched harness round-trips.
- `SensorLogicTest` (5, this slice): confidence bands from fix
  accuracy · provider-choice matrix (permissions × enabled providers)
  · claim text (coordinates, provider, honest accuracy) · motion
  classifier transitions (transitions only; settles back to still) ·
  step tracker (baseline, threshold emission, counter-reset survival).

## Remaining steps to CI (parent executes, on Caleb's word)

1. Mirror this staging tree over the repo's `android-app-v2/` (the
   module, settings include, and workflow already exist there from
   the first cut — this slice changes module files only, plus it
   DELETES `SensorStubs.kt`).
2. Push via the PAT `gh-put-file` route.
3. CI run = first compilation of this slice. The log must show 38
   tests, not 26 and not 0. Watch the CI-verify list below in order.
4. Download the APK artifact; record its SHA-256 with the commit hash.
5. On-device smoke on the Galaxy A36 (not CI-able): state restore
   across an app restart (chains verify, answers intact), permission
   trips for location/activity-recognition, FGS survival across
   screen-off and reboot, location fixes landing in Position with
   sensible confidences, motion transitions while walking, honest
   degradation with each permission denied (spec §6.10/§6.11), and a
   battery soak for the framework-location power cost.

## CI-verify list for this slice (unverified locally — no SDK/kotlinc here)

In addition to the first cut's list (MODULE-INTEGRATION.md §5):

1. `EncryptedFile` slot behavior: repeated `openFileOutput` on a
   pre-deleted slot file, and `MasterKey.Builder(...).build()` under
   the app's minSdk 26 (KeyGen
...[truncated 2691 chars]