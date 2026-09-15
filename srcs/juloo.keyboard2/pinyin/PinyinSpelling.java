package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded spelling alternatives; the actual vocabulary and ranking stay native. */
final class PinyinSpelling
{
  private static final int BEAM = 24;
  private static final int MAX_VARIANTS = 48;
  private final Map<String, List<Syllable>> aliases = new LinkedHashMap<String, List<Syllable>>(256, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, List<Syllable>> entry)
    {
      return size() > 2048;
    }
  };
  private final Set<String> fullSpellings = new HashSet<>();
  private final List<String> orderedSpellings;
  private final String[] neighborKeys = new String[26];
  private static final String INITIALS = " b c ch d f g h j k l m n p q r s sh t w x y z zh ";

  static final class Variant
  {
    final String text;
    final int cost;
    final int changes;
    final int abbreviations;
    final int syllables;

    Variant(String text, int cost, int changes, int abbreviations, int syllables)
    {
      this.text = text;
      this.cost = cost;
      this.changes = changes;
      this.abbreviations = abbreviations;
      this.syllables = syllables;
    }
  }

  private static final Comparator<Variant> ORDER = (a, b) -> {
    int result = Integer.compare(a.cost, b.cost);
    if (result == 0) result = Integer.compare(a.abbreviations, b.abbreviations);
    if (result == 0) result = Integer.compare(a.syllables, b.syllables);
    return result == 0 ? a.text.compareTo(b.text) : result;
  };

  private static final class Syllable
  {
    final String text;
    final int cost;
    final boolean lastOnly;

    Syllable(String text, int cost, boolean lastOnly)
    {
      this.text = text;
      this.cost = cost;
      this.lastOnly = lastOnly;
    }
  }

  PinyinSpelling(String[] spellings)
  {
    for (String spelling : spellings) fullSpellings.add(spelling.toLowerCase(Locale.ROOT));
    orderedSpellings = new ArrayList<>(fullSpellings);
    Collections.sort(orderedSpellings);
    for (char c = 'a'; c <= 'z'; c++) neighborKeys[c - 'a'] = neighbors(c);
  }

  /** Derive only spellings the user actually entered. Eager construction of
   * every possible typo made the first key take seconds on a software device. */
  private List<Syllable> syllables(String input)
  {
    List<Syllable> cached = aliases.get(input);
    if (cached != null) return cached;
    List<Syllable> result = new ArrayList<>();
    aliases.put(input, result);
    if (fullSpellings.contains(input) || INITIALS.contains(" " + input + " "))
      add(input, input, 0, false);
    if (input.length() < 2) return result;

    // Common fuzzy pronunciations add alternatives rather than rewriting the
    // input. The rules follow the same families as Rime Ice speller/algebra.
    for (String initial : fuzzyInitials(input))
    {
      add(input, initial, 1, false);
      for (String both : fuzzyFinals(initial)) add(input, both, 2, false);
    }
    for (String ending : fuzzyFinals(input)) add(input, ending, 1, false);
    add(input, input.replace('u', 'v'), 1, false);
    add(input, input.replace('v', 'u'), 1, false);
    for (int i = 0; i < input.length(); i++)
    {
      char c = input.charAt(i);
      if (i > 0 && input.charAt(i - 1) == c)
        add(input, input.substring(0, i) + input.substring(i + 1), 2, false);
      for (char neighbor : neighborKeys[c - 'a'].toCharArray())
        add(input, input.substring(0, i) + neighbor + input.substring(i + 1), 2, false);
      if (i + 1 < input.length() && c != input.charAt(i + 1))
        add(input, input.substring(0, i) + input.charAt(i + 1) + c + input.substring(i + 2), 2, false);
    }
    if (input.length() < 6)
      for (int i = 0; i <= input.length(); i++)
        for (char missing = 'a'; missing <= 'z'; missing++)
          add(input, input.substring(0, i) + missing + input.substring(i), 2, false);
    for (String spelling : orderedSpellings)
      if (spelling.length() > input.length() && spelling.startsWith(input)) add(input, spelling, 1, true);
    return result;
  }

  List<Variant> alternatives(String input)
  {
    if (input.length() < 2 || !hasVowel(input)) return Collections.emptyList();
    List<List<Variant>> paths = new ArrayList<>();
    for (int i = 0; i <= input.length(); i++) paths.add(new ArrayList<>());
    paths.get(0).add(new Variant("", 0, 0, 0, 0));
    for (int start = 0; start < input.length(); start++)
    {
      List<Variant> previous = paths.get(start);
      trim(previous, BEAM);
      if (previous.isEmpty()) continue;
      if (input.charAt(start) == '\'')
      {
        if (start > 0)
          for (Variant v : previous)
            addPath(paths.get(start + 1), new Variant(v.text + "'", v.cost,
                  v.changes, v.abbreviations, v.syllables));
        continue;
      }
      for (int end = start + 1; end <= Math.min(input.length(), start + 7); end++)
      {
        if (input.charAt(end - 1) == '\'') break;
        List<Syllable> matches = syllables(input.substring(start, end));
        if (matches.isEmpty()) continue;
        for (Variant v : previous)
          for (Syllable syllable : matches)
          {
            if (syllable.lastOnly && end != input.length()) continue;
            int changes = v.changes + (syllable.cost == 0 ? 0 : 1);
            int cost = v.cost + syllable.cost;
            if (changes > 2 || cost > 4) continue;
            String text = v.text + syllable.text;
            if (text.length() > PinyinDecoder.MAX_PINYIN_LENGTH) continue;
            addPath(paths.get(end), new Variant(text, cost, changes,
                  v.abbreviations + (fullSpellings.contains(syllable.text) ? 0 : 1),
                  v.syllables + 1));
          }
      }
    }
    List<Variant> result = paths.get(input.length());
    for (int i = result.size() - 1; i >= 0; i--)
      if (result.get(i).cost == 0 || result.get(i).text.equals(input)) result.remove(i);
    trim(result, MAX_VARIANTS);
    return result;
  }

  boolean isFullSpelling(String input)
  {
    boolean[] matched = new boolean[input.length() + 1];
    matched[0] = true;
    for (int start = 0; start < input.length(); start++)
    {
      if (!matched[start]) continue;
      if (input.charAt(start) == '\'' && start > 0) matched[start + 1] = true;
      for (int end = start + 1; end <= Math.min(input.length(), start + 6); end++)
      {
        String syllable = input.substring(start, end);
        if (fullSpellings.contains(syllable) && hasVowel(syllable)) matched[end] = true;
      }
    }
    return matched[input.length()];
  }

  private static boolean hasVowel(String text)
  {
    for (int i = 0; i < text.length(); i++)
      if ("aeiouv".indexOf(text.charAt(i)) != -1) return true;
    return false;
  }

  private void add(String alias, String syllable, int cost, boolean lastOnly)
  {
    if (cost != 0 && (alias.length() < 2 || alias.equals(syllable) || !fullSpellings.contains(syllable))) return;
    List<Syllable> list = aliases.get(alias);
    if (list == null) { list = new ArrayList<>(); aliases.put(alias, list); }
    for (int i = 0; i < list.size(); i++)
      if (list.get(i).text.equals(syllable) && list.get(i).lastOnly == lastOnly)
      {
        Syllable old = list.get(i);
        if (cost < old.cost || (cost == old.cost && old.lastOnly && !lastOnly))
          list.set(i, new Syllable(syllable, cost, lastOnly));
        return;
      }
    list.add(new Syllable(syllable, cost, lastOnly));
  }

  private static void addPath(List<Variant> paths, Variant variant)
  {
    for (int i = 0; i < paths.size(); i++)
      if (paths.get(i).text.equals(variant.text))
      {
        if (ORDER.compare(variant, paths.get(i)) < 0) paths.set(i, variant);
        return;
      }
    paths.add(variant);
    if (paths.size() > BEAM * 3) trim(paths, BEAM * 2);
  }

  private static void trim(List<Variant> paths, int limit)
  {
    Collections.sort(paths, ORDER);
    if (paths.size() > limit) paths.subList(limit, paths.size()).clear();
  }

  private static List<String> fuzzyInitials(String syllable)
  {
    List<String> result = new ArrayList<>();
    String[][] pairs = {{"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"n", "l"}, {"f", "h"}};
    for (String[] pair : pairs)
      if (syllable.startsWith(pair[0])) result.add(pair[1] + syllable.substring(pair[0].length()));
      else if (syllable.startsWith(pair[1])) result.add(pair[0] + syllable.substring(pair[1].length()));
    return result;
  }

  private static List<String> fuzzyFinals(String syllable)
  {
    List<String> result = new ArrayList<>();
    for (String pair : new String[] {"an", "en", "in"})
      if (syllable.endsWith(pair + "g")) result.add(syllable.substring(0, syllable.length() - 1));
      else if (syllable.endsWith(pair)) result.add(syllable + "g");
    return result;
  }

  private static String neighbors(char letter)
  {
    String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    float[] offsets = {0, 0.5f, 1.5f};
    StringBuilder result = new StringBuilder();
    for (int row = 0; row < rows.length; row++)
    {
      int column = rows[row].indexOf(letter);
      if (column < 0) continue;
      float x = column + offsets[row];
      for (int otherRow = Math.max(0, row - 1); otherRow <= Math.min(2, row + 1); otherRow++)
        for (int other = 0; other < rows[otherRow].length(); other++)
          if (rows[otherRow].charAt(other) != letter && Math.abs(other + offsets[otherRow] - x) <= 1)
            result.append(rows[otherRow].charAt(other));
    }
    return result.toString();
  }
}
