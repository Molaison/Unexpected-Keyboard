package juloo.keyboard2.pinyin;

/** Bounded editor-local context. Never persisted, and never crosses punctuation. */
public final class PredictionContext
{
  // The AOSP decoder's maximum history is kMaxLemmaSize - 1 = 7 Hanzi.
  public static final int LIMIT = 7;

  public static String tail(CharSequence text)
  {
    if (text == null) return "";
    int end = text.length(), start = end;
    while (start > 0 && end - start < LIMIT)
    {
      char c = text.charAt(start - 1);
      if (!((c >= '\u3400' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff'))) break;
      start--;
    }
    return text.subSequence(start, end).toString();
  }

  public static String append(String context, String committed)
  { return tail(context + committed); }

  private PredictionContext() {}
}
