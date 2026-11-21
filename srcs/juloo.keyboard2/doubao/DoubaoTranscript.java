package juloo.keyboard2.doubao;

import java.util.TreeMap;

/** Edits one in-flight transcript while the result stream still owns the editor. */
final class DoubaoTranscript
{
  private final TreeMap<Integer, String> utterances = new TreeMap<>();
  private final TreeMap<Integer, Span> spans = new TreeMap<>();
  private int currentIndex;
  private int lastFragment;
  private String snapshot;
  private boolean sealed;

  private static final class Span
  {
    int start;
    int end;
    boolean coversUnassignedTail;
    Span(int start, int end) { this.start = start; this.end = end; }
  }

  String update(DoubaoProtocol.Response response, boolean stopping)
  {
    if (sealed && response.layout != DoubaoProtocol.TranscriptLayout.FINAL_WHOLE)
      return null;
    if (response.layout != DoubaoProtocol.TranscriptLayout.INDEXED
        || (snapshot != null && response.utteranceIndex < 0))
      return setSnapshot(response, stopping);
    if (snapshot != null)
      return updateFragment(response.utteranceIndex, response.text, response.stable);

    int index = response.utteranceIndex >= 0 ? response.utteranceIndex : currentIndex;
    currentIndex = Math.max(currentIndex, index);
    utterances.put(index, response.text);
    StringBuilder text = new StringBuilder();
    for (String utterance : utterances.values()) text.append(utterance);
    return text.toString();
  }

  private String setSnapshot(DoubaoProtocol.Response response, boolean stopping)
  {
    snapshot = response.text;
    sealed = stopping && response.layout == DoubaoProtocol.TranscriptLayout.FINAL_WHOLE;
    utterances.clear();
    spans.clear();
    lastFragment = -1;
    int cursor = 0;
    for (DoubaoProtocol.Fragment fragment : response.fragments)
    {
      lastFragment = Math.max(lastFragment, fragment.index);
      if (fragment.text.isEmpty() || spans.containsKey(fragment.index))
        continue;
      Span span = locateFragment(fragment.text, cursor);
      if (span != null)
      {
        cursor = span.end;
        spans.put(fragment.index, span);
      }
    }
    return snapshot;
  }

  private Span locateFragment(String text, int cursor)
  {
    int exact = snapshot.indexOf(text, cursor);
    if (exact == cursor) return new Span(exact, exact + text.length());

    // The punctuation pass may reformat a fragment at the expected boundary.
    int start = skipFormatting(snapshot, cursor);
    int end = matchFormattedPrefix(snapshot, start, text);
    if (end >= 0 && (exact < 0 || start < exact))
    {
      while (end < snapshot.length())
      {
        int kind = Character.getType(snapshot.charAt(end));
        if (kind != Character.END_PUNCTUATION && kind != Character.FINAL_QUOTE_PUNCTUATION
            && kind != Character.OTHER_PUNCTUATION) break;
        end++;
      }
      return new Span(start, end);
    }
    return exact < 0 ? null : new Span(exact, exact + text.length());
  }

  /** Finds the end of a matching prefix after ignoring punctuation and spacing. */
  private static int matchFormattedPrefix(String text, int start, String prefix)
  {
    int part = skipFormatting(prefix, 0);
    if (part == prefix.length()) return -1;
    int end = start;
    while (part < prefix.length())
    {
      end = skipFormatting(text, end);
      if (end == text.length() || text.charAt(end) != prefix.charAt(part)) return -1;
      end++;
      part = skipFormatting(prefix, part + 1);
    }
    return end;
  }

  private static int skipFormatting(String text, int position)
  {
    while (position < text.length())
    {
      char character = text.charAt(position);
      int kind = Character.getType(character);
      boolean punctuation = (kind >= Character.DASH_PUNCTUATION && kind <= Character.OTHER_PUNCTUATION)
          || kind == Character.INITIAL_QUOTE_PUNCTUATION || kind == Character.FINAL_QUOTE_PUNCTUATION;
      if (!punctuation && !isSpacing(character)) break;
      position++;
    }
    return position;
  }

  private static boolean isSpacing(char character)
  {
    return Character.isSpaceChar(character) || Character.isWhitespace(character);
  }

  private static boolean sharesTail(String tail, String text)
  {
    return text.startsWith(tail) || tail.startsWith(text)
        || matchFormattedPrefix(tail, 0, text) >= 0 || matchFormattedPrefix(text, 0, tail) >= 0;
  }

  private String updateFragment(int index, String text, boolean stable)
  {
    Span span = spans.get(index);
    if (span == null && index == 0 && !spans.isEmpty() && spans.firstEntry().getValue().start == 0)
      span = spans.firstEntry().getValue();
    if (span == null)
    {
      if (index <= lastFragment || index <= 0) return null;
      int start = snapshot.length();
      Span previous = spans.get(lastFragment);
      if (previous != null)
      {
        String tail = snapshot.substring(previous.end);
        if (sharesTail(tail, text))
        {
          start = previous.end;
          // Retain the separator if the revised fragment doesn't provide one.
          if (!isSpacing(text.charAt(0)))
            while (start < snapshot.length() && isSpacing(snapshot.charAt(start))) start++;
        }
      }
      span = new Span(start, snapshot.length());
      span.coversUnassignedTail = span.start < span.end;
      spans.put(index, span);
      lastFragment = index;
    }
    else if (span == spans.get(lastFragment) && span.end < snapshot.length())
    {
      // An existing detail may describe only the beginning of the visible tail.
      int extension = matchFormattedPrefix(text, 0, snapshot.substring(span.start, span.end));
      if (extension >= 0 && extension < text.length()
          && sharesTail(snapshot.substring(span.end), text.substring(extension)))
      {
        span.end = snapshot.length();
        span.coversUnassignedTail = true;
      }
    }

    int previousEnd = span.end;
    // Keep the fuller snapshot visible while the new indexed fragment catches up.
    if (span.coversUnassignedTail && !stable)
    {
      String current = snapshot.substring(span.start, previousEnd);
      int prefixEnd = matchFormattedPrefix(current, 0, text);
      if (prefixEnd >= 0 && skipFormatting(current, prefixEnd) < current.length()) return null;
    }
    span.coversUnassignedTail = false;
    int delta = text.length() - (previousEnd - span.start);
    snapshot = snapshot.substring(0, span.start) + text + snapshot.substring(previousEnd);
    span.end += delta;
    for (Span later : spans.values())
      if (later != span && later.start >= previousEnd)
      {
        later.start += delta;
        later.end += delta;
      }
    return snapshot;
  }

  void clear()
  {
    utterances.clear();
    spans.clear();
    snapshot = null;
    sealed = false;
    currentIndex = 0;
    lastFragment = -1;
  }
}
