"""Publish a stable GitHub release's signed index and small patches in an artifact checkout."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("tag")
args = parser.parse_args()
if not re.fullmatch(r"v[0-9]{1,5}(\.[0-9]{1,5}){1,2}", args.tag):
    parser.error("Expected stable Android release tag")
repo = "murrymujia/MicBar"
def gh(*args):
    return subprocess.check_output(["gh", *args])

release = json.loads(gh("api", f"repos/{repo}/releases/latest"))
if release["tag_name"] != args.tag or release["prerelease"] or release["draft"]:
    raise SystemExit("Only the current public stable release may advance the channel")
assets = {a["name"]: a for a in release["assets"] if a["state"] == "uploaded"}
def fetch(name, destination):
    asset = assets[name]
    gh("release","download",args.tag,"--repo",repo,"--pattern",name,"--output",str(destination),"--clobber")
    if "sha256:" + hashlib.sha256(destination.read_bytes()).hexdigest() != asset["digest"]:
        raise ValueError("Download digest mismatch: " + name)

index = Path("latest.json")
fetch("MicBar-update-index.json", index)
payload = json.loads(base64.b64decode(json.loads(index.read_text())["manifest"], validate=True))
if payload["tag_name"] != args.tag:
    raise ValueError("Index version mismatch")
for old in Path(".").glob("MicBar-*.mupd"):
    old.unlink()
for item in payload["assets"]:
    name = item["name"]
    if item["digest"] != assets[name]["digest"] or item["size"] != assets[name]["size"]:
        raise ValueError("Published asset differs from signed index: " + name)
    if name.endswith(".mupd") and item["size"] <= 20_000_000:
        if not re.fullmatch(r"MicBar-(Lite|Full)-[0-9.]+-to-[0-9.]+\.mupd", name):
            raise ValueError("Invalid patch filename")
        fetch(name, Path(name))
