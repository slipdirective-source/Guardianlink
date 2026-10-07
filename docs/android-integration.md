# Android execution-layer integration contract

How the Android execution layer (Kotlin `VpnService` TUN + Rust `libp2p` core)
binds to GuardianLink as its governor. This is the deployment that makes
GuardianLink real: the engine governs, the Android layer obeys.

## Deployment shapes

Two, not one. Pick per component:

1. **Embedded engine (in-process).** The Kotlin engine runs inside the Android
   app process. The app's action dispatcher calls `ConciergeInterface.execute`
   directly. Lowest latency, no network hop. The engine's instruments (clock,
   ledger) live in the app sandbox.
2. **Sidecar.** The Rust core or any networked component talks to
   `guardianlink-sidecar` over REST (`POST /v1/actions/submit` →
   `GET /v1/actions/{id}/challenge` → `POST /v1/actions/{id}/assent`).
   Required whenever the acting component is not the app process.

In both shapes the invariant holds: **no substrate mutation without passing
the gates.** The Android dispatcher must not contain a second write path.

## Action modeling

Every operation the layer can perform maps to the closed `Action` AST
(`Read | Write | Delete | Sequence | Guarded`) before it executes:

| Android operation | Action modeling |
|---|---|
| File write / SharedPreferences / DB mutation | `Write(path, value)` |
| File delete / cache clear | `Delete(path)` |
| TUN packet forward / socket send | `Write("net/<dst>", bytes)` — network is a substrate |
| Settings / keystore mutation | `Write("settings/<key>", …)` |
| Multi-step flows | `Sequence(...)` — impact prices max-over-tree |
| Conditional operations | `Guarded(predicate, action)` |

Anything that cannot be modeled does not execute. That is the whole contract.

## Why Android answers the ambient-authority residual

The engine's declared residual: the gates govern the *substrate*, not the
*agent* — a host-process agent with ambient authority walks around them.
Android's UID sandbox is the capability-stripping the audit asked for: the
agent code runs inside the app sandbox, its reachable world is the permission
set, and GuardianLink governs every mutation of that world. The claim
"the substrate cannot change without assent" becomes true *of the device* when
the dispatcher is the only writer and the sandbox holds.

What the Android side must guarantee: no raw `File`, socket, or
`ContentResolver` writes outside the dispatcher; the dispatcher is the only
holder of write capability. Audit this by grep, not by promise.

## Assent ceremony on-device

1. Dispatcher submits the `Action` → sidecar/engine returns the canonical
   `Render(a)`.
2. App displays the rendering on an attested surface (this is the
   display-ceremony residual — a lying UI defeats byte-level guarantees;
   prefer a system-trusted display path where available).
3. App issues the voice challenge, captures 16 kHz mono PCM from the mic,
   runs `BiometricVerifier.verify` against the Keystore-held template.
4. On voice-pass, the user signs; the signature binds
   `(rendering, assentedAtMs, actionId)`.
5. Engine executes, seals, anchors.

## Voice on Android

- **Enrollment:** on-device, supervised, into a template stored in Android
  Keystore (StrongBox where available). Never leaves the device.
- **Model:** the `SpeakerModel` port takes the implementation as a
  constructor instrument. The bundled GMM-UBM baseline runs on-device
  today; an ONNX ECAPA-TDNN drops into the same interface when the
  accuracy budget demands it.
- **Re-calibration is mandatory:** thresholds shipped are calibrated on
  synthetic audio. Re-calibrate on real microphone captures before any
  production claim — ADC quantization shifts the liveness margin
  (see sidecar README, int16 wire-format finding).
- **Residuals that travel with it:** challenge-response liveness only
  (replay of the *current* challenge verifies; real-time clones not
  detected); coercion is out of scope for the engine.

## Anchor and ledger on-device

- `MerkleAuditLog` + engine-owned clock live in the app sandbox.
- `ExternalAnchor`: `AppendOnlyFileAnchor` on device storage for the local
  chain; a Rekor/TSA/write-once adapter behind the same port publishes
  seal roots upstream on connectivity. Anchor failures are fail-loud —
  the transition is sealed locally and the caller handles the outage.

## Revocation

`Verifiers.revocationsFor` is backed by an on-device revocation store
synced from the operator feed. A throwing feed halts — the device must
fail closed on sync failure, never fail open on stale data.

## Integration checklist

- [ ] Dispatcher is the sole writer; no direct I/O paths (grep-verified)
- [ ] Every operation maps to the `Action` AST; unmappable ops are rejected
- [ ] Voice template enrolled on-device, Keystore-held, thresholds
      re-calibrated on real captures
- [ ] Display path documented against the display-ceremony residual
- [ ] Anchor chain verifies end-to-end: device → upstream
- [ ] Revocation feed sync failure → halt (tested, not assumed)
- [ ] 179 engine tests green; Android-side dispatcher tests cover the
      no-second-write-path invariant
