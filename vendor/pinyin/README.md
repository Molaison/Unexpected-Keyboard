# AOSP Pinyin decoder

Vendored from Android Open Source Project PinyinIME, commit
`49aebad1c1cfbbcaa9288ffed5161e79e57c3679`:

<https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/+/49aebad1c1cfbbcaa9288ffed5161e79e57c3679/jni/>

`include/` and `share/` retain their Apache-2.0 headers. `NOTICE` contains the
upstream attribution and license. `pinyin_jni.cpp`, `Android.mk`, and
`dictionary_builder.cpp` are this application's adapters; the upstream app and
its private Android JNI dependencies are not included.

Local changes are marked with `Unexpected Keyboard` comments:

- Increase the system dictionary range to 1,000,000 entries and reserve a
  separate range for learned phrases. Remove the builder's 240,000-entry,
  four-character, and low-frequency filters. Runtime lemmas remain limited to
  eight BMP Hanzi; longer sentences combine several lemmas.
- Write fixed-width dictionary fields correctly on 64-bit build hosts.
- Honor the decoder's configured length rather than the original nine-syllable
  UI limit. The Java input controller commits before exceeding 39 pinyin
  characters, including apostrophe separators.
- Replace the unused private `cutils/log.h` dependency with standard diagnostic
  output for optional performance logging.
- Make public reset clear spelling bytes and avoid flushing an unopened user
  dictionary after initialization failure.
- Honor Android's no-personalized-learning flag and report user-dictionary
  initialization failure instead of silently disabling learning.
- Use a separate candidate deduplication buffer. The larger vocabulary can
  fill the result array, so its unused tail is no longer adequate scratch space.
- Expand dictionary workspaces to 1,024 milestones and 32,768 parsing marks.
  Editability tests exhausted all 600 original marks on an ambiguous prefix;
  offsets still fit the existing uint16 representation.
- Duplicate borrowed APK descriptors before `fdopen`, preserving Java's
  ownership and avoiding Android fdsan aborts. The native test wraps the
  dictionary with a nonzero offset and checks descriptor lifetime.
- Visit all matching spelling branches instead of truncating at 200 nodes.
  Keep the best-scoring lemmas in a bounded heap in both candidate lookup and
  sentence extension. The old traversal-order cutoff omitted 中国 for `zg`
  with the larger vocabulary.
- Expose read-only syllable lists and candidate scores for Java spelling
  alternatives. Fuzzy sounds, transpositions, neighboring keys, extra/missing
  letters, and incomplete final syllables reuse the existing dictionary.
  Exact full-pinyin candidates keep precedence. Queries and fixed-prefix
  replay do not learn; accepting a candidate honors the current learning flag.
  Rules are generated for observed input fragments and cached (up to 2,048),
  avoiding a several-second eager initialization on a software emulator.

The bundled dictionary is generated from Rime Ice, not AOSP's old dictionary.
Source pins, checksums, counts and exclusions are in
`assets/pinyin/dictionary.json`. Its GNU GPL v3 license and the source
attributions are distributed under `assets/pinyin/`.

Rebuild the pinned dictionary:

```sh
python3 tools/build_pinyin_dictionary.py --download --jobs 2
```

The source cache and native builder outputs are under `build/pinyin-dictionary`.
Re-running without `--download` reuses the checked source cache and unchanged
compiler objects. Dictionary downloads are only a maintainer build step;
ordinary Android builds and Chinese typing are offline.

Run actual decoder/JNI and composition tests on Linux with a JDK and g++:

```sh
bash test/native/run-pinyin-tests.sh
```

`PinyinToleranceTest` exercises production candidate queries after every key,
including initials, 15 fuzzy/typo examples, precise-word precedence, mixed
abbreviations, partial selection, raw commits, backspace and no-learning.
Its latency output measures the host JVM, not an Android phone.

Android uses `APP_STL=c++_static`; both native libraries support 16 KiB pages.
Release obfuscation keeps the JNI class names in `proguard-rules.pro`.

Dictionary updates must pass the assertion-enabled native tests, including the
200 deterministic editability cases. Phrase-only checks did not expose the
upstream candidate scratch-space and parsing-workspace limits. Do not disable
assertions in this gate. Check both the native API and Android asset-descriptor
loading before reporting device validation.
