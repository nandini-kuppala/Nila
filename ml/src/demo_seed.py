"""
The demo family: authored in MongoDB, shipped inside the APK.

Why both. The data has to be *editable* -- a demo you cannot adjust the night
before a pitch is a demo you end up rebuilding by hand -- and it has to be
*present on a phone that has never seen a network*, because the whole claim
Nila makes is that it never calls one. A judge on Appetize, or on a plane, gets
the same populated app as everyone else.

So MongoDB is where the data lives and is edited (database `Nila`, collection
`demo`), and this script is the bridge: it pushes the dataset up, reads it back,
and writes `android/app/src/main/assets/demo_seed.json`. The app reads only the
asset. It never connects to MongoDB, and there is no connection string anywhere
in the APK to find.

    python src/demo_seed.py --push     # local definition -> MongoDB
    python src/demo_seed.py --export   # MongoDB -> the APK asset
    python src/demo_seed.py            # both

### Everything is relative

Nothing here stores an absolute date. A feed is "165 minutes ago", not a
timestamp -- so an APK built today still shows a baby who fed two hours ago
when someone installs it next month. Absolute times were the first version and
they aged into a demo about a baby last fed nine days ago.
"""

import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT.parent / "android" / "app" / "src" / "main" / "assets" / "demo_seed.json"

DB_NAME = "Nila"
COLLECTION = "demo"
DOC_ID = "demo-family-v1"


# --------------------------------------------------------------- the family

def dataset() -> dict:
    """
    One plausible household, chosen so every feature has something to show.

    Meera is four months old, which is the age where the app is most useful and
    also the age that makes the guardrails matter: she is too young for solids,
    so a question about what to feed her must not return the choking-hazard
    page. Her mother is breastfeeding, has asthma and a penicillin allergy, and
    takes ferrous sulphate -- the three facts that turn the medicine check from
    a lookup into an answer about her.
    """
    return {
        "_id": DOC_ID,
        "version": 1,
        "note": "Demo data for Nila. Relative offsets, never absolute dates.",

        "baby": {
            "name": "Meera",
            "ageDays": 124,
            "language": "en",
            "healthNotes": "Exclusively breastfed. No known allergies. "
                           "Passed newborn hearing screen.",
        },

        "mother": {
            "name": "Nandini",
            "isBreastfeeding": True,
            "deliveryDaysAgo": 124,
            # These three drive the medicine verdict in the demo. Amoxicillin
            # and Combiflam both come back flagged because of them, and the app
            # shows which of her own facts caused the flag.
            "conditions": "Asthma, Iron deficiency anaemia",
            "allergies": "Penicillin",
            "currentMedicines": "Ferrous sulphate",
            "notes": "Six-week check normal. Bleeding settled by week five.",
        },

        # A day and a half of care, at the intervals a four-month-old actually
        # keeps. The most recent feed is close enough that "when did she last
        # feed" answers with something a demo audience recognises as now.
        "care": [
            {"kind": "FEED", "minutesAgo": 95},
            {"kind": "DIAPER", "minutesAgo": 140},
            {"kind": "SLEEP_START", "minutesAgo": 210},
            {"kind": "FEED", "minutesAgo": 265},
            {"kind": "DIAPER", "minutesAgo": 320},
            {"kind": "FEED", "minutesAgo": 430},
            {"kind": "SLEEP_START", "minutesAgo": 500, "detail": "Long nap"},
            {"kind": "FEED", "minutesAgo": 605},
            {"kind": "DIAPER", "minutesAgo": 640, "detail": "NFC tag"},
            {"kind": "FEED", "minutesAgo": 760},
            {"kind": "FEED", "minutesAgo": 915},
            {"kind": "DIAPER", "minutesAgo": 950},
            {"kind": "FEED", "minutesAgo": 1080},
            {"kind": "SLEEP_START", "minutesAgo": 1140},
            {"kind": "FEED", "minutesAgo": 1260},
            {"kind": "DIAPER", "minutesAgo": 1310},
            {"kind": "FEED", "minutesAgo": 1425},
            {"kind": "FEED", "minutesAgo": 1580},
            {"kind": "DIAPER", "minutesAgo": 1640},
            {"kind": "FEED", "minutesAgo": 1755},
        ],

        # A week of crying, shaped so the colic panel has something true to
        # report: two of the seven days cross three hours, which is *below* the
        # Wessel threshold of three such days. The panel therefore shows the
        # count and stays quiet -- a demo that tripped the criterion every time
        # would be showing a diagnosis, which is what this app refuses to do.
        "cries": [
            # today
            {"hoursAgo": 2.1, "seconds": 240, "trend": "SETTLING",
             "confidence": 0.94, "cause": "tired", "causeConfidence": 0.38,
             "soothed": "Amma humming", "settled": True},
            {"hoursAgo": 6.4, "seconds": 95, "trend": "SETTLING",
             "confidence": 0.88, "cause": None},
            {"hoursAgo": 9.8, "seconds": 410, "trend": "RISING",
             "confidence": 0.96, "cause": "discomfort", "causeConfidence": 0.34,
             "soothed": "White noise", "settled": False, "escalated": True},
            # yesterday
            {"hoursAgo": 26.0, "seconds": 180, "trend": "SETTLING",
             "confidence": 0.91, "cause": "hungry", "causeConfidence": 0.41,
             "soothed": "Amma humming", "settled": True},
            {"hoursAgo": 31.5, "seconds": 620, "trend": "RISING",
             "confidence": 0.97, "cause": "belly_pain", "causeConfidence": 0.31,
             "soothed": "Heartbeat", "settled": False, "escalated": True},
            {"hoursAgo": 35.2, "seconds": 130, "trend": "STEADY",
             "confidence": 0.86, "cause": None},
            {"hoursAgo": 40.0, "seconds": 300, "trend": "SETTLING",
             "confidence": 0.93, "cause": "tired", "causeConfidence": 0.36,
             "soothed": "Amma humming", "settled": True},
            # Two evenings that cross the three-hour line. Built from several
            # episodes each rather than one continuous cry, because a baby does
            # not cry for three and a half hours without pausing, and a demo
            # that says otherwise is describing something that does not happen.
            {"hoursAgo": 50.0, "seconds": 2700, "trend": "RISING",
             "confidence": 0.97, "cause": "belly_pain", "causeConfidence": 0.33,
             "soothed": "White noise", "settled": False, "escalated": True},
            {"hoursAgo": 52.5, "seconds": 3300, "trend": "RISING",
             "confidence": 0.98, "cause": "belly_pain", "causeConfidence": 0.31,
             "soothed": "Heartbeat", "settled": False, "escalated": True},
            {"hoursAgo": 55.0, "seconds": 2400, "trend": "STEADY",
             "confidence": 0.95, "cause": "discomfort", "causeConfidence": 0.28,
             "soothed": "Amma humming", "settled": True},
            {"hoursAgo": 58.0, "seconds": 2900, "trend": "SETTLING",
             "confidence": 0.94, "cause": "discomfort", "causeConfidence": 0.29,
             "soothed": "Amma humming", "settled": True},

            {"hoursAgo": 73.0, "seconds": 3100, "trend": "RISING",
             "confidence": 0.97, "cause": "belly_pain", "causeConfidence": 0.35,
             "soothed": "Heartbeat", "settled": False, "escalated": True},
            {"hoursAgo": 76.0, "seconds": 3600, "trend": "RISING",
             "confidence": 0.98, "cause": "belly_pain", "causeConfidence": 0.34,
             "soothed": "White noise", "settled": False, "escalated": True},
            {"hoursAgo": 79.0, "seconds": 2200, "trend": "SETTLING",
             "confidence": 0.93, "cause": "tired", "causeConfidence": 0.40,
             "soothed": "Amma humming", "settled": True},
            {"hoursAgo": 81.5, "seconds": 2100, "trend": "SETTLING",
             "confidence": 0.92, "cause": "tired", "causeConfidence": 0.38,
             "soothed": "Amma humming", "settled": True},

            # tapering off
            {"hoursAgo": 99.0, "seconds": 260, "trend": "SETTLING",
             "confidence": 0.90, "cause": "hungry", "causeConfidence": 0.44},
            {"hoursAgo": 121.0, "seconds": 175, "trend": "STEADY",
             "confidence": 0.87, "cause": None},
            {"hoursAgo": 147.0, "seconds": 340, "trend": "SETTLING",
             "confidence": 0.93, "cause": "tired", "causeConfidence": 0.37,
             "soothed": "Amma humming", "settled": True},
        ],

        # The shelf. Text is stored directly here rather than OCR'd from a
        # rendered image, because these exist to be *found* by the assistant and
        # by the medicine check -- the OCR path has its own tests and its own
        # demo on the Scan tab.
        "records": [
            {"subject": "BABY", "category": "VACCINATION",
             "title": "Immunisation card", "daysAgo": 32,
             "notes": "Up to date at 14 weeks.",
             "text": "NATIONAL IMMUNISATION SCHEDULE. BCG at birth: given. "
                     "OPV-0 at birth: given. Pentavalent 1 at 6 weeks: given. "
                     "Pentavalent 2 at 10 weeks: given. Pentavalent 3 at 14 "
                     "weeks: given. Next due: Measles-Rubella at 9 months."},
            {"subject": "BABY", "category": "CHECKUP",
             "title": "Four-month review", "daysAgo": 9,
             "notes": "Growing along the 50th centile.",
             "text": "Weight 6.4 kg. Length 62 cm. Head circumference 41 cm. "
                     "Feeding: exclusively breastfed, on demand. Development: "
                     "social smile present, good head control, rolling front to "
                     "back. Newborn hearing screening: passed, both ears. "
                     "No concerns raised."},
            {"subject": "BABY", "category": "OTHER",
             "title": "Discharge summary", "daysAgo": 122,
             "notes": "Term delivery, no complications.",
             "text": "Born at 39 weeks by normal vaginal delivery. Birth weight "
                     "3.1 kg. Apgar 9 and 10. Vitamin K given. Newborn "
                     "screening sent. Discharged day 2, feeding well."},
            {"subject": "MOTHER", "category": "CHECKUP",
             "title": "Six-week postnatal check", "daysAgo": 82,
             "notes": "All normal.",
             "text": "Blood pressure 118/74. Perineum healed. Lochia settled at "
                     "week five. Mood screening: EPDS score 6, below referral "
                     "threshold, no action needed. Breastfeeding established. "
                     "Contraception discussed."},
            {"subject": "MOTHER", "category": "PRESCRIPTION",
             "title": "Prescription, iron and inhaler", "daysAgo": 45,
             "notes": "Repeat for three months.",
             "text": "Ferrous sulphate 200 mg, one tablet twice daily with "
                     "food. Salbutamol inhaler, two puffs as required for "
                     "wheeze. Review in three months. Note: penicillin allergy "
                     "documented -- rash and facial swelling, age 19."},
            {"subject": "MOTHER", "category": "LAB",
             "title": "Full blood count", "daysAgo": 47,
             "notes": "Iron low, treatment started.",
             "text": "Haemoglobin 9.8 g/dL (low). Mean cell volume 74 fL (low). "
                     "Ferritin 11 ng/mL (low). White cells normal. Platelets "
                     "normal. Impression: iron deficiency anaemia. Commence oral "
                     "iron and repeat in three months."},
        ],

        # What the app has learned about which sound settles this baby. Her own
        # voice leads, which is the finding the feature exists to surface.
        "soothers": [
            {"id": "Amma-humming", "attempts": 9, "successes": 7},
            {"id": "white_noise", "attempts": 6, "successes": 2},
            {"id": "heartbeat", "attempts": 4, "successes": 1},
            {"id": "shush", "attempts": 2, "successes": 1},
        ],
    }


# ------------------------------------------------------------------- mongo

def connection_string() -> str:
    """
    Read the URI from the environment, never from a committed file.

    .env is gitignored; .env.example carries the key with no value so that
    stays obvious to whoever clones this next.
    """
    uri = os.environ.get("NILA_MONGODB_URI", "").strip()
    if not uri:
        env = ROOT.parent / ".env"
        if env.exists():
            for line in env.read_text().splitlines():
                if line.strip().startswith("NILA_MONGODB_URI="):
                    uri = line.split("=", 1)[1].strip()
                    break
    if not uri:
        sys.exit(
            "NILA_MONGODB_URI is not set.\n"
            "Put it in .env (which git ignores) or export it, then re-run."
        )
    return uri


def collection():
    from pymongo import MongoClient
    client = MongoClient(connection_string(), serverSelectionTimeoutMS=20_000)
    client.admin.command("ping")
    return client[DB_NAME][COLLECTION]


def push() -> None:
    data = dataset()
    col = collection()
    col.replace_one({"_id": DOC_ID}, data, upsert=True)
    print(f"pushed {DOC_ID} to {DB_NAME}.{COLLECTION}")
    print(f"  {len(data['care'])} care entries, {len(data['cries'])} cry episodes, "
          f"{len(data['records'])} documents")


def export() -> None:
    """
    Read back from MongoDB and write the asset the APK ships.

    Deliberately a round trip rather than writing `dataset()` straight to disk:
    what lands in the APK is then exactly what is in the collection, including
    any edit made there since. If the two ever disagree, the collection wins.
    """
    doc = collection().find_one({"_id": DOC_ID})
    if not doc:
        sys.exit(f"{DOC_ID} is not in {DB_NAME}.{COLLECTION} -- run --push first")
    doc.pop("_id", None)
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    print(f"  -> {ASSET.relative_to(ROOT.parent)}  {ASSET.stat().st_size / 1024:.1f} KB")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--push", action="store_true",
                        help="write the local definition to MongoDB")
    parser.add_argument("--export", action="store_true",
                        help="read MongoDB and write the APK asset")
    args = parser.parse_args()

    both = not (args.push or args.export)
    if args.push or both:
        push()
    if args.export or both:
        export()


if __name__ == "__main__":
    main()
