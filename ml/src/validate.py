"""
End-to-end validation of what actually ships.

Training metrics describe a Keras model. The phone runs a quantised TFLite
graph fed by a hand-ported frontend, and any of those three steps can silently
change the answer. This re-measures the exported artefacts on the same
subject-wise test set and fails loudly if they disagree with the Keras model.

Also runs the bundled self-test clip through the detector, so the demo's opening
move is verified here rather than discovered on stage.
"""

import json
import sys
import time

import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, f1_score, roc_auc_score

from config import MODELS, REPORTS, PATCH_FRAMES, N_MELS, PATCH_SAMPLES
from prepare import CACHE
import audio
import datasets as ds

TOLERANCE_AUC = 0.02          # quantisation may cost a little; 2 points is plenty


def run_tflite(path, X, batch_log=None):
    interp = tf.lite.Interpreter(model_path=str(path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]

    n_out = out["shape"][-1]
    proba = np.zeros((len(X), n_out), np.float32)
    times = []
    for i in range(len(X)):
        interp.set_tensor(inp["index"], X[i:i + 1].astype(inp["dtype"]))
        t0 = time.perf_counter()
        interp.invoke()
        times.append((time.perf_counter() - t0) * 1000)
        proba[i] = interp.get_tensor(out["index"])[0]
    if batch_log is not None:
        batch_log.extend(times)
    return proba


def metrics(y, proba, classes):
    pred = proba.argmax(1)
    n = len(classes)
    try:
        auc = float(roc_auc_score(
            y, proba[:, 1] if n == 2 else proba,
            multi_class="ovr", average="macro",
            labels=list(range(n)) if n > 2 else None))
    except Exception:
        auc = float("nan")
    return {
        "accuracy": float(accuracy_score(y, pred)),
        "macro_f1": float(f1_score(y, pred, average="macro", zero_division=0)),
        "macro_auc": auc,
    }


def validate_model(name: str, asset: str | None = None) -> dict:
    asset = asset or name
    z = np.load(CACHE / f"{name}.npz", allow_pickle=True)
    X, y = z["Xte"], z["yte"]
    classes = [str(c) for c in z["classes"]]

    from models import PatchNorm
    from audio import LogMelLayer
    keras = tf.keras.models.load_model(
        MODELS / f"{name}.keras",
        custom_objects={"PatchNorm": PatchNorm, "LogMelLayer": LogMelLayer},
        compile=False,
    )
    keras_metrics = metrics(y, keras.predict(X, batch_size=256, verbose=0), classes)

    times = []
    tfl_metrics = metrics(y, run_tflite(MODELS / f"{asset}_mel.tflite", X, times), classes)

    delta = tfl_metrics["macro_auc"] - keras_metrics["macro_auc"]
    ok = abs(delta) <= TOLERANCE_AUC

    print(f"\n  {name}  ({len(y)} held-out patches)")
    print(f"    keras   acc {keras_metrics['accuracy']:.4f}  "
          f"F1 {keras_metrics['macro_f1']:.4f}  AUC {keras_metrics['macro_auc']:.4f}")
    print(f"    tflite  acc {tfl_metrics['accuracy']:.4f}  "
          f"F1 {tfl_metrics['macro_f1']:.4f}  AUC {tfl_metrics['macro_auc']:.4f}")
    print(f"    quantisation cost: {delta:+.4f} AUC   "
          f"{'OK' if ok else 'REGRESSION'}")
    print(f"    desktop latency: mean {np.mean(times):.2f}ms  "
          f"p95 {np.percentile(times, 95):.2f}ms")

    return {
        "model": name, "keras": keras_metrics, "tflite": tfl_metrics,
        "auc_delta": delta, "within_tolerance": ok,
        "latency_ms_mean": float(np.mean(times)),
        "latency_ms_p95": float(np.percentile(times, 95)),
    }


def validate_selftest() -> dict:
    """The demo's opening move: does the detector fire on the bundled clip?"""
    assets = MODELS.parents[1] / "android" / "app" / "src" / "main" / "assets"
    clip = assets / "selftest_cry.wav"
    if not clip.exists():
        return {"error": "selftest_cry.wav missing"}

    wave = ds.load_wave(clip)
    patches = audio.patches_from_wave(wave, PATCH_FRAMES // 2)
    proba = run_tflite(MODELS / "detect_mel.tflite", patches)
    best = float(proba[:, 1].max())

    print(f"\n  self-test clip: {len(patches)} patches, "
          f"peak cry probability {best:.3f}  "
          f"{'FIRES' if best > 0.62 else 'DOES NOT FIRE'}")
    return {"patches": len(patches), "peak_cry_probability": best, "fires": best > 0.62}


def validate_negatives() -> dict:
    """Check the detector against sounds a nursery actually contains."""
    import pandas as pd
    from config import ESC50

    meta = pd.read_csv(ESC50 / "meta" / "esc50.csv")
    interesting = ["vacuum_cleaner", "washing_machine", "clock_alarm", "door_wood_knock",
                   "can_opening", "laughing", "coughing", "snoring", "cat",
                   "crying_baby", "siren", "car_horn"]
    rows = []
    for category in interesting:
        files = meta[meta["category"] == category]["filename"].tolist()[:8]
        peaks = []
        for f in files:
            wave = ds.load_wave(ESC50 / "audio" / f)
            patches = audio.patches_from_wave(wave, PATCH_FRAMES)
            if not len(patches):
                continue
            proba = run_tflite(MODELS / "detect_mel.tflite", patches)
            peaks.append(float(proba[:, 1].max()))
        if peaks:
            fired = sum(1 for p in peaks if p > 0.62)
            rows.append({"category": category, "clips": len(peaks),
                         "mean_peak": float(np.mean(peaks)), "fired": fired})

    print("\n  detector against real room sounds (fires at p > 0.62):")
    for r in sorted(rows, key=lambda x: -x["mean_peak"]):
        flag = "<- target" if r["category"] == "crying_baby" else ""
        print(f"    {r['category']:18s} peak {r['mean_peak']:.3f}  "
              f"fired {r['fired']}/{r['clips']}  {flag}")
    return {"per_category": rows}


def main() -> None:
    print("=" * 70)
    print("VALIDATING EXPORTED ARTEFACTS")
    print("=" * 70)

    results = [validate_model("detect_hn", asset="detect"), validate_model("reason")]
    selftest = validate_selftest()
    negatives = validate_negatives()

    report = {"models": results, "selftest": selftest, "negatives": negatives}
    (REPORTS / "validation.json").write_text(json.dumps(report, indent=2))

    failures = [r["model"] for r in results if not r["within_tolerance"]]
    print("\n" + "=" * 70)
    if failures:
        print(f"FAILED: quantisation changed {', '.join(failures)} beyond tolerance")
        sys.exit(1)
    print("All exported models match their Keras originals within tolerance.")
    print("  -> reports/validation.json")


if __name__ == "__main__":
    main()
