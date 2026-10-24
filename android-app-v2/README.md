# android-app-v2 — GuardianLink v0.2 surface (Android module)

Status: **slice 3 — the engine evidence path is built.** The v0.2 core
(ledger, standing questions, conversation, governed actions, rails,
sync, audit) is ported to pure Kotlin and JVM-tested. The device layer
persists the harness state encrypted at rest (AES-256-GCM via
EncryptedFile + Android Keystore MasterKey), collects location and
motion through framework APIs only (no Google Play Services), and —
new in this slice — carries governed actions **all the way into the
GuardianLink engine**: persistent Ed25519 key custody, policy ports
derived from the core's own governance state, substrate/action
translation, and the full assent ceremony through
`ConciergeInterface`, the engine's sole doorway. What the ceremony can
and cannot complete today is stated per gate, not smoothed over:
**Gate 3 (ZK) is at deny by ruling, so production execution halts at
Gate 3** and the UI says exactly that. The build is still
**code-complete / CI-pending**: no local SDK exists in the authoring
environment; the parent's CI run (push + `:android-app-v2:testDebugUnitTest`
+ APK assemble) is the compiler of record.

Scope authority: `EVIDENCE-PATH.md` (per-gate evidence ledger, the
assent ceremony steps 1–6, key-custody requirements) and
`ANDROID-PORT-MAP.md` in the parent directory. The TypeScript core in
`guardianlink-v0.2/` remains the semantic reference; this module is its
Kotlin twin, held to identical canonical bytes (hash vectors pinned in
tests).

## Real vs stubbed — the honest table

| Capability | State |
|---|---|
| Ledger (hash-chained), standing questions, conversation (classify/answer/correct/retract), actions + rails, sync bundles, audit | **Real**, JVM-tested |
| Persistence: full harness state, encrypted at rest | **Real** (EncryptedFile/AES-256-GCM; plaintext store is tests-only) |
| Collectors: location (framework LocationManager), motion (SensorManager) | **Real** on device; rule logic JVM-tested. Battery/device-state pending (collector interface exists) |
| Sensors stay off | **Real**: OS permission AND core collector status both required; either one off ⇒ no collection |
| Foreground sensing service | **Real** code; on-device survival (screen-off, reboot) is a device-test item, not yet run |
| Engine evidence path: key custody, policy ports, translation, assent ceremony, Concierge submission, outcome write-back | **Real**, JVM-tested (see below). End-to-end `Integrated` is reachable in tests with a TEST-ONLY ZK stub; in production wiring Gate 3 denies, so execution halts at Gate 3 — by design, per ruling |
| Engine substrate | **Real but seeded-once**: the substrate is the core projection taken at engine init; later core changes are not re-projected until restart (named limitation) |
| Voice (Gate 7 θ_voice) | **Absent, honestly**: the production port never accepts — no enrolled template ⇒ `NOT_CHECKED`; a template without a real verifier ⇒ `NOT_LIVE`. Voice evidence absent ⇒ Gate 7 halts. Enrollment is the named next step; no synthetic template exists anywhere |
| Revocation feed (Gate 8) | **Real mechanism, empty deployment**: a SignedRevocationListFeed-compatible JSON file (`revocations.json`) is loaded when present; absent file ⇒ no revocations. No list is deployed yet |
| Accounts / plugs / sync transports | Registries and rules real; transports are local (file-grade) — no network sync yet |

## The engine evidence path (this slice)

### The assent ceremony (EVIDENCE-PATH steps 1–6)

`core/AssentCeremony.kt` builds the GateContext bundle and submits it;
`app/GovernedActionBridge.kt` is now a thin delegate to it (the old
throwing `FailClosedEvidenceProvider` is gone).

1. **Proposal** — the core's `ActionProposal`, admitted and visible
   before anything engine-side exists.
2. **Translate** — `EngineTranslation.translate` produces the engine
   `Action` (below). Unrepresentable ⇒ the ceremony refuses with the
   named reason; nothing approximate is ever submitted.
3. **Render shown** — `Render.render(action)` is displayed in the UI
   before confirm; the exact string shown is the string the assent
   signature binds.
4. **Origin signature (Gate 1)** — at `begin`, the serialized request
   bytes are signed with the operator private key (AssentCodec/
   Ed25519 path the engine verifies).
5. **Assent (Gate 7)** — at the sovereign's `confirm`, and only then,
   `AssentCodec.sign(operatorPrivate, rendering, assentedAt, actionId)`
   produces σ. Proofs are stamped at the same instant: empty zkProof
   bytes (Gate 3 decides their fate) with publicInputs bound to
   `actionId | rendering`. The ceremony refuses to submit before
   `assentedAt + Rails.coolingWindowMs(impact)` — cooling is real time
   on the engine clock, not a formality.
6. **Submit** — `ConciergeInterface.submit(context)` only. The result
   is written back into the v2 ledger verbatim: an `EXECUTION` event
   carrying the Integrated seal root, or an `OUTCOME` event carrying
   "HALTED at gate N: \<verbatim engine reason\>", plus audit records.
   `MainActivity` surfaces the outcome; for a Gate-3 halt the message
   is exactly: *"engine execution halts at Gate 3 (ZK proof system not
   yet deployed): \<reason\>"*.

Honest-supply rules inside the bundle: parse-tree count comes from
the conversation classifier and **must be exactly 1 or the ceremony
refuses to start**; signal SNR and context entropy are supplied by
the caller from real measurements — `begin` refuses when they are
absent rather than fabricating values (the app derives SNR from the
request text's content-word ratio and entropy from the Shannon
entropy of the standing answers' Δ distribution; both are labeled
proxies in code). Intent vectors are the classifier's ACT one-hot for
both slots — session-scale intent drift is **not instrumented** and
the code says so; Gate 5 therefore measures zero drift on that axis
in this slice.

### Translation — core ⇄ engine (the heart of the slice)

Substrate projection (`EngineTranslation.substrateFrom`), one engine
record per core object, field names shared with the StateStore
vocabulary:

| Core object | Engine record | Fields |
|---|---|---|
| harness meta | `harness/meta` | `ledgerRoot`, `eventCount`, `schemaVersion` |
| collector | `collector/<name>` | `status`, `purpose`, `statusReason?` |
| standing question | `standing_question/<id>` | `delta`, `version` |
| account | `account/<id>` | `kind`, `label`, `status`, `connected` |
| plug | `plug/<id>` | `name`, `capabilities`, `status` |
| rail | `rail/<id>` | `actionType`, `target`, `envelope`, `status` |

Action translation (`EngineTranslation.translate`) covers ONLY the
core's local action classes:

| Core action | Engine action |
|---|---|
| `pause_collector` / `resume_collector` | `Write collector/<target>` with the same field map a real pause writes (status + statusReason provenance) |
| `create_standing_question`, `set_rail` | `Write harness/meta` refreshing `ledgerRoot`/`eventCount` |
| `export_ledger` | `Read harness/meta (ledgerRoot)` |

Refusals (named, never approximated): action types in the core's
DENIED set; any type with no local adapter (`send_email`, …); pause/
resume of a collector that is not a projected record; and **all
Deletes** — no local action class maps to one, so `Action.Delete`,
`Action.Sequence`, and `Action.Guarded` are never produced. When a
pause/resume seals, the ceremony reconciles the effect back into the
core registry (the substrate and the core converge; the substrate is
not a second source of truth).

### Policy ports — engine gates read core governance state

`core/EnginePorts.kt` (`CorePolicyPorts`), wired into
`ProductionVerifiers`. Every port is fail-closed: any exception ⇒
deny / maximal divergence / incoherent.

- **Gate 2 `boundaryCheck`** — structural, from the projection: a
  `Read` is inside the boundary iff its record was projected; a
  `Write` iff it targets `collector/…` or `harness/meta` AND carries
  only fields the translator itself emits (a foreign field is outside
  the local deployment boundary); `Delete` never; `Sequence` iff
  every step is; `Guarded` iff its action is.
- **Gate 6 `policyCheck` + `divergenceFn`** — the action is
  reverse-mapped to the live core proposal it translates from (the
  translation is re-run and compared — a hand-forged action matching
  no proposal has no policy standing). `EXECUTABLE_UNDER_RAIL` ⇒
  pass; `NEEDS_FRESH_ASSENT` ⇒ pass only when the core holds an
  assent event for that proposal (the sovereign's core-level assent,
  distinct from the Gate-7 signature); everything else (denied,
  drift-flagged, blocked-on-question, already-terminal) ⇒ deny.
  **Divergence** is derived, never a constant:
  `0.05 × min(conflicted standing questions, 2) + 0.05 if the
  Trajectory answer text carries a DRIFT FLAGGED marker`, capped at
  0.15 — under the fixed rail's ε = 0.1 for the common cases, over it
  when conflicts and drift coincide, which is the intended semantics.
- **Gate 5 `loopCheck`** — bundle coherence only: the context's
  rendering equals `Render.render(action)`, every record the action
  names exists in the substrate, intent/current vectors are
  same-length and finite, entropy is finite, parse trees == 1. It
  establishes that the bundle is internally consistent and binds the
  rendering the sovereign saw. It does **not** establish that the
  action is wise, that the substrate reflects the world, or anything
  about provenance — those are Gates 1/6/7 and the core's job.

### Key custody

`core/KeyCustody.kt` (pure, StateStorage-backed) + `app/KeyCustodyProvider.kt`.

- Two Ed25519 keypairs: **operator** (origin + assent signatures) and
  **revocation authority** (Gate-8 list signing) — distinct by
  construction; a custody document whose halves are the same keypair
  is rejected at load.
- **API 34's Android Keystore cannot serve the JCA
  `Signature.getInstance("Ed25519")` path the engine's crypto uses**,
  so the pairs are generated in software and the **encoded private
  keys are stored sealed inside the existing EncryptedStateStorage**
  (MasterKey/EncryptedFile, AES-256-GCM — the Keystore holds the
  wrapping key, non-exportable). Public keys are stored alongside in
  the clear (inside the same sealed document). Key id =
  SHA-256 hex of the X.509 DER public key.
- Every load re-verifies each pair (sign/verify probe + key-id
  recomputation): a tampered or mismatched half fails the load with a
  named error. The ids are also recorded in the harness state
  document; on startup the app checks continuity between the recorded
  ids and the loaded keys — **a swapped key fails loudly**
  (`CONTINUITY_MISMATCH`) rather than being adopted.
- **Loss posture**: if the sealed store exists but cannot be read,
  custody is UNAVAILABLE — the app surfaces "engine unavailable: key
  custody …" and governed execution stays down. Keys are generated
  only when the store is verifiably absent (true first run). There is
  no silent regeneration path: regenerating would orphan every prior
  signature and revocation the sovereign ever made.

### Gate 3 posture (user-visible)

`ProductionVerifiers` is constructed **without** a `zkVerifier`
argument, keeping the engine's deny default. Consequence, stated in
the UI verbatim: governed engine execution **halts at Gate 3** for
every action until Caleb rules otherwise (real ZK / non-ZK proof
honestly relabeled / keep closed — EVIDENCE-PATH, Gate 3). The engine
demo path in `MainActivity` exercises the full ceremony and shows the
halt; it does not pretend otherwise. A TEST-ONLY ZK stub
(`zkVerifier = { _, _ -> true }`) exists **only** in
`EngineEvidenceTest` wiring to prove the rest of the bundle; it is
named as such in that file and appears nowhere in production code.

## Layout

```
src/main/kotlin/guardianlink/v2/
  core/    pure Kotlin, zero android.* imports — JVM-testable
    Canonical.kt Ledger.kt Questions.kt Conversation.kt Actions.kt
    Registries.kt Sync.kt Audit.kt Types.kt Harness.kt
    StateStore.kt JsonParser.kt
    KeyCustody.kt         Ed25519 custody over StateStorage (sealed-at-rest by the app store)
    EngineTranslation.kt  core ⇄ engine substrate + action translation, named refusals
    EnginePorts.kt        Gate 2/5/6 ports derived from core governance state
    AssentCeremony.kt     ceremony steps 1–6 + Concierge submission + ledger write-back
  app/     Android shell
    GuardianLinkV2App.kt  Application: stores, collectors, custody-first engine wiring
    GovernedActionBridge.kt  proposal → ceremony (begin/confirm/submit) delegate
    KeyCustodyProvider.kt    custody on the encrypted state store
    VoiceBiometricPort.kt    Gate-7 port: never accepts without a real verifier + template
    VoiceTemplateStore.kt    scaffold enrollment-format template store (GLVT)
    EncryptedStateStorage.kt MasterKey/EncryptedFile StateStorage
    LocationCollector.kt MotionCollector.kt DeviceStateCollector.kt
    CollectorAvailability.kt SensingService.kt MainActivity.kt
src/test/kotlin/guardianlink/v2/core/
  AcceptanceTest.kt (18) LedgerTest.kt (8) PersistenceTest.kt (7)
  EngineEvidenceTest.kt (16) TestSupport.kt
src/test/kotlin/guardianlink/v2/app/
  SensorLogicTest.kt (5)
```

## Tests — expected total: 54

38 prior + **16 new** in `EngineEvidenceTest`: full ceremony to
Integrated for a representable local action (TEST-ONLY ZK stub,
named); production-wiring halt at Gate 3 with the exact user message;
per-gate halts — G1 bad origin signature, G2 boundary deny, G6
drift-flagged policy deny, G7 assent replay / backdated assent /
assent for a different actionId / absent voice evidence, G8 valid
revocation; ceremony refusals (parse trees ≠ 1, cooling window not
elapsed); translation refusals (no-adapter, hard-constraint Delete,
unprojected collector); divergence derived-from-signals; key custody
round trip + StateStore identity binding; swapped-key continuity
failure; tampered-pair load failure. Run:
`:android-app-v2:testDebugUnitTest` — the CI log must show **54**.

## CI-verify list (signatures read from engine source @ 50ae09df; compilation is CI's to confirm)

1. `ProductionVerifiers` constructor — named params incl. defaults
   for `zkVerifier`/`boundaryCheck`/`policyCheck`/`divergenceFn`/
   `loopCheck`/`transitionFn`; constructed WITHOUT `zkVerifier` in
   production wiring (the deny default is load-bearing).
2. `ConciergeInterface(policyEngine, auditLog, gates, anchor,
   initialSubstrate, issuer, permitStore)` + `submit(GateContext)`,
   `currentSubstrate()`, `ExecutionResult.Executed/Rejected`.
3. `NineGates(rails, verifiers, biometricVerifier, enrolledVoice,
   clock, ledger)`; `FakeClock` in `guardianlink.gates` for tests.
4. `AssentCodec.sign(private, rendering, assentedAtMs, actionId)`,
   `encodeRevocation(actionId, revokedAtMs)`; `Ed25519.sign/verify`
   and its `KeyPair` (raw 32-byte halves).
5. `SignedRevocationListFeed.load(path, authorityPubKey)` incl. its
   top-level `{"revocations":[…]}` JSON shape with
   base64url-no-pad signature encoding.
6. GateContext field order/names exactly as the data class declares
   (the ceremony builds it with named arguments).
7. `gateMessage` import: `guardianlink.core.gateMessage` (top-level
   fun in Concierge.kt) — used by MainActivity's fallback surface.
8. Scaffold voice types: `VoiceTemplate(embedding, metadata)`,
   `SpeakerEmbedding.Vector`, `VerificationResult(score, decision,
   liveness)` enum names.
9. JCA `KeyFactory.getInstance("Ed25519")` + `Signature.getInstance(
   "Ed25519")` availability on the CI JDK 17 (custody conversions).
10. `Intent.entries` / enum entries use for the ACT one-hot vector
    (Kotlin 2.0.21).

## Not yet (named, in order)

1. **Gate 3** — per Caleb's ruling: stays CLOSED until he chooses
   (A) real ZK, (B) non-ZK proof honestly relabeled, (C) keep closed.
2. **Voice enrollment** — production-quality enrollment UX +
   a real UBM-backed verifier behind `VoiceBiometricPort`; until
   then Gate 7 halts on absent voice evidence in production.
3. **Deployed signed revocation list** — the loader is wired; no
   list is published/deployed yet.
4. **Substrate re-projection** — core changes after engine init are
   not re-projected until restart; continuous projection is future
   work (the ceremony reconciles sealed pause/resume back itself).
5. On-device smoke: FGS survival, permission-denial paths,
   Keystore behavior on the A36 (API 34) — device-test items.
6. Admission-level durability (fsync ordering) for the encrypted
   store; release signing; network sync transports; plugs runtime.
