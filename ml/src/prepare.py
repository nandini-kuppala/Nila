"""
Turn clip manifests into cached patch arrays.

Split first, augment second. That ordering is the whole point of this file:
augmented variants of a recording must never be able to cross the train/test
boundary. The `leaky` flag reproduces the opposite ordering on purpose, so
evaluate.py can measure how many accuracy points the mistake is worth instead
of taking anyone's word for it.

Augmentation is class-balanced. Rather than a fixed number of variants per
clip, each class is topped up towards the size of the largest class, which is
what keeps `burping` (8 clips) from being statistically invisible next to
`hungry` (382).
"""

import argparse
import time
from collections import Counter
from concurrent.futures import ProcessPoolExecutor

import numpy as np

from config import (DATA, SEED, PATCH_FRAMES, N_MELS, PATCH_HOP_FRAMES,
                    REASON_CLASSES, DETECT_CLASSES, VAL_FRACTION)
import datasets as ds
from augment import augment_wave

CACHE = DATA / "cache"
CACHE.mkdir(parents=True, exist_ok=True)

MAX_PATCHES_PER_CLIP = 6          # long clips must not dominate the epoch


def _clip_patches(path, n_aug: int, seed: int, use_noise: bool):
    """Worker: load one clip, emit clean + augmented patches."""
    from audio import patches_from_wave
    rng = np.random.default_rng(seed)
    wave = ds.load_wave(path)
    if wave.size < 800:
        return np.zeros((0, PATCH_FRAMES, N_MELS), np.float32)

    out = [patches_from_wave(wave, PATCH_HOP_FRAMES)[:MAX_PATCHES_PER_CLIP]]
    for _ in range(n_aug):
        aug = augment_wave(wave, rng, use_noise=use_noise)
        out.append(patches_from_wave(aug, PATCH_HOP_FRAMES)[:MAX_PATCHES_PER_CLIP])
    return np.concatenate(out, axis=0)


def _aug_plan(clips, cap: int) -> dict[str, int]:
    """Variants per clip, per class, to pull every class towards the majority."""
    counts = Counter(c.label for c in clips)
    biggest = max(counts.values())
    plan = {}
    for label, n in counts.items():
        need = max(0, int(np.ceil(biggest / max(n, 1))) - 1)
        plan[label] = int(min(need, cap))
    return plan


def build(clips, classes, n_aug_plan, seed, use_noise, workers, tag):
    idx = {c: i for i, c in enumerate(classes)}
    jobs, labels = [], []
    for i, clip in enumerate(clips):
        n_aug = n_aug_plan.get(clip.label, 0) if n_aug_plan else 0
        jobs.append((clip.path, n_aug, seed + i, use_noise))
        labels.append(idx[clip.label])

    t0 = time.time()
    X, y = [], []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for n, patches in enumerate(pool.map(_clip_patches, *zip(*jobs), chunksize=8)):
            if patches.shape[0]:
                X.append(patches)
                y.append(np.full(patches.shape[0], labels[n], np.int64))
            if (n + 1) % 250 == 0:
                print(f"    {tag}: {n + 1}/{len(jobs)} clips "
                      f"({time.time() - t0:.0f}s)", flush=True)

    X = np.concatenate(X).astype(np.float32)
    y = np.concatenate(y).astype(np.int64)
    print(f"    {tag}: {X.shape[0]} patches in {time.time() - t0:.0f}s "
          f"{dict(Counter(classes[i] for i in y))}", flush=True)
    return X, y


def prepare_task(task: str, leaky: bool, workers: int, aug_cap: int):
    rng_seed = SEED
    if task == "detect":
        clips = ds.detection_clips()
        classes = DETECT_CLASSES
        test_frac = 0.20
    else:
        clips = ds.donateacry_clips()
        classes = REASON_CLASSES
        test_frac = 0.25          # tiny classes need a bigger slice to be measurable

    print(f"\n[{task}{' · LEAKY' if leaky else ''}] {len(clips)} clips, "
          f"{len({c.subject for c in clips})} subjects", flush=True)

    if leaky:
        # The protocol we are arguing against: augment the pool, then split it
        # at random. Variants of one recording land on both sides.
        plan = _aug_plan(clips, aug_cap)
        X, y = build(clips, classes, plan, rng_seed, True, workers, "pool")
        rng = np.random.default_rng(rng_seed)
        order = rng.permutation(X.shape[0])
        X, y = X[order], y[order]
        cut = int(X.shape[0] * (1 - test_frac))
        Xtr, ytr, Xte, yte = X[:cut], y[:cut], X[cut:], y[cut:]
    else:
        train_clips, test_clips = ds.stratified_subject_split(clips, test_frac, rng_seed)
        overlap = {c.subject for c in train_clips} & {c.subject for c in test_clips}
        assert not overlap, f"subject leak: {overlap}"
        print(ds.summarise(train_clips, "train").to_string(index=False), flush=True)
        print(ds.summarise(test_clips, "test").to_string(index=False), flush=True)

        plan = _aug_plan(train_clips, aug_cap)
        print(f"    augment plan: {plan}", flush=True)
        Xtr, ytr = build(train_clips, classes, plan, rng_seed, True, workers, "train")
        Xte, yte = build(test_clips, classes, None, rng_seed + 99, False, workers, "test")

    # Validation comes out of the training patches only -- the test set is never
    # touched for model selection.
    rng = np.random.default_rng(rng_seed + 7)
    order = rng.permutation(Xtr.shape[0])
    Xtr, ytr = Xtr[order], ytr[order]
    n_val = int(Xtr.shape[0] * VAL_FRACTION)
    Xva, yva, Xtr, ytr = Xtr[:n_val], ytr[:n_val], Xtr[n_val:], ytr[n_val:]

    name = f"{task}{'_leaky' if leaky else ''}"
    np.savez_compressed(CACHE / f"{name}.npz",
                        Xtr=Xtr, ytr=ytr, Xva=Xva, yva=yva, Xte=Xte, yte=yte,
                        classes=np.array(classes))
    print(f"  -> cache/{name}.npz  train {Xtr.shape} val {Xva.shape} test {Xte.shape}",
          flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", choices=["detect", "reason", "all"], default="all")
    ap.add_argument("--leaky", action="store_true")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--aug-cap", type=int, default=14)
    a = ap.parse_args()

    tasks = ["detect", "reason"] if a.task == "all" else [a.task]
    for t in tasks:
        prepare_task(t, a.leaky, a.workers, a.aug_cap)
