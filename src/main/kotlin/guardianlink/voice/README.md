# Voice biometrics — the Identity & Intent Anchor

Real voice-biometric verification for Gate 7 assent. No stubs: every code
path does genuine work — MFCC feature extraction, EM-trained GMM-UBM
speaker scoring, DTW challenge-response liveness — all implemented in this
package, dependency-free.

## Architecture

```
assent ceremony (deployment)            engine (Gate 7, theta_voice)
─────────────────────────              ────────────────────────────
issueChallenge() → prompt ──► person speaks ──► PCM ──► verify()
                                                        │
                                    ┌───────────────────┴───────────────────┐
                                    │ speaker check          liveness check │
                                    │ MFCC → GMM-UBM LLR     DTW(utterance,  │
                                    │ vs enrolled template   enrolled phrase │
                                    │                        ref for the     │
                                    │                        ACTIVE challenge│
                                    └───────────────────┬───────────────────┘
                                                        │ both must pass
                                                     ACCEPT / REJECT
```

**Pipeline:** `AudioSample` (16 kHz mono PCM) → `Mfcc.extract` (39-dim
frames: 13 cepstra + deltas + delta-deltas, CMN) → `SpeakerModel`
(GMM-UBM log-likelihood ratio vs. enrolled voiceprint) + `Dtw` (spoken
content vs. enrolled reference for the challenged phrase).

**Fail-closed:** empty / non-finite / wrong-rate audio, no active
challenge, missing phrase reference, or template mismatch all yield
REJECT — never ACCEPT, never throw. The challenge is single-use: `verify`
consumes it, so every ceremony needs a fresh `issueChallenge`.

## The port contract (`BiometricVerifier`)

```kotlin
interface BiometricVerifier {
    fun enroll(samples: List<AudioSample>): VoiceTemplate
    fun verify(sample: AudioSample, template: VoiceTemplate): VerificationResult
}
```

- **Enrollment is deployment-time.** `enroll` turns labeled audio into a
  `VoiceTemplate`; `enrollPhrases` adds labeled phrase references for the
  liveness content check. The template is deployment-owned state — it is
  passed to the engine as a constructor instrument (`NineGates(...,
  biometricVerifier, enrolledVoice, ...)`), never as caller evidence. A
  caller-supplied template would let the caller enroll themselves.
- **Verification is assent-time.** `verify` returns
  `VerificationResult(score, decision, liveness)`; Gate 7 requires
  `ACCEPT` **and** `LIVE`.
- **Total:** verification may fail, never throw. A throwing adapter fails
  closed via the engine's `checked()` wrapper like every other port.

## Model pluggability (`SpeakerModel`)

```kotlin
interface SpeakerModel {
    fun enroll(frames: List<DoubleArray>): SpeakerEmbedding
    fun score(frames: List<DoubleArray>, embedding: SpeakerEmbedding): Double
}
```

The shipped baseline is `GmmUbmSpeakerModel`: UBM trained by EM on a
background population, speaker voiceprint by MAP-adapting the UBM means,
score as mean per-frame log-likelihood ratio (positive = more like the
enrolled speaker than the background). **Dropping in a stronger model**
(e.g. an ONNX ECAPA-TDNN): implement `SpeakerModel` — `enroll` runs the
network over the frames and returns `SpeakerEmbedding.Vector`
(L2-normalized); `score` returns cosine similarity. Nothing else changes:
MFCC extraction, the challenge/DTW liveness check, the fail-closed Gate 7
wiring, and the port are all model-agnostic. The ONNX runtime itself is
deployment scope — this module stays dependency-free on purpose.

## Thresholds (calibrated, synthetic data)

| Threshold | Value | Same-speaker | Impostor / wrong phrase |
|---|---|---|---|
| `acceptThreshold` (LLR) | 0.0 | +6.7 … +11.6 | −2.2 … −3.8 |
| `phraseThreshold` (DTW) | 2.0 | ≈ 0.0 | ≈ 20–23 |

Calibrated on deterministic synthetic voices (see below). **Re-calibrate
on real data before production use** — synthetic margins do not transfer.

## Honest limits

1. **No deepfake / TTS detection.** Liveness is challenge-response only:
   the spoken content must match a random issued phrase. A real-time voice
   clone that speaks the challenge phrase on demand is not detected.
2. **Replay of the active challenge works.** A recording of the *current*
   challenge response verifies while that challenge is active.
   Recourse: the deployment issues a fresh challenge per ceremony and
   bounds challenge lifetime; the adapter consumes each challenge on use.
3. **UBM quality bounds accuracy.** The baseline trains the background
   model on whatever population the deployment supplies (constructor
   argument). A narrow or mismatched background population degrades
   discrimination — the classic GMM-UBM caveat, not a code bug.
4. **16 kHz mono only.** Resampling, microphone capture, echo/noise
   handling, and the challenge ceremony are the deployment's job.
5. **Templates are sensitive.** They contain model parameters — protect
   at rest. A stolen template aids impersonation research but cannot by
   itself pass liveness.
6. **Display-ceremony gap (engine level).** The engine proves the signed
   bytes equal `Render(a)` of the executed action; it cannot prove the
   human's eyes saw those bytes, nor that the voice at the microphone was
   uncoerced. An attested display/signing path is deployment's job.

## Synthetic voices (`SyntheticVoice`)

Deterministic formant-based fixture for tests and the demo — **not a
biometric claim**. Generates labeled PCM (speaker = f0 × formant scale;
phrase = digit triple rendered as vowel-like formant sequences) so the
pipeline is exercised without microphone capture. Do not cite
synthetic-voice test results as biometric performance claims.
