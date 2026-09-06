"""
The log-mel frontend. One implementation, two heads on top of it.

Two paths are provided and they must agree:

  logmel_numpy(...)   reference implementation used for training data prep
  LogMelLayer         a Keras layer of the same maths, so the frontend can be
                      baked into the exported TFLite graph and the phone can
                      hand us raw PCM instead of reimplementing mel filters

build_mel_matrix() is shared by both, so the filterbank can never drift
between them.
"""

import numpy as np
import tensorflow as tf

from config import (SAMPLE_RATE, WIN_LENGTH, HOP_LENGTH, N_FFT, N_MELS,
                    FMIN, FMAX, LOG_OFFSET, PATCH_FRAMES, PATCH_SAMPLES)


def build_mel_matrix() -> np.ndarray:
    """(N_FFT//2+1, N_MELS) HTK-style mel filterbank, matching tf.signal."""
    return tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=N_MELS,
        num_spectrogram_bins=N_FFT // 2 + 1,
        sample_rate=SAMPLE_RATE,
        lower_edge_hertz=FMIN,
        upper_edge_hertz=FMAX,
    ).numpy()


_MEL = build_mel_matrix()


def logmel_numpy(wave: np.ndarray) -> np.ndarray:
    """float32 mono waveform -> (frames, N_MELS) log-mel, framed like YAMNet."""
    wave = np.asarray(wave, dtype=np.float32)
    if wave.size < WIN_LENGTH:
        wave = np.pad(wave, (0, WIN_LENGTH - wave.size))

    frames = 1 + (wave.size - WIN_LENGTH) // HOP_LENGTH
    idx = np.arange(WIN_LENGTH)[None, :] + HOP_LENGTH * np.arange(frames)[:, None]
    win = np.hanning(WIN_LENGTH + 1)[:-1].astype(np.float32)  # periodic, == tf.signal
    framed = wave[idx] * win

    spec = np.abs(np.fft.rfft(framed, n=N_FFT)).astype(np.float32)
    mel = spec @ _MEL
    return np.log(mel + LOG_OFFSET).astype(np.float32)


def patches_from_wave(wave: np.ndarray, hop_frames: int) -> np.ndarray:
    """(n_patches, PATCH_FRAMES, N_MELS). Short clips are zero-padded to one patch."""
    mel = logmel_numpy(wave)
    if mel.shape[0] < PATCH_FRAMES:
        pad = PATCH_FRAMES - mel.shape[0]
        # pad with the log of near-silence rather than zeros, which in log space
        # would be a *loud* value and teach the model a phantom edge feature
        mel = np.pad(mel, ((0, pad), (0, 0)), constant_values=np.log(LOG_OFFSET))
    starts = range(0, mel.shape[0] - PATCH_FRAMES + 1, hop_frames)
    return np.stack([mel[s:s + PATCH_FRAMES] for s in starts]).astype(np.float32)


def dbfs(wave: np.ndarray) -> float:
    rms = float(np.sqrt(np.mean(np.square(wave, dtype=np.float64)) + 1e-12))
    return 20.0 * np.log10(max(rms, 1e-9))


class LogMelLayer(tf.keras.layers.Layer):
    """Raw PCM -> log-mel patch, inside the graph.

    Lets us export a TFLite model that eats a float array straight off
    AudioRecord. Whether it survives conversion depends on RFFT support in the
    target runtime, so export_tflite.py tries this and falls back to a
    mel-input model if the converter refuses.
    """

    def __init__(self, **kw):
        super().__init__(**kw)
        self.mel_matrix = tf.constant(build_mel_matrix(), dtype=tf.float32)

    def call(self, wave):                      # (batch, PATCH_SAMPLES)
        stft = tf.signal.stft(
            wave,
            frame_length=WIN_LENGTH,
            frame_step=HOP_LENGTH,
            fft_length=N_FFT,
            window_fn=tf.signal.hann_window,
            pad_end=False,
        )
        mel = tf.matmul(tf.abs(stft), self.mel_matrix)
        return tf.math.log(mel + LOG_OFFSET)

    def compute_output_shape(self, s):
        return (s[0], PATCH_FRAMES, N_MELS)


def verify_frontends(tol: float = 2e-3) -> float:
    """Max abs difference between the numpy and graph frontends.

    Called by the training scripts. If these two ever disagree, the TFLite
    export is computing different features than the model was trained on.
    """
    rng = np.random.default_rng(0)
    wave = rng.standard_normal(PATCH_SAMPLES).astype(np.float32) * 0.1
    a = logmel_numpy(wave)[:PATCH_FRAMES]
    b = LogMelLayer()(wave[None, :]).numpy()[0]
    diff = float(np.max(np.abs(a - b)))
    if diff > tol:
        raise AssertionError(f"frontend mismatch: {diff:.6f} > {tol}")
    return diff
