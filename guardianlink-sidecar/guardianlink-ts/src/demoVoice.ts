/**
 * Demo-voice synthesis — a TypeScript port of the engine's SyntheticVoice fixture.
 *
 * Lets the quickstart speak a voice challenge without a microphone. This is a
 * DEMO/TEST FIXTURE, not a biometric: the pipeline it feeds (MFCC -> GMM-UBM ->
 * challenge/DTW -> Gate 7) is real, the "voice" is not.
 *
 * Faithfulness notes vs guardianlink/voice/SyntheticVoice.kt: formant table,
 * phoneme duration (120 ms), 14 harmonics, formant bandwidth (120 Hz), 1/h
 * glottal rolloff, vibrato (+-0.6% at 5 Hz), raised-cosine edges, and peak
 * normalization are ported exactly, as is the per-utterance detune (+-0.2%
 * of f0, one kotlin.random.Random draw per utterance) via a measured
 * constant (Kotlin's RNG is version-specific; without the detune the DTW
 * liveness distance exceeds the engine's threshold — verified empirically
 * against the real sidecar's Gate 7).
 *
 * Wire encoding: float32 little-endian mono PCM at 16 kHz, base64.
 * float32, not int16: int16's absolute quantization step destroys
 * near-silent frames in log-mel space and pushes the DTW liveness
 * distance over the engine's threshold for some phrases.
 */

export const SAMPLE_RATE_HZ = 16000;

/** Ten vowel-like phonemes as (F1, F2, F3) in Hz at scale 1.0, indexed by digit. */
const PHONEMES: ReadonlyArray<readonly [number, number, number]> = [
  [730, 1090, 2440], // 0
  [270, 2290, 3010], // 1
  [300, 870, 2240], // 2
  [530, 1840, 2480], // 3
  [570, 840, 2410], // 4
  [440, 1800, 2600], // 5
  [660, 1720, 2410], // 6
  [310, 2000, 2800], // 7
  [520, 1500, 2500], // 8
  [380, 2100, 2900], // 9
];

const PHONEME_MS = 120;
const FORMANT_BW = 120.0;
const HARMONICS = 14;

/** Challenge inventory: phraseId -> digit triple (mirrors the engine). */
export const PHRASE_DIGITS: Record<string, number[]> = {
  p1: [3, 7, 1], p2: [9, 2, 5], p3: [4, 8, 6], p4: [1, 9, 3],
  p5: [6, 4, 7], p6: [2, 8, 9], p7: [5, 1, 4], p8: [7, 3, 2],
};

export interface DemoSpeaker {
  f0Hz: number;
  formantScale: number;
  seed?: number;
}

/** The enrolled demo "person" (mirrors the sidecar's demo enrollment). */
export const DEMO_SPEAKER: DemoSpeaker = { f0Hz: 120.0, formantScale: 1.0, seed: 7 };

/**
 * Per-utterance detune the engine fixture applies: f0 *= 1 + (r - 0.5) * 0.004
 * where r is the first kotlin.random.Random(seed).nextDouble() of a fresh
 * generator per utterance. Kotlin's RNG is version-specific, so the factor
 * for the demo seed is a measured constant — exact from the JVM (Kotlin
 * 1.9.22). Required: without it the DTW liveness distance against the
 * Kotlin-enrolled phrase reference exceeds the engine's threshold.
 */
const DETUNE: Record<number, number> = { 7: 1.0018302085997326 }; // == 0x1.0077f1ce14a96p0, shortest round-trip decimal

/** Render a digit sequence as mono PCM floats in [-1, 1]. `seed` applies the engine fixture's per-utterance detune. */
export function speakDigits(f0Hz: number, formantScale: number, digits: number[], seed?: number): Float64Array {
  const f0 = seed !== undefined ? f0Hz * (DETUNE[seed] ?? 1.0) : f0Hz;
  const segLen = (PHONEME_MS * SAMPLE_RATE_HZ) / 1000;
  const pcm = new Float64Array(segLen * digits.length);
  digits.forEach((digit, s) => {
    const formants = PHONEMES[digit].map((f) => f * formantScale);
    const off = s * segLen;
    for (let n = 0; n < segLen; n++) {
      const t = n / SAMPLE_RATE_HZ;
      const instF0 = f0 * (1.0 + 0.006 * Math.sin(2.0 * Math.PI * 5.0 * t));
      let v = 0.0;
      for (let h = 1; h <= HARMONICS; h++) {
        const f = h * instF0;
        if (f > SAMPLE_RATE_HZ / 2.0) break;
        let gain = 0.0;
        for (const ff of formants) {
          const d = (f - ff) / FORMANT_BW;
          gain += 1.0 / (1.0 + d * d);
        }
        v += (gain * Math.sin(2.0 * Math.PI * f * t + h)) / h;
      }
      const edge = Math.min(n, segLen - 1 - n) / (0.01 * SAMPLE_RATE_HZ);
      const e = Math.min(1.0, Math.max(0.0, edge));
      const env = 0.5 * (1.0 - Math.cos(Math.PI * e));
      pcm[off + n] = 0.25 * v * env;
    }
  });
  let peak = 0.0;
  for (const x of pcm) peak = Math.max(peak, Math.abs(x));
  if (peak > 0) for (let i = 0; i < pcm.length; i++) pcm[i] /= peak;
  return pcm;
}

/** Render a challenge phrase as the given (or demo) speaker. */
export function speakPhrase(phraseId: string, speaker: DemoSpeaker = DEMO_SPEAKER): Float64Array {
  const digits = PHRASE_DIGITS[phraseId];
  if (!digits) throw new Error(`unknown phrase ${phraseId}`);
  return speakDigits(speaker.f0Hz, speaker.formantScale, digits, speaker.seed);
}

/**
 * Float PCM -> base64 of little-endian float32 mono. float32, not int16:
 * int16's absolute quantization step destroys near-silent frames in
 * log-mel space and pushes the DTW liveness distance over the engine's
 * threshold for some phrases.
 */
export function pcmToB64(pcm: Float64Array): string {
  const buf = Buffer.alloc(pcm.length * 4);
  for (let i = 0; i < pcm.length; i++) {
    buf.writeFloatLE(pcm[i], i * 4);
  }
  return buf.toString("base64");
}
