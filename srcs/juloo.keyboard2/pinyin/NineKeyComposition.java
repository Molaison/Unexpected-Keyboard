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

/** Bounded phonetic beam plus an exact, memory-mapped nine-key word index.
 * Raw keys are retained independently of the decoder's mutable search workspace.
 * All speculative searches disable learning; only an explicit choice can learn. */
public final class NineKeyComposition implements ChineseComposition
{
  private static final String[] LETTERS = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
  private static final int BEAM = 48;
  private static final int CANDIDATE_PATHS = 24;
  private static final int PER_PATH = 32;
  private static final int MAX_CANDIDATES = 256;
  private static final int IMPOSSIBLE = 1000000;
  private static final String INITIALS = " b c ch d f g h j k l m n p q r s sh t w x y z zh ";
  private final PinyinDecoder decoder;
  private final NineKeyLexicon lexicon;
  private final Set<String> syllables = new HashSet<>();
  private final Set<String> prefixes = new HashSet<>();
  private final List<Fixed> fixed = new ArrayList<>();
  private final List<Candidate> candidates = new ArrayList<>();
  private final Map<String, List<Path>> cache = new LinkedHashMap<String, List<Path>>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, List<Path>> e)
    { return size() > PinyinDecoder.MAX_PINYIN_LENGTH + 1; }
  };
  private String raw = "";
  private String fixedText = "";
  private String constraint = "";
  private int fixedEnd;
  private boolean dirty = true;
  private List<Path> paths = Collections.emptyList();

  private static final class Fixed
  {
    final String text;
    final int end;
    Fixed(String text, int end) { this.text = text; this.end = end; }
  }

  private static final class Path
  {
    final String spelling;
    final float score;
    Path(String spelling, float score) { this.spelling = spelling; this.score = score; }
  }

  private static final class Candidate
  {
    final String text;
    final String spelling;
    final int index;
    final int consumed;
    final float score;
    String display;
    Candidate(String text, String spelling, int index, int consumed, float score)
    {
      this.text = text; this.spelling = spelling; this.index = index;
      this.consumed = consumed; this.score = score;
      this.display = spelling;
    }
  }

  public NineKeyComposition(PinyinDecoder decoder) { this(decoder, null); }

  public NineKeyComposition(PinyinDecoder decoder, NineKeyLexicon lexicon)
  {
    this.decoder = decoder;
    this.lexicon = lexicon;
    for (String value : decoder.getSpellings())
    {
      String s = value.toLowerCase(Locale.ROOT);
      syllables.add(s);
      for (int i = 1; i <= s.length(); i++) prefixes.add(s.substring(0, i));
    }
  }

  public static String digits(String spelling)
  {
    StringBuilder out = new StringBuilder();
    for (char c : spelling.toLowerCase(Locale.ROOT).toCharArray())
    {
      if (c == '\'') { out.append(c); continue; }
      int key = 2;
      while (key <= 9 && LETTERS[key].indexOf(c) < 0) key++;
      if (key > 9) throw new IllegalArgumentException("Not a pinyin letter: " + c);
      out.append((char)('0' + key));
    }
    return out.toString();
  }

  public boolean isEmpty() { return raw.isEmpty(); }
  public boolean isFull() { return raw.length() >= PinyinDecoder.MAX_PINYIN_LENGTH; }
  public String getSpelling() { return raw; }
  public String getComposingText() { return fixedText + resolvedSpelling(); }
  public String getDisplayText()
  {
    prepare();
    if (candidates.isEmpty()) return getComposingText();
    return fixedText + candidates.get(0).display;
  }

  private String resolvedSpelling()
  {
    prepare();
    if (!candidates.isEmpty()) return candidates.get(0).spelling;
    if (!paths.isEmpty()) return paths.get(0).spelling;
    return raw.substring(fixedEnd); // Unparseable input remains recoverable.
  }

  private static String formatSpelling(String spelling, int[] starts)
  {
    StringBuilder out = new StringBuilder();
    int begin = 0;
    for (int i = 1; i < starts.length; i++)
    {
      int end = Math.min(spelling.length(), starts[i]);
      if (end <= begin) continue;
      String part = spelling.substring(begin, end).replace("'", "");
      if (out.length() > 0) out.append(' ');
      out.append(part);
      begin = end;
    }
    if (begin < spelling.length())
    {
      if (out.length() > 0) out.append(' ');
      out.append(spelling.substring(begin).replace("'", ""));
    }
    return out.toString();
  }
  public int getCandidateCount() { prepare(); return candidates.size(); }
  public String getCandidate(int index) { prepare(); return candidates.get(index).text; }

  /** The visible Pinyin control exposes these dictionary-backed readings. */
  public List<String> getSpellings()
  {
    prepare();
    List<String> result = new ArrayList<>();
    if (!candidates.isEmpty()) result.add(candidates.get(0).spelling);
    for (Path path : paths)
      if (result.size() < CANDIDATE_PATHS && !result.contains(path.spelling)) result.add(path.spelling);
    return result;
  }

  public void constrainSpelling(int index)
  {
    List<String> choices = getSpellings();
    if (index < 0 || index >= choices.size()) return;
    constraint = choices.get(index);
    cache.clear();
    dirty = true;
  }

  public void append(char key)
  {
    if (isFull()) throw new IllegalStateException("Commit nine-key input before appending");
    if (!((key >= '2' && key <= '9') || (key >= 'a' && key <= 'z') || key == '\''))
      throw new IllegalArgumentException("Nine-key input accepts 2-9, letters and separators");
    raw += key;
    dirty = true;
  }

  public void backspace()
  {
    if (isEmpty()) return;
    if (raw.length() == fixedEnd && !fixed.isEmpty())
    {
      fixed.remove(fixed.size() - 1);
      fixedText = "";
      for (Fixed f : fixed) fixedText += f.text;
      fixedEnd = fixed.isEmpty() ? 0 : fixed.get(fixed.size() - 1).end;
      constraint = "";
      cache.clear();
    }
    else
    {
      raw = raw.substring(0, raw.length() - 1);
      int remaining = raw.length() - fixedEnd;
      if (constraint.length() > remaining) constraint = constraint.substring(0, remaining);
    }
    dirty = true;
    if (raw.isEmpty()) reset();
  }

  public String select(int index)
  {
    prepare();
    Candidate c = candidates.get(index);
    if (c.index < 0)
    {
      // Direct index entries cover the complete remaining spelling. Never replay
      // their index into the native decoder, whose candidates are a different set.
      String result = fixedText + c.text;
      reset();
      return result;
    }
    boolean learning = decoder.isLearningEnabled();
    decoder.setLearningEnabled(false);
    try
    {
      decoder.reset();
      decoder.search(c.spelling);
      if (c.index >= decoder.getCandidateCount() || !c.text.equals(decoder.getCandidate(c.index)))
        throw new IllegalStateException("Nine-key candidate changed during replay");
    }
    finally { decoder.setLearningEnabled(learning); }
    decoder.choose(c.index);
    int[] starts = decoder.getSpellingStarts();
    int consumed = starts[decoder.getFixedLength()];
    if (consumed <= 0 || consumed > raw.length() - fixedEnd)
      throw new IllegalStateException("Nine-key choice did not consume a valid prefix");
    fixedEnd += consumed;
    fixedText += c.text;
    fixed.add(new Fixed(c.text, fixedEnd));
    constraint = "";
    cache.clear();
    dirty = true;
    decoder.reset();
    if (fixedEnd < raw.length()) return "";
    String result = fixedText;
    reset();
    return result;
  }

  public String acceptBest()
  {
    StringBuilder result = new StringBuilder();
    // Each selection consumes at least one raw key; the loop cannot exceed 39.
    for (int i = 0; !isEmpty() && i < PinyinDecoder.MAX_PINYIN_LENGTH; i++)
    {
      prepare();
      if (candidates.isEmpty()) { result.append(acceptRaw()); break; }
      result.append(select(0));
    }
    if (!isEmpty()) result.append(acceptRaw());
    return result.toString();
  }

  public String acceptRaw()
  {
    String result = getComposingText();
    reset();
    return result;
  }

  public void reset()
  {
    decoder.reset();
    raw = ""; fixedText = ""; constraint = ""; fixedEnd = 0;
    fixed.clear(); candidates.clear(); cache.clear(); paths = Collections.emptyList();
    dirty = true;
  }

  /** Minimum phonetic cost: complete syllables, optional initials, and an
   * unfinished final syllable. Keeping that final prefix prevents pruning
   * 'zhon' before the user can finish 'zhong'. */
  private int phoneticCost(String input, boolean incomplete)
  {
    int n = input.length();
    int[] costs = new int[n + 1];
    java.util.Arrays.fill(costs, IMPOSSIBLE);
    costs[0] = 0;
    for (int i = 0; i < n; i++)
    {
      if (costs[i] == IMPOSSIBLE) continue;
      if (input.charAt(i) == '\'')
      {
        if (i > 0 && input.charAt(i - 1) != '\'') costs[i + 1] = Math.min(costs[i + 1], costs[i]);
        continue;
      }
      for (int j = i + 1; j <= Math.min(n, i + 6); j++)
      {
        String s = input.substring(i, j);
        int penalty = syllables.contains(s) ? 800 : INITIALS.contains(" " + s + " ") ? 3300 : IMPOSSIBLE;
        if (incomplete && j == n && prefixes.contains(s)) penalty = Math.min(penalty, 1000);
        if (penalty < IMPOSSIBLE) costs[j] = Math.min(costs[j], costs[i] + penalty);
      }
    }
    return costs[n];
  }

  private List<Path> searchPaths(String keys)
  {
    List<Path> hit = cache.get(keys);
    if (hit != null) return hit;
    if (keys.isEmpty()) return Collections.singletonList(new Path("", 0));
    List<Path> previous = searchPaths(keys.substring(0, keys.length() - 1));
    char key = keys.charAt(keys.length() - 1);
    String letters = key >= '2' && key <= '9' ? LETTERS[key - '0'] : String.valueOf(key);
    List<Path> next = new ArrayList<>();
    for (Path p : previous)
      for (int i = 0; i < letters.length(); i++)
      {
        String s = p.spelling + letters.charAt(i);
        if (s.length() <= constraint.length() && !constraint.startsWith(s)) continue;
        if (s.length() > constraint.length() && !s.startsWith(constraint)) continue;
        int cost = phoneticCost(s, true);
        if (cost == IMPOSSIBLE) continue;
        decoder.reset();
        int count = decoder.search(s);
        // An incomplete syllable can temporarily have no native candidate.
        float lexical = count == 0 ? 9000 : decoder.getCandidateScore(0)
          / Math.max(1, decoder.getCandidate(0).length());
        next.add(new Path(s, lexical + cost));
      }
    Collections.sort(next, (a, b) -> {
      int c = Float.compare(a.score, b.score);
      return c == 0 ? a.spelling.compareTo(b.spelling) : c;
    });
    if (next.size() > BEAM) next.subList(BEAM, next.size()).clear();
    cache.put(keys, next);
    return next;
  }

  private void prepare()
  {
    if (!dirty) return;
    candidates.clear();
    if (isEmpty() || fixedEnd == raw.length()) { paths = Collections.emptyList(); dirty = false; return; }
    boolean learning = decoder.isLearningEnabled();
    decoder.setLearningEnabled(false);
    try
    {
      String remaining = raw.substring(fixedEnd);
      paths = new ArrayList<>(searchPaths(remaining));
      List<NineKeyLexicon.Entry> direct = lexicon == null ? Collections.emptyList()
          : lexicon.lookup(remaining, constraint, 128);
      // Exact lexical hits rescue pronunciations discarded by the incremental
      // beam. Query a bounded number natively as well, preserving native learning.
      List<Path> rescued = new ArrayList<>();
      Set<String> seenReadings = new HashSet<>();
      for (NineKeyLexicon.Entry e : direct)
        if (rescued.size() < 12 && seenReadings.add(e.spelling))
          rescued.add(new Path(e.spelling, e.score));
      for (Path path : paths) if (seenReadings.add(path.spelling)) rescued.add(path);
      paths = rescued;
      Map<String, Candidate> unique = new LinkedHashMap<>();
      int pathCount = 0;
      for (Path path : paths)
      {
        if (pathCount++ >= CANDIDATE_PATHS) break;
        decoder.reset();
        int count = decoder.search(path.spelling);
        int[] starts = decoder.getSpellingStarts();
        for (int i = 0; i < Math.min(count, PER_PATH); i++)
        {
          String text = decoder.getCandidate(i);
          if (text.isEmpty() || text.length() >= starts.length) continue;
          int consumed = starts[text.length()];
          if (consumed <= 0) continue;
          int spellingCost = phoneticCost(path.spelling.substring(0, consumed), false);
          if (spellingCost == IMPOSSIBLE) continue;
          float score = decoder.getCandidateScore(i) / Math.max(1, text.length()) + spellingCost;
          // Fully decoded words/sentences lead; partial choices remain available.
          if (consumed < remaining.length()) score += 12000 + (remaining.length() - consumed) * 100;
          Candidate c = new Candidate(text, path.spelling, i, consumed, score);
          c.display = formatSpelling(path.spelling, starts);
          Candidate old = unique.get(text);
          if (old == null || c.score < old.score) unique.put(text, c);
        }
      }
      for (NineKeyLexicon.Entry e : direct)
      {
        if (unique.containsKey(e.text)) continue; // Prefer native choice/learning when present.
        Candidate c = new Candidate(e.text, e.spelling, -1, remaining.length(), e.score);
        c.display = e.display;
        unique.put(e.text, c);
      }
      candidates.addAll(unique.values());
      Collections.sort(candidates, (a, b) -> {
        int c = Float.compare(a.score, b.score);
        return c == 0 ? a.text.compareTo(b.text) : c;
      });
      if (candidates.size() > MAX_CANDIDATES) candidates.subList(MAX_CANDIDATES, candidates.size()).clear();
    }
    finally { decoder.reset(); decoder.setLearningEnabled(learning); }
    dirty = false;
  }
}
