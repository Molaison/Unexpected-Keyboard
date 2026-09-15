package juloo.keyboard2.doubao;

import java.io.IOException;

/** Native libopus, with the same 16 kHz mono / 20 ms wire format as before. */
final class OpusFrameEncoder implements AutoCloseable
{
  static { System.loadLibrary("opus_jni"); }
  private long handle;

  OpusFrameEncoder() throws IOException { handle = nativeCreate(); }

  synchronized byte[] encode(short[] pcm) throws IOException
  {
    if (handle == 0) throw new IllegalStateException("The Opus encoder is closed");
    if (pcm.length != 320) throw new IllegalArgumentException("Expected 320 PCM samples, got " + pcm.length);
    return nativeEncode(handle, pcm);
  }

  @Override public synchronized void close()
  {
    if (handle != 0)
    {
      nativeDestroy(handle);
      handle = 0;
    }
  }

  private static native long nativeCreate() throws IOException;
  private static native byte[] nativeEncode(long handle, short[] pcm) throws IOException;
  private static native void nativeDestroy(long handle);
}
