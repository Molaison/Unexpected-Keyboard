package juloo.keyboard2.doubao;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.TreeMap;

/** Edits one in-flight transcript while the result stream still owns the editor. */
final class DoubaoTranscript
{
  private final TreeMap<Integer, String> utterances = new TreeMap<>();
  private final TreeMap<Integer, Span> spans = new TreeMap<>();
  private int currentIndex;
  private boolean hasExplicitIndex;
  private boolean endedImplicitUtterance;
  private int lastFragment;
  private String snapshot;
  private boolean sealed;

  private static final class Span
  {
    int start;
    int end;
    boolean coversUnassignedTail;
    boolean openSnapshotDetail;
    boolean alignedWithSnapshot;
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

    int index = response.utteranceIndex;
    if (index >= 0)
      hasExplicitIndex = true;
    else
      index = endedImplicitUtterance ? currentIndex + 1 : currentIndex;
    // The ordinary indexless stream starts a new sentence after a VAD boundary.
    endedImplicitUtterance = !hasExplicitIndex && response.vadFinished;
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
    if (snapshot.isEmpty())
    {
      // Clear the previous indices so a later recognition can start again.
      snapshot = null;
      currentIndex = 0;
      hasExplicitIndex = false;
      endedImplicitUtterance = false;
      return "";
    }
    int cursor = 0;
    for (DoubaoProtocol.Fragment fragment : response.fragments)
    {
      lastFragment = Math.max(lastFragment, fragment.index);
      if (fragment.text.isEmpty() || spans.containsKey(fragment.index))
        continue;
      Span span = locateFragment(fragment.text, cursor);
      if (span != null)
      {
        span.openSnapshotDetail = !fragment.stable;
        span.alignedWithSnapshot = true;
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
      return new Span(start, end);
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

  private int observedTailEnd(int start, String text)
  {
    if (start == snapshot.length()) return start;
    // An unassigned suffix may contain multiple sentences. Claim the first one,
    // or a longer range only when its words are present in the incoming detail.
    BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
    sentences.setText(snapshot.substring(start));
    int end = start + sentences.following(0);
    while (end > start && isSpacing(snapshot.charAt(end - 1))) end--;
    return Math.max(end, matchFormattedPrefix(snapshot, start, text));
  }

  private static boolean isClosingPunctuation(char character)
  {
    return isClosingDelimiter(character) || Character.getType(character) == Character.OTHER_PUNCTUATION;
  }

  private static boolean isClosingDelimiter(char character)
  {
    int kind = Character.getType(character);
    return kind == Character.END_PUNCTUATION || kind == Character.FINAL_QUOTE_PUNCTUATION
        || character == '"' || character == '\'';
  }

  private static boolean isEnclosingDelimiter(char character)
  {
    int kind = Character.getType(character);
    return isClosingDelimiter(character)
        || kind == Character.START_PUNCTUATION || kind == Character.INITIAL_QUOTE_PUNCTUATION;
  }

  private static boolean isBoundaryFormatting(char character)
  {
    return isEnclosingDelimiter(character) || Character.getType(character) == Character.OTHER_PUNCTUATION
        || isSpacing(character);
  }

  private static int trailingPunctuationStart(String text, int start, int end)
  {
    while (end > start && isClosingPunctuation(text.charAt(end - 1))) end--;
    return end;
  }

  private int endBeforeEnclosingPunctuation(int start, int end)
  {
    int marks = trailingPunctuationStart(snapshot, start, end);
    // Leave enclosing punctuation outside the enlarged text span so the
    // replacement can match it without taking ownership of it.
    for (int i = marks; i < end; i++)
      if (isClosingDelimiter(snapshot.charAt(i))) return marks;
    return end;
  }

  private static int nextClosingDelimiter(String text, int start, int end)
  {
    while (start < end && !isClosingDelimiter(text.charAt(start))) start++;
    return start;
  }

  private int startAfterEnclosingPunctuation(int start)
  {
    int end = start;
    boolean encloses = false;
    while (end < snapshot.length() && isBoundaryFormatting(snapshot.charAt(end)))
    {
      encloses |= isEnclosingDelimiter(snapshot.charAt(end));
      end++;
    }
    return encloses ? end : start;
  }

  private String withoutRepeatedLeadingPunctuation(Span span, String text)
  {
    if (!span.alignedWithSnapshot || text.isEmpty() || !isBoundaryFormatting(text.charAt(0))) return text;
    int precedingEnd = 0;
    for (Span earlier : spans.values())
      if (earlier != span && earlier.end <= span.start)
        precedingEnd = Math.max(precedingEnd, earlier.end);
    int start = span.start;
    boolean encloses = false;
    while (start > precedingEnd && isBoundaryFormatting(snapshot.charAt(start - 1)))
    {
      start--;
      encloses |= isEnclosingDelimiter(snapshot.charAt(start));
    }
    if (!encloses) return text;

    // The surrounding format stays outside the detail across all its revisions.
    int copied = 0;
    for (int i = start; i < span.start && copied < text.length(); i++)
      if (snapshot.charAt(i) == text.charAt(copied)) copied++;
    return text.substring(copied);
  }

  private String replaceFragment(Span span, String text)
  {
    text = withoutRepeatedLeadingPunctuation(span, text);
    int previousEnd = span.end;
    int enclosingOffset = -1;
    int textEnd = text.length();
    while (textEnd > 0 && isSpacing(text.charAt(textEnd - 1))) textEnd--;
    if (textEnd > 0 && isClosingPunctuation(text.charAt(textEnd - 1))
        && previousEnd < snapshot.length() && isClosingPunctuation(snapshot.charAt(previousEnd)))
    {
      int limit = snapshot.length();
      for (Span later : spans.values())
        if (later != span && later.start >= span.end) limit = Math.min(limit, later.start);

      int oldMarksEnd = previousEnd;
      while (oldMarksEnd < limit && isClosingPunctuation(snapshot.charAt(oldMarksEnd))) oldMarksEnd++;
      int replacement = trailingPunctuationStart(text, 0, textEnd);
      int replacementCloser = nextClosingDelimiter(text, replacement, textEnd);
      // Account for delimiters already in the old detail before matching the enclosing marks.
      for (int i = trailingPunctuationStart(snapshot, span.start, span.end);
          i < span.end && replacementCloser < textEnd; i++)
        if (snapshot.charAt(i) == text.charAt(replacementCloser))
        {
          replacement = replacementCloser + 1;
          replacementCloser = nextClosingDelimiter(text, replacement, textEnd);
        }

      StringBuilder merged = new StringBuilder(text.length());
      merged.append(text, 0, replacement);
      while (previousEnd < oldMarksEnd && replacement < textEnd)
      {
        int previousCloser = nextClosingDelimiter(snapshot, previousEnd, oldMarksEnd);
        // Replace ordinary punctuation only at the same nesting level.
        if (replacement < replacementCloser)
        {
          merged.append(text, replacement, replacementCloser);
          previousEnd = previousCloser;
          replacement = replacementCloser;
        }
        if (replacementCloser == textEnd) break;
        int matchedCloser = previousCloser;
        while (matchedCloser < oldMarksEnd && snapshot.charAt(matchedCloser) != text.charAt(replacementCloser))
          matchedCloser++;
        if (matchedCloser == oldMarksEnd) break;

        // Keep matched and omitted enclosing marks outside the detail.
        if (enclosingOffset < 0) enclosingOffset = merged.length();
        merged.append(snapshot, previousEnd, matchedCloser + 1);
        previousEnd = matchedCloser + 1;
        replacement = replacementCloser + 1;
        replacementCloser = nextClosingDelimiter(text, replacement, textEnd);
      }
      merged.append(text, replacement, text.length());
      text = merged.toString();
    }
    int delta = text.length() - (previousEnd - span.start);
    snapshot = snapshot.substring(0, span.start) + text + snapshot.substring(previousEnd);
    span.end = span.start + (enclosingOffset < 0 ? text.length() : enclosingOffset);
    for (Span later : spans.values())
      if (later != span && later.start >= previousEnd)
      {
        later.start += delta;
        later.end += delta;
      }
    return snapshot;
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
      int end = start;
      Span previous = spans.get(lastFragment);
      if (previous != null)
      {
        int tailStart = startAfterEnclosingPunctuation(previous.end);
        int tailEnd = observedTailEnd(tailStart, text);
        String tail = snapshot.substring(tailStart, tailEnd);
        if (sharesTail(tail, text))
        {
          start = tailStart;
          // Retain the separator if the revised fragment doesn't provide one.
          if (!isSpacing(text.charAt(0)))
            while (start < tailEnd && isSpacing(snapshot.charAt(start))) start++;
          end = endBeforeEnclosingPunctuation(start, tailEnd);
        }
      }
      span = new Span(start, end);
      span.alignedWithSnapshot = span.start < span.end;
      span.coversUnassignedTail = span.start < span.end;
      spans.put(index, span);
      lastFragment = index;
    }
    else if (span == spans.get(lastFragment) && span.end < snapshot.length())
    {
      // An existing detail may describe only the beginning of the visible tail.
      int tailEnd = endBeforeEnclosingPunctuation(span.end, observedTailEnd(span.start, text));
      if (tailEnd > span.end)
      {
        int extension = matchFormattedPrefix(text, 0, snapshot.substring(span.start, span.end));
        boolean confirmedOverlap = extension >= 0 && extension < text.length()
            && sharesTail(snapshot.substring(span.end, tailEnd), text.substring(extension));
        if (span.openSnapshotDetail || confirmedOverlap)
        {
          span.end = tailEnd;
          span.coversUnassignedTail = true;
        }
      }
    }

    // Keep the fuller snapshot visible while the new indexed fragment catches up.
    if (span.coversUnassignedTail && !stable)
    {
      String current = snapshot.substring(span.start, span.end);
      int prefixEnd = matchFormattedPrefix(current, 0, text);
      if (prefixEnd >= 0 && skipFormatting(current, prefixEnd) < current.length()) return null;
    }
    span.coversUnassignedTail = false;
    span.openSnapshotDetail = false;
    return replaceFragment(span, text);
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
