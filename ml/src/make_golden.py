"""
Golden vectors that pin the Kotlin frontend to the Python one.

The Android log-mel implementation is a hand-port of audio.py. A hand-port can
drift in ways that never throw -- an off-by-one in the mel filterbank, a
symmetric Hann window instead of a periodic one -- and the only symptom is
slightly worse accuracy that nobody attributes to the right cause.

So we serialise a few real inputs and their expected outputs, and let a unit
test fail loudly instead.
"""

import json

import numpy as np

from config import PATCH_SAMPLES, PATCH_FRAMES, N_MELS, MODELS
import audio
import datasets as ds

TEST_RES = MODELS.parents[1] / "android" / "app" / "src" / "test" / "resources"


def main() -> None:
    TEST_RES.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(4242)
    cases = []

    # A deterministic synthetic signal: reproducible anywhere, and it exercises
    # the whole frequency range rather than whatever a particular clip contains.
    t = np.arange(PATCH_SAMPLES) / 16_000.0
    sweep = (0.6 * np.sin(2 * np.pi * (200 + 2600 * t) * t)
             + 0.2 * rng.standard_normal(PATCH_SAMPLES)).astype(np.float32)
    cases.append(("sweep_plus_noise", sweep))

    cases.append(("silence", np.zeros(PATCH_SAMPLES, dtype=np.float32)))
    cases.append(("impulse", np.eye(1, PATCH_SAMPLES, 5000, dtype=np.float32)[0]))

    # And one real cry, so the test covers the actual signal distribution.
    clip = ds.donateacry_clips()[0]
    wave = ds.load_wave(clip.path)
    if wave.size >= PATCH_SAMPLES:
        cases.append(("real_cry", wave[:PATCH_SAMPLES].astype(np.float32)))

    payload = {
        "spec": {
            "sample_rate": 16_000, "win_length": 400, "hop_length": 160,
            "n_fft": 512, "n_mels": N_MELS, "fmin": 125.0, "fmax": 7500.0,
            "patch_frames": PATCH_FRAMES, "patch_samples": PATCH_SAMPLES,
        },
        "cases": [],
    }

    for name, wave in cases:
        mel = audio.logmel_numpy(wave)[:PATCH_FRAMES]
        payload["cases"].append({
            "name": name,
            # NOT rounded. Quiet frames of a real recording contain samples
            # around 1e-6, and rounding those to a fixed number of decimal
            # places quantises them to two significant figures -- which shifts
            # the near-silent mel bins enough to move the log floor and makes
            # the fixture, not the implementation, the thing under test.
            "input": [float(v) for v in wave],
            # Full patch would be 6144 floats per case; the first and last few
            # frames plus per-frame sums catch every realistic porting error
            # while keeping the fixture readable.
            "first_frame": [round(float(v), 5) for v in mel[0]],
            "middle_frame": [round(float(v), 5) for v in mel[PATCH_FRAMES // 2]],
            "last_frame": [round(float(v), 5) for v in mel[-1]],
            "frame_sums": [round(float(v), 4) for v in mel.sum(axis=1)],
            "shape": list(mel.shape),
        })

    out = TEST_RES / "logmel_golden.json"
    out.write_text(json.dumps(payload))
    print(f"  {out.relative_to(MODELS.parents[1])}  "
          f"{out.stat().st_size / 1024:.1f} KB  {len(payload['cases'])} cases")
    for c in payload["cases"]:
        print(f"    {c['name']:20s} shape={c['shape']}  "
              f"frame0 sum={sum(c['first_frame']):.2f}")


if __name__ == "__main__":
    main()
