package juloo.keyboard2.pinyin;

/** Single-owner JNI session for the AOSP full-pinyin decoder. No Android APIs. */
public final class PinyinDecoder implements AutoCloseable
{
  public static final int MAX_PINYIN_LENGTH = 39;
  private static PinyinDecoder owner;
  private boolean closed;
  private int candidateCount;
  private boolean learningEnabled = true;

  static { System.loadLibrary("pinyin_jni"); }

  public PinyinDecoder(String systemDictionary, String userDictionary)
  {
    if (systemDictionary == null || userDictionary == null)
      throw new NullPointerException("Dictionary paths must not be null");
    synchronized (PinyinDecoder.class)
    {
      if (owner != null)
        throw new IllegalStateException("A pinyin decoder is already open");
      nativeOpen(systemDictionary, userDictionary);
      owner = this;
    }
  }

  public synchronized int search(String spelling)
  {
    checkOpen();
    if (spelling.length() > MAX_PINYIN_LENGTH)
      throw new IllegalArgumentException("Pinyin composition is too long");
    for (int i = 0; i < spelling.length(); i++)
    {
      char c = spelling.charAt(i);
      if ((c < 'a' || c > 'z') && c != '\'')
        throw new IllegalArgumentException("Pinyin accepts lowercase letters and apostrophes");
    }
    candidateCount = nativeSearch(spelling);
    return candidateCount;
  }

  /** Open an uncompressed asset directly from its APK; the caller owns fd. */
  public PinyinDecoder(int fd, long offset, long length, String userDictionary)
  {
    if (fd < 0 || offset < 0 || length <= 0 || userDictionary == null)
      throw new IllegalArgumentException("Invalid pinyin dictionary descriptor");
    synchronized (PinyinDecoder.class)
    {
      if (owner != null)
        throw new IllegalStateException("A pinyin decoder is already open");
      nativeOpenFd(fd, offset, length, userDictionary);
      owner = this;
    }
  }

  public synchronized int choose(int index)
  {
    checkCandidate(index);
    candidateCount = nativeChoose(index);
    return candidateCount;
  }

  public synchronized int cancelLastChoice()
  {
    checkOpen();
    candidateCount = nativeCancelChoice();
    return candidateCount;
  }

  public synchronized int delete(int position, boolean syllable, boolean clearFixed)
  {
    checkOpen();
    int length = syllable ? nativeSpellingStarts().length - 1 : nativePinyin().length();
    if (position < 0 || position >= length)
      throw new IllegalArgumentException("Invalid pinyin deletion position");
    candidateCount = nativeDelete(position, syllable, clearFixed);
    return candidateCount;
  }

  public synchronized void reset()
  {
    checkOpen();
    nativeReset();
    candidateCount = 0;
  }

  public synchronized int getCandidateCount() { checkOpen(); return candidateCount; }
  public synchronized String getPinyin() { checkOpen(); return nativePinyin(); }
  public synchronized int getDecodedLength() { checkOpen(); return nativeDecodedLength(); }
  public synchronized int getFixedLength() { checkOpen(); return nativeFixedLength(); }
  public synchronized int[] getSpellingStarts() { checkOpen(); return nativeSpellingStarts(); }

  /** Candidate zero includes the fixed prefix; other candidates are suffixes. */
  public synchronized String getCandidate(int index)
  {
    checkCandidate(index);
    return nativeCandidate(index);
  }

  public synchronized float getCandidateScore(int index)
  {
    checkCandidate(index);
    return nativeCandidateScore(index);
  }

  public synchronized int getUnfixedLemmaCount() { checkOpen(); return nativeUnfixedLemmaCount(); }
  public synchronized String[] getSpellings() { checkOpen(); return nativeSpellings(); }
  public synchronized boolean isLearningEnabled() { checkOpen(); return learningEnabled; }

  /** Predictions require an empty search; AOSP reuses its search workspace. */
  public synchronized String[] predict(String history)
  {
    checkOpen();
    if (candidateCount != 0 || !nativePinyin().isEmpty())
      throw new IllegalStateException("Reset composition before requesting predictions");
    return nativePredict(history);
  }

  public synchronized void setLearningEnabled(boolean enabled)
  {
    checkOpen();
    nativeSetLearning(enabled);
    learningEnabled = enabled;
  }

  public synchronized void flush() { checkOpen(); nativeFlush(); }

  @Override
  public synchronized void close()
  {
    if (closed) return;
    synchronized (PinyinDecoder.class)
    {
      nativeClose();
      closed = true;
      owner = null;
    }
  }

  private void checkOpen()
  {
    if (closed) throw new IllegalStateException("Pinyin decoder is closed");
  }

  private void checkCandidate(int index)
  {
    checkOpen();
    if (index < 0 || index >= candidateCount)
      throw new IllegalArgumentException("Invalid pinyin candidate index: " + index);
  }

  private static native void nativeOpen(String systemDictionary, String userDictionary);
  private static native void nativeOpenFd(int fd, long offset, long length, String userDictionary);
  private static native int nativeSearch(String spelling);
  private static native int nativeChoose(int index);
  private static native int nativeDelete(int position, boolean syllable, boolean clearFixed);
  private static native int nativeCancelChoice();
  private static native void nativeReset();
  private static native String nativePinyin();
  private static native int nativeDecodedLength();
  private static native int nativeFixedLength();
  private static native int[] nativeSpellingStarts();
  private static native String nativeCandidate(int index);
  private static native float nativeCandidateScore(int index);
  private static native int nativeUnfixedLemmaCount();
  private static native String[] nativeSpellings();
  private static native String[] nativePredict(String history);
  private static native void nativeSetLearning(boolean enabled);
  private static native void nativeFlush();
  private static native void nativeClose();
}
