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
      return updateFragment(response.utteranceIndex, response.text);

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
      // Fragments can differ from the aggregate after the punctuation pass.
      // Only edit a span that can be located in the actual snapshot.
      int start = snapshot.indexOf(fragment.text, cursor);
      if (start >= 0)
      {
        cursor = start + fragment.text.length();
        spans.put(fragment.index, new Span(start, cursor));
      }
    }
    return snapshot;
  }

  private String updateFragment(int index, String text)
  {
    Span span = spans.get(index);
    if (span == null && index == 0 && !spans.isEmpty() && spans.firstEntry().getValue().start == 0)
      span = spans.firstEntry().getValue();
    if (span == null)
    {
      if (index <= lastFragment || index <= 0) return null;
      int start = snapshot.length();
      Span previous = spans.get(lastFragment);
      // A newly indexed fragment may complete the tail of the full snapshot.
      // Keep characters already anchored to a preceding fragment.
      if (previous != null && text.startsWith(snapshot.substring(previous.end)))
        start = previous.end;
      spans.put(index, new Span(start, start + text.length()));
      lastFragment = index;
      snapshot = snapshot.substring(0, start) + text;
      return snapshot;
    }

    int previousEnd = span.end;
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
