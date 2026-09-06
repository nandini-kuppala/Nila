"""
Is the 461-feature Random Forest a better reason classifier than our CNN?

Two experiments, and only the second one means anything.

CONTAMINATED  Score the shipped .pkl on our held-out infants. This produces a
              very high number and it is worthless: that model was trained on
              the entire corpus, so "held out" holds nothing back from it. It is
              computed here only to show what the trap looks like from inside --
              a number that would sail through review while measuring memory.

HONEST        Retrain the same architecture -- same 461 features, same forest --
              on our subject-wise training split alone, and score it on infants
              it has genuinely never heard. This is the real question: does the
              handcrafted-feature approach generalise where our CNN did not?
              With 330 training clips it plausibly could; small-data problems
              often favour good features over learned ones.
"""

import json
import warnings

import numpy as np
import librosa
import scipy.signal as signal
from scipy.stats import skew
from sklearn.metrics import (accuracy_score, balanced_accuracy_score, f1_score,
                             roc_auc_score, classification_report, confusion_matrix)

from config import REPORTS, SEED, REASON_CLASSES
import datasets as ds

warnings.filterwarnings("ignore")

LEGACY = REPORTS.parents[2] / "Why-is-my-Baby-Crying"


# ---- their preprocessing, ported verbatim from ai.py ------------------
def pre_emphasis(sig, alpha=0.97):
    return np.append(sig[0], sig[1:] - alpha * sig[:-1])


def bandpass_filter(y, sr=16000, lowcut=142.91, highcut=6620.12, order=5):
    nyq = 0.5 * sr
    b, a = signal.butter(order, [lowcut / nyq, highcut / nyq], btype="band")
    return signal.filtfilt(b, a, y)


def extract_461(audio, sr=16000, n_mfcc=40, n_fft=1024, hop_length=160,
                win_length=400, window="hann", n_chroma=12, n_mels=13,
                n_bands=7, fmin=100, spectral_roll_percent=0.95):
    y = pre_emphasis(audio, alpha=0.97)
    y = bandpass_filter(y, sr=sr)

    zcr = librosa.feature.zero_crossing_rate(y, frame_length=win_length,
                                             hop_length=hop_length)
    rms = librosa.feature.rms(y=y, frame_length=win_length, hop_length=hop_length)
    stft = np.abs(librosa.stft(y, n_fft=n_fft, hop_length=hop_length,
                               win_length=win_length))
    S, _ = librosa.magphase(stft)

    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=n_mfcc, n_fft=n_fft,
                                hop_length=hop_length, win_length=win_length,
                                window=window)
    mfcc_delta = librosa.feature.delta(mfcc)
    mfcc_delta2 = librosa.feature.delta(mfcc, order=2)

    families = [
        (mfcc, ["mean", "std", "skew", "max", "min"]),
        (mfcc_delta, ["mean", "std"]),
        (mfcc_delta2, ["mean", "std"]),
        (zcr, ["mean", "std", "max"]),
        (rms, ["mean", "std", "max", "min"]),
        (librosa.feature.spectral_centroid(S=S, sr=sr), ["mean", "std", "max"]),
        (librosa.feature.spectral_bandwidth(S=S, sr=sr), ["mean", "std", "max"]),
        (librosa.feature.spectral_rolloff(S=S, sr=sr,
                                          roll_percent=spectral_roll_percent),
         ["mean", "std"]),
        (librosa.feature.spectral_contrast(S=S, sr=sr, n_bands=n_bands, fmin=fmin),
         ["mean", "std", "max"]),
        (librosa.feature.chroma_stft(S=S, sr=sr, n_chroma=n_chroma), ["mean", "std"]),
        (librosa.feature.melspectrogram(y=y, sr=sr, n_fft=n_fft,
                                        hop_length=hop_length,
                                        win_length=win_length, n_mels=n_mels),
         ["mean", "std"]),
        (librosa.feature.tonnetz(y=y, sr=sr), ["mean", "std"]),
    ]

    out = []
    for feat, stats in families:
        for i in range(feat.shape[0]):
            if "mean" in stats: out.append(np.mean(feat[i]))
            if "std" in stats: out.append(np.std(feat[i]))
            if "skew" in stats: out.append(skew(feat[i]))
            if "max" in stats: out.append(np.max(feat[i]))
            if "min" in stats: out.append(np.min(feat[i]))
    return np.array(out, dtype=np.float64)


def load_legacy(name="baby_cry_model2.pkl"):
    import pickle
    info = pickle.load(open(LEGACY / name, "rb"))
    return info["model"], [str(c) for c in info["class_names"]], info.get("performance")


def featurise(clips, classes):
    idx = {c: i for i, c in enumerate(classes)}
    X, y, kept = [], [], []
    for n, clip in enumerate(clips):
        wave = ds.load_wave(clip.path)
        if wave.size < 800:
            continue
        feats = extract_461(wave)
        if feats.shape[0] != 461:
            continue
        X.append(feats)
        y.append(idx[clip.label])
        kept.append(clip)
        if (n + 1) % 100 == 0:
            print(f"    featurised {n + 1}/{len(clips)}", flush=True)
    return np.nan_to_num(np.vstack(X)), np.array(y), kept


def score(model, X, y, classes, title):
    proba = model.predict_proba(X)
    pred = proba.argmax(1)
    counts = np.bincount(y, minlength=len(classes))
    baseline = float(counts.max() / max(counts.sum(), 1))
    try:
        auc = float(roc_auc_score(y, proba, multi_class="ovr", average="macro",
                                  labels=list(range(len(classes)))))
    except Exception:
        auc = float("nan")
    m = {
        "n": int(len(y)),
        "accuracy": float(accuracy_score(y, pred)),
        "majority_baseline": baseline,
        "balanced_accuracy": float(balanced_accuracy_score(y, pred)),
        "macro_f1": float(f1_score(y, pred, average="macro", zero_division=0)),
        "macro_auc": auc,
        "confusion": confusion_matrix(y, pred,
                                      labels=list(range(len(classes)))).tolist(),
        "per_class": classification_report(y, pred, labels=list(range(len(classes))),
                                           target_names=classes, output_dict=True,
                                           zero_division=0),
    }
    print(f"\n  {title}  (n={m['n']})")
    print(f"    accuracy         {m['accuracy']:.4f}  (baseline {baseline:.4f})")
    print(f"    balanced acc     {m['balanced_accuracy']:.4f}")
    print(f"    macro F1         {m['macro_f1']:.4f}")
    print(f"    macro AUC        {auc:.4f}")
    print("    per-class recall: " + "  ".join(
        f"{c}={m['per_class'][c]['recall']:.2f}" for c in classes))
    return m


def main() -> None:
    model, classes, claimed = load_legacy()
    print(f"\nLegacy model: RandomForest, {model.n_estimators} trees, "
          f"{model.n_features_in_} features, classes={classes}")
    print(f"Its own reported performance: {claimed}")

    clips = ds.donateacry_clips()
    train_clips, test_clips = ds.stratified_subject_split(clips, 0.25, SEED)
    print(f"\nEvaluating on the SAME held-out set the CNN was measured on:")
    print(f"  {len(test_clips)} clips from "
          f"{len({c.subject for c in test_clips})} unseen infants")

    Xte, yte, _ = featurise(test_clips, classes)

    print("\n" + "=" * 68)
    print("EXPERIMENT 1 -- the shipped .pkl (CONTAMINATED, shown as a warning)")
    print("=" * 68)
    contaminated = score(model, Xte, yte, classes,
                         "shipped RF on 'held-out' infants")
    print("\n    ^ This model trained on the whole corpus. These clips were in")
    print("      its training data, so this measures memory, not skill. It is")
    print("      exactly the kind of number that passes review and means nothing.")

    print("\n" + "=" * 68)
    print("EXPERIMENT 2 -- same architecture, retrained on the honest split")
    print("=" * 68)
    print(f"  featurising {len(train_clips)} training clips")
    Xtr, ytr, _ = featurise(train_clips, classes)

    from sklearn.ensemble import RandomForestClassifier
    fair = RandomForestClassifier(
        n_estimators=500, max_depth=20, class_weight="balanced_subsample",
        random_state=SEED, n_jobs=-1,
    )
    fair.fit(Xtr, ytr)
    honest = score(fair, Xte, yte, classes,
                   "461-feature RF, trained only on training infants")

    metrics = {
        "contaminated_shipped_pkl": contaminated,
        "honest_retrained_same_architecture": honest,
        "claimed_performance": {k: float(v) for k, v in (claimed or {}).items()
                                if isinstance(v, (int, float))},
    }

    try:
        cnn = json.loads((REPORTS / "reason.json").read_text())
        t = next(m for m in cnn["metrics"] if m["split"] == "test")
        metrics["our_cnn"] = t
        print("\n" + "=" * 68)
        print("HEAD TO HEAD on infants neither model ever heard")
        print("=" * 68)
        print(f"  461-feature RF   macro AUC {honest['macro_auc']:.4f}  "
              f"F1 {honest['macro_f1']:.4f}  acc {honest['accuracy']:.4f}")
        print(f"  our log-mel CNN  macro AUC {t['macro_auc']:.4f}  "
              f"F1 {t['macro_f1']:.4f}  acc {t['accuracy']:.4f}")
        winner = ("461-feature RF" if honest["macro_auc"] > t["macro_auc"]
                  else "log-mel CNN")
        metrics["winner"] = winner
        print(f"\n  better: {winner}")
        if max(honest["macro_auc"], t["macro_auc"]) < 0.60:
            print("  ...but both are at chance. The features were never the problem.")
            metrics["both_at_chance"] = True
    except Exception:
        pass

    (REPORTS / "legacy_rf.json").write_text(json.dumps(metrics, indent=2))
    print(f"\n  -> reports/legacy_rf.json")


if __name__ == "__main__":
    main()
