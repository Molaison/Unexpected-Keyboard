#!/usr/bin/env bash
# Run on a disposable emulator. Builds are performed separately by Gradle.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
pinyin_serial="${1:?Usage: run-pinyin-instrumentation.sh EMULATOR_SERIAL}"
pinyin_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$pinyin_root/build/validation"
pinyin_adb="$ANDROID_HOME/platform-tools/adb"
pinyin_package=com.molaison.unexpectedkeyboard.doubao
pinyin_ime="$pinyin_package/juloo.keyboard2.Keyboard2"
"$pinyin_adb" -s "$pinyin_serial" shell input keyevent KEYCODE_WAKEUP
"$pinyin_adb" -s "$pinyin_serial" shell wm dismiss-keyguard
"$pinyin_adb" -s "$pinyin_serial" install -r "$pinyin_root/build/outputs/apk/debug/Unexpected-Keyboard-debug.apk"
"$pinyin_adb" -s "$pinyin_serial" install -r "$pinyin_root/build/outputs/apk/androidTest/debug/Unexpected-Keyboard-debug-androidTest.apk"
pinyin_previous_ime="$("$pinyin_adb" -s "$pinyin_serial" shell settings get secure default_input_method | tr -d '\r')"
restore_ime() {
  pinyin_status=$?
  for screenshot in pinyin-keyboard pinyin-nine-key clipboard-light clipboard-dark nine-key-light nine-key-dark clipboard-undo failure-ux; do
    if "$pinyin_adb" -s "$pinyin_serial" exec-out run-as "$pinyin_package" cat "cache/$screenshot.png" \
        > "$pinyin_root/build/validation/$screenshot.png.tmp" 2>/dev/null; then
      mv "$pinyin_root/build/validation/$screenshot.png.tmp" "$pinyin_root/build/validation/$screenshot.png"
    else
      rm -f "$pinyin_root/build/validation/$screenshot.png.tmp"
    fi
  done
  "$pinyin_adb" -s "$pinyin_serial" logcat -d -v threadtime > "$pinyin_root/build/validation/logcat.txt" || true
  "$pinyin_adb" -s "$pinyin_serial" shell dumpsys input_method > "$pinyin_root/build/validation/input-method.txt" || true
  "$pinyin_adb" -s "$pinyin_serial" shell dumpsys window > "$pinyin_root/build/validation/window.txt" || true
  "$pinyin_adb" -s "$pinyin_serial" exec-out screencap -p > "$pinyin_root/build/validation/final-screen.png" || true
  if [[ -n "$pinyin_previous_ime" && "$pinyin_previous_ime" != null ]]; then
    "$pinyin_adb" -s "$pinyin_serial" shell ime set "$pinyin_previous_ime" || true
  fi
  return "$pinyin_status"
}
trap restore_ime EXIT
"$pinyin_adb" -s "$pinyin_serial" shell ime enable "$pinyin_ime"
# Instrumentation restarts this app process. Select the IME inside the runner,
# after that restart, rather than starting it and immediately killing it here.
"$pinyin_adb" -s "$pinyin_serial" shell am instrument -w -r \
  "$pinyin_package.test/juloo.keyboard2.PinyinSmokeTest" | tee "$pinyin_root/build/validation/instrumentation.log"
grep -q 'PINYIN_SMOKE_OK' "$pinyin_root/build/validation/instrumentation.log"
grep -q '^INSTRUMENTATION_CODE: -1' "$pinyin_root/build/validation/instrumentation.log"
