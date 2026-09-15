#!/usr/bin/env python3
"""Refresh the pinned portable libopus sources used by the Android encoder."""
import argparse
import hashlib
import io
from pathlib import Path
import re
import tarfile
import urllib.request

REVISION = "ddbe48383984d56acd9e1ab6a090c54ca6b735a6"
SHA256 = "18e9d9e26565e279753aafb6823a482ef85ea9fddbfdcdbff8fd095b12dd96b6"
URL = f"https://codeload.github.com/xiph/opus/tar.gz/{REVISION}"
ROOT = Path(__file__).resolve().parent.parent
SOURCE_LISTS = {
    "opus_sources.mk": ("OPUS_SOURCES", "OPUS_SOURCES_FLOAT"),
    "celt_sources.mk": ("CELT_SOURCES",),
    "silk_sources.mk": ("SILK_SOURCES", "SILK_SOURCES_FLOAT"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT / "build/validation/opus-1.5.2.tar.gz")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if not args.archive.exists():
        if not args.download:
            parser.error("Source archive is absent; use --download or --archive")
        args.archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=40) as response:
            args.archive.write_bytes(response.read())
    data = args.archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("The libopus archive checksum does not match the pinned source")
    prefix = f"opus-{REVISION}/"
    selected = {"COPYING", *SOURCE_LISTS}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for filename, names in SOURCE_LISTS.items():
            definitions = archive.extractfile(prefix + filename).read().decode().replace("\\\n", " ")
            for name in names:
                match = re.search(r"^" + name + r"\s*=\s*(.*)$", definitions, re.MULTILINE)
                if match is None:
                    raise ValueError(f"Missing source list {name}")
                selected.update(match.group(1).split())
        for member in archive.getmembers():
            relative = member.name.removeprefix(prefix)
            if member.isfile() and relative.endswith(".h") and relative.split("/")[0] in {
                "include", "celt", "silk", "src", "dnn"
            } and "/tests/" not in relative:
                selected.add(relative)
        for relative in sorted(selected):
            target = ROOT / "vendor/opus" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            contents = archive.extractfile(prefix + relative).read().rstrip(b"\n") + b"\n"
            if not target.exists() or target.read_bytes() != contents:
                target.write_bytes(contents)
        notice = ROOT / "assets/opus/NOTICE"
        notice.parent.mkdir(parents=True, exist_ok=True)
        notice.write_bytes(archive.extractfile(prefix + "COPYING").read())
    print(f"Vendored {len(selected)} libopus source/header files at {REVISION}")


if __name__ == "__main__":
    main()
