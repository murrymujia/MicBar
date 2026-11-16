"""Build a gzip patch by reusing identical compressed ZIP entries from a signed APK."""
import argparse
import gzip
import hashlib
import mmap
import struct
import zipfile
from pathlib import Path


def entries(path, data):
    with zipfile.ZipFile(path) as package:
        for item in sorted(package.infolist(), key=lambda e: e.header_offset):
            offset = item.header_offset
            name, extra = struct.unpack_from("<HH", data, offset + 26)
            start = offset + 30 + name + extra
            if item.compress_size >= 512:
                yield item.filename, start, item.compress_size


def digest(data, offset, count):
    return hashlib.sha256(memoryview(data)[offset:offset + count]).digest()


def make(old_path, new_path, dest):
    with old_path.open("rb") as old, new_path.open("rb") as new, mmap.mmap(old.fileno(), 0, access=mmap.ACCESS_READ) as a, mmap.mmap(new.fileno(), 0, access=mmap.ACCESS_READ) as b, gzip.open(dest, "wb", compresslevel=6) as out:
        old_entries = {name: (offset, count) for name, offset, count in entries(old_path, a)}
        cursor = 0
        out.write(b"MICDIFF1" + struct.pack(">Q", len(b)))

        def literal(end):
            if end > cursor:
                out.write(b"L" + struct.pack(">Q", end - cursor))
                for at in range(cursor, end, 1024 * 1024):
                    out.write(b[at:min(end, at + 1024 * 1024)])

        for name, offset, count in entries(new_path, b):
            source = old_entries.get(name)
            if source and source[1] == count and digest(a, source[0], count) == digest(b, offset, count):
                literal(offset)
                out.write(b"C" + struct.pack(">QQ", count, source[0]))
                cursor = offset + count
        literal(len(b))
        out.write(b"E")


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    for key in ("old", "new", "output"):
        cli.add_argument(key, type=Path)
    args = cli.parse_args()
    make(args.old, args.new, args.output)
