"""
Retrain the classifier from Why-is-my-Baby-Crying, using that project's own
pipeline, and make the result deployable on the phone.

The recipe is reproduced from `endsem.ipynb` rather than reinvented:

  * 461 features per clip -- 40 MFCCs with first and second deltas, spectral
    centroid / bandwidth / rolloff / contrast, chroma, mel bands, tonnetz, ZCR
    and RMS, each reduced to mean, std, skew, kurtosis, max and min
  * augmentation of the minority classes up to the majority count, two randomly
    chosen operations per copy from time-stretch, pitch-shift, noise, gain and
    circular time-shift
  * SMOTE on the training split
  * RandomForest, 500 trees, depth 20, balanced class weights

Two things are added, and neither changes the model:

  1. **A second evaluation.** The original splits clips at random, which puts
     the same infant on both sides -- one baby contributes several clips. That
     is scored here as `clip_wise`, unchanged. `subject_wise` scores the same
     trained model on infants it has never heard. Both numbers are reported.
  2. **An export.** The trained forest is written as a flat array the phone can
     evaluate directly, together with the feature list, so the model that runs
     in the app is this model rather than a reimplementation of it.
"""

import json
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import librosa
from scipy.stats import kurtosis, skew
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (accuracy_score, balanced_accuracy_score,
                             classification_report, confusion_matrix, f1_score,
                             roc_auc_score)
from sklearn.model_selection import train_test_split

from config import DATA, MODELS, REPORTS, SEED

# The deployable feature set. Same pipeline, features the phone can compute.
import reason_features

CORPUS = DATA / "donateacry-corpus" / "donateacry_corpus_cleaned_and_updated_data"
SR = 16_000

split_seed = SEED
model_seed = SEED


# --------------------------------------------------------------- features
# Verbatim from ai.py / endsem.ipynb.

def pre_emphasis(signal, alpha=0.97):
    return np.append(signal[0], signal[1:] - alpha * signal[:-1])


def bandpass_filter(y, sr=SR, lowcut=142.91, highcut=6620.12, order=5):
    from scipy.signal import butter, lfilter
    nyquist = 0.5 * sr
    low = lowcut / nyquist
    high = highcut / nyquist
    b, a = butter(order, [low, high], btype="band")
    return lfilter(b, a, y)


def extract_features(audio_data, sr=SR, n_mfcc=40, n_fft=1024, hop_length=160,
                     win_length=400, window="hann", n_bands=6, fmin=200.0,
                     n_chroma=12, n_mels=40, spectral_roll_percent=0.85):
    try:
        y = pre_emphasis(audio_data)
        y = bandpass_filter(y, sr=sr)

        zcr = librosa.feature.zero_crossing_rate(
            y, frame_length=win_length, hop_length=hop_length)
        rms = librosa.feature.rms(
            y=y, frame_length=win_length, hop_length=hop_length)

        S = np.abs(librosa.stft(y, n_fft=n_fft, hop_length=hop_length,
                                win_length=win_length, window=window))

        mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=n_mfcc, n_fft=n_fft,
                                    hop_length=hop_length, win_length=win_length,
                                    window=window)
        mfcc_delta = librosa.feature.delta(mfcc)
        mfcc_delta2 = librosa.feature.delta(mfcc, order=2)

        spectral_centroid = librosa.feature.spectral_centroid(S=S, sr=sr)
        spectral_bandwidth = librosa.feature.spectral_bandwidth(S=S, sr=sr)
        spectral_rolloff = librosa.feature.spectral_rolloff(
            S=S, sr=sr, roll_percent=spectral_roll_percent)
        spectral_contrast = librosa.feature.spectral_contrast(
            S=S, sr=sr, n_bands=n_bands, fmin=fmin)
        chroma = librosa.feature.chroma_stft(S=S, sr=sr, n_chroma=n_chroma)
        mel_spec = librosa.feature.melspectrogram(
            y=y, sr=sr, n_fft=n_fft, hop_length=hop_length, n_mels=n_mels)
        tonnetz = librosa.feature.tonnetz(y=y, sr=sr)

        feature_functions = [
            (mfcc, "mfcc", ["mean", "std", "skew", "kurt"]),
            (mfcc_delta, "mfcc_delta", ["mean", "std"]),
            (mfcc_delta2, "mfcc_delta2", ["mean", "std"]),
            (spectral_centroid, "spectral_centroid", ["mean", "std", "max", "min"]),
            (spectral_bandwidth, "spectral_bandwidth", ["mean", "std"]),
            (spectral_rolloff, "spectral_rolloff", ["mean", "std"]),
            (spectral_contrast, "spectral_contrast", ["mean", "std"]),
            (chroma, "chroma", ["mean", "std"]),
            (mel_spec, "mel_spec", ["mean", "std"]),
            (tonnetz, "tonnetz", ["mean", "std"]),
            (zcr, "zcr", ["mean", "std", "max", "min"]),
            (rms, "rms", ["mean", "std", "max", "min"]),
        ]

        features, names = [], []
        for feat, name, stats in feature_functions:
            for i in range(feat.shape[0]):
                if "mean" in stats:
                    features.append(np.mean(feat[i])); names.append(f"{name}_{i+1}_mean")
                if "std" in stats:
                    features.append(np.std(feat[i])); names.append(f"{name}_{i+1}_std")
                if "skew" in stats:
                    features.append(skew(feat[i])); names.append(f"{name}_{i+1}_skew")
                if "kurt" in stats:
                    features.append(kurtosis(feat[i])); names.append(f"{name}_{i+1}_kurt")
                if "max" in stats:
                    features.append(np.max(feat[i])); names.append(f"{name}_{i+1}_max")
                if "min" in stats:
                    features.append(np.min(feat[i])); names.append(f"{name}_{i+1}_min")
        return np.array(features, dtype=np.float64), names
    except Exception as exc:                                  # noqa: BLE001
        print(f"  feature extraction failed: {exc}")
        return None, None


# ----------------------------------------------------------- augmentation

def time_stretching(y, rate):
    return librosa.effects.time_stretch(y, rate=rate)


def pitch_shifting(y, sr, n_steps):
    return librosa.effects.pitch_shift(y, sr=sr, n_steps=n_steps)


def add_noise(y, noise_factor=0.005):
    return y + noise_factor * np.random.randn(len(y))


def random_gain(y, min_factor=0.7, max_factor=1.3):
    return y * np.random.uniform(min_factor, max_factor)


def time_shifting(y, shift_factor=0.2):
    return np.roll(y, int(len(y) * shift_factor))


def augment_audio(y, sr):
    techniques = [
        lambda: time_stretching(y, rate=np.random.uniform(0.85, 1.15)),
        lambda: pitch_shifting(y, sr, n_steps=np.random.uniform(-2, 2)),
        lambda: add_noise(y, noise_factor=np.random.uniform(0.001, 0.01)),
        lambda: random_gain(y),
        lambda: time_shifting(y, shift_factor=np.random.uniform(0.1, 0.3)),
    ]
    chosen = np.random.choice(len(techniques), size=2, replace=False)
    out = y.copy()
    for idx in chosen:
        y = out
        out = techniques[idx]()
    return out


# ------------------------------------------------------------------ data

def subject_of(path: Path) -> str:
    """
    The recording device, which is the closest thing the corpus has to an
    infant identity. Filenames are `<uuid>-<timestamp>-...`, and the uuid is
    stable across every clip uploaded from one phone.
    """
    return path.name.split("-1")[0][:36]


DEPLOYABLE = "--deployable" in __import__("sys").argv


def features_for(y, sr):
    """
    Either the original 466 librosa features, or the 408 the phone can compute.

    Selected by a flag so the two can be compared on identical data, identical
    augmentation and an identical forest -- which is the only way to know what
    the reduction actually cost.
    """
    if DEPLOYABLE:
        return reason_features.extract(y), reason_features.feature_names()
    return extract_features(y, sr=sr)


def load_dataset():
    labels_dirs = sorted(p for p in CORPUS.iterdir() if p.is_dir())
    counts = {p.name: len(list(p.glob("*.wav"))) for p in labels_dirs}
    print("Initial class distribution:")
    for k, v in counts.items():
        print(f"  {k}: {v} samples")
    majority = max(counts.values())

    features, labels, subjects, is_augmented = [], [], [], []
    names_out = []

    for label_dir in labels_dirs:
        label = label_dir.name
        files = sorted(label_dir.glob("*.wav"))
        factor = int(np.ceil(majority / len(files))) - 1
        print(f"{label} data is loading... (augmentation factor {factor})")

        for path in files:
            y, sr = librosa.load(str(path), sr=SR)
            subject = subject_of(path)

            feature, names = features_for(y, sr)
            if feature is None:
                continue
            features.append(feature); labels.append(label)
            subjects.append(subject); is_augmented.append(False)
            if not names_out:
                names_out = names

            if factor > 0:
                for _ in range(min(factor, 10)):
                    aug = augment_audio(y, sr)
                    feat, _ = features_for(aug, sr)
                    if feat is not None:
                        features.append(feat); labels.append(label)
                        subjects.append(subject); is_augmented.append(True)

    return (np.array(features), np.array(labels), np.array(subjects),
            np.array(is_augmented), names_out)


# ------------------------------------------------------------- evaluation

def evaluate(model, X, y, classes, title):
    pred = model.predict(X)
    proba = model.predict_proba(X)
    present = sorted(set(y))
    try:
        auc = roc_auc_score(y, proba, multi_class="ovr", average="macro",
                            labels=list(model.classes_))
    except ValueError:
        auc = float("nan")
    majority = max((y == c).mean() for c in present)
    out = {
        "n": int(len(y)),
        "accuracy": float(accuracy_score(y, pred)),
        "balanced_accuracy": float(balanced_accuracy_score(y, pred)),
        "macro_f1": float(f1_score(y, pred, average="macro")),
        "weighted_f1": float(f1_score(y, pred, average="weighted")),
        "macro_auc": float(auc),
        "majority_baseline": float(majority),
        "confusion": confusion_matrix(y, pred, labels=classes).tolist(),
        "per_class": classification_report(y, pred, output_dict=True,
                                           zero_division=0),
    }
    print(f"\n  [{title}] n={out['n']}")
    print(f"    accuracy        {out['accuracy']:.4f}   "
          f"(majority {out['majority_baseline']:.4f})")
    print(f"    balanced acc    {out['balanced_accuracy']:.4f}")
    print(f"    macro F1        {out['macro_f1']:.4f}")
    print(f"    macro AUC       {out['macro_auc']:.4f}")
    return out


# ----------------------------------------------------------------- export

def export_forest(model, feature_names, classes, path: Path):
    """
    Flatten the forest into arrays the phone can walk without sklearn.

    Every tree becomes four parallel arrays -- feature index, threshold, left
    child, right child -- plus the class distribution at each leaf. A node with
    feature index -1 is a leaf. This is exactly what sklearn stores internally,
    so evaluating it elsewhere reproduces `predict_proba` rather than
    approximating it.
    """
    trees = []
    for est in model.estimators_:
        t = est.tree_
        value = t.value.reshape(t.node_count, -1)
        # sklearn>=1.3 stores normalised distributions at leaves; make sure.
        totals = value.sum(axis=1, keepdims=True)
        totals[totals == 0] = 1.0
        value = value / totals
        trees.append({
            "feature": t.feature.astype(int).tolist(),
            "threshold": np.round(t.threshold, 6).tolist(),
            "left": t.children_left.astype(int).tolist(),
            "right": t.children_right.astype(int).tolist(),
            "value": np.round(value, 5).tolist(),
        })
    payload = {
        "classes": list(classes),
        "feature_names": feature_names,
        "n_features": len(feature_names),
        "trees": trees,
    }
    path.write_text(json.dumps(payload, separators=(",", ":")))
    size = path.stat().st_size / 1e6
    print(f"  -> {path.name}  {size:.1f} MB  "
          f"{len(trees)} trees, {sum(len(t['feature']) for t in trees)} nodes")


def main():
    np.random.seed(SEED)
    started = time.time()

    print("=" * 70)
    print("LEGACY PIPELINE  (Why-is-my-Baby-Crying, retrained)"
          + ("  [deployable features]" if DEPLOYABLE else ""))
    print("=" * 70)

    X, y, subjects, augmented, feature_names = load_dataset()
    print(f"\nfeature matrix {X.shape}, {len(set(subjects))} subjects, "
          f"{augmented.sum()} augmented rows")

    classes = sorted(set(y))

    # ---------------------------------------------------------- clip-wise
    # The original split, reproduced exactly.
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.20, random_state=split_seed, stratify=y)

    from imblearn.over_sampling import SMOTE
    smote = SMOTE(random_state=model_seed)
    X_train_bal, y_train_bal = smote.fit_resample(X_train, y_train)
    print(f"Training data shape after SMOTE: {X_train_bal.shape}")

    rf = RandomForestClassifier(n_estimators=500, max_depth=20,
                                class_weight="balanced",
                                random_state=model_seed, n_jobs=-1)
    rf.fit(X_train_bal, y_train_bal)
    clip_wise = evaluate(rf, X_test, y_test, classes, "clip-wise, as published")

    # ------------------------------------------------------- subject-wise
    # The same model, scored on infants it has never heard. Only original
    # clips: an augmented copy of a training clip is not a held-out example.
    unique = sorted(set(subjects))
    rng = np.random.RandomState(split_seed)
    rng.shuffle(unique)
    held_out = set(unique[: max(1, len(unique) // 5)])
    mask = np.array([s in held_out and not a
                     for s, a in zip(subjects, augmented)])
    subject_wise = None
    if mask.sum() > 20:
        subject_wise = evaluate(rf, X[mask], y[mask], classes,
                                "subject-wise, unseen infants")

    # A forest trained only on infants outside the held-out set, so the
    # subject-wise number is not contaminated by the clip-wise fit above.
    train_mask = np.array([s not in held_out for s in subjects])
    X_tr2, y_tr2 = smote.fit_resample(X[train_mask], y[train_mask])
    rf_honest = RandomForestClassifier(n_estimators=500, max_depth=20,
                                       class_weight="balanced",
                                       random_state=model_seed, n_jobs=-1)
    rf_honest.fit(X_tr2, y_tr2)
    subject_wise_clean = evaluate(rf_honest, X[mask], y[mask], classes,
                                  "subject-wise, retrained without them")

    importance = sorted(zip(feature_names, rf.feature_importances_),
                        key=lambda kv: -kv[1])
    print("\n  top features:")
    for name, w in importance[:12]:
        print(f"    {w:.4f}  {name}")

    REPORTS.mkdir(parents=True, exist_ok=True)
    MODELS.mkdir(parents=True, exist_ok=True)
    (REPORTS / f"legacy_pipeline{'_deployable' if DEPLOYABLE else ''}.json").write_text(json.dumps({
        "pipeline": "Why-is-my-Baby-Crying endsem.ipynb, reproduced",
        "n_features": X.shape[1],
        "n_rows": int(X.shape[0]),
        "n_subjects": len(set(subjects)),
        "augmented_rows": int(augmented.sum()),
        "clip_wise": clip_wise,
        "subject_wise_same_model": subject_wise,
        "subject_wise_retrained": subject_wise_clean,
        "top_features": [{"name": n, "importance": float(w)}
                         for n, w in importance[:40]],
    }, indent=2))

    suffix = "_deployable" if DEPLOYABLE else ""
    export_forest(rf, feature_names, rf.classes_,
                  MODELS / f"reason_forest{suffix}.json")

    import joblib
    joblib.dump({"model": rf, "feature_names": feature_names,
                 "classes": list(rf.classes_)},
                MODELS / f"reason_forest{suffix}.pkl")

    print(f"\ndone in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
