"""
Training-fold-only augmentation.

The original project augmented the whole corpus and *then* split it, so pitch-
shifted copies of a recording ended up on both sides of the test boundary and
the reported accuracy measured memorisation. Everything here is applied after
the split, to training clips only. evaluate.py deliberately reproduces the old
protocol as a comparison arm so the difference can be measured rather than
asserted.

The noise beds matter as much as the transforms. A nursery microphone hears a
fan, a television and adult conversation far more often than it hears a clean
cry, and a demo happens in a hall with a few hundred people in it.
"""

import numpy as np
import librosa

from config import SAMPLE_RATE, ESC50

# ESC-50 categories that plausibly share a room with a sleeping infant.
NOISE_CATEGORIES = (
    "vacuum_cleaner", "washing_machine", "clock_alarm", "clock_tick",
    "door_wood_knock", "can_opening", "keyboard_typing", "mouse_click",
    "drinking_sipping", "breathing", "coughing", "footsteps", "snoring",
    "toilet_flush", "water_drops", "wind", "rain", "engine",
)

_noise_cache: list[np.ndarray] | None = None


def noise_bank() -> list[np.ndarray]:
    """Lazily load a bank of real room noise to mix under training clips."""
    global _noise_cache
    if _noise_cache is not None:
        return _noise_cache

    import pandas as pd
    import datasets as ds

    meta = pd.read_csv(ESC50 / "meta" / "esc50.csv")
    wanted = meta[meta["category"].isin(NOISE_CATEGORIES)]
    bank = []
    for row in wanted.itertuples():
        try:
            bank.append(ds.load_wave(ESC50 / "audio" / row.filename))
        except Exception:
            continue
    _noise_cache = bank
    return bank


def _fit(wave: np.ndarray, length: int) -> np.ndarray:
    if wave.size >= length:
        return wave[:length]
    reps = int(np.ceil(length / max(wave.size, 1)))
    return np.tile(wave, reps)[:length]


def augment_wave(wave: np.ndarray, rng: np.random.Generator,
                 use_noise: bool = True) -> np.ndarray:
    """One randomly-perturbed variant of a training clip."""
    out = wave.astype(np.float32, copy=True)

    if rng.random() < 0.5:                                   # pitch, +/- 2 semitones
        steps = float(rng.uniform(-2.0, 2.0))
        try:
            out = librosa.effects.pitch_shift(y=out, sr=SAMPLE_RATE, n_steps=steps)
        except Exception:
            pass

    if rng.random() < 0.5:                                   # tempo, +/- 12 %
        rate = float(rng.uniform(0.88, 1.12))
        try:
            out = librosa.effects.time_stretch(y=out, rate=rate)
        except Exception:
            pass

    if use_noise and rng.random() < 0.6:                     # real room noise
        bank = noise_bank()
        if bank:
            bed = _fit(bank[rng.integers(len(bank))], out.size)
            snr_db = float(rng.uniform(5.0, 25.0))
            sig_p = float(np.mean(out ** 2)) + 1e-12
            bed_p = float(np.mean(bed ** 2)) + 1e-12
            scale = np.sqrt(sig_p / (bed_p * (10 ** (snr_db / 10.0))))
            out = out + bed * scale

    if rng.random() < 0.5:                                   # gain, -9..+3 dB
        out = out * float(10 ** (rng.uniform(-9.0, 3.0) / 20.0))

    if rng.random() < 0.3:                                   # small time shift
        shift = int(rng.integers(-SAMPLE_RATE // 4, SAMPLE_RATE // 4))
        out = np.roll(out, shift)

    peak = float(np.max(np.abs(out))) if out.size else 0.0
    if peak > 1.0:
        out = out / peak
    return out.astype(np.float32)


def spec_augment(patch: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """SpecAugment-style masking, applied in log-mel space at batch time."""
    out = patch.copy()
    floor = float(out.min())

    for _ in range(rng.integers(0, 3)):                      # frequency masks
        f = int(rng.integers(1, 9))
        f0 = int(rng.integers(0, max(1, out.shape[1] - f)))
        out[:, f0:f0 + f] = floor

    for _ in range(rng.integers(0, 3)):                      # time masks
        t = int(rng.integers(1, 13))
        t0 = int(rng.integers(0, max(1, out.shape[0] - t)))
        out[t0:t0 + t, :] = floor

    return out
