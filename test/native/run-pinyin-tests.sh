#!/usr/bin/env bash
set -euo pipefail
pinyin_root="$(cd "$(dirname "$0")/../.." && pwd)"
pinyin_build="$pinyin_root/build/native-tests"
pinyin_jdk="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
mkdir -p "$pinyin_build/classes"
g++ -std=c++11 -O2 -fPIC -shared \
  -I"$pinyin_jdk/include" -I"$pinyin_jdk/include/linux" \
  "$pinyin_root"/vendor/pinyin/share/*.cpp \
  "$pinyin_root/vendor/pinyin/pinyin_jni.cpp" \
  -o "$pinyin_build/libpinyin_jni.so"
g++ -std=c++11 -O2 -I"$pinyin_root/vendor/pinyin/include" \
  "$pinyin_root/test/native/PinyinFdTest.cpp" -L"$pinyin_build" -lpinyin_jni \
  -Wl,-rpath,"$pinyin_build" -o "$pinyin_build/pinyin-fd-test"
"$pinyin_build/pinyin-fd-test" "${1:-$pinyin_root/assets/pinyin/dict_pinyin.dat}" "$pinyin_build"
"$pinyin_jdk/bin/javac" -encoding UTF-8 -d "$pinyin_build/classes" \
  "$pinyin_root/srcs/juloo.keyboard2/pinyin/PinyinDecoder.java" \
  "$pinyin_root/srcs/juloo.keyboard2/pinyin/PinyinSpelling.java" \
  "$pinyin_root/srcs/juloo.keyboard2/pinyin/PinyinComposition.java" \
  "$pinyin_root/test/native/PinyinDecoderTest.java" \
  "$pinyin_root/test/native/PinyinCompositionTest.java" \
  "$pinyin_root/test/native/PinyinToleranceTest.java"
"$pinyin_jdk/bin/java" -ea -XX:ErrorFile="$pinyin_build/hs_err_pid%p.log" -Djava.library.path="$pinyin_build" \
  -cp "$pinyin_build/classes" juloo.keyboard2.pinyin.PinyinDecoderTest \
  "${1:-$pinyin_root/assets/pinyin/dict_pinyin.dat}" "$pinyin_build"
"$pinyin_jdk/bin/java" -ea -XX:ErrorFile="$pinyin_build/hs_err_pid%p.log" -Djava.library.path="$pinyin_build" \
  -cp "$pinyin_build/classes" juloo.keyboard2.pinyin.PinyinCompositionTest \
  "${1:-$pinyin_root/assets/pinyin/dict_pinyin.dat}" "$pinyin_build"
"$pinyin_jdk/bin/java" -ea -XX:ErrorFile="$pinyin_build/hs_err_pid%p.log" -Djava.library.path="$pinyin_build" \
  -cp "$pinyin_build/classes" juloo.keyboard2.pinyin.PinyinToleranceTest \
  "${1:-$pinyin_root/assets/pinyin/dict_pinyin.dat}" "$pinyin_build"
