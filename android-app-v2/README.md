# android-app-v2 — GuardianLink v0.2 surface (Android module)

Status: **slice 4 — Gate 3 is a real ZK stack.** The v0.2 core
(ledger, standing questions, conversation, governed actions, rails,
sync, audit) is ported to pure Kotlin and JVM-tested. The device layer
persists the harness state encrypted at rest (AES-256-GCM via
EncryptedFile + Android Keystore MasterKey), collects location and
motion through framework APIs only (no Google Play Services), and —
new in this slice — carries governed actions **all the way into the
GuardianLink engine**: persistent Ed25519 key custody, policy ports
derived from the core's own governance state, substrate/action
translation, and the full assent ceremony through
`ConciergeInterface`, the engine's sole doorway. **Gate 3 (theta_zk)
is now a real zero-knowledge proof system** (Caleb's Option A
ruling): aggregated Bulletproofs range proofs over Ristretto255 via
the Rust `glzk` crate in `../zk-bridge/`, proving rail-envelope
compliance, wired into `ProductionVerifiers` and proved by the
ceremony at submit time. What the ceremony can and cannot complete
today is stated per gate, not smoothed over. The build is still
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
| Engine evidence path: key custody, policy ports, translation, assent ceremony, Concierge submission, outcome write-back | **Real**, JVM-tested (see below). End-to-end `Integrated` is reachable in tests through the bridge seam's TEST-ONLY fake (FakeZkBridge); production wiring uses the native Rust bridge |
| Gate 3 (theta_zk): Bulletproofs envelope proofs (Rust glzk), rail-commit commitments, prove-at-submit ceremony, Gate 5 cross-binding | **Real**: the Rust crate is host-tested (12/12, attack corpus); the Kotlin layer is JVM-tested through the seam. Android cross-compile + on-device run are CI/device items — the benchmark gate is NOT yet passed (see below) |
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
   produces σ. Confirm stamps NOTHING else (design §5.2). The Gate 3
   statement was already assembled at `begin` from the covering
   rail's envelope commitments; a governed parameter without a
   descriptor/commitment — or numeric parameters with no committed
   envelope — refuses the ceremony at begin, named. At submit, AFTER
   the cooling check passes, the ceremony proves through the bridge:
   `issuedAtMs` is read from the engine clock immediately before
   proving and lands in the GLZK1 block, in `Proofs.issuedAtMs`, and
   in the Gate 5 coherence check as one value. The bridge's returned
   block is cross-checked against the assembled statement through
   the codec before anything is submitted; a bridge that is
   unavailable or refuses means nothing is submitted, named. The
   ceremony refuses to submit before
   `assentedAt + Rails.coolingWindowMs(impact)` — cooling is real time
   on the engine clock, not a formality.
6. **Submit** — `ConciergeInterface.submit(context)` only. The result
   is written back into the v2 ledger verbatim: an `EXECUTION` event
   carrying the Integrated seal root, or an `OUTCOME` event carrying
   "HALTED at gate N: \<verbatim engine reason\>", plus audit records.
   `MainActivity` surfaces the outcome; for a Gate-3 halt the message
   names the verifier's failure class (§5.4), refined by
   `GovernedActionBridge` from the wiring's reason code:
   *"engine execution halts at Gate 3 (ZK proof rejected —
   \<parse|freshness|proof-invalid|bridge-internal\>): \<reason\>"*.

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

### Gate 3 — the real ZK stack (ZK-GATE3-DESIGN)

Caleb ruled **Option A** (2026-10-23): a genuine proof system. The
claim this module may make, verbatim from design §6:

> "Gate 3 verifies a zero-knowledge proof (Bulletproofs range
> proofs over Ristretto255, no trusted setup) that the governed
> numeric parameters of the action lie within the envelope of the
> rail the action executes under. The proof is bound to the action
> and its rendering and is fresh within the engine's fixed window.
> The proof itself reveals neither the parameter values nor the
> envelope ceilings to anyone who holds it."

> "GuardianLink's Gate 3 proof system is implemented with the dalek
> Bulletproofs library (Rust), the reference implementation of the
> Bulletproofs protocol."

How it is wired here:

- **The crate**: `../zk-bridge/` (repo-root `glzk/` when installed)
  — dalek `bulletproofs` 5.0.0 over Ristretto255, exact pins,
  `Cargo.lock` committed, Rust toolchain pinned to 1.85.0 (dalek
  4.1.3's build script breaks on newer rustc — verified). Host-side
  `cargo test --release`: **12/12 passing**, including the §8
  attack corpus (forgery, tampering, binding, freshness, malformed
  inputs, fuzz flips). Proof size for the v1 shape (n = 32, m = 4):
  800 bytes, host-measured.
- **The bridge**: uniFFI proc-macro exports in the crate; generated
  Kotlin bindings committed at `src/main/kotlin/uniffi/glzk/glzk.kt`
  (package `uniffi.glzk`, requires the pinned JNA aar). The core
  seam is `core/zk/ZkProverBridge.kt` (interface + the unavailable
  bridge); the Android-coupled implementation is
  `app/NativeZkBridge.kt`, total on every entry point — a missing
  .so or FFI failure becomes a named refusal / reason code, never
  an escaping throwable.
- **Verifier wiring**: `GuardianLinkV2App` passes
  `zkVerifier = ZkVerifierWiring(NativeZkBridge) { clock.nowMs() }`
  to `ProductionVerifiers`. The wrapper is total (any failure ⇒
  false ⇒ Gate 3 halts). It enforces the §4.2.3 freshness window on
  the timestamp INSIDE the block against the engine's tau clock.
- **Coherence**: the Gate 5 port (`EnginePorts.loopCheck`) performs
  the §4.3 cross-binding the engine cannot: the GLZK1 block must
  decode and its actionId / renderingHash / issuedAtMs must equal
  the bundle's own fields, or the bundle is incoherent.
- **Rail commit**: with the bridge available, `addRail` computes a
  descriptor + quantized ceiling + Pedersen commitment per
  envelope parameter (blinding generated in-crate, stored only in
  the sealed state document's rail record). A ceiling that does
  not quantize into `[0, 2^32)` refuses the COMMIT (§8 case 13).
  Rails committed without the bridge carry no commitments and are
  named not-Gate-3-provable at ceremony begin.
- **The empty statement**: an action with no governed numeric
  parameters carries the canonical vacuity marker (`GLZK0`) against
  a paramCount-0 block — it proves nothing and claims nothing; the
  §6 claim quantifies over governed parameters.
- **Recorded deviation**: §4.2 idealizes issuance stamped at proof
  completion; a transcript-bound timestamp cannot be stamped after
  the proof that binds it exists, so issuance is read immediately
  BEFORE proving. Proving time sits inside the 60 s window, bounded
  by the benchmark gate; a proof can only be older than declared by
  the proving time, never fresher.
- **Benchmark gate — NOT YET PASSED**: no on-device numbers exist.
  §8 targets (Galaxy A36): prove (n = 32, m = 4) ≤ 10 s, verify
  ≤ 1 s, APK size delta per ABI recorded. No performance claim is
  made anywhere until those are measured on device.
- The TEST-ONLY ZK stub (`zkVerifier = { _, _ -> true }`) still
  exists **only** in `EngineEvidenceTest` wiring for the pre-ZK
  bundle tests, re-documented there as distinct from the production
  path; `ZkGate3Test` exercises the production shapes through
  `FakeZkBridge`, the seam's test fake.

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
    zk/ZkStatement.kt      Gate 3 descriptors, quantization, rail ZK records, statement assembly
    zk/PublicInputsCodec.kt  GLZK1 canonical codec (pure, JVM-tested) + ZkReason codes
    zk/ZkProverBridge.kt  the FFI seam: prover/verifier interfaces + the unavailable bridge
  app/     Android shell
    GuardianLinkV2App.kt  Application: stores, collectors, custody-first engine wiring
    GovernedActionBridge.kt  proposal → ceremony (begin/confirm/submit) delegate + Gate 3 failure-class refinement
    NativeZkBridge.kt     core zk seam over the uniFFI bindings to libglzk (total, fail-closed)
    ZkVerifierWiring.kt   ProductionVerifiers zkVerifier = total wrapper over the bridge verifier
    KeyCustodyProvider.kt    custody on the encrypted state store
    VoiceBiometricPort.kt    Gate-7 port: never accepts without a real verifier + template
    VoiceTemplateStore.kt    scaffold enrollment-format template store (GLVT)
    EncryptedStateStorage.kt MasterKey/EncryptedFile StateStorage
    LocationCollector.kt MotionCollector.kt DeviceStateCollector.kt
    CollectorAvailability.kt SensingService.kt MainActivity.kt
  uniffi/glzk/glzk.kt   GENERATED uniFFI Kotlin bindings for the Rust glzk crate (committed; regenerate per zk-bridge/README.md)
src/test/kotlin/guardianlink/v2/core/
  AcceptanceTest.kt (18) LedgerTest.kt (8) PersistenceTest.kt (7)
  EngineEvidenceTest.kt (16) TestSupport.kt
  ZkGate3Test.kt (14)  zk/FakeZkBridge.kt (test-only seam fake)
src/test/kotlin/guardianlink/v2/app/
  SensorLogicTest.kt (5)
```

## Tests — expected total: 68

54 prior (EngineEvidenceTest's pre-ZK bundle tests unchanged in
count; its production-wiring test now pins the ENGINE's no-argument
deny default and its hand-built contexts carry coherent GLZK1
blocks, as §4.3 requires) + **14 new** in `ZkGate3Test`: GLZK1
encode/decode round trip over all fields; malformed blocks rejected
(wrong tag, truncation, trailing bytes); Gate 5 cross-check accepts
a coherent block and rejects mismatched actionId, mismatched
rendering, and inner/outer issuedAtMs mismatch; a rail-covered
numeric action integrates end-to-end through the bridge seam's fake
with a real statement assembled at begin; prove-at-submit keeps the
proof fresh across a 70 s cooling wait (§8 case 16); bridge
unavailable between confirm and submit ⇒ ceremony refuses at submit
naming Gate 3, nothing submitted; the production verifier wiring
over an unavailable bridge halts a direct evaluation at Gate 3 with
the INTERNAL reason recorded; an ungoverned (smuggled) numeric
parameter and numeric parameters with no covering rail both refuse
at begin; a ceiling outside the quantized domain refuses the rail
COMMIT; the rail ZK record survives a StateStore round trip
byte-identically. The remaining 54: full ceremony to
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
   `loopCheck`/`transitionFn`; production wiring now PASSES
   `zkVerifier = ZkVerifierWiring(...).asLambda()` (the real bridge
   verifier — the deny default remains only for unwired deployments
   and is pinned as such in EngineEvidenceTest).
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
11. Gate 3 ZK chain (new in slice 4): the `glzk-cargo-test` CI job
    (Rust 1.85.0, `cargo test --release` in zk-bridge — host-verified
    locally, 12/12); the cargo-ndk step (cargo-ndk 4.1.2 pin —
    confirm current at install time; NDK 27.2.12479018) producing
    `jniLibs/arm64-v8a/libglzk.so`; the committed uniFFI bindings
    (`uniffi/glzk/glzk.kt`) against uniffi 0.28.3's runtime checksum
    verification at first load; the JNA aar
    (`net.java.dev.jna:jna:5.14.0@aar`) resolving from mavenCentral;
    `GateContext`/`Proofs` data-class `copy` used by ZkGate3Test;
    the StateStore "zk" rail section round-tripping through the
    strict JsonParser (Base64 fields, Long ceilingQ/offset).

## Not yet (named, in order)

1. **Gate 3 benchmark gate** — the stack is built and wired; the
   §8 on-device numbers (prove/verify latency on the A36, APK size
   delta) are NOT yet measured, and no performance claim ships
   until they are. Also outstanding: armeabi-v7a / x86_64 .so
   builds (arm64-v8a first), and rail SYNC carrying commitments +
   descriptors as the envelope's public face (§5) — until then,
   synced rail records elsewhere may still carry cleartext
   envelopes: the named interim leak channel (the ZK artifacts
   themselves do not leak).
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
