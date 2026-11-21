#!/usr/bin/env bash
# Explicit online check against the configured service, using synthetic speech.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
voice_serial="${1:?Usage: run-doubao-live-test.sh EMULATOR_SERIAL [FRESH=false] [MODE=false|true|quality] [PCM_FIXTURE_DIR]}"
voice_root="$(cd "$(dirname "$0")/.." && pwd)"
voice_adb="$ANDROID_HOME/platform-tools/adb"
voice_package=com.molaison.unexpectedkeyboard.doubao
voice_fresh="${2:-false}"
voice_continuous="${3:-false}"
voice_fixture_dir="${4:-}"
voice_quality=false
voice_log="$voice_root/build/validation/voice-live.log"
voice_success=DOUBAO_LIVE_OK
voice_fixtures=(test)
if [[ "$voice_continuous" == quality ]]; then
  voice_quality=true
  voice_continuous=false
  voice_log="$voice_root/build/validation/voice-quality-live.log"
  voice_success=DOUBAO_QUALITY_OK
  voice_fixtures=(mandarin mixed terms)
elif [[ "$voice_continuous" == true ]]; then
  voice_log="$voice_root/build/validation/voice-continuous-live.log"
  voice_success=DOUBAO_CONTINUOUS_OK
  voice_fixtures=(first second)
fi
if [[ "$voice_quality" == true && "$voice_fresh" == true ]]; then
  echo "DOUBAO_QUALITY_ERROR: quality uses the configured app session and cannot combine with FRESH=true" >&2
  exit 2
fi
voice_app_apk="${DOUBAO_APP_APK:-$voice_root/build/outputs/apk/debug/Unexpected-Keyboard-debug.apk}"
voice_test_apk="${DOUBAO_TEST_APK:-$voice_root/build/outputs/apk/androidTest/debug/Unexpected-Keyboard-debug-androidTest.apk}"
voice_log="${DOUBAO_TEST_LOG:-$voice_log}"
mkdir -p "$(dirname "$voice_log")"
voice_stage_files=()
cleanup_voice_staging() {
  voice_exit_status=$?
  trap - EXIT
  for voice_stage_path in "${voice_stage_files[@]}"; do
    MSYS2_ARG_CONV_EXCL=/data/local/tmp "$voice_adb" -s "$voice_serial" shell rm -f "$voice_stage_path" >/dev/null 2>&1 || true
  done
  exit "$voice_exit_status"
}
trap cleanup_voice_staging EXIT
if [[ "$voice_quality" == true && -z "$voice_fixture_dir" ]]; then
  echo "DOUBAO_QUALITY_ERROR: quality requires a fixture directory containing mandarin.pcm, mixed.pcm and terms.pcm" >&2
  exit 2
fi
if [[ -n "$voice_fixture_dir" ]]; then
  voice_pcm_prefix="$voice_fixture_dir/"
  for voice_part in "${voice_fixtures[@]}"; do
    if [[ ! -s "$voice_pcm_prefix$voice_part.pcm" ]]; then
      echo "Missing or empty PCM fixture: $voice_pcm_prefix$voice_part.pcm" >&2
      exit 2
    fi
  done
else
  voice_pcm_prefix="$voice_root/build/validation/voice-"
  for voice_part in "${voice_fixtures[@]}"; do
    voice_text='This is a voice test.'
    if [[ "$voice_part" != test ]]; then voice_text="This is the $voice_part sentence."; fi
    ffmpeg -hide_banner -loglevel error -y -f lavfi \
      -i "flite=text=$voice_text:voice=slt" \
      -ar 16000 -ac 1 -f s16le "$voice_pcm_prefix$voice_part.pcm"
  done
fi
"$voice_adb" -s "$voice_serial" install -r "$voice_app_apk"
"$voice_adb" -s "$voice_serial" install -r "$voice_test_apk"
voice_stage_id="$$-$RANDOM-$(date +%s)"
for voice_part in "${voice_fixtures[@]}"; do
  voice_stage_path="/data/local/tmp/unexpected-keyboards-doubao-$voice_stage_id-$voice_part.pcm"
  voice_stage_files+=("$voice_stage_path")
  MSYS2_ARG_CONV_EXCL=/data/local/tmp "$voice_adb" -s "$voice_serial" push "$voice_pcm_prefix$voice_part.pcm" "$voice_stage_path"
  MSYS2_ARG_CONV_EXCL=/data/local/tmp "$voice_adb" -s "$voice_serial" shell chmod 0644 "$voice_stage_path"
  MSYS2_ARG_CONV_EXCL=/data/local/tmp "$voice_adb" -s "$voice_serial" shell run-as "$voice_package" cp -f "$voice_stage_path" "cache/voice-$voice_part.pcm"
done
voice_instrument_args=(-e voice_live true -e voice_fresh "$voice_fresh" -e voice_continuous "$voice_continuous")
if [[ "$voice_quality" == true ]]; then voice_instrument_args+=(-e voice_quality true); fi
"$voice_adb" -s "$voice_serial" shell am instrument -w -r "${voice_instrument_args[@]}" \
  "$voice_package.test/juloo.keyboard2.PinyinSmokeTest" | tee "$voice_log"
grep -q "$voice_success" "$voice_log"
grep -q '^INSTRUMENTATION_CODE: -1' "$voice_log"
