// Unexpected Keyboard JNI adapter. AOSP decoder attribution: see NOTICE.
#include <jni.h>
#include <cstring>
#include "include/pinyinime.h"
#include "include/spellingtrie.h"

using namespace ime_pinyin;
#define JNI(name) Java_juloo_keyboard2_pinyin_PinyinDecoder_##name

static void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" {

JNIEXPORT void JNICALL JNI(nativeOpen)(JNIEnv *env, jclass, jstring system,
                                      jstring user) {
  const char *system_path = env->GetStringUTFChars(system, NULL);
  if (system_path == NULL) return;  // Pending JVM exception.
  const char *user_path = env->GetStringUTFChars(user, NULL);
  if (user_path == NULL) {
    env->ReleaseStringUTFChars(system, system_path);
    return;
  }
  bool ok = im_open_decoder(system_path, user_path);
  env->ReleaseStringUTFChars(system, system_path);
  env->ReleaseStringUTFChars(user, user_path);
  if (!ok) {
    im_close_decoder();
    fail(env, "Cannot open the pinyin system or user dictionary");
    return;
  }
  im_set_max_lens(kMaxSearchSteps - 1, kMaxSearchSteps - 1);
  im_reset_search();
}

JNIEXPORT jint JNICALL JNI(nativeSearch)(JNIEnv *env, jclass, jstring spelling) {
  const char *text = env->GetStringUTFChars(spelling, NULL);
  if (text == NULL) return 0;
  size_t count = im_search(text, std::strlen(text));
  env->ReleaseStringUTFChars(spelling, text);
  return static_cast<jint>(count);
}

JNIEXPORT void JNICALL JNI(nativeOpenFd)(JNIEnv *env, jclass, jint fd,
                                        jlong offset, jlong length, jstring user) {
  const char *user_path = env->GetStringUTFChars(user, NULL);
  if (user_path == NULL) return;
  bool ok = im_open_decoder_fd(fd, static_cast<long>(offset),
                              static_cast<long>(length), user_path);
  env->ReleaseStringUTFChars(user, user_path);
  if (!ok) {
    im_close_decoder();
    fail(env, "Cannot open the packaged pinyin or user dictionary");
    return;
  }
  im_set_max_lens(kMaxSearchSteps - 1, kMaxSearchSteps - 1);
  im_reset_search();
}

JNIEXPORT jint JNICALL JNI(nativeChoose)(JNIEnv *, jclass, jint index) {
  return static_cast<jint>(im_choose(index));
}

JNIEXPORT jint JNICALL JNI(nativeDelete)(JNIEnv *, jclass, jint position,
                                        jboolean syllable, jboolean clear_fixed) {
  return static_cast<jint>(im_delsearch(position, syllable, clear_fixed));
}

JNIEXPORT jint JNICALL JNI(nativeCancelChoice)(JNIEnv *, jclass) {
  return static_cast<jint>(im_cancel_last_choice());
}

JNIEXPORT void JNICALL JNI(nativeReset)(JNIEnv *, jclass) { im_reset_search(); }
JNIEXPORT void JNICALL JNI(nativeFlush)(JNIEnv *, jclass) { im_flush_cache(); }
JNIEXPORT void JNICALL JNI(nativeClose)(JNIEnv *, jclass) { im_close_decoder(); }

JNIEXPORT void JNICALL JNI(nativeSetLearning)(JNIEnv *, jclass, jboolean enabled) {
  im_set_learning_enabled(enabled);
}

JNIEXPORT jstring JNICALL JNI(nativePinyin)(JNIEnv *env, jclass) {
  size_t decoded = 0;
  const char *text = im_get_sps_str(&decoded);
  if (text == NULL) {
    fail(env, "Pinyin decoder is not open");
    return NULL;
  }
  return env->NewStringUTF(text);
}

JNIEXPORT jint JNICALL JNI(nativeDecodedLength)(JNIEnv *, jclass) {
  size_t decoded = 0;
  im_get_sps_str(&decoded);
  return static_cast<jint>(decoded);
}

JNIEXPORT jint JNICALL JNI(nativeFixedLength)(JNIEnv *, jclass) {
  return static_cast<jint>(im_get_fixed_len());
}

JNIEXPORT jintArray JNICALL JNI(nativeSpellingStarts)(JNIEnv *env, jclass) {
  const uint16 *starts = NULL;
  size_t count = im_get_spl_start_pos(starts);
  jint result[kMaxSearchSteps + 1] = {};
  if (count >= kMaxSearchSteps || starts == NULL) {
    fail(env, "Invalid pinyin spelling boundaries");
    return NULL;
  }
  for (size_t i = 0; i <= count; ++i) result[i] = starts[i];
  jintArray array = env->NewIntArray(count + 1);
  if (array != NULL) env->SetIntArrayRegion(array, 0, count + 1, result);
  return array;
}

JNIEXPORT jstring JNICALL JNI(nativeCandidate)(JNIEnv *env, jclass, jint index) {
  char16 text[kMaxSearchSteps + 1] = {};
  if (im_get_candidate(index, text, kMaxSearchSteps + 1) == NULL) {
    fail(env, "Pinyin candidate does not exist");
    return NULL;
  }
  return env->NewString(reinterpret_cast<jchar *>(text), utf16_strlen(text));
}

JNIEXPORT jfloat JNICALL JNI(nativeCandidateScore)(JNIEnv *, jclass, jint index) {
  return im_get_candidate_score(index);
}

JNIEXPORT jint JNICALL JNI(nativeUnfixedLemmaCount)(JNIEnv *, jclass) {
  return static_cast<jint>(im_get_unfixed_lemma_count());
}

JNIEXPORT jobjectArray JNICALL JNI(nativeSpellings)(JNIEnv *env, jclass) {
  SpellingTrie &trie = SpellingTrie::get_instance();
  size_t count = trie.get_spelling_num();
  jobjectArray array = env->NewObjectArray(count, env->FindClass("java/lang/String"), NULL);
  if (array == NULL) return NULL;
  for (size_t i = 0; i < count; ++i) {
    jstring spelling = env->NewStringUTF(trie.get_spelling_str(kFullSplIdStart + i));
    if (spelling == NULL) return NULL;
    env->SetObjectArrayElement(array, i, spelling);
    env->DeleteLocalRef(spelling);
  }
  return array;
}

JNIEXPORT jobjectArray JNICALL JNI(nativePredict)(JNIEnv *env, jclass,
                                                 jstring history) {
  char16 text[kMaxPredictSize + 1] = {};
  jsize length = env->GetStringLength(history);
  jsize used = length < kMaxPredictSize ? length : kMaxPredictSize;
  env->GetStringRegion(history, length - used, used, reinterpret_cast<jchar *>(text));
  if (env->ExceptionCheck()) return NULL;
  char16 (*predictions)[kMaxPredictSize + 1] = NULL;
  size_t count = im_get_predicts(text, predictions);
  jobjectArray array = env->NewObjectArray(count, env->FindClass("java/lang/String"), NULL);
  if (array == NULL) return NULL;
  for (size_t i = 0; i < count; ++i) {
    jstring candidate = env->NewString(reinterpret_cast<jchar *>(predictions[i]),
                                      utf16_strlen(predictions[i]));
    if (candidate == NULL) return NULL;
    env->SetObjectArrayElement(array, i, candidate);
    env->DeleteLocalRef(candidate);
  }
  return array;
}
}
