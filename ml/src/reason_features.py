"""
The feature vector the phone can compute.

The Why-is-my-Baby-Crying pipeline uses 466 librosa features. Most of its
weight sits in `mel_spec`, `mfcc` and the MFCC deltas -- chroma contributes
nothing to the top forty and tonnetz contributes 0.004 -- and the expensive
ones to reimplement are exactly the ones that do not matter.

So this keeps the families that carry the model and computes them from the
frontend that already has a bit-checked Kotlin twin, rather than from librosa.
That matters more than it sounds: the log-mel transform exists in Python for
training and in Kotlin for inference, and a golden fixture pins them together.
Deriving MFCCs from that same log-mel means the whole 456-dimensional vector
inherits the guarantee, instead of introducing a second chance for the two
languages to disagree about a windowing convention.

Everything else about the pipeline -- the augmentation, SMOTE, the forest and
its hyperparameters -- is unchanged from the original.

    mel_spec   64 bands x {mean, std}                        128
    mfcc       40 coefficients x {mean, std, skew, kurt}     160
    mfcc_d     40 x {mean, std}                               80
    mfcc_d2    40 x {mean, std}                               80
    zcr        {mean, std, max, min}                           4
    rms        {mean, std, max, min}                           4
                                                            ---
                                                            456
"""

import numpy as np
from scipy.stats import kurtosis, skew

from audio import logmel_numpy
from config import HOP_LENGTH, N_MELS, WIN_LENGTH

N_MFCC = 40


def dct2_ortho(x: np.ndarray, n_out: int) -> np.ndarray:
    """
    Orthonormal DCT-II along the last axis, written out rather than imported.

    scipy's version is identical, but this is the definition the Kotlin side
    implements, and having the two sit next to each other in the same form is
    what stops them drifting.
    """
    n = x.shape[-1]
    k = np.arange(n_out)[:, None]
    i = np.arange(n)[None, :]
    basis = np.cos(np.pi * (2 * i + 1) * k / (2 * n))
    scale = np.full((n_out, 1), np.sqrt(2.0 / n))
    scale[0, 0] = np.sqrt(1.0 / n)
    return (basis * scale) @ x[..., None].squeeze(-1) if x.ndim == 1 else \
        x @ (basis * scale).T


def delta(x: np.ndarray) -> np.ndarray:
    """
    Centred first difference along time, edges replicated.

    librosa uses a Savitzky-Golay fit over nine frames. This is the simpler
    definition on purpose: it is one line in both languages and has no window
    or padding convention to get wrong, and for a statistic that is then
    reduced to a mean and a standard deviation the difference does not survive
    the reduction.
    """
    if x.shape[0] < 3:
        return np.zeros_like(x)
    out = np.empty_like(x)
    out[1:-1] = (x[2:] - x[:-2]) * 0.5
    out[0] = out[1]
    out[-1] = out[-2]
    return out


def frame_stats(wave: np.ndarray):
    """Zero-crossing rate and RMS, framed like the spectrogram."""
    wave = np.asarray(wave, dtype=np.float32)
    if wave.size < WIN_LENGTH:
        wave = np.pad(wave, (0, WIN_LENGTH - wave.size))
    frames = 1 + (wave.size - WIN_LENGTH) // HOP_LENGTH
    idx = np.arange(WIN_LENGTH)[None, :] + HOP_LENGTH * np.arange(frames)[:, None]
    framed = wave[idx]

    signs = np.signbit(framed)
    zcr = np.mean(signs[:, 1:] != signs[:, :-1], axis=1)
    rms = np.sqrt(np.mean(framed.astype(np.float64) ** 2, axis=1))
    return zcr.astype(np.float64), rms


def _stats(column: np.ndarray, which):
    out = []
    for stat in which:
        if stat == "mean":
            out.append(float(np.mean(column)))
        elif stat == "std":
            out.append(float(np.std(column)))
        elif stat == "skew":
            out.append(float(skew(column)) if np.std(column) > 1e-12 else 0.0)
        elif stat == "kurt":
            out.append(float(kurtosis(column)) if np.std(column) > 1e-12 else -3.0)
        elif stat == "max":
            out.append(float(np.max(column)))
        elif stat == "min":
            out.append(float(np.min(column)))
    return out


def feature_names():
    names = []
    for i in range(N_MELS):
        names += [f"mel_{i}_mean", f"mel_{i}_std"]
    for i in range(N_MFCC):
        names += [f"mfcc_{i}_mean", f"mfcc_{i}_std",
                  f"mfcc_{i}_skew", f"mfcc_{i}_kurt"]
    for i in range(N_MFCC):
        names += [f"mfccd_{i}_mean", f"mfccd_{i}_std"]
    for i in range(N_MFCC):
        names += [f"mfccdd_{i}_mean", f"mfccdd_{i}_std"]
    names += ["zcr_mean", "zcr_std", "zcr_max", "zcr_min"]
    names += ["rms_mean", "rms_std", "rms_max", "rms_min"]
    return names


def extract(wave: np.ndarray) -> np.ndarray:
    """float32 mono at SAMPLE_RATE -> (408,) float32."""
    mel = logmel_numpy(wave)                      # (frames, 40), natural log
    mfcc = dct2_ortho(mel, N_MFCC)                # (frames, 40)
    d1 = delta(mfcc)
    d2 = delta(d1)
    zcr, rms = frame_stats(wave)

    out = []
    for i in range(N_MELS):
        out += _stats(mel[:, i], ("mean", "std"))
    for i in range(N_MFCC):
        out += _stats(mfcc[:, i], ("mean", "std", "skew", "kurt"))
    for i in range(N_MFCC):
        out += _stats(d1[:, i], ("mean", "std"))
    for i in range(N_MFCC):
        out += _stats(d2[:, i], ("mean", "std"))
    out += _stats(zcr, ("mean", "std", "max", "min"))
    out += _stats(rms, ("mean", "std", "max", "min"))

    return np.asarray(out, dtype=np.float32)
