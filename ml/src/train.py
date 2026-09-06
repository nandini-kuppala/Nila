"""
Train either head from a cached patch array.

Reports macro F1 and macro AUC alongside accuracy, and always prints the
majority-class baseline next to them. On a corpus that is 84 % one class, a
bare accuracy figure carries almost no information -- 83.6 % is what you get
for answering "hungry" every time, and any number near it means the model has
learned nothing.
"""

import argparse
import json
import time

import numpy as np
import tensorflow as tf
from sklearn.metrics import (accuracy_score, f1_score, roc_auc_score,
                             confusion_matrix, classification_report,
                             balanced_accuracy_score)

from config import (MODELS, REPORTS, SEED, BATCH_SIZE, DETECT_EPOCHS,
                    REASON_EPOCHS)
from prepare import CACHE
from models import build_detector, build_classifier
from augment import spec_augment

tf.keras.utils.set_random_seed(SEED)


def load_cache(name: str):
    z = np.load(CACHE / f"{name}.npz", allow_pickle=True)
    classes = [str(c) for c in z["classes"]]
    return (z["Xtr"], z["ytr"], z["Xva"], z["yva"], z["Xte"], z["yte"], classes)


def make_dataset(X, y, n_classes, training: bool, batch: int):
    ds = tf.data.Dataset.from_tensor_slices((X, y))
    if training:
        ds = ds.shuffle(min(len(X), 20_000), seed=SEED, reshuffle_each_iteration=True)

        rng = np.random.default_rng(SEED)

        def _mask(patch, label):
            out = tf.numpy_function(
                lambda p: spec_augment(p, rng).astype(np.float32),
                [patch], tf.float32)
            out.set_shape(patch.shape)
            return out, label

        ds = ds.map(_mask, num_parallel_calls=tf.data.AUTOTUNE)

    ds = ds.map(lambda x, l: (x, tf.one_hot(l, n_classes)),
                num_parallel_calls=tf.data.AUTOTUNE)
    return ds.batch(batch).prefetch(tf.data.AUTOTUNE)


def class_weights(y, n_classes):
    """Inverse-frequency weights, damped by a square root.

    Raw inverse frequency on an 84/2 split hands one class a weight ~50x the
    other and the model spends the run oscillating. The square root keeps the
    correction meaningful without destabilising training.
    """
    counts = np.bincount(y, minlength=n_classes).astype(np.float64)
    counts = np.maximum(counts, 1.0)
    w = (counts.sum() / (n_classes * counts)) ** 0.5
    return {i: float(v) for i, v in enumerate(w)}


def evaluate(model, X, y, classes, tag: str) -> dict:
    proba = model.predict(X, batch_size=256, verbose=0)
    pred = proba.argmax(1)
    n = len(classes)

    counts = np.bincount(y, minlength=n)
    majority = float(counts.max() / max(counts.sum(), 1))

    try:
        auc = float(roc_auc_score(
            y, proba[:, 1] if n == 2 else proba,
            multi_class="ovr", average="macro",
            labels=list(range(n)) if n > 2 else None))
    except Exception:
        auc = float("nan")

    m = {
        "split": tag,
        "n": int(len(y)),
        "accuracy": float(accuracy_score(y, pred)),
        "majority_baseline": majority,
        "balanced_accuracy": float(balanced_accuracy_score(y, pred)),
        "macro_f1": float(f1_score(y, pred, average="macro", zero_division=0)),
        "weighted_f1": float(f1_score(y, pred, average="weighted", zero_division=0)),
        "macro_auc": auc,
        "confusion": confusion_matrix(y, pred, labels=list(range(n))).tolist(),
        "per_class": classification_report(y, pred, labels=list(range(n)),
                                           target_names=classes, output_dict=True,
                                           zero_division=0),
    }

    print(f"\n  [{tag}] n={m['n']}")
    print(f"    accuracy        {m['accuracy']:.4f}   (majority baseline "
          f"{majority:.4f}  ->  {m['accuracy'] - majority:+.4f})")
    print(f"    balanced acc    {m['balanced_accuracy']:.4f}")
    print(f"    macro F1        {m['macro_f1']:.4f}")
    print(f"    macro AUC       {auc:.4f}")
    print(f"    per-class recall: " + "  ".join(
        f"{c}={m['per_class'][c]['recall']:.2f}" for c in classes))
    return m


def main(task: str, leaky: bool, epochs: int | None, cache: str | None = None):
    name = cache or f"{task}{'_leaky' if leaky else ''}"
    Xtr, ytr, Xva, yva, Xte, yte, classes = load_cache(name)
    n_classes = len(classes)

    print(f"\n{'=' * 70}\nTRAIN {name}   classes={classes}")
    print(f"  train {Xtr.shape}  val {Xva.shape}  test {Xte.shape}")

    model = build_detector() if task == "detect" else build_classifier(n_classes)
    epochs = epochs or (DETECT_EPOCHS if task == "detect" else REASON_EPOCHS)

    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss=tf.keras.losses.CategoricalCrossentropy(label_smoothing=0.05),
        metrics=[tf.keras.metrics.CategoricalAccuracy(name="acc"),
                 tf.keras.metrics.AUC(name="auc", multi_label=n_classes > 2)],
    )
    print(f"  params: {model.count_params():,}")

    ckpt = MODELS / f"{name}.keras"
    t0 = time.time()
    hist = model.fit(
        make_dataset(Xtr, ytr, n_classes, True, BATCH_SIZE),
        validation_data=make_dataset(Xva, yva, n_classes, False, 256),
        epochs=epochs,
        class_weight=class_weights(ytr, n_classes),
        callbacks=[
            tf.keras.callbacks.ModelCheckpoint(ckpt, monitor="val_auc", mode="max",
                                               save_best_only=True, verbose=0),
            tf.keras.callbacks.ReduceLROnPlateau(monitor="val_auc", mode="max",
                                                 factor=0.5, patience=6, min_lr=1e-5,
                                                 verbose=1),
            tf.keras.callbacks.EarlyStopping(monitor="val_auc", mode="max",
                                             patience=14, restore_best_weights=True,
                                             verbose=1),
        ],
        verbose=2,
    )
    print(f"  trained in {time.time() - t0:.0f}s over {len(hist.history['loss'])} epochs")

    model.load_weights(ckpt)
    report = {
        "task": task, "leaky": leaky, "classes": classes,
        "params": int(model.count_params()),
        "epochs_run": len(hist.history["loss"]),
        "metrics": [evaluate(model, Xva, yva, classes, "val"),
                    evaluate(model, Xte, yte, classes, "test")],
    }
    (REPORTS / f"{name}.json").write_text(json.dumps(report, indent=2))
    print(f"\n  -> models/{name}.keras   reports/{name}.json")
    return report


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", choices=["detect", "reason"], required=True)
    ap.add_argument("--leaky", action="store_true")
    ap.add_argument("--epochs", type=int, default=None)
    ap.add_argument("--cache", default=None,
                    help="override the cache name, e.g. detect_hn")
    a = ap.parse_args()
    main(a.task, a.leaky, a.epochs, a.cache)
