#!/usr/bin/env python3
"""Download the jars used by scripts/check_doubao.py into a local directory.

These jars are only needed for the lightweight `javac` feedback loop; the Gradle
build resolves its own dependencies from the versions declared in
build.gradle.kts.
"""
from __future__ import annotations

import argparse
import os
import sys
import urllib.request

BASE = "https://repo1.maven.org/maven2"

ARTIFACTS = {
    "gson.jar": "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
    "okhttp.jar": "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
    "okio.jar": "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
    "kotlin-stdlib.jar": "org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar",
    "concentus.jar": "io/github/jaredmdobson/concentus/1.0.2/concentus-1.0.2.jar",
    "junit.jar": "junit/junit/4.13.2/junit-4.13.2.jar",
    "hamcrest.jar": "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                      "..", "build-check-jars"))
    args = parser.parse_args()

    target = os.path.abspath(args.out)
    os.makedirs(target, exist_ok=True)
    for name, path in ARTIFACTS.items():
        destination = os.path.join(target, name)
        if os.path.exists(destination) and os.path.getsize(destination) > 1024:
            print("have", name)
            continue
        url = "%s/%s" % (BASE, path)
        print("fetch", url)
        urllib.request.urlretrieve(url, destination)
    print("jars in", target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
