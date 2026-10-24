//! GuardianLink Gate 3 (theta_zk) proof system — ZK-GATE3-DESIGN.md.
//!
//! The v1 statement is **rail-envelope compliance**: for every governed
//! numeric parameter `i` of an action, the quantized value `v_i` lies
//! within the rail's committed envelope ceiling `c_i`, proven with ONE
//! aggregated Bulletproofs range proof over Ristretto255 (dalek
//! `bulletproofs` 5.0.0, no trusted setup) over the `2m` values
//! `{v_i} ∪ {c_i − v_i}`, each in `[0, 2^n)`:
//!
//! ```text
//! π proves:  ∀i: 0 ≤ v_i  ∧  0 ≤ c_i − v_i        ⇒  v_i ≤ c_i
//! ```
//!
//! Value ranges are proven against value commitments `V_i` published
//! in `publicInputs`; difference ranges against the derived
//! commitments `C_i − V_i`, where `C_i` is the rail's Pedersen
//! commitment to the ceiling, committed at rail-commit time
//! ([`commit_ceiling`]). The Fiat–Shamir transcript is bound to the
//! full canonical public-input block (§4.1), so a proof is a proof of
//! THIS action, THIS rendering, THIS issuance — not a floating
//! compliance token.
//!
//! Honest scope (§2.1): prover and verifier are the same device in
//! v1; this proof does NOT hide parameters from the local engine or
//! policy gates. It reveals neither the values nor the ceilings to
//! anyone who later HOLDS the proof artifact (ledger/audit holders,
//! anchors, sync peers, agent-plug context packages, future split
//! verifiers). Key possession is not proven here — Gates 1 and 7
//! authenticate by signature.
//!
//! All curve arithmetic in the system lives in this crate. The FFI
//! surface (see `ffi`) is panic-contained: no Rust panic crosses into
//! the JVM as anything but a typed error / reason code.

use bulletproofs::{BulletproofGens, PedersenGens, RangeProof};
use curve25519_dalek::ristretto::CompressedRistretto;
use curve25519_dalek::scalar::Scalar;
use merlin::Transcript;
use rand::rngs::OsRng;
use rand_core::RngCore;
use sha2::{Digest, Sha256};

mod ffi;
pub use ffi::*;

/// Domain separator opening every public-input block (§4.1). Not
/// length-prefixed.
pub const DOMAIN_SEPARATOR: &[u8; 5] = b"GLZK1";

/// Merlin transcript label (§4.1).
pub const TRANSCRIPT_LABEL: &[u8] = b"guardianlink/gate3/v1";

/// Transcript label under which the public-input block hash is
/// absorbed before the range proof runs (§4.1).
const BLOCK_HASH_LABEL: &[u8] = b"glzk-public-inputs-sha256";

/// The canonical proof bytes for the EMPTY statement (no governed
/// numeric parameters): a domain-separated vacuity marker, not a
/// range proof. Mirrors `PublicInputsCodec.VACUITY_PROOF` in Kotlin.
pub const VACUITY_PROOF: &[u8; 5] = b"GLZK0";

/// Freshness window the verifier enforces on the timestamp INSIDE
/// publicInputs (§4.2.3): the engine's fixed rail `deltaT`. This is
/// the engine's number, mirrored — never widen it here.
pub const FRESHNESS_WINDOW_MS: i64 = 60_000;

/// Hard caps (parse class): a block or proof beyond these is rejected
/// before any curve work. The v1 proof shape is ~1 KB; 64 KB is a
/// generous ceiling that still bounds verifier work on junk input.
pub const MAX_PUBLIC_INPUTS_BYTES: usize = 64 * 1024;
pub const MAX_PROOF_BYTES: usize = 64 * 1024;
pub const MAX_PARAMS: usize = 64;

/// Reason codes returned by [`verify_proof`]. The numeric values are
/// part of the FFI contract with the Kotlin wiring; the ceremony maps
/// them to the §5.4 user-facing failure classes.
pub mod reason {
    /// Proof verifies and is fresh.
    pub const OK: i32 = 0;
    /// publicInputs do not parse as a GLZK1 block (incl. oversized,
    /// truncated, wrong tag, param-count/commitment-length mismatch).
    pub const PARSE: i32 = 1;
    /// Inner issuedAtMs is future-dated or older than the window.
    pub const FRESHNESS: i32 = 2;
    /// The range proof does not verify against the parsed block.
    pub const PROOF_INVALID: i32 = 3;
    /// An internal failure (e.g. a contained panic). Fail-closed.
    pub const INTERNAL: i32 = 4;
}

/// One governed parameter's descriptor (§2.3), fixed at rail commit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParamDescriptor {
    pub name: String,
    pub scale: u32,
    pub bits: u8,
    pub offset: i64,
}

/// A parsed GLZK1 public-input block (§4.1).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublicInputs {
    pub action_id: String,
    /// SHA-256 of the rendering as UTF-8 (32 bytes).
    pub rendering_hash: Vec<u8>,
    pub issued_at_ms: i64,
    pub params: Vec<ParsedParam>,
}

/// One parameter row of a parsed block: descriptor + both commitments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedParam {
    pub descriptor: ParamDescriptor,
    /// Value commitment V_i (compressed Ristretto, 32 bytes).
    pub value_commitment: Vec<u8>,
    /// Envelope commitment C_i (compressed Ristretto, 32 bytes).
    pub envelope_commitment: Vec<u8>,
}

/// Errors from encoding/proving. Every variant is a named refusal —
/// proving never panics on bad input.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ZkError {
    /// A value or ceiling does not quantize into `[0, 2^n)`, or the
    /// value exceeds its ceiling (no proof of a false statement).
    OutOfDomain(String),
    /// Descriptors/values/ceilings/commitments/blindings disagree in
    /// count, or a byte field has the wrong length.
    ShapeMismatch(String),
    /// publicInputs do not parse (see [`reason::PARSE`]).
    Parse(String),
    /// The underlying Bulletproofs prover rejected the witness.
    Prover(String),
}

impl std::fmt::Display for ZkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ZkError::OutOfDomain(m) => write!(f, "out-of-domain: {m}"),
            ZkError::ShapeMismatch(m) => write!(f, "shape-mismatch: {m}"),
            ZkError::Parse(m) => write!(f, "parse: {m}"),
            ZkError::Prover(m) => write!(f, "prover: {m}"),
        }
    }
}
impl std::error::Error for ZkError {}

/* ------------------------------------------------------------------ */
/* Canonical encoding (§4.1)                                           */
/* ------------------------------------------------------------------ */

fn put_u32be(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}
fn put_u16be(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_be_bytes());
}
fn put_i64be(out: &mut Vec<u8>, v: i64) {
    out.extend_from_slice(&v.to_be_bytes());
}
fn put_len_prefixed(out: &mut Vec<u8>, bytes: &[u8]) {
    put_u32be(out, bytes.len() as u32);
    out.extend_from_slice(bytes);
}

/// Encode the canonical GLZK1 block (§4.1) from its parts. The
/// per-proof value commitments `V_i` are inputs here (the prover
/// generates their blindings internally and publishes the points).
pub fn encode_public_inputs(
    action_id: &str,
    rendering_hash: &[u8],
    issued_at_ms: i64,
    descriptors: &[ParamDescriptor],
    value_commitments: &[Vec<u8>],
    envelope_commitments: &[Vec<u8>],
) -> Result<Vec<u8>, ZkError> {
    let m = descriptors.len();
    if value_commitments.len() != m || envelope_commitments.len() != m {
        return Err(ZkError::ShapeMismatch(format!(
            "commitment counts ({}, {}) do not match descriptor count {m}",
            value_commitments.len(),
            envelope_commitments.len()
        )));
    }
    if rendering_hash.len() != 32 {
        return Err(ZkError::ShapeMismatch(format!(
            "rendering hash must be 32 bytes, got {}",
            rendering_hash.len()
        )));
    }
    if m > MAX_PARAMS {
        return Err(ZkError::ShapeMismatch(format!("param count {m} exceeds cap {MAX_PARAMS}")));
    }
    let mut out = Vec::with_capacity(256 + m * 96);
    out.extend_from_slice(DOMAIN_SEPARATOR);
    put_len_prefixed(&mut out, action_id.as_bytes());
    put_len_prefixed(&mut out, rendering_hash);
    put_i64be(&mut out, issued_at_ms);
    put_u16be(&mut out, m as u16);
    for i in 0..m {
        let d = &descriptors[i];
        if value_commitments[i].len() != 32 || envelope_commitments[i].len() != 32 {
            return Err(ZkError::ShapeMismatch(format!(
                "commitments for param {} must be 32 bytes each",
                d.name
            )));
        }
        put_len_prefixed(&mut out, d.name.as_bytes());
        put_u32be(&mut out, d.scale);
        out.push(d.bits);
        put_i64be(&mut out, d.offset);
        out.extend_from_slice(&value_commitments[i]);
        out.extend_from_slice(&envelope_commitments[i]);
    }
    Ok(out)
}

/* ------------------------------------------------------------------ */
/* Parsing (total: every malformed input is a named Parse error)       */
/* ------------------------------------------------------------------ */

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize, what: &str) -> Result<&'a [u8], ZkError> {
        if self.bytes.len() - self.pos < n {
            return Err(ZkError::Parse(format!("truncated at {what}")));
        }
        let s = &self.bytes[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }
    fn u32be(&mut self, what: &str) -> Result<u32, ZkError> {
        Ok(u32::from_be_bytes(self.take(4, what)?.try_into().unwrap()))
    }
    fn u16be(&mut self, what: &str) -> Result<u16, ZkError> {
        Ok(u16::from_be_bytes(self.take(2, what)?.try_into().unwrap()))
    }
    fn i64be(&mut self, what: &str) -> Result<i64, ZkError> {
        Ok(i64::from_be_bytes(self.take(8, what)?.try_into().unwrap()))
    }
    fn len_prefixed(&mut self, what: &str) -> Result<&'a [u8], ZkError> {
        let len = self.u32be(what)? as usize;
        if len > MAX_PUBLIC_INPUTS_BYTES {
            return Err(ZkError::Parse(format!("{what} length {len} exceeds cap")));
        }
        self.take(len, what)
    }
}

/// Parse a GLZK1 block. Total: garbage in → [`ZkError::Parse`],
/// never a panic, never a partial value.
pub fn parse_public_inputs(bytes: &[u8]) -> Result<PublicInputs, ZkError> {
    if bytes.len() > MAX_PUBLIC_INPUTS_BYTES {
        return Err(ZkError::Parse(format!(
            "publicInputs length {} exceeds cap {MAX_PUBLIC_INPUTS_BYTES}",
            bytes.len()
        )));
    }
    let mut r = Reader { bytes, pos: 0 };
    let tag = r.take(5, "domain separator")?;
    if tag != DOMAIN_SEPARATOR {
        return Err(ZkError::Parse("wrong domain separator (expected GLZK1)".into()));
    }
    let action_id_bytes = r.len_prefixed("actionId")?;
    let action_id = String::from_utf8(action_id_bytes.to_vec())
        .map_err(|_| ZkError::Parse("actionId is not valid UTF-8".into()))?;
    let rendering_hash = r.len_prefixed("renderingHash")?.to_vec();
    if rendering_hash.len() != 32 {
        return Err(ZkError::Parse(format!(
            "renderingHash must be 32 bytes, got {}",
            rendering_hash.len()
        )));
    }
    let issued_at_ms = r.i64be("issuedAtMs")?;
    let m = r.u16be("paramCount")? as usize;
    if m > MAX_PARAMS {
        return Err(ZkError::Parse(format!("param count {m} exceeds cap {MAX_PARAMS}")));
    }
    let mut params = Vec::with_capacity(m);
    for i in 0..m {
        let name_bytes = r.len_prefixed("param name")?;
        let name = String::from_utf8(name_bytes.to_vec())
            .map_err(|_| ZkError::Parse(format!("param {i} name is not valid UTF-8")))?;
        let scale = r.u32be("scale")?;
        let bits = r.take(1, "bits")?[0];
        if !(bits == 8 || bits == 16 || bits == 32 || bits == 64) {
            return Err(ZkError::Parse(format!("param {name} has invalid bit size {bits}")));
        }
        let offset = r.i64be("offset")?;
        let v = r.take(32, "value commitment")?.to_vec();
        let c = r.take(32, "envelope commitment")?.to_vec();
        params.push(ParsedParam {
            descriptor: ParamDescriptor { name, scale, bits, offset },
            value_commitment: v,
            envelope_commitment: c,
        });
    }
    if r.pos != bytes.len() {
        return Err(ZkError::Parse(format!(
            "{} trailing bytes after the final parameter",
            bytes.len() - r.pos
        )));
    }
    Ok(PublicInputs { action_id, rendering_hash, issued_at_ms, params })
}

/* ------------------------------------------------------------------ */
/* Commitments (§2.2, §5)                                              */
/* ------------------------------------------------------------------ */

fn scalar_from_u64(v: u64) -> Scalar {
    Scalar::from(v)
}

/// Decode a 32-byte little-endian scalar (the blinding wire format).
/// Rejects non-canonical / out-of-range encodings.
fn scalar_from_bytes(bytes: &[u8]) -> Result<Scalar, ZkError> {
    let arr: [u8; 32] = bytes
        .try_into()
        .map_err(|_| ZkError::ShapeMismatch(format!("blinding must be 32 bytes, got {}", bytes.len())))?;
    Option::<Scalar>::from(Scalar::from_canonical_bytes(arr))
        .ok_or_else(|| ZkError::ShapeMismatch("blinding is not a canonical scalar".into()))
}

fn random_scalar(rng: &mut OsRng) -> Scalar {
    let mut bytes = [0u8; 64];
    rng.fill_bytes(&mut bytes);
    Scalar::from_bytes_mod_order_wide(&bytes)
}

/// Compute a Pedersen commitment `value·G + blinding·H` with an
/// explicit blinding (rail-commit path and tests; §7.1 `commit`).
/// Returns the compressed point (32 bytes).
pub fn commit(value_q: u64, blinding: &[u8]) -> Result<Vec<u8>, ZkError> {
    let pc_gens = PedersenGens::default();
    let b = scalar_from_bytes(blinding)?;
    Ok(pc_gens
        .commit(scalar_from_u64(value_q), b)
        .compress()
        .to_bytes()
        .to_vec())
}

/// Rail-commit (§5): commit to a quantized ceiling with a FRESH
/// blinding generated inside this crate from the OS RNG. Returns
/// `(C_i, t_i)`: the commitment is the envelope's public face; the
/// blinding stays in the rail's private, device-local record.
pub fn commit_ceiling(ceiling_q: u64) -> (Vec<u8>, Vec<u8>) {
    let mut rng = OsRng;
    let t = random_scalar(&mut rng);
    let pc_gens = PedersenGens::default();
    let c = pc_gens.commit(scalar_from_u64(ceiling_q), t);
    (c.compress().to_bytes().to_vec(), t.to_bytes().to_vec())
}

/* ------------------------------------------------------------------ */
/* Proving (§2.2, §4, §5)                                              */
/* ------------------------------------------------------------------ */

/// A completed proof: the Bulletproofs bytes plus the canonical
/// public-input block they are bound to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProofBundle {
    pub proof: Vec<u8>,
    pub public_inputs: Vec<u8>,
}

/// The transcript discipline of §4.1: label `guardianlink/gate3/v1`,
/// absorb the SHA-256 of the entire public-input block, then let the
/// range proof run on the same transcript.
fn bound_transcript(public_inputs_block: &[u8]) -> Transcript {
    let mut transcript = Transcript::new(TRANSCRIPT_LABEL);
    let block_hash = Sha256::digest(public_inputs_block);
    transcript.append_message(BLOCK_HASH_LABEL, &block_hash);
    transcript
}

/// Prove envelope compliance for one action.
///
/// * `values_q` / `ceilings_q`: quantized values and ceilings, in
///   descriptor order (§2.3 — quantization is the caller's job and is
///   range-checked again here).
/// * `envelope_commitments` / `envelope_blindings`: the rail record's
///   `C_i` / `t_i` per parameter. Each `C_i` is RE-DERIVED from
///   `(ceiling, blinding)` and must match — a rail record whose
///   commitment does not open to its claimed ceiling is refused
///   before any proof exists.
/// * `issued_at_ms`: the tau-domain issuance time bound into the
///   block. TIMESTAMP DISCIPLINE (recorded deviation, see the crate
///   README): the design's §4.2 idealization stamps issuance at proof
///   COMPLETION, but a transcript-bound timestamp cannot be stamped
///   after the proof that binds it exists. The ceremony therefore
///   reads its clock immediately BEFORE calling the bridge and that
///   single value is used for the block, the bundle's outer
///   `Proofs.issuedAtMs`, and Gate 5 coherence. Proving time sits
///   inside the freshness window, bounded by the §8 benchmark gate
///   (≤10 s target against a 60 s window). The safe direction is
///   preserved: a proof can only ever be OLDER than its declared
///   issuance by the proving time, never fresher.
///
/// Value blindings `r_i` are generated here, per proof, from the OS
/// RNG, and never leave this function (§5).
pub fn prove_envelope(
    action_id: &str,
    rendering_hash: &[u8],
    issued_at_ms: i64,
    descriptors: &[ParamDescriptor],
    values_q: &[u64],
    ceilings_q: &[u64],
    envelope_commitments: &[Vec<u8>],
    envelope_blindings: &[Vec<u8>],
) -> Result<ProofBundle, ZkError> {
    let m = descriptors.len();
    if values_q.len() != m || ceilings_q.len() != m || envelope_commitments.len() != m || envelope_blindings.len() != m {
        return Err(ZkError::ShapeMismatch(format!(
            "statement parts disagree in count (descriptors {m}, values {}, ceilings {}, commitments {}, blindings {})",
            values_q.len(),
            ceilings_q.len(),
            envelope_commitments.len(),
            envelope_blindings.len()
        )));
    }
    if m == 0 {
        return Err(ZkError::ShapeMismatch(
            "no governed parameters: the v1 statement is vacuous and the ceremony must refuse rather than mint an empty proof".into(),
        ));
    }
    if m > MAX_PARAMS {
        return Err(ZkError::ShapeMismatch(format!("param count {m} exceeds cap {MAX_PARAMS}")));
    }
    // Common bit size: mixed sizes pad to the maximum (§2.3).
    let n = descriptors.iter().map(|d| d.bits).max().unwrap_or(32) as usize;
    if !(n == 8 || n == 16 || n == 32 || n == 64) {
        return Err(ZkError::ShapeMismatch(format!("invalid common bit size {n}")));
    }
    let bound: u128 = 1u128 << n;
    for i in 0..m {
        let d = &descriptors[i];
        if (values_q[i] as u128) >= bound {
            return Err(ZkError::OutOfDomain(format!(
                "value for {} quantizes to {} which is >= 2^{n}",
                d.name, values_q[i]
            )));
        }
        if (ceilings_q[i] as u128) >= bound {
            return Err(ZkError::OutOfDomain(format!(
                "ceiling for {} quantizes to {} which is >= 2^{n} (an unprovable envelope is a commit-time error)",
                d.name, ceilings_q[i]
            )));
        }
        if values_q[i] > ceilings_q[i] {
            return Err(ZkError::OutOfDomain(format!(
                "value {} exceeds ceiling {} for {}: no proof of a false statement",
                values_q[i], ceilings_q[i], d.name
            )));
        }
        // The rail record's commitment must open to its claimed ceiling.
        let derived = commit(ceilings_q[i], &envelope_blindings[i])?;
        if derived != envelope_commitments[i] {
            return Err(ZkError::ShapeMismatch(format!(
                "envelope commitment for {} does not open to the claimed ceiling under the stored blinding",
                d.name
            )));
        }
    }

    let pc_gens = PedersenGens::default();
    let mut rng = OsRng;

    // Witnesses: values with fresh blindings r_i; differences with
    // blindings t_i - r_i (§2.2). Witness material exists only in
    // this frame and is never returned or logged (§5).
    let mut agg_values: Vec<u64> = Vec::with_capacity(2 * m);
    let mut agg_blindings: Vec<Scalar> = Vec::with_capacity(2 * m);
    let mut value_commitments: Vec<Vec<u8>> = Vec::with_capacity(m);
    for i in 0..m {
        let r_i = random_scalar(&mut rng);
        let t_i = scalar_from_bytes(&envelope_blindings[i])?;
        let v_point = pc_gens.commit(scalar_from_u64(values_q[i]), r_i);
        value_commitments.push(v_point.compress().to_bytes().to_vec());
        agg_values.push(values_q[i]);
        agg_blindings.push(r_i);
        agg_values.push(ceilings_q[i] - values_q[i]);
        agg_blindings.push(t_i - r_i);
    }

    let block = encode_public_inputs(
        action_id,
        rendering_hash,
        issued_at_ms,
        descriptors,
        &value_commitments,
        envelope_commitments,
    )?;

    // Aggregated range proof over the 2m values. Bulletproofs
    // requires the aggregate count to be a power of two; pad with
    // (0, blinding 0) pairs — a range proof of 0 against the identity
    // commitment, which constrains nothing.
    let mut padded_values = agg_values.clone();
    let mut padded_blindings = agg_blindings.clone();
    while !padded_values.len().is_power_of_two() {
        padded_values.push(0);
        padded_blindings.push(Scalar::ZERO);
    }
    let bp_gens = BulletproofGens::new(n, padded_values.len());
    let mut transcript = bound_transcript(&block);
    let (proof, _commitments) = RangeProof::prove_multiple(
        &bp_gens,
        &pc_gens,
        &mut transcript,
        &padded_values,
        &padded_blindings,
        n,
    )
    .map_err(|e| ZkError::Prover(format!("{e:?}")))?;

    Ok(ProofBundle { proof: proof.to_bytes(), public_inputs: block })
}

/* ------------------------------------------------------------------ */
/* Verification (§4.2.3, §4.3 support)                                 */
/* ------------------------------------------------------------------ */

/// Verify a proof against its public-input block. Returns a
/// [`reason`] code — never panics, never throws across the FFI.
///
/// Checks, in order: block parses (PARSE) → inner timestamp fresh
/// against `now_ms` (§4.2.3: future-dated or older than
/// [`FRESHNESS_WINDOW_MS`] → FRESHNESS) → the aggregated range proof
/// verifies against `[V_i] ++ [C_i − V_i]` interleaved per parameter,
/// plus identity padding, on the §4.1 transcript (else PROOF_INVALID).
///
/// Note what this does NOT check: that the block's actionId /
/// renderingHash / issuedAtMs match the engine bundle's fields. The
/// verifyZk port never sees those fields — the cross-check is the
/// app's Gate 5 port (§4.3), implemented in `EnginePorts`.
pub fn verify_proof(proof_bytes: &[u8], public_inputs: &[u8], now_ms: i64) -> i32 {
    if proof_bytes.is_empty() || proof_bytes.len() > MAX_PROOF_BYTES {
        return reason::PARSE;
    }
    let parsed = match parse_public_inputs(public_inputs) {
        Ok(p) => p,
        Err(_) => return reason::PARSE,
    };
    // §4.2.3 freshness on the BOUND timestamp.
    if parsed.issued_at_ms > now_ms {
        return reason::FRESHNESS;
    }
    let age = now_ms.wrapping_sub(parsed.issued_at_ms);
    if age < 0 || age > FRESHNESS_WINDOW_MS {
        return reason::FRESHNESS;
    }
    let m = parsed.params.len();
    if m == 0 {
        // The empty statement (an action with no governed numeric
        // parameters): the section 2.2 claim is vacuously true, and
        // the only acceptable proof artifact is the canonical
        // vacuity marker — checked only after parse + freshness, and
        // bound to this action/rendering by the caller's Gate 5
        // coherence port (section 4.3). Anything else is invalid.
        return if proof_bytes == VACUITY_PROOF { reason::OK } else { reason::PROOF_INVALID };
    }
    let n = parsed.params.iter().map(|p| p.descriptor.bits).max().unwrap_or(32) as usize;

    // Rebuild the commitment list the proof ranges over, in the
    // prover's order: per parameter V_i then C_i - V_i, then identity
    // padding to a power of two.
    let mut commitments: Vec<CompressedRistretto> = Vec::with_capacity(2 * m);
    for p in &parsed.params {
        let v = CompressedRistretto(p.value_commitment.clone().try_into().unwrap());
        let v_point = match v.decompress() {
            Some(pt) => pt,
            None => return reason::PARSE,
        };
        let c = CompressedRistretto(p.envelope_commitment.clone().try_into().unwrap());
        let c_point = match c.decompress() {
            Some(pt) => pt,
            None => return reason::PARSE,
        };
        commitments.push(v);
        commitments.push((c_point - v_point).compress());
    }
    while !commitments.len().is_power_of_two() {
        commitments.push(CompressedRistretto::default());
    }

    let proof = match RangeProof::from_bytes(proof_bytes) {
        Ok(p) => p,
        Err(_) => return reason::PARSE,
    };
    let pc_gens = PedersenGens::default();
    let bp_gens = BulletproofGens::new(n, commitments.len());
    let mut transcript = bound_transcript(public_inputs);
    match proof.verify_multiple(&bp_gens, &pc_gens, &mut transcript, &commitments, n) {
        Ok(()) => reason::OK,
        Err(_) => reason::PROOF_INVALID,
    }
}

/// SHA-256 helper (rendering hashes, §4.1) — one hash path shared by
/// the codec, the FFI surface, and tests.
pub fn sha256(bytes: &[u8]) -> Vec<u8> {
    Sha256::digest(bytes).to_vec()
}
