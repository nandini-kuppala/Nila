"""
Two heads on one frontend.

Both models take a (PATCH_FRAMES, N_MELS) log-mel patch. They are deliberately
small: the detector runs continuously all night on a phone, so its cost is a
battery number, not a benchmark number. Depthwise-separable convolutions give
most of the accuracy of a plain conv stack at roughly a third of the multiply-
accumulates, which is the trade every mobile audio model makes.

Per-patch normalisation (rather than a dataset-wide mean/std) is what makes the
model robust to microphone gain and room level. It also means the phone needs
no calibration step and no stored statistics -- the normalisation travels
inside the graph.
"""

import tensorflow as tf
from tensorflow.keras import layers

from config import PATCH_FRAMES, N_MELS, PATCH_SAMPLES
from audio import LogMelLayer


class PatchNorm(layers.Layer):
    """Zero-mean unit-variance per patch, computed in-graph."""

    def call(self, x):
        mean = tf.reduce_mean(x, axis=[1, 2], keepdims=True)
        var = tf.math.reduce_variance(x, axis=[1, 2], keepdims=True)
        return (x - mean) * tf.math.rsqrt(var + 1e-5)

    def compute_output_shape(self, s):
        return s


def _sep_block(x, filters: int, stride: int, name: str):
    x = layers.SeparableConv2D(filters, 3, strides=stride, padding="same",
                               use_bias=False, name=f"{name}_sep")(x)
    x = layers.BatchNormalization(name=f"{name}_bn")(x)
    return layers.ReLU(6.0, name=f"{name}_relu")(x)


def build_cnn(n_classes: int, width: tuple[int, ...], dropout: float,
              name: str) -> tf.keras.Model:
    inp = layers.Input(shape=(PATCH_FRAMES, N_MELS), name="logmel")
    x = PatchNorm(name="patch_norm")(inp)
    x = layers.Reshape((PATCH_FRAMES, N_MELS, 1), name="expand")(x)

    x = layers.Conv2D(width[0], 3, strides=2, padding="same", use_bias=False,
                      name="stem_conv")(x)
    x = layers.BatchNormalization(name="stem_bn")(x)
    x = layers.ReLU(6.0, name="stem_relu")(x)

    for i, f in enumerate(width[1:], start=1):
        x = _sep_block(x, f, stride=2 if i < len(width) - 1 else 1, name=f"block{i}")

    x = layers.GlobalAveragePooling2D(name="gap")(x)
    x = layers.Dropout(dropout, name="drop")(x)
    out = layers.Dense(n_classes, activation="softmax", dtype="float32",
                       name="probs")(x)
    return tf.keras.Model(inp, out, name=name)


def build_detector() -> tf.keras.Model:
    """Binary cry / not-cry. Small, because it never stops running."""
    return build_cnn(2, width=(24, 48, 96, 128), dropout=0.30, name="nila_detector")


def build_classifier(n_classes: int) -> tf.keras.Model:
    """Reason head. Wider, because it only runs once a cry episode is confirmed.

    Heavier dropout: 457 clips over 221 subjects is a small, deeply imbalanced
    problem and this head will overfit given the slightest opportunity.
    """
    return build_cnn(n_classes, width=(32, 64, 128, 192), dropout=0.50,
                     name="nila_classifier")


def wrap_with_frontend(model: tf.keras.Model, name: str) -> tf.keras.Model:
    """Raw PCM in, probabilities out -- so the phone hands us AudioRecord output.

    Exporting this variant removes any chance of the Kotlin frontend drifting
    from the training frontend, which is the classic way a mobile audio model
    silently degrades after deployment.
    """
    wave = layers.Input(shape=(PATCH_SAMPLES,), name="waveform")
    mel = LogMelLayer(name="logmel")(wave)
    return tf.keras.Model(wave, model(mel), name=name)
