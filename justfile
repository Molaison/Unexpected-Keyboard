# Maintainer commands; the checked-in dictionary keeps ordinary builds offline.
pinyin-dictionary:
    python3 tools/build_pinyin_dictionary.py --download --jobs 2

pinyin-native-test:
    bash test/native/run-pinyin-tests.sh

android-check:
    ./gradlew checkKeyboardLayouts testDebugUnitTest assembleDebug assembleDebugAndroidTest minifyReleaseWithR8

pinyin-device-test serial:
    bash tools/run-pinyin-instrumentation.sh '{{serial}}'

doubao-live-test serial:
    bash tools/run-doubao-live-test.sh '{{serial}}'

doubao-continuous-test serial:
    bash tools/run-doubao-live-test.sh '{{serial}}' false true

doubao-device-test serial:
    bash tools/run-doubao-device-tests.sh '{{serial}}'

doubao-device-cancel-test serial:
    bash tools/run-doubao-device-tests.sh '{{serial}}' cancel

opus-source:
    python3 tools/vendor_opus.py --download
