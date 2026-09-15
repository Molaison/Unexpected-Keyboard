LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := pinyin_jni
LOCAL_CPPFLAGS := -std=c++11
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_SRC_FILES := pinyin_jni.cpp \
    share/dictbuilder.cpp share/dictlist.cpp share/dicttrie.cpp \
    share/lpicache.cpp share/matrixsearch.cpp share/mystdlib.cpp \
    share/ngram.cpp share/pinyinime.cpp share/searchutility.cpp \
    share/spellingtable.cpp share/spellingtrie.cpp share/splparser.cpp \
    share/userdict.cpp share/utf16char.cpp share/utf16reader.cpp share/sync.cpp
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--build-id=none

include $(BUILD_SHARED_LIBRARY)
