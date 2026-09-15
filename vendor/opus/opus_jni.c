#include <jni.h>
#include <stdint.h>
#include "opus.h"

#define JNI(name) Java_juloo_keyboard2_doubao_OpusFrameEncoder_##name

static void fail(JNIEnv *env, int code) {
  (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), opus_strerror(code));
}

JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv *env, jclass type) {
  int error = OPUS_OK;
  OpusEncoder *encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_AUDIO, &error);
  if (error != OPUS_OK) { fail(env, error); return 0; }
  return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL JNI(nativeEncode)(JNIEnv *env, jclass type, jlong handle, jshortArray pcm) {
  opus_int16 samples[320];
  unsigned char output[4000];
  (*env)->GetShortArrayRegion(env, pcm, 0, 320, samples);
  if ((*env)->ExceptionCheck(env)) return NULL;
  int length = opus_encode((OpusEncoder *)(intptr_t)handle, samples, 320, output, sizeof(output));
  if (length < 0) { fail(env, length); return NULL; }
  jbyteArray result = (*env)->NewByteArray(env, length);
  if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, length, (jbyte *)output);
  return result;
}

JNIEXPORT void JNICALL JNI(nativeDestroy)(JNIEnv *env, jclass type, jlong handle) {
  opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}
