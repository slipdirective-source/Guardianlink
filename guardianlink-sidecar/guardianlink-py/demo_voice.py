"""Demo-voice synthesis — a Python port of the engine's SyntheticVoice fixture.

This exists so the quickstart can speak a voice challenge without a
microphone. It is a DEMO/TEST FIXTURE, not a biometric: the pipeline it
feeds (MFCC -> GMM-UBM -> challenge/DTW -> Gate 7) is real, the "voice"
is not. Do not cite synthetic-voice results as biometric performance
claims.

Faithfulness notes vs guardianlink/voice/SyntheticVoice.kt:
- formant table, phoneme duration (120 ms), harmonic series (14),
  formant bandwidth (120 Hz), 1/h glottal rolloff, vibrato (+-0.6% at
  5 Hz), raised-cosine edges, peak normalization, and the per-utterance
  detune (+-0.2% of f0, one kotlin.random.Random draw per utterance)
  are all reproduced. Kotlin's RNG algorithm is version-specific, so the
  detune factor for the demo seed is a measured constant (see _DETUNE);
  it is required — without it the DTW liveness distance against the
  Kotlin-enrolled phrase reference exceeds the engine's threshold.
- math.sin/cos differ from the JVM in the last ulp; irrelevant at the
  pipeline's margins.

Wire encoding: float32 little-endian mono PCM at 16 kHz, base64 — the
sidecar's ceremony audio format. float32, not int16: int16's absolute
quantization step destroys near-silent frames in log-mel space and pushes
the DTW liveness distance over the engine's threshold for some phrases.
"""

import base64
import math
import struct

SAMPLE_RATE_HZ = 16000

# Ten vowel-like phonemes as (F1, F2, F3) in Hz at scale 1.0, indexed by digit.
PHONEMES = [
    (730.0, 1090.0, 2440.0),  # 0
    (270.0, 2290.0, 3010.0),  # 1
    (300.0, 870.0, 2240.0),   # 2
    (530.0, 1840.0, 2480.0),  # 3
    (570.0, 840.0, 2410.0),   # 4
    (440.0, 1800.0, 2600.0),  # 5
    (660.0, 1720.0, 2410.0),  # 6
    (310.0, 2000.0, 2800.0),  # 7
    (520.0, 1500.0, 2500.0),  # 8
    (380.0, 2100.0, 2900.0),  # 9
]

PHONEME_MS = 120
FORMANT_BW = 120.0
HARMONICS = 14

# Challenge inventory: phraseId -> digit triple (mirrors the engine).
PHRASE_DIGITS = {
    "p1": (3, 7, 1), "p2": (9, 2, 5), "p3": (4, 8, 6), "p4": (1, 9, 3),
    "p5": (6, 4, 7), "p6": (2, 8, 9), "p7": (5, 1, 4), "p8": (7, 3, 2),
}

# The enrolled demo "person" (mirrors the sidecar's demo enrollment).
DEMO_SPEAKER = {"f0_hz": 120.0, "formant_scale": 1.0, "seed": 7}

# Per-utterance detune the engine fixture applies: f0 *= 1 + (r - 0.5) * 0.004
# where r is the first kotlin.random.Random(seed).nextDouble() of a fresh
# generator per utterance. Kotlin's RNG algorithm is version-specific, so the
# detune factor for the demo seed is a measured constant — exact hex from
# the JVM (Kotlin 1.9.22): Random(7L).nextDouble() feeds
# 0x1.0077f1ce14a96p0. It is required: without it, the DTW liveness
# distance against the Kotlin-enrolled phrase reference exceeds the
# engine's threshold; with it, the port verifies at Gate 7.
# Only the demo speaker's factor is needed — this module only ever speaks
# as the enrolled person.
_DETUNE = {
    7: float.fromhex("0x1.0077f1ce14a96p0"),
    99: float.fromhex("0x1.000a0f02e9b99p0"),
}

# A non-enrolled "impostor" voice, for adversarial testing.
IMPOSTOR_SPEAKER = {"f0_hz": 165.0, "formant_scale": 1.18, "seed": 99}


def speak_digits(f0_hz: float, formant_scale: float, digits, seed: int | None = None) -> list:
    """Render a digit sequence as mono PCM floats in [-1, 1].

    `seed` applies the engine fixture's per-utterance detune for that
    speaker seed (see _DETUNE). Omit it only for non-enrolled speakers.
    """
    f0 = f0_hz * _DETUNE.get(seed, 1.0) if seed is not None else f0_hz
    seg_len = PHONEME_MS * SAMPLE_RATE_HZ // 1000
    pcm = [0.0] * (seg_len * len(digits))
    for s, digit in enumerate(digits):
        formants = [f * formant_scale for f in PHONEMES[digit]]
        off = s * seg_len
        for n in range(seg_len):
            t = n / SAMPLE_RATE_HZ
            inst_f0 = f0 * (1.0 + 0.006 * math.sin(2.0 * math.pi * 5.0 * t))
            v = 0.0
            for h in range(1, HARMONICS + 1):
                f = h * inst_f0
                if f > SAMPLE_RATE_HZ / 2.0:
                    break
                gain = 0.0
                for ff in formants:
                    d = (f - ff) / FORMANT_BW
                    gain += 1.0 / (1.0 + d * d)
                v += gain * math.sin(2.0 * math.pi * f * t + h) / h
            edge = min(n, seg_len - 1 - n) / (0.01 * SAMPLE_RATE_HZ)
            e = min(1.0, max(0.0, edge))
            env = 0.5 * (1.0 - math.cos(math.pi * e))
            pcm[off + n] = 0.25 * v * env
    peak = max((abs(x) for x in pcm), default=0.0)
    if peak > 0:
        pcm = [x / peak for x in pcm]
    return pcm


def speak_phrase(phrase_id: str, speaker: dict | None = None) -> list:
    """Render a challenge phrase as the given (or demo) speaker."""
    sp = speaker or DEMO_SPEAKER
    digits = PHRASE_DIGITS.get(phrase_id)
    if digits is None:
        raise ValueError(f"unknown phrase {phrase_id}")
    return speak_digits(sp["f0_hz"], sp["formant_scale"], digits, sp.get("seed"))


def pcm_to_b64(pcm: list) -> str:
    """Float PCM -> base64 of little-endian float32 mono.

    float32, not int16: int16's absolute quantization step destroys
    near-silent frames in log-mel space and pushes the DTW liveness
    distance over the engine's threshold for some phrases. float32 keeps
    the wire transparent (measured DTW ~1e-5 vs a 2.0 threshold).
    """
    return base64.b64encode(struct.pack("<" + "f" * len(pcm), *pcm)).decode("ascii")
