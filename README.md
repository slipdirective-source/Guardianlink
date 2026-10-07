# GuardianLink — fail-closed execution governor

GuardianLink is infrastructure, not a product: a deterministic, fail-closed
governor that sits between an agent and the substrate it mutates. Every action
is forced through nine gates — canonical rendering, impact-scaled cooling,
cryptographically bound human assent, voice-biometric liveness, signed
revocation re-scan — before the transition is sealed into a Merkle ledger and
published through an external anchor. The engine checks the consistency of
everything it is told and trusts nothing it is given: clock, ledger, and
revocation feed are engine-owned constructor instruments, never request
parameters.

Use it as a sidecar: `guardianlink-sidecar/` exposes the full ceremony over
REST (`POST /v1/actions/submit` → `GET /v1/actions/{id}/challenge` →
`POST /v1/actions/{id}/assent`), with Python and TypeScript clients. See
"Adopter quickstart" below.

## Adopter quickstart

The fastest path is the sidecar — no Kotlin required:

```python
from guardianlink_client import GuardianLinkClient

client = GuardianLinkClient("http://localhost:8080")
action = {"type": "Write", "path": "vault/api_key", "value": "..."}

sub = client.submit(action)                     # → actionId + canonical rendering
chal = client.get_challenge(sub.action_id)      # → single-use voice challenge
print("Say:", chal.phrase, "| you see:", sub.rendering)

audio = record_16khz_mono()                     # your mic capture
result = client.assent(sub.action_id, sign(sub.rendering), audio)
# → executed {sealRoot, anchorReceipt} or rejected {atGate, reason}
```

The ceremony is: submit, read the rendering, speak the challenge phrase,
assent. The signature binds `(rendering, assentedAtMs, actionId)` — replay,
refresh, and backdating are defeated by the binding, not by policy. Every
execution — including yours — goes through `ConciergeInterface.execute`;
there is no other path, over REST or otherwise.

TypeScript: `guardianlink-sidecar/guardianlink-ts/` (`client.ts`, quickstart in
`demoVoice.ts`). Full API: `guardianlink-sidecar/README.md`.

## Declared residuals

Stated boundaries, not hidden ones. Each is the deployment's job:

- **Ambient authority.** The gates are airtight around the *substrate*, not the
  *agent*. A governed agent running with ambient authority (host process, raw
  sockets) can act without touching the gates. The honest claim is "the
  substrate cannot change without assent" — never "the agent cannot act
  without assent." Deployments must sandbox the agent or bound the claim.
  (Android's UID sandbox is the natural answer — see
  `docs/android-integration.md`.)
- **Display ceremony.** The engine proves the signed bytes equal `Render(a)`
  of the executed action; it cannot prove the human's eyes saw those bytes or
  spoke uncoerced. Attested display and signing are deployment scope.
- **Voice liveness.** Challenge-response only: a recording of the *current*
  challenge verifies; real-time voice clones are not detected. Thresholds are
  calibrated on synthetic audio — re-calibrate on real voice before
  production. See `voice/README.md` for the full honest-limits list.
- **Anchor grade.** The bundled `AppendOnlyFileAnchor` is host-filesystem
  grade: append-only, hash-chained, tamper-evident — not disk-attacker-proof.
  A Rekor/TSA/write-once adapter behind the `ExternalAnchor` port is the next
  grade up.
- **Verifiers lie; they don't just throw.** `checked {}` fail-closes on
  *throwing* adapters, not *lying* ones. Signature, ZK, and attestation
  adapters are the system's largest trust assumption — they must be real.
- **No cross-action cumulative reasoning.** Fifty individually-innocent writes
  can compose a deletion; each passes all gates. Per-execution bound assent
  makes every step human-visible, which mitigates but does not close this.

## Build

Push this repo to GitHub as-is — .github/workflows/ci.yml will build and test
it automatically via GitHub Actions (free runners, full JDK/Gradle support).

Locally: `gradle build test` (requires JDK 17+; no Gradle wrapper is checked in).

## Verification status

The algorithmic core of every bug-prone module (Merkle checkpointing, MAD shift
detection, CAS concurrency, Shamir reconstruction, PolicyEngine fail-closed
invariant) was verified via adversarial Python-ported test batches before this
Kotlin code was written, confirming the logic is sound. The Kotlin itself is
compiler-verified (kotlinc 1.9.22, JDK 17) with a 179-test suite run
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
| `GateContext` evidence (assent audio PCM, SNR, entropy, parse trees, proof bytes, claimed assent time) | Internal consistency, bounds, finiteness | That the values are true — a caller can supply self-consistent lies; the assent signature must bind (rendering, assentedAtMs, actionId) per the `verifyAssent` contract, and the voice adapter verifies ceremony audio against the deployment-enrolled template (it cannot verify the microphone wasn't coerced or the display wasn't lying — see `voice/README.md` honest limits) |
| `Verifiers` ports (signatures, ZK, assent, revocation, policy) | Totality (throw → halt), boolean outcomes | Cryptographic validity itself — real signature/ZK/attestation adapters required |
| Shamir share categories | Share math (field ranges, no dup x, threshold, mandatory HW label) | That a share labeled `HARDWARE_BIOMETRIC` really came from hardware — attestation binding + VSS commitments required |
| Trajectory samples | Finiteness, magnitude bounds, self-consistency, windowed anomalies | That samples are authentic — signed/attested sensor streams required |
| Merkle ledger | Tamper-evidence within the process; engine records carry engine-attested timestamps from the engine-owned clock | Immutability — sealed roots are published through the `ExternalAnchor` port; the bundled `AppendOnlyFileAnchor` is host-filesystem grade (durable, tamper-evident, not disk-attacker-proof). Direct external appends (outside the engine) keep the caller-supplied timestamp caveat |
| Assent idle-expiry (`theta_assent_idle`) | Assent age + ledger silence vs `maxAssentIdleMs` (24h) — an assent dies only when BOTH are exceeded | The silence check reads the engine-owned ledger, and engine appends are engine-attested, closing the forged-liveness vector for engine records — the external anchor remains the recourse for direct ledger writes |
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
- Gate 7: assent over `Render(a)` (never the payload), with the signature binding `(rendering, assentedAtMs, actionId)`; idle-max-age
  (`maxAssentIdleMs` = 24h — an assent dies only when BOTH the assent is old
  AND the ledger has been silent that long), fixed-rail cooling window
  scaling with AST-computed impact, signed revocation until seal.
- Engine-owned instruments: `NineGates` takes its clock and ledger as
  constructor arguments — never from the request. Revocations arrive via
  the `Verifiers.revocationsFor` port. A request cannot substitute the
  engine's time source, audit sink, or revocation feed.
- Gate 8: prepare-then-commit — `applyAction` is pure, the caller commits
  by adopting `GateOutcome.Integrated.newSubstrate`. The seal is never post-hoc.
- Forced composition: `ConciergeInterface.execute` is the interface's only
  execution path — every action is forced through the gates, the governed
  substrate is carried across calls, and each seal root is published through
  the `ExternalAnchor` port (`AppendOnlyFileAnchor` bundled: append-only,
  hash-chained anchor file). A throwing anchor fails loud, never silent.
- 179 tests across the suite (engine gates, adversarial, composed path, anchor,
  voice biometrics) cover every gate's
  halt path, cooling scaling, revocation, ledger appends, render injectivity,
  and single-evaluation. Demo in `Main.kt`.