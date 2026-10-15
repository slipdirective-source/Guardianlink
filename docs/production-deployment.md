# GuardianLink production deployment guide

The engine is fail-closed; the deployment is what makes that claim true.
This document is the checklist for turning the scaffold into a production
system. Every item here is deployment scope — the engine cannot do these
for you, by design.

## 1. Cryptographic adapters (ship real ones)

The demo's permissive verifiers (`Main.kt: demoVerifiers`, every check
`= true`) must never reach production. The repo now ships real adapters:

| Port | Production adapter | Location |
|---|---|---|
| `verifySignature` (Gate 1) | Ed25519 (JDK built-in, RFC 8032) | `crypto/Ed25519.kt` |
| `verifyAssent` (Gate 7) | Ed25519 over the canonical `(rendering, assentedAtMs, actionId)` triple — rendering-only signatures do NOT verify | `crypto/AssentCodec.kt` |
| `verifyRevocation` / `revocationsFor` (Gate 8) | Authority-signed JSONL revocation list, every entry signature-checked at load; one bad entry poisons the whole load | `adapters/RevocationFeed.kt` |
| `verifyZk` (Gate 3) | **No bundled implementation.** `ProductionVerifiers` defaults to DENY until you inject a real ZK verifier. A permissive default here would be a `RailAuthorizer.PERMISSIVE`-class error. | `adapters/ProductionVerifiers.kt` |
| Shamir share integrity | Feldman VSS commitments over secp256k1 — corrupt/malicious shares are detectable before reconstruction | `mfa/FeldmanVss.kt` (use with `ShamirMfa(Secp256k1.N)`) |
| Anchor | `AppendOnlyFileAnchor` (fsync'd) or `MirroredAnchor` (N-way fan-out with quorum health check) | `audit/` |

Wire them via `adapters/ProductionVerifiers.kt`, which composes the crypto
ports with injectable policy ports (default deny) and the revocation feed.

## 2. Key custody

- **Operator assent key**: whoever holds the private key can assent. Generate
  on the operator's device (never on a server), protect at rest (Android
  Keystore / hardware secure element where available), and publish the
  public key to the deployment out-of-band.
- **Revocation authority key**: MUST differ from the operator key. The hand
  that assents must not be the hand that revokes — otherwise a compromised
  operator key can un-revoke itself.
- **Key rotation**: rotation is a revocation event. Publish the new public
  key through the same out-of-band channel as the original; the engine
  takes keys as constructor arguments, so rotation = re-wiring + restart,
  never a request parameter.

## 3. Enrollment ceremony (voice)

The voice adapter verifies ceremony audio against the
deployment-enrolled template — it cannot verify the microphone wasn't
coerced or the display wasn't lying (see `voice/README.md` honest limits).

- Enroll on real microphone audio, in person, with a witnessed ceremony.
- Store the template in the deployment's own enrollment store; pass it as
  the engine's constructor instrument (never from the request).
- Re-calibrate liveness thresholds on real voice before production —
  shipped thresholds are calibrated on synthetic audio.
- Real-time voice clones are NOT detected. If your threat model includes
  them, the voice gate is necessary but not sufficient; add an
  independent channel (the attested display + signing ceremony below).

## 4. Authority containment (the ambient-authority residual)

The gates are airtight around the *substrate*, not the *agent*. A governed
agent running with ambient authority (host process, raw sockets) can act
without touching the gates. The honest claim remains "the substrate cannot
change without assent" — never "the agent cannot act without assent."
Close the gap at deployment:

- [ ] Run the agent in a UID sandbox (Android) or equivalent OS-level
      sandbox: no raw sockets, no ambient filesystem, no direct
      substrate handles.
- [ ] The ONLY substrate mutation path is `ConciergeInterface.execute`.
      Audit the deployment for side channels (debug interfaces, backup
      agents, direct DB credentials) that bypass it.
- [ ] Capability-strip the agent process: it holds no keys, no anchor
      write access, no revocation-feed write access.
- [ ] See `docs/android-integration.md` for the Android execution-layer
      contract (VpnService TUN + Rust core).

## 5. Display & signing ceremony

The engine proves the signed bytes equal `Render(a)` of the executed
action; it cannot prove the human's eyes saw those bytes or spoke
uncoerced. For high-consequence actions: attested display (the rendering
shown on a trusted screen) and a signing ceremony the deployment
controls. This is deployment scope, not engine scope.

## 6. Anchor operations

- Mirror the anchor across at least two independent filesystems/hosts
  (`MirroredAnchor`); monitor `quorumHealthy()` and alert on divergence.
- Next grade up: a write-once store or timestamping authority (Rekor /
  RFC 3161 TSA) behind the `ExternalAnchor` port. The port is stable;
  the file anchor is the simple real path, not the final one.
- Anchor receipts (`sealRoot` + receipt location) are the independently
  checkable evidence of each transition — publish or retain them per
  your audit retention policy.

## 7. Revocation feed operations

- The revocation list is published by the revocation authority and
  consumed read-only by the deployment. Sign entries offline with
  `SignedRevocationListFeed.signEntry`; distribute the file; the engine
  host calls `reload()` on a schedule or on inotify.
- A failed reload throws — the engine fail-closes. Monitor reload
  failures; a stale feed is a availability problem, a forged feed is a
  security problem, and this design refuses to confuse the two.

## 8. Clock

Inject a real monotonic source. `DurableMonotonicClock` persists at most
once per 1000ms of advance and resumes at persisted+1000ms (crash-safe,
up to 1s forward ratchet per restart). Monitor its state file: if the
file can't be written, crash-monotonicity degrades to the wall clock.

## 9. What remains out of scope (honest list)

- Real-time voice-clone detection.
- Coercion prevention (witnessed ceremony is the mitigation, not a fix).
- Disk-attacker-proof anchoring without a write-once store / TSA.
- Truth of caller-supplied evidence (the engine checks consistency,
  never truth — see README's trust-boundary table).
- Production ZK verification (port exists, adapter is deployment scope).
- Hardware attestation binding for Shamir share categories.
- Cumulative cross-action reasoning (the trajectory estimator is
  advisory by design — see README residuals).
