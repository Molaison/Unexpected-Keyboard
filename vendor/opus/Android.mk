LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/opus_sources.mk
include $(LOCAL_PATH)/celt_sources.mk
include $(LOCAL_PATH)/silk_sources.mk
include $(CLEAR_VARS)

LOCAL_MODULE := opus_codec
LOCAL_SRC_FILES := $(OPUS_SOURCES) $(OPUS_SOURCES_FLOAT) $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FLOAT)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include $(LOCAL_PATH)/celt $(LOCAL_PATH)/silk $(LOCAL_PATH)/silk/float $(LOCAL_PATH)/src
LOCAL_CFLAGS := -O2 -std=c99 -DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINTF -DHAVE_LRINT
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := opus_jni
LOCAL_SRC_FILES := opus_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_STATIC_LIBRARIES := opus_codec
LOCAL_LDLIBS := -lm
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--build-id=none
include $(BUILD_SHARED_LIBRARY)
