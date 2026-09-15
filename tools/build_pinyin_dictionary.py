#!/usr/bin/env python3
"""Rebuild the offline AOSP dictionary from pinned, attributed Rime Ice data."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "59fcb4a6bfa71e6ba4fc83af07ee55f0c5b76081"
BASE_URL = f"https://raw.githubusercontent.com/iDvel/rime-ice/{REVISION}"
FILES = ("8105.dict.yaml", "base.dict.yaml")
SOURCE_SHA256 = {
    "8105.dict.yaml": "1f9a42b91dea6982baee2551981780271aeffd78876662b9c9f324e56b37b120",
    "base.dict.yaml": "9d759771544adf196a0adf9092435ba4df4c0c50d7886963b6b54bf59d14775a",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path,
                        default=ROOT / "build/pinyin-dictionary/sources")
    parser.add_argument("--download", action="store_true",
                        help="download the pinned source files before conversion")
    parser.add_argument("--jobs", type=int, default=2,
                        help="parallel compiler processes (default: 2)")
    args = parser.parse_args()
    work = ROOT / "build/pinyin-dictionary"
    work.mkdir(parents=True, exist_ok=True)
    args.source_dir.mkdir(parents=True, exist_ok=True)
    if args.download:
        for name in FILES:
            with urllib.request.urlopen(f"{BASE_URL}/cn_dicts/{name}") as response:
                (args.source_dir / name).write_bytes(response.read())

    entries = {}
    filtered = Counter()
    sources = []
    for name in FILES:
        source = args.source_dir / name
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != SOURCE_SHA256[name]:
            raise ValueError(f"Source checksum does not match pinned Rime Ice revision: {name}")
        sources.append({"path": f"cn_dicts/{name}",
                        "sha256": hashlib.sha256(data).hexdigest()})
        in_entries = False
        for line in data.decode("utf-8").splitlines():
            if line.strip() == "...":
                in_entries = True
                continue
            if not in_entries or not line.strip() or line.lstrip().startswith("#"):
                continue
            fields = line.split("#", 1)[0].strip().split("\t")
            if len(fields) != 3:
                raise ValueError(f"Malformed dictionary entry in {name}: {line}")
            text, code, weight = fields
            spelling = code.lower().split()
            frequency = float(weight)
            if not 1 <= len(text) <= 8:
                filtered["longer_than_eight_characters"] += 1
                continue
            if any(not (0x3400 <= ord(c) <= 0x9FFF or 0xF900 <= ord(c) <= 0xFAFF)
                   for c in text):
                filtered["non_bmp_or_non_han_character"] += 1
                continue
            if len(spelling) != len(text) or any(
                    re.fullmatch(r"[a-z]{1,6}", s) is None for s in spelling):
                filtered["unsupported_spelling"] += 1
                continue
            key = (text, " ".join(spelling))
            if key in entries:
                filtered["duplicate_word_and_pronunciation"] += 1
            entries[key] = max(entries.get(key, 0), max(1, frequency))
    if not 100_000 < len(entries) < 1_000_000:
        raise ValueError(f"Unexpected vocabulary size: {len(entries)}")

    # AOSP input: hanzi, frequency, GBK marker, one spelling per character.
    raw = work / "rime-ice.utf16"
    with raw.open("w", encoding="utf-16", newline="\n") as out:
        for (text, spelling), frequency in sorted(entries.items()):
            out.write(f"{text} {frequency:g} 0 {spelling.upper()}\n")

    # Compile independent translation units in parallel; cache unchanged objects.
    # Ordinary Android builds use the checked-in .dat and need neither this
    # compiler nor network access to dictionary sources.
    from concurrent.futures import ThreadPoolExecutor
    native = ROOT / "vendor/pinyin"
    cpp_files = sorted((native / "share").glob("*.cpp"))
    cpp_files.append(native / "dictionary_builder.cpp")
    headers_mtime = max(p.stat().st_mtime for p in (native / "include").glob("*.h"))

    def compile_object(source):
        obj = work / (source.stem + ".o")
        if not obj.exists() or obj.stat().st_mtime < max(source.stat().st_mtime, headers_mtime):
            subprocess.run(["g++", "-std=c++11", "-O2", "-D___BUILD_MODEL___",
                            "-c", str(source), "-o", str(obj)], check=True)
        return obj

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        objects = list(pool.map(compile_object, cpp_files))
    builder = work / "dictionary_builder"
    subprocess.run(["g++", *(str(obj) for obj in objects), "-o", str(builder)], check=True)
    output = ROOT / "assets/pinyin/dict_pinyin.dat"
    result = subprocess.run([str(builder), str(raw), str(work / "dict_pinyin.dat")],
                            text=True, stdout=subprocess.PIPE, check=True)
    print(result.stdout, end="")
    loaded = re.search(r"read succesfully, lemma num: (\d+)", result.stdout)
    if loaded is None or int(loaded.group(1)) != len(entries):
        raise ValueError("Decoder compiler discarded entries; inspect source spellings before publishing")
    data = (work / "dict_pinyin.dat").read_bytes()
    if len(data) < 1_000_000:
        raise ValueError("Dictionary output is unexpectedly small")
    output.write_bytes(data)
    report = {"repository": "https://github.com/iDvel/rime-ice",
              "revision": REVISION, "sources": sources,
              "entries": len(entries), "filtered": dict(filtered),
              "binary_bytes": len(data), "binary_sha256": hashlib.sha256(data).hexdigest()}
    (ROOT / "assets/pinyin/dictionary.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
