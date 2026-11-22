package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete syllable cuts of a letter reading that already matches the raw keys. */
final class NineKeySyllableGraph
{
  private static final int MAX_CUTS = 3;
  private static final int MAX_EDITS = 24;
  private final Set<String> syllables;
  private final Map<String, Set<String>> aliases = new HashMap<>();

  static final class Edit
  {
    private final int start;
    private final int end;
    private final String replacement;

    Edit(int start, int end, String replacement)
    {
      this.start = start;
      this.end = end;
      this.replacement = replacement;
    }

    String replace(String input)
    {
      String before = input.substring(0, start);
      String after = input.substring(end);
      return before + (before.isEmpty() || before.endsWith("'") ? "" : "'") + replacement
          + (after.isEmpty() || after.startsWith("'") ? "" : "'") + after;
    }
  }

  static final class Reading
  {
    final String spelling;
    final String query;
    private final int[] rawEnds;

    Reading(String spelling) { this(spelling, spelling, null); }

    private Reading(String spelling, String query, int[] rawEnds)
    {
      this.spelling = spelling;
      this.query = query;
      this.rawEnds = rawEnds;
    }

    int rawEnd(int decoderEnd)
    {
      return rawEnds == null ? decoderEnd : rawEnds[decoderEnd];
    }

    static Reading withWordEnding(String prefix, NineKeyLexicon.Entry ending)
    {
      return withWordEnding(new Reading(prefix), ending);
    }

    static Reading withWordEnding(Reading prefix, NineKeyLexicon.Entry ending)
    {
      return withWordEnding(prefix, ending.spelling, ending.display);
    }

    static Reading withWordEnding(String prefix, String endingDisplay)
    {
      return withWordEnding(new Reading(prefix), endingDisplay.replace(" ", ""), endingDisplay);
    }

    private static Reading withWordEnding(Reading prefix, String endingSpelling, String endingDisplay)
    {
      String spelling = prefix.spelling + endingSpelling;
      boolean[] cuts = new boolean[spelling.length()];
      for (int i = 0; i < prefix.query.length(); i++)
        if (prefix.query.charAt(i) == '\'') cuts[prefix.rawEnd(i)] = true;
      int raw = prefix.spelling.length();
      cuts[raw] = true;
      for (int i = 0; i < endingDisplay.length(); i++)
      {
        if (endingDisplay.charAt(i) == ' ') cuts[raw] = true;
        else
        {
          while (spelling.charAt(raw) == '\'') raw++;
          raw++;
        }
      }
      return withCuts(spelling, cuts);
    }

    static Reading withCuts(String spelling, boolean[] cuts)
    {
      int[] rawEnds = new int[spelling.length() * 2 + 1];
      StringBuilder query = new StringBuilder();
      for (int i = 0; i < spelling.length(); i++)
      {
        char character = spelling.charAt(i);
        if (cuts[i] && i > 0 && spelling.charAt(i - 1) != '\'' && character != '\'')
        {
          query.append('\'');
          rawEnds[query.length()] = i;
        }
        query.append(character);
        rawEnds[query.length()] = i + 1;
      }
      return new Reading(spelling, query.toString(), rawEnds);
    }
  }

  NineKeySyllableGraph(Set<String> syllables)
  {
    this.syllables = syllables;
    List<String> ordered = new ArrayList<>(syllables);
    Collections.sort(ordered);
    for (String syllable : ordered)
    {
      if (!PinyinSpelling.hasVowel(syllable)) continue;
      for (String typed : PinyinSpelling.fuzzyInitials(syllable)) addAlias(typed, syllable);
      for (String typed : PinyinSpelling.fuzzyFinals(syllable)) addAlias(typed, syllable);
    }
  }

  private void addAlias(String typed, String corrected)
  {
    if (typed.length() < 2 || typed.length() > 6) return;
    String keys = NineKeyComposition.digits(typed);
    if (keys.equals(NineKeyComposition.digits(corrected))) return;
    Set<String> matches = aliases.get(keys);
    if (matches == null)
    {
      matches = new LinkedHashSet<>();
      aliases.put(keys, matches);
    }
    matches.add(corrected);
  }

  List<Edit> edits(String raw)
  {
    List<Edit> result = new ArrayList<>();
    for (int start = 0; start < raw.length(); start++)
      for (int end = start + 2; end <= Math.min(raw.length(), start + 6); end++)
      {
        Set<String> matches = aliases.get(raw.substring(start, end));
        if (matches == null) continue;
        for (String replacement : matches)
        {
          result.add(new Edit(start, end, replacement));
          if (result.size() == MAX_EDITS) return result;
        }
      }
    return result;
  }

  List<Reading> splits(String spelling)
  {
    return splits(spelling, 0);
  }

  List<Reading> splits(String spelling, int firstSyllableEnd)
  {
    int length = spelling.length();
    List<List<Integer>> edges = new ArrayList<>(length + 1);
    for (int i = 0; i <= length; i++) edges.add(new ArrayList<>());
    boolean[] canFinish = new boolean[length + 1];
    canFinish[length] = true;
    for (int start = length - 1; start >= 0; start--)
    {
      List<Integer> ends = edges.get(start);
      if (spelling.charAt(start) == '\'')
      {
        if (start > 0 && spelling.charAt(start - 1) != '\'' && canFinish[start + 1]) ends.add(start + 1);
      }
      else
      {
        for (int end = Math.min(length, start + 6); end > start; end--)
        {
          if (start == 0 && firstSyllableEnd != 0 && end != firstSyllableEnd) continue;
          if (!canFinish[end]) continue;
          String syllable = spelling.substring(start, end);
          if (syllables.contains(syllable) && PinyinSpelling.hasVowel(syllable)) ends.add(end);
        }
      }
      canFinish[start] = !ends.isEmpty();
    }

    List<Reading> result = new ArrayList<>(MAX_CUTS);
    if (canFinish[0]) collect(spelling, edges, 0, new boolean[length], result);
    return result;
  }

  private void collect(String spelling, List<List<Integer>> edges, int start,
      boolean[] cuts, List<Reading> result)
  {
    if (start == spelling.length())
    {
      result.add(Reading.withCuts(spelling, cuts));
      return;
    }
    cuts[start] = true;
    for (int end : edges.get(start))
    {
      collect(spelling, edges, end, cuts, result);
      if (result.size() >= MAX_CUTS) break;
    }
    cuts[start] = false;
  }
}
