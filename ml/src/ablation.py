"""
Two experiments that decide what the app is allowed to claim.

1. LEAKY ARM -- rerun the reason task under the protocol the original paper
   used (augment the pool, then split it at random) so the cost of the mistake
   is a measured number in our own pipeline rather than a citation.

2. GROUPING SWEEP -- the five-way reason task collapses to chance under a
   subject-wise split. Before concluding that cry audio carries no
   generalisable reason signal at all, check whether some coarser grouping
   survives. If any of them clears its baseline we have a real feature; if none
   do, the app ships evidence instead of labels and we can say why.
"""

import json
import subprocess
import sys
from collections import Counter

import numpy as np
import tensorflow as tf
from sklearn.metrics import roc_auc_score, f1_score, balanced_accuracy_score

from config import REPORTS, SEED, BATCH_SIZE
from prepare import CACHE
from models import build_classifier
from train import make_dataset, class_weights

# Each grouping maps the five original labels onto a coarser target. The
# hypothesis behind each one is stated, because a sweep without hypotheses is
# just fishing for a number.
GROUPINGS = {
    "five_way": {
        "hypothesis": "the original task",
        "map": {"belly_pain": "belly_pain", "burping": "burping",
                "discomfort": "discomfort", "hungry": "hungry", "tired": "tired"},
    },
    "hungry_vs_rest": {
        "hypothesis": "hunger is the majority class and the one parents act on first",
        "map": {"belly_pain": "other", "burping": "other", "discomfort": "other",
                "hungry": "hungry", "tired": "other"},
    },
    "pain_vs_rest": {
        "hypothesis": "pain cries are acoustically distinct -- the one claim with "
                      "independent support in the literature",
        "map": {"belly_pain": "pain", "burping": "pain", "discomfort": "other",
                "hungry": "other", "tired": "other"},
    },
    "three_way": {
        "hypothesis": "merge the gut-related classes and keep tired separate",
        "map": {"belly_pain": "gut", "burping": "gut", "discomfort": "gut",
                "hungry": "hungry", "tired": "tired"},
    },
}


def load_reason(name="reason"):
    z = np.load(CACHE / f"{name}.npz", allow_pickle=True)
    return (z["Xtr"], z["ytr"], z["Xva"], z["yva"], z["Xte"], z["yte"],
            [str(c) for c in z["classes"]])


def remap(y, classes, mapping):
    targets = sorted({mapping[c] for c in classes})
    idx = {t: i for i, t in enumerate(targets)}
    lut = np.array([idx[mapping[c]] for c in classes], dtype=np.int64)
    return lut[y], targets


def run_grouping(key: str, spec: dict) -> dict:
    Xtr, ytr, Xva, yva, Xte, yte, classes = load_reason()
    ytr2, targets = remap(ytr, classes, spec["map"])
    yva2, _ = remap(yva, classes, spec["map"])
    yte2, _ = remap(yte, classes, spec["map"])
    n = len(targets)

    tf.keras.utils.set_random_seed(SEED)
    model = build_classifier(n)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss=tf.keras.losses.CategoricalCrossentropy(label_smoothing=0.05),
        metrics=[tf.keras.metrics.AUC(name="auc", multi_label=n > 2)],
    )
    model.fit(
        make_dataset(Xtr, ytr2, n, True, BATCH_SIZE),
        validation_data=make_dataset(Xva, yva2, n, False, 256),
        epochs=45, class_weight=class_weights(ytr2, n),
        callbacks=[tf.keras.callbacks.EarlyStopping(
            monitor="val_auc", mode="max", patience=12,
            restore_best_weights=True, verbose=0)],
        verbose=0,
    )

    proba = model.predict(Xte, batch_size=256, verbose=0)
    pred = proba.argmax(1)
    counts = np.bincount(yte2, minlength=n)
    baseline = float(counts.max() / counts.sum())

    try:
        auc = float(roc_auc_score(yte2, proba[:, 1] if n == 2 else proba,
                                  multi_class="ovr", average="macro",
                                  labels=list(range(n)) if n > 2 else None))
    except Exception:
        auc = float("nan")

    out = {
        "grouping": key,
        "hypothesis": spec["hypothesis"],
        "targets": targets,
        "test_n": int(len(yte2)),
        "test_accuracy": float((pred == yte2).mean()),
        "majority_baseline": baseline,
        "balanced_accuracy": float(balanced_accuracy_score(yte2, pred)),
        "macro_f1": float(f1_score(yte2, pred, average="macro", zero_division=0)),
        "macro_auc": auc,
        "beats_chance": bool(auc > 0.60),
    }
    verdict = "ABOVE CHANCE" if out["beats_chance"] else "at chance"
    print(f"  {key:18s} targets={len(targets)}  "
          f"AUC {auc:.3f}  bal-acc {out['balanced_accuracy']:.3f}  "
          f"F1 {out['macro_f1']:.3f}   -> {verdict}", flush=True)
    return out


def main():
    print("\n" + "=" * 74)
    print("EXPERIMENT 1 -- leaky protocol (augment before split)")
    print("=" * 74, flush=True)
    subprocess.run([sys.executable, "prepare.py", "--task", "reason", "--leaky"],
                   check=True)
    subprocess.run([sys.executable, "train.py", "--task", "reason", "--leaky"],
                   check=True)

    print("\n" + "=" * 74)
    print("EXPERIMENT 2 -- does any coarser grouping survive a subject-wise split?")
    print("=" * 74, flush=True)
    results = [run_grouping(k, v) for k, v in GROUPINGS.items()]

    honest = json.loads((REPORTS / "reason.json").read_text())
    leaky = json.loads((REPORTS / "reason_leaky.json").read_text())
    detect = json.loads((REPORTS / "detect.json").read_text())

    def test_of(r):
        return next(m for m in r["metrics"] if m["split"] == "test")

    summary = {
        "detection_subject_wise": test_of(detect),
        "reason_subject_wise": test_of(honest),
        "reason_leaky": test_of(leaky),
        "groupings": results,
    }
    (REPORTS / "ablation.json").write_text(json.dumps(summary, indent=2))

    h, l, d = test_of(honest), test_of(leaky), test_of(detect)
    print("\n" + "=" * 74)
    print("HEADLINE")
    print("=" * 74)
    print(f"  cry DETECTION,  subject-wise split   macro AUC {d['macro_auc']:.3f}"
          f"   acc {d['accuracy']:.3f}")
    print(f"  cry REASON,     leaky split          macro AUC {l['macro_auc']:.3f}"
          f"   acc {l['accuracy']:.3f}")
    print(f"  cry REASON,     subject-wise split   macro AUC {h['macro_auc']:.3f}"
          f"   acc {h['accuracy']:.3f}")
    print(f"\n  leakage is worth {l['macro_auc'] - h['macro_auc']:+.3f} macro AUC "
          f"and {l['accuracy'] - h['accuracy']:+.3f} accuracy on identical data,")
    print("  identical features and an identical model. Only the split changed.")
    print(f"\n  -> reports/ablation.json")


if __name__ == "__main__":
    main()
