#!/usr/bin/env bash
# Explicit online check against the configured service, using synthetic speech.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
voice_serial="${1:?Usage: run-doubao-live-test.sh EMULATOR_SERIAL}"
voice_root="$(cd "$(dirname "$0")/.." && pwd)"
voice_adb="$ANDROID_HOME/platform-tools/adb"
voice_package=com.molaison.unexpectedkeyboard.doubao
voice_fresh="${2:-false}"
mkdir -p "$voice_root/build/validation"
ffmpeg -hide_banner -loglevel error -y -f lavfi \
  -i 'flite=text=This is a voice test.:voice=slt' \
  -ar 16000 -ac 1 -f s16le "$voice_root/build/validation/voice-test.pcm"
"$voice_adb" -s "$voice_serial" install -r "$voice_root/build/outputs/apk/debug/Unexpected-Keyboard-debug.apk"
"$voice_adb" -s "$voice_serial" install -r "$voice_root/build/outputs/apk/androidTest/debug/Unexpected-Keyboard-debug-androidTest.apk"
"$voice_adb" -s "$voice_serial" shell run-as "$voice_package" sh -c "'cat > cache/voice-test.pcm'" \
  < "$voice_root/build/validation/voice-test.pcm"
"$voice_adb" -s "$voice_serial" shell am instrument -w -r -e voice_live true -e voice_fresh "$voice_fresh" \
  "$voice_package.test/juloo.keyboard2.PinyinSmokeTest" | tee "$voice_root/build/validation/voice-live.log"
grep -q 'DOUBAO_LIVE_OK' "$voice_root/build/validation/voice-live.log"
grep -q '^INSTRUMENTATION_CODE: -1' "$voice_root/build/validation/voice-live.log"
