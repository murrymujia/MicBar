"""Write the signed mirror index for an Android release to stdout."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("version")
parser.add_argument("--key", type=Path, required=True, help="DER PKCS#8 APK signing key")
parser.add_argument("files", type=Path, nargs="+")
args = parser.parse_args()
version = re.escape(args.version)
if not re.fullmatch(r"[0-9]{1,5}(\.[0-9]{1,5}){1,2}", args.version):
    parser.error("Invalid release version")
assets = []
for path in sorted(args.files):
    if not re.fullmatch(rf"MicBar-(?:Lite|Full)-(?:{version}\.apk|[0-9.]+-to-{version}\.mupd)", path.name):
        parser.error(f"Unexpected asset: {path.name}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    assets.append(dict(name=path.name, size=path.stat().st_size, state="uploaded", digest="sha256:" + digest.hexdigest()))
names = {a["name"] for a in assets}
if len(names) != len(assets) or not all(f"MicBar-{edition}-{args.version}.apk" in names for edition in ("Lite","Full")):
    parser.error("Supply both signed APKs and distinct same-edition patches")
data = json.dumps(dict(tag_name="v" + args.version, assets=assets), separators=(",", ":")).encode()
sig = subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-sign", str(args.key)],
                     input=data, stdout=subprocess.PIPE, check=True).stdout
print(json.dumps(dict(manifest=base64.b64encode(data).decode(), signature=base64.b64encode(sig).decode())))
