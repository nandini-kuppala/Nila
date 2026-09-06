"""
Manifest building for both heads.

The important thing this file does is expose a *subject* for every cry clip.
Donate-A-Cry filenames look like

    643D64AD-B711-469A-AF69-55C0D5D3E30F-1430138495-1.0-m-72-bp.wav
    ^--------------- app install UUID ---------------^  ^gender/age^ ^label^

and the same UUID recurs across recordings, so it is the closest thing the
corpus has to an infant identity. Grouping on it lets us split by subject
instead of by clip, which is the difference between a believable score and a
leaked one.
"""

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import soundfile as sf
import librosa

from config import DONATEACRY, ESC50, REASON_CLASSES, REASON_SHORT, SAMPLE_RATE

UUID_RE = re.compile(
    r"^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
)


@dataclass(frozen=True)
class Clip:
    path: Path
    label: str
    subject: str
    source: str


def load_wave(path: Path) -> np.ndarray:
    """Mono float32 at SAMPLE_RATE, peak-normalised."""
    try:
        wave, sr = sf.read(str(path), dtype="float32", always_2d=False)
    except Exception:
        wave, sr = librosa.load(str(path), sr=None, mono=False)
    if wave.ndim > 1:
        wave = wave.mean(axis=-1)
    if sr != SAMPLE_RATE:
        wave = librosa.resample(np.asarray(wave, np.float32),
                                orig_sr=sr, target_sr=SAMPLE_RATE)
    wave = np.asarray(wave, dtype=np.float32)
    peak = float(np.max(np.abs(wave))) if wave.size else 0.0
    return wave / peak if peak > 1e-6 else wave


def donateacry_clips() -> list[Clip]:
    clips: list[Clip] = []
    for label in REASON_CLASSES:
        for path in sorted((DONATEACRY / label).glob("*.wav")):
            m = UUID_RE.match(path.name)
            # no UUID -> treat the clip as its own subject rather than lumping
            # every odd filename into one giant pseudo-subject
            subject = m.group(1).lower() if m else f"solo::{path.stem}"
            clips.append(Clip(path, label, subject, "donateacry"))
    return clips


def esc50_frame() -> pd.DataFrame:
    meta = pd.read_csv(ESC50 / "meta" / "esc50.csv")
    meta["path"] = meta["filename"].map(lambda f: ESC50 / "audio" / f)
    return meta


def detection_clips() -> list[Clip]:
    """Binary cry / not-cry.

    Positives  every Donate-A-Cry clip plus ESC-50 `crying_baby`.
    Negatives  the other 49 ESC-50 classes -- household, urban, animal and
               human non-cry sound, which is exactly the confusion space a
               nursery microphone lives in.

    ESC-50 ships a 5-fold assignment; we carry it through as the subject key so
    the split respects the dataset's own grouping and never puts two crops of
    one recording on both sides.
    """
    clips = [Clip(c.path, "cry", c.subject, "donateacry") for c in donateacry_clips()]

    meta = esc50_frame()
    for row in meta.itertuples():
        is_cry = row.category == "crying_baby"
        clips.append(Clip(
            path=row.path,
            label="cry" if is_cry else "not_cry",
            subject=f"esc50::fold{row.fold}::{row.category}::{row.src_file}",
            source="esc50",
        ))
    return clips


def grouped_stratified_split(clips: list[Clip], test_frac: float, seed: int
                             ) -> tuple[list[Clip], list[Clip]]:
    """Split by subject, globally, while holding each class near `test_frac`.

    A per-class split is not safe here: some Donate-A-Cry UUIDs contribute clips
    under more than one label, so splitting each class independently puts the
    same infant on both sides of the boundary. Every subject therefore gets one
    assignment for all of its clips.

    This is greedy iterative stratification -- subjects carrying the rarest
    class are placed first, when there is still room to place them well, and
    each subject goes to the test side only when it fills more of the remaining
    per-class quota than it overshoots.
    """
    from collections import Counter, defaultdict

    labels = sorted({c.label for c in clips})
    by_subject: dict[str, list[Clip]] = defaultdict(list)
    for c in clips:
        by_subject[c.subject].append(c)

    subject_counts = {s: Counter(c.label for c in cl) for s, cl in by_subject.items()}
    total = Counter(c.label for c in clips)
    remaining = {l: test_frac * total[l] for l in labels}
    rarity = {l: 1.0 / max(total[l], 1) for l in labels}

    rng = np.random.default_rng(seed)

    def order_key(s: str):
        counts = subject_counts[s]
        return (-max(rarity[l] for l in counts), -sum(counts.values()), rng.random())

    test_subjects: set[str] = set()
    for s in sorted(by_subject, key=order_key):
        counts = subject_counts[s]
        gain = sum(min(n, max(remaining[l], 0.0)) for l, n in counts.items())
        over = sum(max(0.0, n - max(remaining[l], 0.0)) for l, n in counts.items())
        if gain > over:
            test_subjects.add(s)
            for l, n in counts.items():
                remaining[l] -= n

    # A class with nothing in the test set makes its recall undefined. Pull in
    # the smallest subject that carries it, from whichever side has spare clips.
    for l in labels:
        in_test = sum(subject_counts[s][l] for s in test_subjects)
        if in_test == 0:
            candidates = [s for s in by_subject
                          if s not in test_subjects and subject_counts[s][l] > 0]
            if candidates:
                test_subjects.add(min(candidates, key=lambda s: len(by_subject[s])))

    train = [c for c in clips if c.subject not in test_subjects]
    test = [c for c in clips if c.subject in test_subjects]
    return train, test


# Kept as the public name used by prepare.py.
stratified_subject_split = grouped_stratified_split


def summarise(clips: list[Clip], name: str) -> pd.DataFrame:
    df = pd.DataFrame([{"label": c.label, "subject": c.subject, "source": c.source}
                       for c in clips])
    out = (df.groupby("label")
             .agg(clips=("label", "size"), subjects=("subject", "nunique"))
             .reset_index())
    out.insert(0, "split", name)
    return out
