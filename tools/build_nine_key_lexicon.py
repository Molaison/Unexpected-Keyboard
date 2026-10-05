#!/usr/bin/env python3
"""Compile an offline, memory-mappable T9 word index from pinned Rime Ice data.

Normal APK builds use the checked-in index; --download is explicit, never runtime.
The original dictionary and its user-learning file are not replaced.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "59fcb4a6bfa71e6ba4fc83af07ee55f0c5b76081"
SOURCES = {
    "8105.dict.yaml": "1f9a42b91dea6982baee2551981780271aeffd78876662b9c9f324e56b37b120",
    "base.dict.yaml": "9d759771544adf196a0adf9092435ba4df4c0c50d7886963b6b54bf59d14775a",
    "ext.dict.yaml": "70ed708720b8d34b1de3f1256859bf7526eedd54b7bad401e600060a465718d8",
}
DIGITS = str.maketrans("abcdefghijklmnopqrstuvwxyz", "22233344455566677778889999")


def compile_index(source_dir, output):
    entries = {}
    filtered = Counter()
    by_source = {}
    total_frequency = 0.0
    for name, digest in SOURCES.items():
        data = (source_dir / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch: {name}")
        in_entries = False
        retained = 0
        for line in data.decode("utf-8").splitlines():
            if line.strip() == "...":
                in_entries = True
                continue
            if not in_entries or not line.strip() or line.lstrip().startswith("#"):
                continue
            fields = line.split("#", 1)[0].strip().split("\t")
            if len(fields) != 3:
                raise ValueError(f"Malformed entry: {name}: {line}")
            text, code, weight = fields
            syllables = code.lower().split()
            freq = max(1.0, float(weight))
            if not math.isfinite(freq):
                raise ValueError("Non-finite frequency")
            total_frequency += freq
            if (not 1 <= len(text) <= 12 or any(not 0x3400 <= ord(c) <= 0x9fff for c in text)
                    or len(text) != len(syllables) or any(not re.fullmatch(r"[a-z]{1,6}", s) for s in syllables)
                    or sum(map(len, syllables)) > 39):
                filtered["unsupported_length_character_or_spelling"] += 1
                continue
            key = (text, " ".join(syllables))
            entries[key] = max(freq, entries.get(key, 0))
            retained += 1
        by_source[name] = retained
    # Same log-probability scale as AOSP; the runtime also accounts for length.
    records = []
    for (text, spelling), freq in entries.items():
        score = min(16383, round(-800 * math.log(freq / total_frequency)))
        digits = spelling.replace(" ", "").translate(DIGITS)
        records.append((digits, score / len(text) + 800 * len(text), text, spelling, score))
    records.sort()
    if not 500_000 < len(records) < 1_500_000:
        raise ValueError(f"Unexpected index size: {len(records)}")
    offset = 8 + 4 * (len(records) + 1)
    offsets = []
    payload = bytearray()
    for _, _, text, spelling, score in records:
        offsets.append(offset + len(payload))
        encoded = spelling.encode("ascii")
        payload.extend(struct.pack("<BBH", len(encoded), len(text), score))
        payload.extend(encoded)
        payload.extend(text.encode("utf-16le"))
    offsets.append(offset + len(payload))
    data = b"NKL1" + struct.pack("<I", len(records)) + struct.pack(f"<{len(offsets)}I", *offsets) + payload
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    report = dict(repository="https://github.com/iDvel/rime-ice", revision=REVISION,
                  license="GPL-3.0", sources=SOURCES, entries=len(records),
                  retained_by_source=by_source, filtered=dict(filtered),
                  binary_bytes=len(data), binary_sha256=hashlib.sha256(data).hexdigest(),
                  format="NKL1: little endian offsets; ASCII pinyin; UTF-16LE Hanzi; AOSP log score")
    output.with_suffix(".json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=ROOT / "build/lexicon-source")
    parser.add_argument("--output", type=Path, default=ROOT / "assets/pinyin/nine_key.dat")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.download:
        args.source_dir.mkdir(parents=True, exist_ok=True)
        for name in SOURCES:
            url = f"https://raw.githubusercontent.com/iDvel/rime-ice/{REVISION}/cn_dicts/{name}"
            with urllib.request.urlopen(url, timeout=60) as response:
                (args.source_dir / name).write_bytes(response.read())
    compile_index(args.source_dir, args.output)


if __name__ == "__main__":
    main()
