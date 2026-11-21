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
      return updateFragment(response.utteranceIndex, response.text, response.isFinal);

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
    int fragment = skipFormatting(text, 0);
    if (fragment < text.length() && (exact < 0 || start < exact))
    {
      int end = start;
      while (fragment < text.length())
      {
        end = skipFormatting(snapshot, end);
        if (end == snapshot.length() || snapshot.charAt(end) != text.charAt(fragment)) break;
        end++;
        fragment = skipFormatting(text, fragment + 1);
      }
      if (fragment == text.length())
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
    }
    return exact < 0 ? null : new Span(exact, exact + text.length());
  }

  private static int skipFormatting(String text, int position)
  {
    while (position < text.length())
    {
      char character = text.charAt(position);
      int kind = Character.getType(character);
      boolean punctuation = (kind >= Character.DASH_PUNCTUATION && kind <= Character.OTHER_PUNCTUATION)
          || kind == Character.INITIAL_QUOTE_PUNCTUATION || kind == Character.FINAL_QUOTE_PUNCTUATION;
      if (!punctuation && !Character.isSpaceChar(character) && !Character.isWhitespace(character)) break;
      position++;
    }
    return position;
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
        if (text.startsWith(tail) || tail.startsWith(text)) start = previous.end;
      }
      span = new Span(start, snapshot.length());
      span.coversUnassignedTail = span.start < span.end;
      spans.put(index, span);
      lastFragment = index;
    }

    int previousEnd = span.end;
    // Keep the fuller snapshot visible while the new indexed fragment catches up.
    if (span.coversUnassignedTail && !stable && text.length() < previousEnd - span.start
        && snapshot.regionMatches(span.start, text, 0, text.length())) return null;
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
