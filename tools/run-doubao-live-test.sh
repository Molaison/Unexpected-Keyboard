#!/usr/bin/env bash
# Explicit online check against the configured service, using synthetic speech.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
voice_serial="${1:?Usage: run-doubao-live-test.sh EMULATOR_SERIAL [FRESH=false] [CONTINUOUS=false]}"
voice_root="$(cd "$(dirname "$0")/.." && pwd)"
voice_adb="$ANDROID_HOME/platform-tools/adb"
voice_package=com.molaison.unexpectedkeyboard.doubao
voice_fresh="${2:-false}"
voice_continuous="${3:-false}"
mkdir -p "$voice_root/build/validation"
voice_log="$voice_root/build/validation/voice-live.log"
voice_success=DOUBAO_LIVE_OK
voice_fixtures=(test)
if [[ "$voice_continuous" == true ]]; then
  voice_log="$voice_root/build/validation/voice-continuous-live.log"
  voice_success=DOUBAO_CONTINUOUS_OK
  voice_fixtures=(first second)
fi
for voice_part in "${voice_fixtures[@]}"; do
  voice_text='This is a voice test.'
  if [[ "$voice_part" != test ]]; then voice_text="This is the $voice_part sentence."; fi
  ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i "flite=text=$voice_text:voice=slt" \
    -ar 16000 -ac 1 -f s16le "$voice_root/build/validation/voice-$voice_part.pcm"
done
"$voice_adb" -s "$voice_serial" install -r "$voice_root/build/outputs/apk/debug/Unexpected-Keyboard-debug.apk"
"$voice_adb" -s "$voice_serial" install -r "$voice_root/build/outputs/apk/androidTest/debug/Unexpected-Keyboard-debug-androidTest.apk"
for voice_part in "${voice_fixtures[@]}"; do
  "$voice_adb" -s "$voice_serial" shell run-as "$voice_package" sh -c "'cat > cache/voice-$voice_part.pcm'" \
    < "$voice_root/build/validation/voice-$voice_part.pcm"
done
"$voice_adb" -s "$voice_serial" shell am instrument -w -r -e voice_live true -e voice_fresh "$voice_fresh" \
  -e voice_continuous "$voice_continuous" "$voice_package.test/juloo.keyboard2.PinyinSmokeTest" | tee "$voice_log"
grep -q "$voice_success" "$voice_log"
grep -q '^INSTRUMENTATION_CODE: -1' "$voice_log"
