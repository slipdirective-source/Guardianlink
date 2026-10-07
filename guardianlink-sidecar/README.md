# GuardianLink Sidecar — REST+JSON adapter over the governed engine

Lets non-Kotlin ecosystems (Python, TypeScript, anything that speaks HTTP+JSON)
use the GuardianLink engine without reimplementing it. The sidecar is a **thin
adapter**: it does transport only. Every execution goes through the engine's
`ConciergeInterface.execute` — there is no other path, by construction (see
"The invariant" below).

## The invariant

`guardianlink-sidecar/src/main/kotlin/guardianlink/sidecar/GuardianLinkEngine.kt`
is the **only** file in the sidecar that references engine classes. Its public
surface is:

- `submit(action)` — parses the action JSON and returns the canonical
  rendering. Pure with respect to the governed substrate: **no execution**.
- `assent(...)` — builds the evidence and calls `concierge.execute`.
  **This is the only engine call in the entire sidecar.**
- `verifyLedger()` / `currentSubstrate()` — read-only inspection.

The HTTP layer (`SidecarServer.kt`) never touches the engine directly. If you
add an endpoint that executes, mutates, or inspects anything except through
`GuardianLinkEngine`, you have broken the invariant — review
`GuardianLinkEngine.kt` first.

## API

### POST /v1/actions/submit

```json
{ "action": { "type": "write", "recordId": "profile", "fields": { "name": "Neo" } } }
```

Action types: `read {recordId, fields[]}`, `write {recordId, fields{}}`,
`delete {recordId}`, `sequence {steps[]}`, `guarded {condition, then, otherwise?}`.
Conditions: `fieldEquals {recordId, field, expected}`, `and {parts[]}`,
`not {inner}`.

Response `200`:

```json
{
  "actionId": "act_…",
  "rendering": "WRITE \"profile\" {\"name\"=\"Neo\"}",
  "impact": "WRITE",
  "coolingWindowMs": 10000,
  "serverTimeMs": 1728172800000
}
```

`rendering` is the canonical `Render(a)` — **show exactly this to the person
and have them sign it**, not a rewording. `coolingWindowMs` is the Gate 7
cooling window for this action's impact (5s READ_ONLY / 10s WRITE / 15s
DESTRUCTIVE): the assent must age past it before the seal. `serverTimeMs` is
the engine's clock at submit — derive `assentedAtMs` from it
(`serverTimeMs + local elapsed`) rather than the client clock, so skew can't
push the assent into the future and trip Gate 7's future-dated halt.

Malformed actions → `400`. Bodies over 2MB → `413`.

### GET /v1/actions/{id}/challenge

Voice ceremony, step 1. Issues a single-use liveness challenge for a
pending action. Response `200`:

```json
{
  "challengeId": "p4",
  "phrase": "Please say: one nine three",
  "expiresInMs": 300000,
  "audioFormat": "base64 of little-endian float32 mono PCM at 16000 Hz"
}
```

The person must speak `phrase`; the PCM goes back with the assent.
Ceremonies are **serial per sidecar instance** (the engine holds one
active challenge): `409` while another action's ceremony is live;
re-issuing for the *same* action replaces its challenge. `404` for an
unknown/expired actionId. Challenges lapse after 5 minutes.

### POST /v1/actions/{id}/assent

```json
{
  "rendering": "WRITE \"profile\" {\"name\"=\"Neo\"}",
  "assentedAtMs": 1728172805000,
  "assentSignature": "<hex HMAC-SHA256>",
  "keySignature": "<hex HMAC-SHA256>",
  "keyMessage": "<optional hex; defaults to actionId bytes>",
  "assentAudio": "<base64 float32-LE mono PCM @ 16 kHz, person speaking the challenged phrase>",
  "intentVector": [1.0, 0.0],
  "currentVector": [1.0, 0.0]
}
```

Signatures (defaults, pre-shared-key grade):

- `assentSignature = HMAC-SHA256(secret, "v1|assent|{actionId}|{assentedAtMs}|{rendering}")`
  as lowercase hex. This enforces the engine's
  `(rendering, assentedAtMs, actionId)` binding contract: backdating,
  refreshing, and cross-action replay all fail the MAC.
- `keySignature = HMAC-SHA256(secret, "v1|caller|" + keyMessage)` — caller
  authentication for Gate 1.

`assentAudio` is **required** (absent → `400`; malformed base64 → `400`).
It is verified by the engine at Gate 7 (`theta_voice`): speaker match
against the enrolled voice template AND liveness (spoken content matches
the issued challenge). Wrong speaker, wrong phrase, or empty audio halts
at Gate 7 — never default-allows. Wire format is float32-LE mono at
16 kHz, base64-encoded; float32 (not int16) is deliberate — int16's
absolute quantization step destroys near-silent frames in log-mel space
and eats the liveness margin (measured DTW ~1e-5 vs a 2.0 threshold on
float32).

The stored action is replayed from the submit — never the caller's bytes —
and the caller's `rendering` is passed through so Gate 7's `theta_render`
checks what the person actually signed against `Render(action)`. The pending
action is consumed, and the ceremony ends whether the gates pass or fail:
one submit, one challenge, one assent, no double-execution.

Responses:

- `200 {"status":"executed","sealRoot":"…","anchorReceipt":{…},"substrate":{…}}`
- `200 {"status":"rejected","atGate":7,"reason":"theta_assent: …"}` — a gate
  decision is a normal outcome, not a transport error.
- `404 {"error":"unknown or expired actionId"}` — pending actions expire
  after 15 minutes.
- `409 {"error":"no active voice ceremony …"}` — assent arrived without a
  live ceremony for this actionId (none issued, lapsed, or a second
  assent). Fetch a challenge first; the pending action is NOT consumed,
  so the client can retry.
- `502 {"status":"anchoring_failed","sealRoot":"…","reason":"…"}` — the gates
  passed and the seal is in the engine ledger, but the external anchor
  threw. **Do not blindly retry**: reconcile via `/v1/ledger/verify` and
  `/v1/substrate` first.

### GET /v1/ledger/verify

```json
{ "merkle": { "ok": true, "entries": 3, "root": "…" },
  "anchor": { "ok": true, "lines": 1 } }
```

### GET /v1/substrate

Read-only view of the governed substrate. Bypasses nothing.

## Verifier posture (read this before deploying)

Real, sidecar-owned:

- **Assent binding** — HMAC-SHA256 under the server secret enforces the
  `(rendering, assentedAtMs, actionId)` triple. This is pre-shared-key
  grade: it binds, but anyone holding the secret can sign. Production
  deployments must substitute signature verification (Ed25519 etc.) behind
  the engine's `Verifiers` port.
- **Caller authentication** — same HMAC construction for Gate 1.
- **Voice biometrics** — the real engine adapter: a GMM-UBM speaker
  verifier with challenge-response liveness, enforced at Gate 7
  (`theta_voice`). What is *demo-grade* is the **enrollment**: the
  sidecar enrolls one synthetic "person" (the engine's `SyntheticVoice`
  fixture) so the real pipeline — MFCC → GMM-UBM → DTW liveness →
  Gate 7 — runs without microphone capture. A production deployment
  enrolls the person on microphone audio, stores the template in its own
  enrollment store, and constructs the engine with a real adapter. The
  template is engine-owned deployment state, never caller evidence.

Deployment-owned (explicit defaults, not silent stubs):

- **Zero-knowledge proofs** are NOT enforced (`verifyZk` returns true).
  There is no ZK system here to check against.
- **Governance policy** (`boundaryPermitted`, `policyRules`,
  `metaLoopConsistent`, `governanceDivergence`) defaults to permissive;
  wire real policy via `SidecarPolicy` in code.
- **Revocation feed** defaults to empty; wire a real feed via
  `SidecarPolicy.revocationsFor`/`verifyRevocation`.
- **Anchor** is `AppendOnlyFileAnchor`: tamper-evident, host-filesystem
  grade — not resistant to an attacker with write access to the anchor
  directory. Substitute a write-once store/TSA behind `ExternalAnchor`
  for host-adversary resistance.

## Voice ceremony (why it exists)

Gate 7 binds the assent to a *person*, not just a key: the HMAC proves
whoever signed holds the secret; the voice ceremony proves a live
enrolled person spoke the challenged phrase. The flow is
submit → `GET /v1/actions/{id}/challenge` → speak → sign →
`POST /v1/actions/{id}/assent` with the PCM. Both clients take an
`audio_provider`/`audioProvider` callable — wire real microphone capture
there. For microphone-free testing, `guardianlink-py/demo_voice.py` and
`guardianlink-ts/src/demoVoice.ts` port the engine's synthetic demo voice
(bit-identical PCM to the Kotlin fixture, verified empirically against
the real Gate 7).
- **Biometric templates** are caller-supplied vectors checked for
  consistency/bounds, not identity truth (engine trust boundary, same as
  the Kotlin API).

## Running

```bash
export GUARDIANLINK_HMAC_SECRET=$(openssl rand -hex 32)  # required, ≥16 bytes
export GUARDIANLINK_PORT=8080          # optional
export GUARDIANLINK_DATA_DIR=./sidecar-data  # optional
./gradlew :guardianlink-sidecar:run
```

The server refuses to start without `GUARDIANLINK_HMAC_SECRET`.

## Clients

### Python (stdlib only)

```python
from guardianlink_client import GuardianLinkClient
from demo_voice import speak_phrase, pcm_to_b64

client = GuardianLinkClient("http://localhost:8080", hmac_secret_hex)
result = client.governed(
    {"type": "write", "recordId": "profile", "fields": {"name": "Neo"}},
    audio_provider=lambda challenge_id, phrase: pcm_to_b64(speak_phrase(challenge_id)),
)
print(result["body"]["status"])  # "executed" | "rejected"
```

`client.governed(action, audio_provider, signer=None)`: submit → challenge
→ speak → sign → wait out the cooling window → assent. `signer` is a
caller-provided `(action_id, assented_at_ms, rendering) -> hex signature`
— plug in an HSM, MPC, or a real person ceremony; the default is the
pre-shared HMAC signer. `audio_provider` is a caller-provided
`(challenge_id, phrase) -> base64 float32-LE PCM @ 16 kHz` — plug in
microphone capture; `demo_voice.py` is the microphone-free stand-in.
See `guardianlink-py/quickstart.py`.

### TypeScript (no runtime dependencies)

```typescript
import { GuardianLinkClient } from "./client";
import { speakPhrase, pcmToB64 } from "./demoVoice";

const client = new GuardianLinkClient("http://localhost:8080", secretHex);
const result = await client.governed(
  { type: "write", recordId: "profile", fields: { name: "Neo" } },
  (challengeId) => pcmToB64(speakPhrase(challengeId))
);
```

`client.governed(action, audioProvider, signer?)`: submit → challenge →
speak → sign → wait out the cooling window → assent. `audioProvider`
wires microphone capture; `demoVoice.ts` is the microphone-free stand-in.

`npm run build && npm run quickstart`. See `guardianlink-ts/src/quickstart.ts`.

## Tests

`SidecarEndToEndTest` spins up the real server on an ephemeral port and
drives it over real HTTP, including the voice ceremony (synthesized with
the engine's own `SyntheticVoice` fixture as the enrolled demo person):

- valid flow executes; receipt carries the seal root; anchor chain intact
- challenge endpoint issues single-use challenges; concurrent ceremonies → 409
- tampered rendering (re-MACed by a key-holding attacker) → rejected at
  Gate 7, `theta_render`
- wrong-key signature → rejected at Gate 7, `theta_assent`
- signature replayed across actionIds → rejected at Gate 7, `theta_assent`
- wrong speaker → rejected at Gate 7, `theta_voice`
- right speaker, wrong phrase → rejected at Gate 7, `theta_voice` (liveness)
- assent without a challenge → 409; missing audio → 400; double-assent → 409
- unknown actions → 404 (fail closed)

Note: the e2e tests bind loopback TCP from Java; in sandboxes that
intercept Java-initiated loopback connections they fail at the transport
with no gate exercised. The same scenarios were verified over real HTTP
from the Python client against the running sidecar (14/14 pass).
