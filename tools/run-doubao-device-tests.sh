#!/usr/bin/env bash
# Real IME touches + AudioRecord, with synthetic sentence replies and a silent mic.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
voice_serial="${1:?Usage: run-doubao-device-tests.sh EMULATOR_SERIAL [all|cancel]}"
voice_root="$(cd "$(dirname "$0")/.." && pwd)"
voice_adb="$ANDROID_HOME/platform-tools/adb"
voice_package=com.molaison.unexpectedkeyboard.doubao
voice_case="${2:-all}"
case "$voice_case" in
  all) voice_checks=4; voice_log="$voice_root/build/validation/voice-microphone.log" ;;
  cancel) voice_checks=1; voice_log="$voice_root/build/validation/voice-microphone-cancel.log" ;;
  *) echo "Unknown microphone case: $voice_case" >&2; exit 2 ;;
esac
voice_previous_ime="$("$voice_adb" -s "$voice_serial" shell settings get secure default_input_method | tr -d '\r')"
voice_had_permission=false
if "$voice_adb" -s "$voice_serial" shell dumpsys package "$voice_package" | grep 'android.permission.RECORD_AUDIO: granted=true' > /dev/null; then
  voice_had_permission=true
fi
restore_voice_test_state() {
  if [[ "$voice_had_permission" == false ]]; then
    "$voice_adb" -s "$voice_serial" shell pm revoke "$voice_package" android.permission.RECORD_AUDIO
  fi
  if [[ -n "$voice_previous_ime" && "$voice_previous_ime" != null ]]; then
    "$voice_adb" -s "$voice_serial" shell ime set "$voice_previous_ime"
  fi
}
trap restore_voice_test_state EXIT
"$voice_adb" -s "$voice_serial" shell input keyevent KEYCODE_WAKEUP
"$voice_adb" -s "$voice_serial" shell wm dismiss-keyguard
"$voice_adb" -s "$voice_serial" shell pm grant "$voice_package" android.permission.RECORD_AUDIO
"$voice_adb" -s "$voice_serial" shell ime enable "$voice_package/juloo.keyboard2.Keyboard2"
"$voice_adb" -s "$voice_serial" shell ime set "$voice_package/juloo.keyboard2.Keyboard2"
"$voice_adb" -s "$voice_serial" shell am instrument -w -r -e voice_microphone true \
  -e voice_microphone_case "$voice_case" "$voice_package.test/juloo.keyboard2.PinyinSmokeTest" | tee "$voice_log"
grep -q "DOUBAO_MICROPHONE_OK checks=$voice_checks" "$voice_log"
grep -q '^INSTRUMENTATION_CODE: -1' "$voice_log"
