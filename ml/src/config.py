"""
Shared constants for the Nila audio pipeline.

Everything in this file is mirrored byte-for-byte in the Android
implementation (core-audio/LogMelFrontend.kt). If you change a number here,
change it there, retrain, and re-export -- otherwise the model sees a
different feature space at inference time than it saw in training and the
accuracy quietly collapses.

The frontend parameters are deliberately YAMNet-compatible: 16 kHz mono,
64 mel bands, 25 ms window, 10 ms hop, 0.96 s patch. That gives us a
well-documented spec to reimplement against, and keeps the door open to
swapping in YAMNet embeddings later without touching the capture layer.
"""

from pathlib import Path

# ---------------------------------------------------------------- paths
ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
MODELS = ROOT / "models"
REPORTS = ROOT / "reports"

DONATEACRY = DATA / "donateacry-corpus" / "donateacry_corpus_cleaned_and_updated_data"
ESC50 = DATA / "ESC-50-master"

for _d in (MODELS, REPORTS):
    _d.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- audio
SAMPLE_RATE = 16_000
WIN_LENGTH = 400          # 25 ms
HOP_LENGTH = 160          # 10 ms
N_FFT = 512               # next pow2 >= WIN_LENGTH
N_MELS = 64
FMIN = 125.0
FMAX = 7_500.0
LOG_OFFSET = 1e-3         # log(mel + offset), YAMNet convention

PATCH_FRAMES = 96         # 0.96 s of context
PATCH_SAMPLES = WIN_LENGTH + HOP_LENGTH * (PATCH_FRAMES - 1)   # 15_600
PATCH_HOP_FRAMES = 48     # 50 % overlap between consecutive patches

# ---------------------------------------------------------------- labels
DETECT_CLASSES = ["not_cry", "cry"]

REASON_CLASSES = ["belly_pain", "burping", "discomfort", "hungry", "tired"]
REASON_SHORT = {"bp": "belly_pain", "bu": "burping", "dc": "discomfort",
                "hu": "hungry", "ti": "tired"}

# ---------------------------------------------------------------- training
SEED = 1337
DETECT_EPOCHS = 40
REASON_EPOCHS = 60
BATCH_SIZE = 64
VAL_FRACTION = 0.15

# Silence gate. Patches quieter than this never reach the detector on device,
# which is what stops a sleeping room from being classified 100 times a second.
SILENCE_DBFS = -55.0
