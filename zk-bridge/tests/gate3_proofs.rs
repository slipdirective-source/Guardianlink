//! Statement-level attack corpus for Gate 3 (design §8, Rust subset).
//! Every rejection case must fail closed: a reason code / typed error,
//! never a panic, never a verifying proof.

use glzk::reason;
use glzk::{commit_ceiling, parse_public_inputs, prove_envelope, sha256, verify_proof, ParamDescriptor};

const NOW: i64 = 1_700_000_000_000;

fn descriptor(name: &str) -> ParamDescriptor {
    ParamDescriptor { name: name.to_string(), scale: 1000, bits: 32, offset: 0 }
}

struct Statement {
    descriptors: Vec<ParamDescriptor>,
    ceilings: Vec<u64>,
    commitments: Vec<Vec<u8>>,
    blindings: Vec<Vec<u8>>,
}

fn statement(ceilings: &[u64]) -> Statement {
    let names = ["amount", "count", "level", "rate"];
    let mut s = Statement { descriptors: vec![], ceilings: vec![], commitments: vec![], blindings: vec![] };
    for (i, c) in ceilings.iter().enumerate() {
        s.descriptors.push(descriptor(names[i]));
        s.ceilings.push(*c);
        let (commitment, blinding) = commit_ceiling(*c);
        s.commitments.push(commitment);
        s.blindings.push(blinding);
    }
    s
}

fn prove(s: &Statement, values: &[u64], action_id: &str, rendering: &str, issued_at_ms: i64) -> glzk::ProofBundle {
    prove_envelope(
        action_id,
        &sha256(rendering.as_bytes()),
        issued_at_ms,
        &s.descriptors,
        values,
        &s.ceilings,
        &s.commitments,
        &s.blindings,
    )
    .expect("honest statement must prove")
}

#[test]
fn honest_proof_verifies_including_boundary() {
    let s = statement(&[1_000_000, 5]);
    // v == c for the second parameter (boundary, section 8 case 1).
    let bundle = prove(&s, &[999_999, 5], "a-1", "render(pause)", NOW);
    assert_eq!(verify_proof(&bundle.proof, &bundle.public_inputs, NOW + 1_000), reason::OK);
    // The block parses back to exactly the bound fields.
    let parsed = parse_public_inputs(&bundle.public_inputs).expect("block parses");
    assert_eq!(parsed.action_id, "a-1");
    assert_eq!(parsed.issued_at_ms, NOW);
    assert_eq!(parsed.params.len(), 2);
    assert_eq!(parsed.rendering_hash, sha256("render(pause)".as_bytes()));
}

#[test]
fn out_of_envelope_value_cannot_produce_a_verifying_proof() {
    let s = statement(&[100]);
    // One quantum over the ceiling (section 8 case 3): the prover
    // refuses outright.
    let err = prove_envelope(
        "a-2",
        &sha256(b"r"),
        NOW,
        &s.descriptors,
        &[101],
        &s.ceilings,
        &s.commitments,
        &s.blindings,
    );
    assert!(err.is_err(), "prover must refuse an out-of-envelope witness");
    // And a hand-assembled forgery attempt: take an honest proof for
    // v=100 and re-present it against a block whose value commitment
    // is swapped for a commitment to 101 (fresh blinding) — the proof
    // no longer matches the commitments and must reject.
    let honest = prove(&s, &[100], "a-2", "r", NOW);
    let mut parsed = parse_public_inputs(&honest.public_inputs).unwrap();
    let (forged_v, _b) = commit_ceiling(101);
    parsed.params[0].value_commitment = forged_v;
    let forged_block = glzk::encode_public_inputs(
        &parsed.action_id,
        &parsed.rendering_hash,
        parsed.issued_at_ms,
        &s.descriptors,
        &[parsed.params[0].value_commitment.clone()],
        &[parsed.params[0].envelope_commitment.clone()],
    )
    .unwrap();
    assert_eq!(verify_proof(&honest.proof, &forged_block, NOW + 500), reason::PROOF_INVALID);
}

#[test]
fn tampered_proof_bytes_fail() {
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "a-3", "r", NOW);
    let mut forged = bundle.proof.clone();
    let mid = forged.len() / 2;
    forged[mid] ^= 0x01;
    assert_eq!(verify_proof(&forged, &bundle.public_inputs, NOW + 500), reason::PROOF_INVALID);
    // Random bytes of plausible length (section 8 case 2).
    let random: Vec<u8> = (0..bundle.proof.len()).map(|i| (i * 37 + 11) as u8).collect();
    let code = verify_proof(&random, &bundle.public_inputs, NOW + 500);
    assert!(code == reason::PROOF_INVALID || code == reason::PARSE, "forged proof rejected, got {code}");
}

#[test]
fn wrong_action_id_binding_fails() {
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "action-A", "r", NOW);
    // Re-encode the same commitments under action B (section 8 case
    // 4): the transcript no longer matches the proof.
    let parsed = parse_public_inputs(&bundle.public_inputs).unwrap();
    let block_b = glzk::encode_public_inputs(
        "action-B",
        &parsed.rendering_hash,
        parsed.issued_at_ms,
        &s.descriptors,
        &[parsed.params[0].value_commitment.clone()],
        &[parsed.params[0].envelope_commitment.clone()],
    )
    .unwrap();
    assert_eq!(verify_proof(&bundle.proof, &block_b, NOW + 500), reason::PROOF_INVALID);
    // A different rendering hash fails the same way (case 5).
    let block_r = glzk::encode_public_inputs(
        &parsed.action_id,
        &sha256(b"a different rendering"),
        parsed.issued_at_ms,
        &s.descriptors,
        &[parsed.params[0].value_commitment.clone()],
        &[parsed.params[0].envelope_commitment.clone()],
    )
    .unwrap();
    assert_eq!(verify_proof(&bundle.proof, &block_r, NOW + 500), reason::PROOF_INVALID);
}

#[test]
fn tampered_commitment_fails() {
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "a-5", "r", NOW);
    let parsed = parse_public_inputs(&bundle.public_inputs).unwrap();
    // One byte flipped in C_i (section 8 case 12).
    let mut c = parsed.params[0].envelope_commitment.clone();
    c[0] ^= 0x01;
    let block = glzk::encode_public_inputs(
        &parsed.action_id,
        &parsed.rendering_hash,
        parsed.issued_at_ms,
        &s.descriptors,
        &[parsed.params[0].value_commitment.clone()],
        &[c],
    )
    .unwrap();
    let code = verify_proof(&bundle.proof, &block, NOW + 500);
    assert!(code == reason::PROOF_INVALID || code == reason::PARSE, "tampered commitment rejected, got {code}");
}

#[test]
fn freshness_window_is_enforced_on_the_bound_timestamp() {
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "a-6", "r", NOW);
    // Stale inner timestamp with a fresh outer presentation (case 6):
    // the verifier reads the INNER time.
    assert_eq!(verify_proof(&bundle.proof, &bundle.public_inputs, NOW + 60_001), reason::FRESHNESS);
    assert_eq!(verify_proof(&bundle.proof, &bundle.public_inputs, NOW + 59_999), reason::OK);
    // Future-dated inner timestamp (case 8).
    let future = prove(&s, &[7], "a-6", "r", NOW + 10_000);
    assert_eq!(verify_proof(&future.proof, &future.public_inputs, NOW), reason::FRESHNESS);
}

#[test]
fn malformed_inputs_error_cleanly() {
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "a-7", "r", NOW);
    // Empty / garbage / truncated publicInputs (case 11).
    assert_eq!(verify_proof(&bundle.proof, &[], NOW), reason::PARSE);
    assert_eq!(verify_proof(&bundle.proof, b"garbage", NOW), reason::PARSE);
    let truncated = &bundle.public_inputs[..bundle.public_inputs.len() - 10];
    assert_eq!(verify_proof(&bundle.proof, truncated, NOW), reason::PARSE);
    // Wrong version tag.
    let mut wrong_tag = bundle.public_inputs.clone();
    wrong_tag[4] = b'2';
    assert_eq!(verify_proof(&bundle.proof, &wrong_tag, NOW), reason::PARSE);
    // Trailing bytes.
    let mut trailing = bundle.public_inputs.clone();
    trailing.push(0);
    assert_eq!(verify_proof(&bundle.proof, &trailing, NOW), reason::PARSE);
    // Empty / truncated proof.
    assert_eq!(verify_proof(&[], &bundle.public_inputs, NOW), reason::PARSE);
    let short_proof = &bundle.proof[..bundle.proof.len() / 3];
    let code = verify_proof(short_proof, &bundle.public_inputs, NOW);
    assert!(code == reason::PARSE || code == reason::PROOF_INVALID, "truncated proof rejected, got {code}");
    // Parse errors are typed, never panics.
    assert!(parse_public_inputs(b"\x00\x01\x02").is_err());
    assert!(parse_public_inputs(&vec![0xFFu8; 100_000]).is_err());
}

#[test]
fn ceiling_outside_domain_is_refused_before_any_proof() {
    // Ceiling >= 2^n (section 8 case 13): commit-time configuration
    // error — proving refuses with a named error.
    let big = 1u64 << 32;
    let (commitment, blinding) = commit_ceiling(big);
    let err = prove_envelope(
        "a-8",
        &sha256(b"r"),
        NOW,
        &[descriptor("amount")],
        &[0],
        &[big],
        &[commitment],
        &[blinding],
    );
    assert!(err.is_err(), "ceiling >= 2^32 must be refused");
}

#[test]
fn envelope_commitment_must_open_to_the_claimed_ceiling() {
    // A rail record whose commitment was made for a DIFFERENT ceiling
    // cannot be re-labeled: the prover re-derives and refuses.
    let (commitment, blinding) = commit_ceiling(50);
    let err = prove_envelope(
        "a-9",
        &sha256(b"r"),
        NOW,
        &[descriptor("amount")],
        &[10],
        &[999],
        &[commitment],
        &[blinding],
    );
    assert!(err.is_err(), "commitment/ceiling mismatch must refuse");
}

#[test]
fn fuzzed_byte_flips_never_verify_and_never_panic() {
    let s = statement(&[1000, 42]);
    let bundle = prove(&s, &[7, 42], "a-10", "r", NOW);
    // Flip every kth byte of the publicInputs: never OK.
    for i in (0..bundle.public_inputs.len()).step_by(7) {
        let mut mutated = bundle.public_inputs.clone();
        mutated[i] ^= 0x40;
        let code = verify_proof(&bundle.proof, &mutated, NOW + 500);
        assert_ne!(code, reason::OK, "byte flip at {i} must not verify");
    }
    // Flip bytes across the proof: never OK.
    for i in (0..bundle.proof.len()).step_by(53) {
        let mut mutated = bundle.proof.clone();
        mutated[i] ^= 0x02;
        let code = verify_proof(&mutated, &bundle.public_inputs, NOW + 500);
        assert_ne!(code, reason::OK, "proof byte flip at {i} must not verify");
    }
}

#[test]
fn empty_statement_accepts_only_the_vacuity_marker() {
    // An action with no governed numeric parameters: block with
    // paramCount 0, proof = the GLZK0 marker.
    let block = glzk::encode_public_inputs("a-empty", &sha256(b"r"), NOW, &[], &[], &[]).unwrap();
    assert_eq!(verify_proof(glzk::VACUITY_PROOF, &block, NOW + 500), reason::OK);
    // A range-proof-shaped artifact or garbage is NOT accepted for
    // the empty statement.
    assert_eq!(verify_proof(b"anything-else", &block, NOW + 500), reason::PROOF_INVALID);
    // And the marker is worthless for a NON-empty statement.
    let s = statement(&[1000]);
    let bundle = prove(&s, &[7], "a-empty2", "r", NOW);
    let code = verify_proof(glzk::VACUITY_PROOF, &bundle.public_inputs, NOW + 500);
    assert!(code == reason::PROOF_INVALID || code == reason::PARSE, "marker must not verify a real statement, got {code}");
    // Stale vacuity still fails freshness.
    assert_eq!(verify_proof(glzk::VACUITY_PROOF, &block, NOW + 61_000), reason::FRESHNESS);
}

#[test]
fn proof_size_for_the_v1_shape_is_measured() {
    // Benchmark-gate bookkeeping (design section 8): record the exact
    // proof size for the v1 shape (n = 32, m = 4). This is a host
    // measurement for the build log — NOT a device performance claim.
    let s = statement(&[1_000_000, 5, 77, 9_999]);
    let bundle = prove(&s, &[1, 5, 76, 9_999], "a-size", "r", NOW);
    assert_eq!(verify_proof(&bundle.proof, &bundle.public_inputs, NOW + 500), reason::OK);
    println!("v1 shape (n=32, m=4): proof = {} bytes, publicInputs = {} bytes", bundle.proof.len(), bundle.public_inputs.len());
    assert!(bundle.proof.len() < 2048, "proof unexpectedly large: {}", bundle.proof.len());
}
