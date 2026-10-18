#!/usr/bin/env python3
"""Generate the minimal Android API stubs used to type-check the doubao package.

These stubs exist only so `javac` can compile the voice-input sources without a
full Android SDK checkout. They are never packaged into the APK.
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stubs")

FILES = {

"android/content/Context.java": """
package android.content;
import android.content.res.Resources;
public class Context {
  public static final int MODE_PRIVATE = 0;
  public Context getApplicationContext() { return this; }
  public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
  public Resources getResources() { return null; }
  public Object getSystemService(String name) { return null; }
  public String getPackageName() { return null; }
}
""",

"android/content/SharedPreferences.java": """
package android.content;
public interface SharedPreferences {
  String getString(String key, String defValue);
  boolean getBoolean(String key, boolean defValue);
  int getInt(String key, int defValue);
  long getLong(String key, long defValue);
  Editor edit();
  interface Editor {
    Editor putString(String key, String value);
    Editor putBoolean(String key, boolean value);
    Editor putInt(String key, int value);
    Editor putLong(String key, long value);
    boolean commit();
  }
}
""",

"android/content/pm/PackageManager.java": """
package android.content.pm;
public class PackageManager {
  public static final int PERMISSION_GRANTED = 0;
  public static final int PERMISSION_DENIED = -1;
}
""",

"android/content/res/Resources.java": """
package android.content.res;
import android.util.DisplayMetrics;
public class Resources {
  public DisplayMetrics getDisplayMetrics() { return null; }
}
""",

"android/os/Build.java": """
package android.os;
public class Build {
  public static final String MODEL = "model";
  public static final String BRAND = "brand";
  public static final String ID = "id";
  public static final String[] SUPPORTED_ABIS = new String[]{"arm64-v8a"};
  public static class VERSION {
    public static final String RELEASE = "14";
    public static final int SDK_INT = 34;
  }
}
""",

"android/os/Handler.java": """
package android.os;
public class Handler {
  public Handler(Looper looper) {}
  public boolean post(Runnable r) { return true; }
  public boolean postDelayed(Runnable r, long delayMillis) { return true; }
  public void removeCallbacks(Runnable r) {}
}
""",

"android/os/Looper.java": """
package android.os;
public class Looper {
  public static Looper getMainLooper() { return null; }
  public static Looper myLooper() { return null; }
}
""",

"android/os/Process.java": """
package android.os;
public class Process {
  public static final int THREAD_PRIORITY_AUDIO = -16;
  public static void setThreadPriority(int priority) {}
}
""",

"android/util/DisplayMetrics.java": """
package android.util;
public class DisplayMetrics {
  public int widthPixels;
  public int heightPixels;
  public int densityDpi;
}
""",

"android/util/Log.java": """
package android.util;
public class Log {
  public static int i(String tag, String msg) { return 0; }
  public static int i(String tag, String msg, Throwable tr) { return 0; }
  public static int w(String tag, String msg) { return 0; }
  public static int w(String tag, String msg, Throwable tr) { return 0; }
  public static int e(String tag, String msg) { return 0; }
  public static int e(String tag, String msg, Throwable tr) { return 0; }
  public static int d(String tag, String msg) { return 0; }
  public static int d(String tag, String msg, Throwable tr) { return 0; }
}
""",

"android/media/AudioFormat.java": """
package android.media;
public class AudioFormat {
  public static final int CHANNEL_IN_MONO = 16;
  public static final int ENCODING_PCM_16BIT = 2;
}
""",

"android/media/AudioRecord.java": """
package android.media;
public class AudioRecord {
  public static final int STATE_INITIALIZED = 1;
  public static final int RECORDSTATE_RECORDING = 3;
  public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig,
      int audioFormat, int bufferSizeInBytes) {}
  public static int getMinBufferSize(int sampleRateInHz, int channelConfig,
      int audioFormat) { return 0; }
  public int getState() { return STATE_INITIALIZED; }
  public int getRecordingState() { return RECORDSTATE_RECORDING; }
  public void startRecording() {}
  public void stop() {}
  public void release() {}
  public int read(short[] audioData, int offsetInShorts, int sizeInShorts) { return 0; }
}
""",

"android/media/MediaRecorder.java": """
package android.media;
public class MediaRecorder {
  public static class AudioSource {
    public static final int MIC = 1;
    public static final int VOICE_RECOGNITION = 6;
  }
}
""",

"android/view/inputmethod/InputConnection.java": """
package android.view.inputmethod;
public interface InputConnection {
  boolean setComposingText(CharSequence text, int newCursorPosition);
  boolean finishComposingText();
  boolean commitText(CharSequence text, int newCursorPosition);
  CharSequence getTextBeforeCursor(int n, int flags);
  CharSequence getTextAfterCursor(int n, int flags);
  CharSequence getSelectedText(int flags);
  boolean deleteSurroundingText(int beforeLength, int afterLength);
}
""",

"android/view/inputmethod/EditorInfo.java": """
package android.view.inputmethod;
public class EditorInfo {
  public static final int IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000;
  public static final int TYPE_CLASS_TEXT = 0x00000001;
  public static final int TYPE_CLASS_NUMBER = 0x00000002;
  public static final int TYPE_CLASS_PHONE = 0x00000003;
  public static final int TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000;
  public int inputType;
  public int imeOptions;
  public CharSequence hintText;
  public String packageName;
  public int fieldId;
  public String fieldName;
}
""",

"android/text/InputType.java": """
package android.text;
public interface InputType {
  int TYPE_NULL = 0;
  int TYPE_MASK_CLASS = 0xf;
  int TYPE_MASK_VARIATION = 0xff0;
  int TYPE_CLASS_TEXT = 1;
  int TYPE_CLASS_NUMBER = 2;
  int TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20;
  int TYPE_TEXT_VARIATION_PASSWORD = 0x80;
  int TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90;
  int TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0;
  int TYPE_NUMBER_VARIATION_PASSWORD = 0x10;
  int TYPE_TEXT_FLAG_MULTI_LINE = 0x20000;
}
""",

"android/Manifest.java": """
package android;
public class Manifest {
  public static class permission {
    public static final String RECORD_AUDIO = "android.permission.RECORD_AUDIO";
  }
}
""",

"androidx/core/content/ContextCompat.java": """
package androidx.core.content;
import android.content.Context;
public class ContextCompat {
  public static int checkSelfPermission(Context context, String permission) {
    return 0;
  }
}
""",

"android/util/Base64.java": """
package android.util;
public class Base64 {
  public static final int DEFAULT = 0;
  public static final int NO_WRAP = 2;
  public static String encodeToString(byte[] input, int flags) {
    return java.util.Base64.getEncoder().encodeToString(input);
  }
  public static byte[] decode(String str, int flags) {
    return java.util.Base64.getDecoder().decode(str);
  }
}
""",
}


def main():
    for relative, body in FILES.items():
        path = os.path.join(ROOT, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(body.lstrip("\n"))
    print("wrote", len(FILES), "stub files to", ROOT)


if __name__ == "__main__":
    main()
