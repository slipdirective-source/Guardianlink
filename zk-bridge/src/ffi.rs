//! uniFFI surface (design §7.1): byte arrays / records in, byte
//! arrays / reason codes out, panic-contained.
//!
//! Choice recorded per the task's simpler-option rule: the design
//! prefers uniFFI over hand-written JNI and leaves .udl vs proc-macro
//! open; this crate uses the **proc-macro** surface
//! (`#[uniffi::export]`) — no .udl file to keep in sync, bindings are
//! generated from the compiled library's metadata by uniffi-bindgen.
//!
//! Panic containment: every exported function wraps its body in
//! `catch_unwind`. A panic becomes a typed error (prover/commit) or
//! the INTERNAL reason code (verifier) — it never unwinds across the
//! FFI boundary into the JVM. (uniFFI's own scaffolding also guards
//! exported calls; the explicit catch here makes the contract ours,
//! not the framework's, and covers the non-uniffi Rust tests too.)

use crate::{ParamDescriptor, ZkError};
use std::panic::{catch_unwind, AssertUnwindSafe};

uniffi::setup_scaffolding!();

/// Descriptor record crossing the FFI (mirrors §2.3).
#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiParamDescriptor {
    pub name: String,
    pub scale: u32,
    pub bits: u8,
    pub offset: i64,
}

/// Rail-commit result: the envelope commitment (public face) and its
/// blinding (private — the caller stores it in the rail's sealed,
/// device-local record and never logs or syncs it in cleartext).
#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiCommitResult {
    pub commitment: Vec<u8>,
    pub blinding: Vec<u8>,
}

/// A completed proof bundle: proof bytes + canonical publicInputs.
#[derive(uniffi::Record, Debug, Clone)]
pub struct FfiProofBundle {
    pub proof: Vec<u8>,
    pub public_inputs: Vec<u8>,
}

/// Typed FFI error: proving/commit failures are named refusals, not
/// panics and not silent empty results.
#[derive(uniffi::Error, Debug, Clone)]
pub enum FfiZkError {
    /// Value/ceiling outside the quantized domain, or value > ceiling.
    OutOfDomain { detail: String },
    /// Statement parts disagree (counts, lengths, commitment that
    /// does not open to its claimed ceiling).
    ShapeMismatch { detail: String },
    /// An internal failure — including a contained panic.
    Internal { detail: String },
}

impl std::fmt::Display for FfiZkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FfiZkError::OutOfDomain { detail } => write!(f, "out-of-domain: {detail}"),
            FfiZkError::ShapeMismatch { detail } => write!(f, "shape-mismatch: {detail}"),
            FfiZkError::Internal { detail } => write!(f, "internal: {detail}"),
        }
    }
}
impl std::error::Error for FfiZkError {}

impl From<ZkError> for FfiZkError {
    fn from(e: ZkError) -> Self {
        match e {
            ZkError::OutOfDomain(m) => FfiZkError::OutOfDomain { detail: m },
            ZkError::ShapeMismatch(m) => FfiZkError::ShapeMismatch { detail: m },
            ZkError::Parse(m) => FfiZkError::ShapeMismatch { detail: m },
            ZkError::Prover(m) => FfiZkError::Internal { detail: m },
        }
    }
}

fn panic_message(context: &str) -> FfiZkError {
    FfiZkError::Internal { detail: format!("{context}: internal panic contained at the FFI boundary") }
}

/// Rail-commit (§5): commit to a quantized ceiling with a fresh
/// in-crate blinding. Returns (C_i, t_i).
#[uniffi::export]
pub fn zk_commit_ceiling(ceiling_q: u64) -> Result<FfiCommitResult, FfiZkError> {
    catch_unwind(AssertUnwindSafe(|| {
        let (commitment, blinding) = crate::commit_ceiling(ceiling_q);
        Ok(FfiCommitResult { commitment, blinding })
    }))
    .unwrap_or_else(|_| Err(panic_message("zk_commit_ceiling")))
}

/// Commitment with an explicit blinding (§7.1 `commit`) — used by the
/// Kotlin test fake's cross-checks and by Rust tests.
#[uniffi::export]
pub fn zk_commit(value_q: u64, blinding: Vec<u8>) -> Result<Vec<u8>, FfiZkError> {
    catch_unwind(AssertUnwindSafe(|| crate::commit(value_q, &blinding).map_err(FfiZkError::from)))
        .unwrap_or_else(|_| Err(panic_message("zk_commit")))
}

/// Prove envelope compliance (§2.2). All witness material (values,
/// ceilings, envelope blindings) crosses in memory only and is never
/// returned, logged, or persisted by this crate (§5).
#[uniffi::export]
pub fn zk_prove_envelope(
    action_id: String,
    rendering_hash: Vec<u8>,
    issued_at_ms: i64,
    descriptors: Vec<FfiParamDescriptor>,
    values_q: Vec<u64>,
    ceilings_q: Vec<u64>,
    envelope_commitments: Vec<Vec<u8>>,
    envelope_blindings: Vec<Vec<u8>>,
) -> Result<FfiProofBundle, FfiZkError> {
    catch_unwind(AssertUnwindSafe(|| {
        let descs: Vec<ParamDescriptor> = descriptors
            .iter()
            .map(|d| ParamDescriptor {
                name: d.name.clone(),
                scale: d.scale,
                bits: d.bits,
                offset: d.offset,
            })
            .collect();
        crate::prove_envelope(
            &action_id,
            &rendering_hash,
            issued_at_ms,
            &descs,
            &values_q,
            &ceilings_q,
            &envelope_commitments,
            &envelope_blindings,
        )
        .map(|b| FfiProofBundle { proof: b.proof, public_inputs: b.public_inputs })
        .map_err(FfiZkError::from)
    }))
    .unwrap_or_else(|_| Err(panic_message("zk_prove_envelope")))
}

/// Verify a proof against its publicInputs (§4.2.3). Returns a reason
/// code (see `crate::reason`): 0 = OK. Total: malformed input, stale
/// or future timestamps, invalid proofs, and contained panics all
/// return codes — this function never throws across the FFI.
/// `now_ms` is the caller's tau-domain clock read (the ZK module
/// never reads a clock itself, §4.2).
#[uniffi::export]
pub fn zk_verify_proof(proof: Vec<u8>, public_inputs: Vec<u8>, now_ms: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| crate::verify_proof(&proof, &public_inputs, now_ms)))
        .unwrap_or(crate::reason::INTERNAL)
}

/// SHA-256 (§4.1 rendering hashes) — exposed so Kotlin and Rust share
/// the codec's hash path in tests.
#[uniffi::export]
pub fn zk_sha256(data: Vec<u8>) -> Vec<u8> {
    catch_unwind(AssertUnwindSafe(|| crate::sha256(&data))).unwrap_or_default()
}
