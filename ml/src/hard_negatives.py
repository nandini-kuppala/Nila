"""
Hard-negative mining for the detector.

Validation surfaced the failure this task is famous for: cat vocalisations fire
the cry detector. That is not a surprise -- a meow occupies a similar F0 range
with a similar harmonic structure and a similar rise-fall envelope -- but it is
exactly the kind of thing that ends a live demo, and more importantly it is the
kind of thing that wakes a parent at 3am for nothing.

Uniform augmentation cannot fix it, because the model already classifies 95% of
negatives easily and spends its capacity there. So: score every training
negative with the current model, find the ones it gets wrong or nearly wrong,
and rebuild the training set with those oversampled.

This is one round. Repeating it tends to overfit to the mined set, so the script
takes a round count and defaults to one.
"""

import argparse
import time
from collections import Counter
from concurrent.futures import ProcessPoolExecutor

import numpy as np
import tensorflow as tf

from config import DETECT_CLASSES, SEED, PATCH_FRAMES, N_MELS, PATCH_HOP_FRAMES, VAL_FRACTION
from prepare import CACHE, _clip_patches, _aug_plan, MAX_PATCHES_PER_CLIP
import datasets as ds
from models import PatchNorm
from audio import LogMelLayer
from config import MODELS


def score_clips(model, clips, workers: int):
    """Peak cry probability per clip, using clean (unaugmented) audio."""
    jobs = [(c.path, 0, SEED, False) for c in clips]
    peaks = np.zeros(len(clips), np.float32)

    t0 = time.time()
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for i, patches in enumerate(pool.map(_clip_patches, *zip(*jobs), chunksize=8)):
            if len(patches):
                peaks[i] = float(model.predict(patches, verbose=0)[:, 1].max())
            if (i + 1) % 400 == 0:
                print(f"    scored {i + 1}/{len(clips)} ({time.time() - t0:.0f}s)",
                      flush=True)
    return peaks


def main(workers: int, top_fraction: float, hard_aug: int) -> None:
    model = tf.keras.models.load_model(
        MODELS / "detect.keras",
        custom_objects={"PatchNorm": PatchNorm, "LogMelLayer": LogMelLayer},
        compile=False,
    )

    clips = ds.detection_clips()
    train_clips, test_clips = ds.stratified_subject_split(clips, 0.20, SEED)
    negatives = [c for c in train_clips if c.label == "not_cry"]
    positives = [c for c in train_clips if c.label == "cry"]

    print(f"\nScoring {len(negatives)} training negatives with the current detector")
    peaks = score_clips(model, negatives, workers)

    order = np.argsort(-peaks)
    n_hard = max(1, int(len(negatives) * top_fraction))
    hard_idx = set(order[:n_hard].tolist())
    hard = [negatives[i] for i in sorted(hard_idx)]

    print(f"\n  hardest {n_hard} negatives, peak cry probability "
          f"{peaks[order[0]]:.3f} down to {peaks[order[n_hard - 1]]:.3f}")

    # Which sounds are actually fooling it. Worth printing: this is a finding,
    # not just an intermediate.
    def category(clip):
        parts = clip.subject.split("::")
        return parts[2] if len(parts) > 2 else "other"

    fooling = Counter(category(c) for c in hard)
    print("  categories over-represented among hard negatives:")
    for cat, n in fooling.most_common(10):
        total = sum(1 for c in negatives if category(c) == cat)
        print(f"    {cat:20s} {n:3d} of {total:3d} clips")

    # Rebuild the training cache: positives as before, negatives with the hard
    # ones augmented and the easy ones left alone.
    print(f"\nRebuilding cache with hard negatives augmented x{hard_aug}")
    plan = _aug_plan(train_clips, 14)

    jobs, labels = [], []
    idx = {c: i for i, c in enumerate(DETECT_CLASSES)}
    for i, clip in enumerate(train_clips):
        if clip.label == "not_cry":
            n_aug = hard_aug if clip in hard_idx_set(hard) else 0
        else:
            n_aug = plan.get(clip.label, 0)
        jobs.append((clip.path, n_aug, SEED + 5000 + i, True))
        labels.append(idx[clip.label])

    X, y = [], []
    t0 = time.time()
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for n, patches in enumerate(pool.map(_clip_patches, *zip(*jobs), chunksize=8)):
            if len(patches):
                X.append(patches)
                y.append(np.full(len(patches), labels[n], np.int64))
            if (n + 1) % 500 == 0:
                print(f"    {n + 1}/{len(jobs)} clips ({time.time() - t0:.0f}s)",
                      flush=True)

    Xtr = np.concatenate(X).astype(np.float32)
    ytr = np.concatenate(y).astype(np.int64)
    print(f"    {len(Xtr)} training patches "
          f"{dict(Counter(DETECT_CLASSES[i] for i in ytr))}")

    old = np.load(CACHE / "detect.npz", allow_pickle=True)
    rng = np.random.default_rng(SEED + 11)
    shuffle = rng.permutation(len(Xtr))
    Xtr, ytr = Xtr[shuffle], ytr[shuffle]
    n_val = int(len(Xtr) * VAL_FRACTION)

    np.savez_compressed(
        CACHE / "detect_hn.npz",
        Xtr=Xtr[n_val:], ytr=ytr[n_val:],
        Xva=Xtr[:n_val], yva=ytr[:n_val],
        Xte=old["Xte"], yte=old["yte"],       # same held-out set, untouched
        classes=np.array(DETECT_CLASSES),
    )
    print(f"\n  -> cache/detect_hn.npz  (test set unchanged: {old['Xte'].shape})")


def hard_idx_set(hard):
    return set(hard)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--top-fraction", type=float, default=0.18)
    ap.add_argument("--hard-aug", type=int, default=5)
    a = ap.parse_args()
    main(a.workers, a.top_fraction, a.hard_aug)
