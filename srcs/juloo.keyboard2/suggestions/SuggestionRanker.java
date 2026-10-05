package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable ranking independent of Android and the dictionary's native workspace. */
public final class SuggestionRanker
{
  public static final int EXACT = 0, PREFIX = 1, CORRECTION = 2;

  public static final class Hit
  {
    public final String word;
    public final int frequency;
    public final int kind;
    public Hit(String word, int frequency, int kind)
    { this.word = word; this.frequency = frequency; this.kind = kind; }
  }

  public static List<String> rank(String typed, List<Hit> hits, int limit)
  {
    Map<String, Hit> unique = new LinkedHashMap<>();
    for (Hit h : hits)
    {
      if (h.word == null || h.word.isEmpty()) continue;
      String key = h.word.toLowerCase(Locale.ROOT);
      Hit old = unique.get(key);
      if (old == null || score(h, typed) > score(old, typed)) unique.put(key, h);
    }
    List<Hit> ordered = new ArrayList<>(unique.values());
    Collections.sort(ordered, (a, b) -> {
      int cmp = Integer.compare(score(b, typed), score(a, typed));
      if (cmp == 0) cmp = Integer.compare(a.kind, b.kind);
      return cmp == 0 ? a.word.compareTo(b.word) : cmp;
    });
    List<String> result = new ArrayList<>();
    for (Hit h : ordered)
    {
      if (result.size() >= Math.max(0, limit)) break;
      String value = matchCase(typed, h.word);
      if (!result.contains(value)) result.add(value);
    }
    return result;
  }

  private static int score(Hit h, String typed)
  {
    // A recognized word always leads; speculative corrections never replace it.
    if (h.kind == EXACT) return 100000;
    int extra = Math.max(0, h.word.codePointCount(0, h.word.length())
      - typed.codePointCount(0, typed.length()));
    return Math.max(0, Math.min(15, h.frequency)) * 8
      - (h.kind == CORRECTION ? 28 : 0) - Math.min(12, extra) * 2;
  }

  public static String matchCase(String typed, String candidate)
  {
    if (typed.isEmpty() || candidate.isEmpty()) return candidate;
    boolean letter = false, allUpper = true;
    for (int i = 0; i < typed.length();)
    {
      int c = typed.codePointAt(i); i += Character.charCount(c);
      if (Character.isLetter(c)) { letter = true; allUpper &= Character.isUpperCase(c); }
    }
    if (letter && allUpper) return candidate.toUpperCase(Locale.ROOT);
    if (Character.isUpperCase(typed.codePointAt(0)))
    {
      int end = Character.charCount(candidate.codePointAt(0));
      return candidate.substring(0, end).toUpperCase(Locale.ROOT) + candidate.substring(end);
    }
    return candidate;
  }

  private SuggestionRanker() {}
}
