# Nila

An offline infant-care companion for Android. It listens for a cry, tries to
settle the baby before it wakes anyone, and answers questions about the baby and
the breastfeeding mother — without a single network request.

<p align="center">
  <img src="docs/screenshots/monitor.png" width="23%" alt="Monitor screen">
  <img src="docs/screenshots/cry-detected.png" width="23%" alt="A cry, with cause and actions">
  <img src="docs/screenshots/safety-watch.png" width="23%" alt="Safety watch">
  <img src="docs/screenshots/ask.png" width="23%" alt="Ask">
</p>

---

## Why

Every baby monitor on the market ends at *notify*. That treats a baby who would
have self-settled in forty seconds identically to one who needs a parent, and it
is why monitors wake households that did not need waking.

Nila adds two rungs before that. When a cry passes twenty seconds it plays a
sound — the caregiver's own recorded voice, if there is one. Thirty-five seconds
later it re-reads the loudness envelope and works out **whether the sound
actually helped**. Only then does it wake somebody, and the alert says what it
tried first.

That verification step is the product. The system does not claim the sound
worked; it measures whether it did, remembers the answer per sound, and lets
that change what it plays next time.

---

## The finding this project is built around

Cry **detection** works. Cry **cause** does not — and being straight about the
difference is the point.

| | Macro AUC, held-out infants |
|---|---|
| Cry detection | **0.992** |
| Cry cause, subject-wise | **0.444** |
| Cry cause, clips split at random | 0.953 |

Almost every published infant-cry classifier reports 85–95% accuracy on the
Donate-a-Cry corpus by splitting **clips** at random. One infant contributes
several clips, so the same baby lands in both train and test, and the model
learns to recognise the *infant* rather than the reason. Split by infant and the
task collapses to chance. Leakage alone is worth **+0.51 AUC** on the identical
model and the identical data.

Two unrelated model families — a CNN trained here and a 461-feature RandomForest
from a separate project — fail identically. That is not a tuning problem.

So the cause estimate still runs, and is shown as *"Probably tired — a best
guess from the sound, not a diagnosis."* Never as a fact.

---

## What it does

**Listens.** 0.992 AUC on infants never heard in training, 98% cry recall,
0.2 ms per 0.96-second window. Runs all night in a foreground service.

**Acts before it wakes you.** Log at 12 s → play a sound at 20 s → judge it at
35 s → alert. It plays your own recorded voice if you made one, learns which
sound settles *this* baby, and can turn a fan down over infrared.

**Answers questions.** Field-weighted BM25 over 46 sourced entries, for the baby
*and* the mother. Answers are retrieved text shown with their source; an
optional 0.5B model shortens them and is never allowed to supply a fact.

**Checks medicines.** PP-OCRv4 on device reads a strip — rotated, upside down or
on foil — then five staged agents check it against your own health records.

**Watches.** Face presence and motion energy from the camera. Not breathing, not
vitals, not SIDS.

**Keeps records.** Health documents for baby and mother, OCR'd at import,
searchable, and usable by the assistant.

---

## Nothing leaves the phone

- **No network requests.** Cleartext is forbidden outright in
  `network_security_config.xml`; the app has no API keys because it calls no APIs.
- **No audio is recorded.** Monitored sound is analysed in memory and discarded.
  The soothing clips you record yourself are the one exception and stay in
  app-private storage.
- **Backup is disabled** in the manifest, so health documents cannot leave by a
  cloud transport.
- **No account, no analytics.**

The only outbound requests the app can make are two optional model downloads,
each behind an explicit tap, each with a URL that is a constant in the source.

> **Nila is an awareness aid, not a medical device.** It does not monitor
> breathing or vital signs and must not be relied on to detect a medical
> emergency. It never states a medicine dose and never diagnoses.

---

## Build and run

```bash
git clone git@github.com:nandini-kuppala/Nila.git
cd Nila/android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

arm64-v8a only, `minSdk 26`, `targetSdk 35`. **Nothing needs downloading** —
every model the app requires ships inside the APK and works offline from first
launch.

### Trying it without a baby

Both are on the relevant screen, and both run the real pipeline:

- **Monitor → Play a demo cry.** A real recorded cry through the real detector
  at 6× speed. Detection, cause, a soothing sound, verification, then the alert
  — about twenty seconds.
- **Watch → Play demo footage.** A real clip of a baby crawling, through the
  real face detector and motion analysis, shown on screen as it is analysed.

### Tests

```bash
cd android
./gradlew :app:testDebugUnitTest            # 120 tests, no device
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true   # 62 tests
```

That flag matters: without it Gradle uninstalls the app afterwards, which
deletes `/sdcard/Android/data/com.nila/` and any optional model in it.

### Retraining

```bash
cd ml
python -m venv ../.venv && ../.venv/bin/pip install -r requirements.txt
../.venv/bin/python src/download.py        # ESC-50 and Donate-a-Cry
../.venv/bin/python src/prepare.py         # split by subject, THEN augment
../.venv/bin/python src/train.py detect
../.venv/bin/python src/hard_negatives.py
../.venv/bin/python src/ablation.py        # the leaky arm, for comparison
../.venv/bin/python src/legacy_pipeline.py --deployable
../.venv/bin/python src/export_tflite.py   # TFLite + ModelCard.kt
```

`export_tflite.py` generates `ModelCard.kt` from the evaluation reports, so no
accuracy claim in the app can drift away from a measurement.

The scripts are run by path rather than as `-m src.x`: they import each other
flatly, so `src/` has to be the working directory of the import, which running
the file directly gives you.

---

## Models

| Model | Job | Size | Where |
|---|---|---|---|
| `detect_mel.tflite` | Cry vs not-cry | 38 KB | In the APK |
| `reason_forest.json` | Five-way cause | 7.6 MB | In the APK |
| `ppocr_det/cls/rec` | Reading a medicine strip | 15.2 MB | In the APK |
| `blaze_face_short_range` | Face presence | 230 KB | In the APK |
| `knowledge.json` | 46 sourced entries | 43 KB | In the APK |
| Qwen2.5-0.5B | Shortening answers | 547 MB | Optional, one tap |
| FastVLM-0.5B | Describing the cot | 1.1 GB | Optional, one tap |

The two optional models change nothing about correctness. Answers are retrieved
and sourced either way.

---

## Repository layout

```
android/            The app
  app/src/main/java/com/nila/
    audio/          Log-mel frontend, detector, hysteresis, features
    monitor/        Foreground service and the escalation ladder
    assistant/      Retrieval, routing, guardrails, OCR
    vision/         Camera watch, demo footage, scene describer
    ml/             TFLite runner, RandomForest evaluator
  app/src/test/     120 JVM tests
  app/src/androidTest/  62 instrumented tests
  wear/             Wear OS module

ml/                 Training
  src/              Data prep, training, ablations, export
  reports/          Every evaluation result quoted above

docs/               Technical record and build spec
```

---

## Known limits

- **The cause classifier is at chance on unseen infants.** See above. Fixing it
  needs data collected properly, not a better architecture.
- **English only.** The language plumbing exists; the corpus does not. Translating
  46 clinical entries needs a human, not a model.
- **The vision model does not run yet.** It is fully wired, and blocked on a
  LiteRT-LM runtime that is not published. A MediaPipe backend is in place for
  whenever a suitable bundle is available.
- **Infrared and Wear OS are unverified on hardware.** Neither can be tested on
  an emulator.

---

## Third-party components and data

The code in this repository is MIT licensed (see `LICENSE`). The models and data
it is built from are not, and some of them restrict what you may do with the
result:

| Component | Licence |
|---|---|
| ESC-50 (detector negatives) | **CC BY-NC 3.0 — non-commercial** |
| UrbanSound8K | CC BY-NC 3.0 — non-commercial |
| Donate-a-Cry corpus | See the corpus repository |
| PP-OCRv4 | Apache-2.0 |
| Qwen2.5-0.5B-Instruct | Apache-2.0 |
| FastVLM-0.5B | Apple ML Research Model License |
| MediaPipe, TensorFlow Lite, ONNX Runtime | Apache-2.0 |

**The trained cry detector inherits ESC-50's non-commercial restriction**,
because ESC-50 supplies its negative examples. Retrain on permissively licensed
negatives before shipping this commercially.

Guidance in `knowledge.json` is drawn from NHS, WHO, AAP, IAP and LactMed
summaries, each entry carrying its source. It is reference material, not medical
advice.
