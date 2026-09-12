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


# ------------------------------------------------------- the daily routine

# One day, at the clock times it is kept. Post-midnight rows belong to the
# calendar day they fall in, so the night sleep that starts at 23:30 is closed
# by the SLEEP_END at 02:40 of the following day -- the app pairs the marks by
# time, not by which line they were authored on.
ROUTINE = [
    (2, 40, "SLEEP_END", None),
    (2, 45, "FEED", None),
    (2, 55, "DIAPER", None),
    (3, 5, "SLEEP_START", None),
    (6, 20, "SLEEP_END", None),
    (6, 30, "FEED", None),
    (6, 45, "DIAPER", None),
    (9, 15, "FEED", None),
    (9, 40, "SLEEP_START", "Morning nap"),
    (10, 50, "SLEEP_END", None),
    (11, 5, "DIAPER", None),
    (12, 0, "FEED", None),
    (13, 20, "SLEEP_START", "Midday nap"),
    (14, 40, "SLEEP_END", None),
    (14, 50, "FEED", None),
    (15, 0, "DIAPER", None),
    (16, 40, "SLEEP_START", None),
    (17, 25, "SLEEP_END", None),
    (17, 35, "FEED", None),
    (19, 0, "FEED", None),
    (19, 20, "DIAPER", None),
    (19, 45, "SLEEP_START", "Down for the night"),
    (23, 10, "SLEEP_END", None),
    (23, 15, "FEED", None),
    (23, 30, "SLEEP_START", None),
]

DAYS = 7


def care_week() -> list:
    """The routine, repeated across the window, oldest day first."""
    out = []
    for days_ago in range(DAYS - 1, -1, -1):
        for hour, minute, kind, detail in ROUTINE:
            entry = {"kind": kind, "daysAgo": days_ago,
                     "atHour": hour, "atMinute": minute}
            if detail:
                entry["detail"] = detail
            out.append(entry)
    return out


# daysAgo, hour, minute, seconds, trend, cause, cause confidence, soother,
# whether it settled, whether it woke somebody.
CRIES = [
    (0, 6, 40, 240, "SETTLING", "tired", 0.38, "Amma humming", True, False),
    (0, 13, 10, 95, "SETTLING", None, 0, None, False, False),
    (0, 18, 50, 410, "RISING", "discomfort", 0.34, "White noise", False, True),

    (1, 7, 20, 180, "SETTLING", "hungry", 0.41, "Amma humming", True, False),
    (1, 16, 5, 130, "STEADY", None, 0, None, False, False),
    (1, 18, 30, 620, "RISING", "belly_pain", 0.31, "Heartbeat", False, True),
    (1, 20, 15, 300, "SETTLING", "tired", 0.36, "Amma humming", True, False),

    # The first heavy evening.
    (2, 15, 40, 2400, "STEADY", "discomfort", 0.28, "Amma humming", True, False),
    (2, 18, 10, 3300, "RISING", "belly_pain", 0.31, "Heartbeat", False, True),
    (2, 21, 0, 2700, "RISING", "belly_pain", 0.33, "White noise", False, True),
    # Ends at 23:28 rather than spilling past midnight. A cry that crosses
    # midnight is split between the two days it occupied, which is correct
    # and which quietly took this evening back under three hours -- and the
    # two heavy days are the whole point of the week.
    (2, 22, 40, 2900, "SETTLING", "discomfort", 0.29, "Amma humming", True, False),

    # And the second.
    (3, 5, 40, 2100, "SETTLING", "discomfort", 0.30, "Amma humming", True, False),
    (3, 17, 50, 3100, "RISING", "belly_pain", 0.35, "Heartbeat", False, True),
    (3, 19, 30, 3600, "RISING", "belly_pain", 0.34, "White noise", False, True),
    (3, 22, 10, 2200, "SETTLING", "tired", 0.37, "Amma humming", True, False),

    (4, 9, 30, 220, "SETTLING", "hungry", 0.39, None, False, False),
    (4, 18, 40, 900, "STEADY", "discomfort", 0.32, "White noise", True, False),
    (4, 20, 40, 600, "SETTLING", "tired", 0.36, "Amma humming", True, False),

    (5, 7, 50, 200, "SETTLING", "hungry", 0.40, "Amma humming", True, False),
    (5, 19, 10, 1500, "RISING", "belly_pain", 0.33, "Heartbeat", False, True),

    (6, 18, 20, 1100, "SETTLING", "tired", 0.35, "Amma humming", True, False),
    (6, 21, 40, 700, "STEADY", "discomfort", 0.29, None, False, False),
]


def cry_week() -> list:
    out = []
    for (d, h, m, secs, trend, cause, conf, soother, settled, esc) in CRIES:
        entry = {
            "daysAgo": d, "atHour": h, "atMinute": m, "seconds": secs,
            "trend": trend,
            "confidence": round(min(0.98, 0.86 + (secs % 13) / 100.0), 2),
        }
        if cause:
            entry["cause"] = cause
            entry["causeConfidence"] = conf
        if soother:
            entry["soothed"] = soother
            entry["settled"] = settled
        if esc:
            entry["escalated"] = True
        out.append(entry)
    return out


def days_over_three_hours() -> int:
    """The assertion the cry list exists to satisfy. Two, and not three."""
    totals = {}
    for (d, _h, _m, secs, *_rest) in CRIES:
        totals[d] = totals.get(d, 0) + secs
    return sum(1 for seconds in totals.values() if seconds >= 3 * 3600)


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
        "note": ("Demo data for Nila. Offsets from install for the recent "
                 "past; clock times on a past day for the daily routine, "
                 "so the dashboard's hour axis and the routine agree."),

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

        # The routine, seven days of it, at the clock times a four-month-old
        # actually keeps -- and anchored to the clock rather than to install
        # time, which is the one thing that could not be expressed before.
        #
        # An offset-only seed rotates: export it and install at nine in the
        # morning and the family's night feeds land at lunchtime, which is
        # harmless for "when did she last feed" and nonsense for a dashboard
        # that plots against the hour of the day. Entries dated `daysAgo` with
        # `atHour` and `atMinute` stay where they were put; the app drops any
        # of today's that have not happened yet, so nothing is ever logged in
        # the future. See DemoSeed.momentOf.
        "care": care_week(),

        # A week of crying, clustered into the evenings the way an unsettled
        # four-month-old's is, and shaped so the colic panel has something true
        # to report: two of the seven days cross three hours, which is *below*
        # the Wessel threshold of three such days. The panel therefore shows the
        # count and stays quiet -- a demo that tripped the criterion every time
        # would be showing a diagnosis, which is what this app refuses to do.
        #
        # The two heavy evenings are several episodes each rather than one
        # continuous cry, because a baby does not cry for three and a half hours
        # without pausing, and a demo that says otherwise is describing
        # something that does not happen.
        "cries": cry_week(),

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

        # Three medicines already checked, so the history on the Scan tab opens
        # with something in it -- one of each verdict, including the AVOID that
        # comes from her own penicillin allergy rather than from the general
        # guidance. No images: these were typed, not photographed, and shipping
        # a photograph of a real strip would be inventing evidence for a demo.
        #
        # These lived only in the collection until now, which meant the offline
        # export silently produced an APK whose Scan history was empty.
        "scans": [
            {"name": "paracetamol", "verdict": "LIKELY_SAFE", "hoursAgo": 30,
             "headline": "Generally considered compatible with breastfeeding",
             "summary": "Amounts in breast milk are far below the dose given to "
                        "infants directly. Usually the first choice for pain or "
                        "fever while breastfeeding.",
             "sources": "NHS | LactMed",
             "cautions": "Confirm with your doctor or pharmacist."},
            {"name": "amoxicillin", "verdict": "AVOID", "hoursAgo": 96,
             "headline": "Do not take this - it matches an allergy you listed",
             "summary": "This appears to match an allergy you listed (penicillin "
                        "allergy). The general guidance for amoxicillin is "
                        "different, but your own history takes priority.",
             "sources": "Your health record | LactMed",
             "cautions": "Confirm with your doctor or pharmacist."},
            {"name": "cetirizine", "verdict": "CAUTION", "hoursAgo": 150,
             "headline": "Check with your doctor before taking this",
             "summary": "Non-sedating antihistamines are generally preferred "
                        "while breastfeeding, but this one is worth confirming "
                        "for your own situation.",
             "sources": "LactMed",
             "cautions": "Confirm with your doctor or pharmacist."},
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


def write_local() -> None:
    """
    Write the asset straight from `dataset()`, with no MongoDB in the way.

    The round trip through the collection is the right default -- see
    [export] -- but it cannot be the only path: editing this file on a machine
    with no connection string, or in CI, must still be able to produce the
    asset the APK ships.
    """
    data = dataset()
    data.pop("_id", None)
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text(json.dumps(data, indent=1, ensure_ascii=False))
    print(f"  -> {ASSET.relative_to(ROOT.parent)}  "
          f"{ASSET.stat().st_size / 1024:.1f} KB  (local, no MongoDB)")


def push() -> None:
    data = dataset()
    col = collection()
    col.replace_one({"_id": DOC_ID}, data, upsert=True)
    print(f"pushed {DOC_ID} to {DB_NAME}.{COLLECTION}")
    print(f"  {len(data['care'])} care entries, {len(data['cries'])} cry episodes, "
          f"{len(data['records'])} documents, "
          f"{days_over_three_hours()} days over three hours")


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
    parser.add_argument("--offline", action="store_true",
                        help="write the APK asset from this file, no MongoDB")
    args = parser.parse_args()

    if args.offline:
        write_local()
        return

    both = not (args.push or args.export)
    if args.push or both:
        push()
    if args.export or both:
        export()


if __name__ == "__main__":
    main()
