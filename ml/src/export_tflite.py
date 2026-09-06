"""
Export both heads to TFLite, and prove the exported graph still agrees with Keras.

Two variants are attempted per model:

  *_mel.tflite    takes a (1, 96, 64) log-mel patch. Always converts.
  *_wave.tflite   takes (1, 15600) raw PCM with the frontend baked in. Depends
                  on RFFT support in the converter; if it fails we say so and
                  ship the mel variant plus the Kotlin frontend.

The wave variant is worth trying for a specific reason: it makes it impossible
for the phone's mel implementation to drift from the training one, which is the
usual way a mobile audio model degrades after deployment without anyone noticing.

Dynamic-range quantisation is applied by default -- roughly 4x smaller, and the
weights land in int8 where the Hexagon NPU wants them, while activations stay
float so we avoid needing a representative dataset for full integer calibration.
"""

import argparse
import json

import numpy as np
import tensorflow as tf

from config import MODELS, REPORTS, PATCH_FRAMES, N_MELS, PATCH_SAMPLES
from models import wrap_with_frontend
from audio import LogMelLayer
from models import PatchNorm


CUSTOM = {"PatchNorm": PatchNorm, "LogMelLayer": LogMelLayer}


def _convert(model, quantise: bool, allow_select_ops: bool):
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantise:
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
    ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    if allow_select_ops:
        ops.append(tf.lite.OpsSet.SELECT_TF_OPS)
    conv.target_spec.supported_ops = ops
    conv._experimental_lower_tensor_list_ops = False
    return conv.convert()


def _run_tflite(blob: bytes, x: np.ndarray) -> np.ndarray:
    interp = tf.lite.Interpreter(model_content=blob)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x.astype(inp["dtype"]))
    interp.invoke()
    return interp.get_tensor(out["index"])


def export(name: str, quantise: bool, as_name: str | None = None) -> dict:
    out_name = as_name or name
    keras_path = MODELS / f"{name}.keras"
    if not keras_path.exists():
        raise FileNotFoundError(keras_path)

    model = tf.keras.models.load_model(keras_path, custom_objects=CUSTOM,
                                       compile=False)
    rng = np.random.default_rng(0)
    result = {"model": name, "quantised": quantise}

    # ---- mel-input variant (the guaranteed path) -----------------------
    mel_probe = rng.standard_normal((4, PATCH_FRAMES, N_MELS)).astype(np.float32)
    keras_out = model.predict(mel_probe, verbose=0)

    blob = _convert(model, quantise, allow_select_ops=False)
    mel_path = MODELS / f"{out_name}_mel.tflite"
    mel_path.write_bytes(blob)

    tfl_out = np.concatenate([_run_tflite(blob, mel_probe[i:i + 1]) for i in range(4)])
    mel_drift = float(np.max(np.abs(keras_out - tfl_out)))
    result["mel"] = {"bytes": len(blob), "max_drift": mel_drift,
                     "agrees": mel_drift < 2e-2}
    print(f"  {out_name}_mel.tflite       {len(blob)/1024:7.1f} KB   "
          f"drift {mel_drift:.2e}  {'OK' if mel_drift < 2e-2 else 'MISMATCH'}")

    # ---- waveform-input variant (preferred if it converts) -------------
    try:
        wrapped = wrap_with_frontend(model, f"{out_name}_wave")
        wave_probe = (rng.standard_normal((4, PATCH_SAMPLES)) * 0.1).astype(np.float32)
        keras_wave = wrapped.predict(wave_probe, verbose=0)

        blob_w = _convert(wrapped, quantise, allow_select_ops=True)
        wave_path = MODELS / f"{out_name}_wave.tflite"
        wave_path.write_bytes(blob_w)

        tfl_wave = np.concatenate([_run_tflite(blob_w, wave_probe[i:i + 1])
                                   for i in range(4)])
        drift = float(np.max(np.abs(keras_wave - tfl_wave)))
        result["wave"] = {"bytes": len(blob_w), "max_drift": drift,
                          "agrees": drift < 2e-2}
        print(f"  {out_name}_wave.tflite      {len(blob_w)/1024:7.1f} KB   "
              f"drift {drift:.2e}  {'OK' if drift < 2e-2 else 'MISMATCH'}")
    except Exception as e:
        result["wave"] = {"error": f"{type(e).__name__}: {e}"[:300]}
        print(f"  {out_name}_wave.tflite      FAILED -- shipping mel input + "
              f"Kotlin frontend\n      {type(e).__name__}: {str(e)[:160]}")

    return result


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    # detect_hn is the shipping detector: same architecture, retrained after a
    # hard-negative mining round. It exports under the plain "detect" asset name
    # so the app does not need to know which round it came from.
    ap.add_argument("--models", nargs="+", default=["detect_hn:detect", "reason"])
    ap.add_argument("--no-quantise", action="store_true")
    a = ap.parse_args()

    print(f"\nExporting TFLite (quantise={'off' if a.no_quantise else 'dynamic range'})")
    out = []
    for spec in a.models:
        src, _, dest = spec.partition(":")
        out.append(export(src, not a.no_quantise, dest or None))
    (REPORTS / "export.json").write_text(json.dumps(out, indent=2))
    print(f"\n  -> reports/export.json")
