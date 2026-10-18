#!/usr/bin/env python3
"""Compile and test the Doubao voice-input package without an Android SDK.

This builds the `juloo.keyboard2.doubao` sources against generated Android API
stubs plus a handful of real dependency jars, then runs the JUnit suite. It is a
fast feedback loop for protocol work; the regular Gradle build remains the
authority for release APKs.

Usage::

    python scripts/check_doubao.py --jars /path/to/jars

The jar directory must contain: gson, okhttp, okio, kotlin-stdlib, concentus,
junit and hamcrest. `scripts/fetch_check_jars.py` downloads them.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

SOURCES = os.path.join(ROOT, "srcs", "juloo.keyboard2", "doubao")
TESTS = os.path.join(ROOT, "test", "juloo.keyboard2.doubao")
STUBS = os.path.join(HERE, "stubs")
OUT = os.path.join(ROOT, "build-check")

REQUIRED_JARS = (
    "gson.jar",
    "okhttp.jar",
    "okio.jar",
    "kotlin-stdlib.jar",
    "concentus.jar",
    "junit.jar",
    "hamcrest.jar",
)

TEST_CLASS = "juloo.keyboard2.doubao.DoubaoProtocolTest"


def find_javac() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "javac.exe" if os.name == "nt" else "javac")
        if os.path.exists(candidate):
            return candidate
    found = shutil.which("javac")
    if not found:
        raise SystemExit("javac not found; set JAVA_HOME or add it to PATH")
    return found


def find_java() -> str:
    javac = find_javac()
    return os.path.join(os.path.dirname(javac), "java.exe" if os.name == "nt" else "java")


def collect(directory: str, suffix: str = ".java"):
    paths = []
    for base, _dirs, files in os.walk(directory):
        for name in files:
            if name.endswith(suffix):
                paths.append(os.path.join(base, name))
    return sorted(paths)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jars", required=True, help="directory holding the dependency jars")
    parser.add_argument("--keep", action="store_true", help="keep build-check output")
    args = parser.parse_args()

    for jar in REQUIRED_JARS:
        if not os.path.exists(os.path.join(args.jars, jar)):
            raise SystemExit("missing jar: %s" % os.path.join(args.jars, jar))

    subprocess.check_call([sys.executable, os.path.join(HERE, "gen_android_stubs.py")])

    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    separator = ";" if os.name == "nt" else ":"
    classpath = separator.join(os.path.join(args.jars, jar) for jar in REQUIRED_JARS)

    sources = collect(SOURCES) + collect(TESTS) + collect(STUBS)
    print("compiling %d files" % len(sources))
    subprocess.check_call([
        find_javac(), "-nowarn", "-encoding", "UTF-8", "-cp", classpath,
        "-d", OUT,
    ] + sources)

    print("running %s" % TEST_CLASS)
    result = subprocess.run(
        [find_java(), "-cp", OUT + separator + classpath,
         "org.junit.runner.JUnitCore", TEST_CLASS])
    if not args.keep and os.path.exists(OUT):
        shutil.rmtree(OUT)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
