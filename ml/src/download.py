"""
Fetch the corpora the models are trained on.

Nothing here is redistributed with this repository. ESC-50 and UrbanSound8K are
CC BY-NC and Donate-a-Cry has its own terms; all three belong to their
publishers, so the repository ships the code that fetches them and not the
audio itself. It also keeps the checkout small: these are 1 GB together.

Idempotent. Anything already unpacked is left alone, so re-running after a
failed download resumes rather than starting again.

    python -m src.download
    python -m src.download --force     # re-fetch even if present
"""

import argparse
import io
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

from config import DATA, DONATEACRY, ESC50

SOURCES = {
    "ESC-50": {
        "url": "https://github.com/karoldvl/ESC-50/archive/master.zip",
        # The zip contains ESC-50-master/, which is exactly what config expects.
        "marker": ESC50 / "meta" / "esc50.csv",
        "note": "2000 environmental clips, 50 classes. CC BY-NC 3.0.",
    },
    "Donate-a-Cry": {
        "url": "https://github.com/gveres/donateacry-corpus/archive/master.zip",
        "marker": DONATEACRY,
        "strip": "donateacry-corpus-master",
        "into": DATA / "donateacry-corpus",
        "note": "457 labelled infant cries from 221 infants.",
    },
}


def fetch(name: str, spec: dict, force: bool) -> None:
    marker: Path = spec["marker"]
    if marker.exists() and not force:
        print(f"  {name}: already present")
        return

    print(f"  {name}: downloading ...")
    try:
        with urllib.request.urlopen(spec["url"], timeout=600) as response:
            payload = response.read()
    except Exception as exc:                                   # noqa: BLE001
        print(f"  {name}: FAILED ({exc})")
        print(f"         fetch it by hand from {spec['url']}")
        return

    DATA.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if "strip" in spec:
            # The archive's top-level directory is named after the branch;
            # unpack it and rename so config.py's paths hold.
            archive.extractall(DATA)
            unpacked = DATA / spec["strip"]
            target: Path = spec["into"]
            if target.exists():
                shutil.rmtree(target)
            unpacked.rename(target)
        else:
            archive.extractall(DATA)

    print(f"  {name}: done ({len(payload) / 1e6:.0f} MB)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true",
                        help="re-download even if already present")
    args = parser.parse_args()

    print("Fetching training corpora into", DATA)
    for name, spec in SOURCES.items():
        print(f"\n{name} -- {spec['note']}")
        fetch(name, spec, args.force)

    missing = [n for n, s in SOURCES.items() if not s["marker"].exists()]
    if missing:
        print(f"\nStill missing: {', '.join(missing)}")
        return 1

    print("\nAll corpora present. Next: python -m src.prepare")
    print(
        "\nUrbanSound8K is not fetched here -- it is behind a registration form\n"
        "at urbansounddataset.weebly.com. The detector trains without it; ESC-50\n"
        "supplies the negatives. Unpack it into data/UrbanSound8K if you have it."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
