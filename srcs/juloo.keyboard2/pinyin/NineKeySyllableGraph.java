package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Complete syllable cuts of a letter reading that already matches the raw keys. */
final class NineKeySyllableGraph
{
  private static final int MAX_CUTS = 3;
  private final Set<String> syllables;

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
  }

  NineKeySyllableGraph(Set<String> syllables) { this.syllables = syllables; }

  List<Reading> splits(String spelling)
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
      result.add(new Reading(spelling, query.toString(), rawEnds));
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
