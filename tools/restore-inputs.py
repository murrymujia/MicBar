"""Restore pinned third-party binary inputs for building MicBar from a clean checkout."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://github.com/murrymujia/MicBar/releases/download/v1.17/"
parser = argparse.ArgumentParser()
parser.add_argument("--edition", choices=["lite", "full"], default="lite")
parser.add_argument("--apk", type=Path, help="Local copy of the matching 1.17 release APK")
parser.add_argument("--libs", type=Path, help="Local copy of MicBar-build-libs.zip")
args = parser.parse_args()


def checksum(file):
    digest = hashlib.sha256()
    with file.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def restore(directory, bundle, local, entries):
    missing = [e for e in entries if not (ROOT / e["path"]).is_file()
               or checksum(ROOT / e["path"]) != e["sha256"]]
    if not missing:
        return
    archive = local or (directory / bundle)
    if not local:
        print("Downloading", bundle, flush=True)
        with urlopen(BASE + bundle, timeout=90) as source, archive.open("wb") as dest:
            shutil.copyfileobj(source, dest)
    with ZipFile(archive) as package:
        for entry in missing:
            target = ROOT / entry["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            temp = directory / "extract.part"
            with package.open(entry["entry"]) as source, temp.open("wb") as dest:
                shutil.copyfileobj(source, dest)
            if checksum(temp) != entry["sha256"]:
                raise ValueError("Incorrect binary input: " + entry["path"])
            shutil.move(str(temp), str(target))


with tempfile.TemporaryDirectory(dir=ROOT) as directory:
    inputs = json.loads((ROOT / "tools/build-inputs.json").read_text())
    restore(Path(directory), "MicBar-build-libs.zip", args.libs,
            [e for e in inputs if e["bundle"] == "libs"])
    restore(Path(directory), "MicBar-" + args.edition.title() + "-1.17.apk", args.apk,
            [e for e in inputs if e["bundle"] in {"main", args.edition}])
