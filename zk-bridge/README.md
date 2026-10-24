# glzk — GuardianLink Gate 3 (theta_zk) proof system

The Rust crate behind Gate 3, per `ZK-GATE3-DESIGN.md` (Caleb's
Option A ruling, 2026-10-23: a real ZK stack, no attestation
substitutes). In the Guardianlink repo this crate sits at the repo
root as `glzk/`; in the v0.2 staging tree it lives at
`guardianlink-v0.2/zk-bridge/`. All curve arithmetic in the system
exists in exactly this one implementation.

## What it proves — the claim, verbatim (design §6)

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

The statement (§2.2): at rail-commit, each governed parameter's
quantized ceiling `c_i` is Pedersen-committed (`C_i = c_i·G + t_i·H`).
At ceremony time one aggregated Bulletproofs range proof covers the
`2m` values `{v_i} ∪ {c_i − v_i}` against the published value
commitments `V_i` and the derived commitments `C_i − V_i`, so
`0 ≤ v_i ≤ c_i` for every governed parameter. The Fiat–Shamir
transcript (Merlin, label `guardianlink/gate3/v1`) absorbs the
SHA-256 of the entire canonical public-input block (§4.1), binding
actionId, rendering hash, issuance timestamp, descriptors, and both
commitment sets.

Honest scope (§2.1): prover and verifier are the same device in v1.
This proof does NOT hide parameters from GuardianLink's own
on-device engine or policy gates, and does not prove identity or key
possession (Gates 1/7, by signature). It is not a zk-SNARK, not
succinct, not post-quantum, and proves no capability-set membership
(§2.5 is a roadmap). No performance figure is claimed for it — see
the benchmark gate below.

## The empty statement

An action with no governed numeric parameters has nothing to range:
its proof artifact is the canonical vacuity marker (`GLZK0`, 5
bytes) against a paramCount-0 block. The verifier accepts the marker
only for a paramCount-0 block that parses and is fresh; the marker
proves nothing and claims nothing — the §2.2 claim quantifies over
governed parameters and is vacuously true when there are none. A
marker presented for a non-empty statement rejects.

## API surface

Core (`src/lib.rs`):

- `commit_ceiling(ceiling_q) -> (C_i, t_i)` — rail-commit; the
  blinding is generated inside the crate from the OS RNG.
- `commit(value_q, blinding) -> C` — explicit-blinding commitment.
- `prove_envelope(action_id, rendering_hash, issued_at_ms,
  descriptors, values_q, ceilings_q, envelope_commitments,
  envelope_blindings) -> ProofBundle` — refuses out-of-domain
  values/ceilings, value > ceiling, and any envelope commitment that
  does not re-derive from its claimed (ceiling, blinding). Value
  blindings are generated per proof inside the crate and never
  leave it; witnesses are never logged or returned.
- `verify_proof(proof, public_inputs, now_ms) -> reason code` —
  total: parse (1) / freshness (2) / proof-invalid (3) / internal
  (4) / OK (0). The freshness window enforced on the timestamp
  INSIDE the block is the engine's fixed 60_000 ms rail (§4.2.3);
  `now_ms` is supplied by the caller (the ZK module never reads a
  clock). Cross-field coherence against the engine bundle is the
  app's Gate 5 port (§4.3) — this port never sees those fields.
- `encode_public_inputs` / `parse_public_inputs` — the GLZK1 codec
  (§4.1), byte-identical to the Kotlin `PublicInputsCodec`.

FFI (`src/ffi.rs`): the same operations as uniFFI proc-macro
exports (`zk_commit_ceiling`, `zk_commit`, `zk_prove_envelope`,
`zk_verify_proof`, `zk_sha256`), byte arrays / records in, byte
arrays / reason codes out. Every export wraps its body in
`catch_unwind`: a panic becomes a typed error or the INTERNAL
reason code — never an unwind across the FFI (§8 case 15). Choice
recorded: proc-macro surface over a `.udl` file (the design leaves
it open; proc-macro keeps one source of truth).

## Timestamp discipline (recorded deviation)

Design §4.2 idealizes issuance as stamped at proof *completion*.
A transcript-bound timestamp cannot be stamped after the proof that
binds it exists, so the ceremony reads its tau clock immediately
BEFORE calling the prover and that single value is used for the
block, the bundle's `Proofs.issuedAtMs`, and the Gate 5 coherence
check. Proving time therefore sits inside the freshness window —
bounded by the benchmark gate below (≤10 s target against a 60 s
window). The safe direction is preserved: a proof can only ever be
older than its declared issuance by the proving time, never fresher.

## Pins and toolchain

Exact pins in `Cargo.toml`, full graph in the committed
`Cargo.lock`: `bulletproofs =5.0.0`, `curve25519-dalek =4.1.3`,
`merlin =3.0.0`, `uniffi =0.28.3`, `rand =0.8.5`, `sha2 =0.10.8`.

**Rust toolchain pinned to 1.85.0** (`rust-toolchain.toml`).
Verified locally: the crate builds and tests green on 1.85.0;
`curve25519-dalek` 4.1.3's build script panics on rustc 1.97.0
("unexpected end of macro invocation" in its target-feature
detection), so the toolchain does not float. The uniFFI `cli`
feature (bindgen tooling) is NOT part of this crate's dependency
graph — it pulls crates requiring a newer rustc; bindings are
generated with a newer toolchain in a scratch copy, tooling only
(see below).

## Tests — host-verified

`cargo test --release` — **12 tests, all passing** (x86_64 host,
2026-10-23, Rust 1.85.0), the §8 attack corpus subset that is
Rust-side: honest proof verifies including the `v == c` boundary;
one-quantum-over cannot produce a verifying proof; forged/tampered
proof bytes reject; wrong actionId and wrong renderingHash reject;
tampered commitment rejects; stale and future inner timestamps
reject at the verifier window; malformed inputs (empty, truncated,
oversized, wrong tag, trailing bytes) error cleanly as the parse
class; ceiling ≥ 2^n refused before any proof exists; an envelope
commitment that does not open to its claimed ceiling refuses
proving; the vacuity marker is accepted only for the empty
statement; ~200 fuzzed byte flips across proof and block never
verify and never panic.

Host measurements for the build log (NOT device claims): the v1
shape (n = 32, m = 4) proof is **800 bytes**, publicInputs 405
bytes; the x86_64 release `libglzk.so` is 993,992 bytes.

## Kotlin bindings

Generated with uniffi-bindgen 0.28.3 from the compiled library and
committed at
`android-app-v2/src/main/kotlin/uniffi/glzk/glzk.kt`
(package `uniffi.glzk`; records `FfiParamDescriptor`,
`FfiCommitResult`, `FfiProofBundle`; exception `FfiZkException`;
functions `zkCommitCeiling`, `zkCommit`, `zkProveEnvelope`,
`zkVerifyProof`, `zkSha256`). They require JNA
(`net.java.dev.jna:jna:5.14.0@aar`, pinned in the module's build
file — the bridge's own generated dependency surface).

Regeneration route (when the FFI surface changes): copy this crate
to a scratch tree, add `features = ["cli"]` to the uniffi
dependency plus a `src/bin/uniffi-bindgen.rs` shim
(`fn main() { uniffi::uniffi_bindgen_main() }`), build that shim
with a current stable toolchain, then
`uniffi-bindgen generate --library <libglzk> --language kotlin`.
CI does not regenerate; it builds the `.so` with cargo-ndk and uses
the committed bindings (see the workflow draft in
`android-app-v2/.github-draft/`).

## Benchmark gate — NOT YET PASSED

Design §8, on the Galaxy A36, recorded in the build log before any
performance sentence ships anywhere:

- Prove (n = 32, m = 4): target ≤ 10 s; hard design-review stop
  if > 20 s. **Not measured on device.**
- Verify: target ≤ 1 s. **Not measured on device.**
- Proof size for the v1 shape: 800 bytes (host-measured; the size
  is platform-independent and may be cited as such).
- APK size delta per ABI: **not measured** (arm64 cross-compile is
  CI's first run of the workflow draft).

Until the device numbers exist, this crate claims correctness of
the proof logic (host-tested) and nothing about on-device latency.
