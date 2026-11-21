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
import juloo.keyboard2.pinyin.NineKeySyllableGraph.Edit;
import juloo.keyboard2.pinyin.NineKeySyllableGraph.Reading;

/** Bounded phonetic beam plus an exact, memory-mapped nine-key word index.
 * Raw keys are retained independently of the decoder's mutable search workspace.
 * All speculative searches disable learning; only an explicit choice can learn. */
public final class NineKeyComposition implements ChineseComposition
{
  private static final String[] LETTERS = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
  private static final int BEAM = 48;
  private static final int CANDIDATE_PATHS = 24;
  private static final int ALTERNATE_PATHS = 4;
  private static final int ENDING_PREFIXES = 12;
  private static final int ENDING_READINGS = 2;
  private static final int ENDING_WORDS = 8;
  private static final int EARLIER_ENDING_PREFIXES = 4;
  private static final int EARLIER_ENDING_WORDS = 3;
  private static final int SENTENCE_READINGS = 12;
  private static final String SEQUENCE_CUE_KEYS = digits("xian");
  private static final String SEQUENCE_JOIN_KEYS = digits("zai");
  private static final int SEQUENCE_WORD_KEYS = 12;
  private static final int SEQUENCE_ENDINGS = 16;
  private static final int FUZZY_NATIVE_PATHS = 6;
  private static final int PROMOTED_CORRECTIONS = 2;
  private static final int TRANSPOSED_WORDS = 3;
  private static final int PER_PATH = 32;
  private static final int MAX_CANDIDATES = 256;
  private static final int IMPOSSIBLE = 1000000;
  private static final String INITIALS = " b c ch d f g h j k l m n p q r s sh t w x y z zh ";
  private final PinyinDecoder decoder;
  private final NineKeyLexicon lexicon;
  private final Set<String> syllables = new HashSet<>();
  private final Set<String> prefixes = new HashSet<>();
  private final NineKeySyllableGraph syllableGraph;
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
    final Reading reading;
    final int index;
    final float score;
    String display;
    int[] spellingStarts;
    boolean partial;
    boolean corrected;
    Candidate(String text, Reading reading, int index, float score)
    {
      this.text = text;
      this.reading = reading;
      this.index = index;
      this.score = score;
      this.display = reading.spelling;
    }
  }

  private static final class EndingCut
  {
    final int rawEnd;
    final int characters;
    final List<NineKeyLexicon.Entry> words;
    EndingCut(int rawEnd, int characters, List<NineKeyLexicon.Entry> words)
    {
      this.rawEnd = rawEnd;
      this.characters = characters;
      this.words = words;
    }
  }

  private static final Comparator<Candidate> CANDIDATE_ORDER = (a, b) -> {
    int c = Float.compare(a.score, b.score);
    return c == 0 ? a.text.compareTo(b.text) : c;
  };

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
    syllableGraph = new NineKeySyllableGraph(syllables);
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
    if (!candidates.isEmpty() && !candidates.get(0).corrected) return candidates.get(0).reading.spelling;
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
    if (!candidates.isEmpty() && !candidates.get(0).corrected) result.add(candidates.get(0).reading.spelling);
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
      // Whole-word index entries and complete corrections cover every remaining key.
      String result = fixedText + c.text;
      reset();
      return result;
    }
    boolean learning = decoder.isLearningEnabled();
    decoder.setLearningEnabled(false);
    try
    {
      decoder.reset();
      decoder.search(c.reading.query);
      if (c.index >= decoder.getCandidateCount() || !c.text.equals(decoder.getCandidate(c.index)))
        throw new IllegalStateException("Nine-key candidate changed during replay");
    }
    finally { decoder.setLearningEnabled(learning); }
    decoder.choose(c.index);
    int[] starts = decoder.getSpellingStarts();
    int consumed = c.reading.rawEnd(starts[decoder.getFixedLength()]);
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

  /** Minimum phonetic cost to each prefix end. The search beam can also
   * accept an unfinished final syllable, such as 'zhon' before 'zhong'. */
  private int[] phoneticCosts(String input, boolean incomplete)
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
    return costs;
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
        int cost = phoneticCosts(s, true)[s.length()];
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
      List<Reading> alternatives = new ArrayList<>(ALTERNATE_PATHS);
      List<Reading> moreSplits = new ArrayList<>(ALTERNATE_PATHS);
      int pathCount = 0;
      for (Path path : paths)
      {
        if (pathCount++ >= CANDIDATE_PATHS) break;
        int[] starts = addNativeCandidates(unique, new Reading(path.spelling), remaining.length(), false);
        if (alternatives.size() < ALTERNATE_PATHS && remaining.length() < PinyinDecoder.MAX_PINYIN_LENGTH)
        {
          String decoded = formatSpelling(path.spelling, starts);
          boolean firstSplit = true;
          for (Reading split : syllableGraph.splits(path.spelling))
          {
            if (split.query.length() > PinyinDecoder.MAX_PINYIN_LENGTH
                || decoded.equals(split.query.replace('\'', ' ')) || !seenReadings.add(split.query))
              continue;
            // Give a different spelling a chance before trying more cuts of this one.
            if (firstSplit)
            {
              alternatives.add(split);
              firstSplit = false;
            }
            else if (moreSplits.size() < ALTERNATE_PATHS)
              moreSplits.add(split);
            if (alternatives.size() >= ALTERNATE_PATHS) break;
          }
        }
      }
      int spare = ALTERNATE_PATHS - alternatives.size();
      alternatives.addAll(moreSplits.subList(0, Math.min(spare, moreSplits.size())));
      for (Reading reading : alternatives) addNativeCandidates(unique, reading, remaining.length(), false);
      for (NineKeyLexicon.Entry e : direct)
      {
        if (unique.containsKey(e.text)) continue; // Prefer native choice/learning when present.
        Candidate c = new Candidate(e.text, new Reading(e.spelling), -1, e.score);
        c.display = e.display;
        unique.put(e.text, c);
      }
      candidates.addAll(unique.values());
      Collections.sort(candidates, CANDIDATE_ORDER);
      if (constraint.isEmpty() && remaining.length() >= 4)
        addCorrections(remaining, lexicon != null && direct.isEmpty());
      if (lexicon != null && direct.isEmpty() && remaining.length() >= 12
          && constraint.isEmpty() && !unique.isEmpty())
        addWordEndings(remaining, unique, seenReadings);
      if (lexicon != null && direct.isEmpty() && remaining.length() >= 12
          && candidates.size() < MAX_CANDIDATES
          && fixedEnd == 0 && constraint.isEmpty() && remaining.indexOf('\'') < 0)
      {
        addSequentialSentence(remaining, unique);
        if (remaining.startsWith(SEQUENCE_CUE_KEYS) && !candidates.isEmpty()
            && candidates.size() < MAX_CANDIDATES)
          addWordSequence(remaining, candidates.get(0));
      }
      if (candidates.size() > MAX_CANDIDATES) candidates.subList(MAX_CANDIDATES, candidates.size()).clear();
    }
    finally { decoder.reset(); decoder.setLearningEnabled(learning); }
    dirty = false;
  }

  private void addSequentialSentence(String raw, Map<String, Candidate> unique)
  {
    Candidate fallback = null, indexed = null, exactContinuation = null;
    String indexedClause = null;
    int readings = 0;
    for (Candidate anchor : unique.values())
    {
      if (anchor.index != 0 || anchor.partial || anchor.text.length() < 6) continue;
      if (readings++ == SENTENCE_READINGS) break;
      char first = anchor.text.charAt(0);
      boolean nativeCue = first == '先' || first == '去';
      if ((!nativeCue && !anchor.display.startsWith("xian ")) || anchor.text.indexOf('在', 3) < 0) continue;
      String[] syllables = anchor.display.split(" ");
      if (syllables.length != anchor.text.length()) continue;
      for (int pivot = 3; pivot < syllables.length - 2; pivot++)
      {
        if (anchor.text.charAt(pivot) != '在' || !"zai".equals(syllables[pivot])) continue;
        String middleReading = syllableReading(syllables, 1, pivot);
        String middle = lexicon.firstReading(middleReading);
        if (middle.length() != pivot - 1) continue;
        String clause = (nativeCue ? first : '先') + middle + '再';
        float score = Math.nextUp(anchor.score);
        Candidate conflict = conflictingSentencePrefix(clause, score, anchor.text.length());
        if (conflict != null)
        {
          // Scores from edited key sequences cannot rule out an exact indexed continuation.
          if (conflict.corrected && exactContinuation == null && anchor == candidates.get(0) && nativeCue
              && anchor.text.regionMatches(1, middle, 0, middle.length()))
            exactContinuation = indexedEnding(raw, anchor, syllables, pivot, clause, score);
          continue;
        }
        int changes = 0;
        for (int i = 0; i < middle.length(); i++) if (middle.charAt(i) != anchor.text.charAt(i + 1)) changes++;
        String tailReading = syllableReading(syllables, pivot + 1, syllables.length);
        if (indexed == null || score < indexed.score)
        {
          Candidate candidate = changes <= 1 ? indexedEnding(raw, anchor, syllables, pivot, clause, score) : null;
          if (candidate == null && nativeCue)
          {
            // A dictionary compound can support both words on either side of 再.
            String compound = lexicon.firstReading(middleReading + " " + tailReading);
            if (compound.length() == anchor.text.length() - 2 && compound.startsWith(middle))
            {
              candidate = new Candidate(clause + compound.substring(middle.length()), anchor.reading, -1, score);
              candidate.display = anchor.display;
            }
          }
          if (candidate != null)
          {
            indexed = candidate;
            indexedClause = clause;
          }
        }
        String tail = anchor.text.substring(pivot + 1);
        if (!nativeCue || changes > 1 || (fallback != null && fallback.score <= score)
            || !tail.equals(lexicon.firstReading(tailReading))) continue;
        fallback = new Candidate(clause + tail, anchor.reading, -1, score);
        fallback.display = anchor.display;
      }
    }
    // Prefer an indexed continuation for the same first clause.
    Candidate alternative = indexed != null && (fallback == null || indexed.score <= fallback.score
        || fallback.text.startsWith(indexedClause)) ? indexed : fallback;
    if (alternative == null && exactContinuation == null) return;
    Map<String, Candidate> alternatives = new LinkedHashMap<>();
    if (alternative != null)
    {
      alternatives.put(alternative.text, alternative);
      // Keep a different, previously offered ending for the same first clause.
      if (alternative == indexed && fallback != null && fallback.text.startsWith(indexedClause)
          && !fallback.text.equals(indexed.text) && candidates.size() + 1 < MAX_CANDIDATES)
        alternatives.put(fallback.text, fallback);
    }
    if (exactContinuation != null && candidates.size() + alternatives.size() < MAX_CANDIDATES)
      alternatives.put(exactContinuation.text, exactContinuation);
    mergeWordEndings(alternatives);
  }

  private Candidate indexedEnding(String raw, Candidate anchor, String[] syllables, int pivot,
      String clause, float score)
  {
    int cut = anchor.reading.rawEnd(anchor.spellingStarts[pivot]);
    for (NineKeyLexicon.Entry ending : lexicon.lookupWords(raw.substring(cut), ENDING_WORDS))
    {
      if (!ending.text.startsWith("再") || !ending.display.startsWith("zai ")
          || ending.text.length() != anchor.text.length() - pivot) continue;
      Reading reading = Reading.withWordEnding(anchor.reading.spelling.substring(0, cut), ending);
      Candidate candidate = new Candidate(clause + ending.text.substring(1), reading, -1, score);
      candidate.display = syllableReading(syllables, 0, pivot) + " " + ending.display;
      return candidate;
    }
    return null;
  }

  private Candidate conflictingSentencePrefix(String prefix, float score, int length)
  {
    Candidate corrected = null;
    for (Candidate candidate : candidates)
      if (!candidate.partial && candidate.text.startsWith(prefix)
          && (candidate.score <= score || candidate.text.length() > length))
      {
        if (!candidate.corrected) return candidate;
        corrected = candidate;
      }
    return corrected;
  }

  private void addWordSequence(String raw, Candidate first)
  {
    if (first.index != 0 || first.partial || first.text.startsWith("先")) return;
    int start = SEQUENCE_CUE_KEYS.length();
    int joinLength = SEQUENCE_JOIN_KEYS.length();
    int limit = Math.min(raw.length() - joinLength - 4, start + SEQUENCE_WORD_KEYS);
    for (int cut = raw.indexOf(SEQUENCE_JOIN_KEYS, start + 4); cut >= 0 && cut <= limit;
         cut = raw.indexOf(SEQUENCE_JOIN_KEYS, cut + 1))
    {
      int tail = cut + joinLength;
      if (raw.length() - tail > SEQUENCE_WORD_KEYS) continue;
      NineKeyLexicon.Entry middle = lexicon.firstWord(raw.substring(start, cut));
      if (middle == null) continue;
      String prefix = "xian " + middle.display;
      String firstClause = "先" + middle.text;
      if (!decodeCompleteDisplay(prefix).equals(firstClause)) continue;
      String clause = firstClause + '再';
      float score = Math.nextUp(first.score);
      Candidate known = conflictingSentencePrefix(clause, score, first.text.length());
      if (known != null && !known.corrected) continue;

      Map<String, Candidate> alternatives = new LinkedHashMap<>();
      for (NineKeyLexicon.Entry word : lexicon.lookupWords(raw.substring(cut), ENDING_WORDS))
      {
        if (!word.text.startsWith("再") || !word.display.startsWith("zai ") || word.text.length() < 3) continue;
        // Trust a complete sequential reading already decoded with this indexed ending.
        if (hasSequentialEnding(word.text)) return;
        String display = prefix + " " + word.display;
        Candidate candidate = new Candidate(firstClause + word.text,
            new Reading(display.replace(" ", "")), -1, score);
        candidate.display = display;
        alternatives.put(candidate.text, candidate);
        break;
      }
      if (alternatives.isEmpty())
        for (NineKeyLexicon.Entry word : lexicon.lookupWords(raw.substring(tail), SEQUENCE_ENDINGS))
        {
          if (alternatives.size() == 2 || candidates.size() + alternatives.size() == MAX_CANDIDATES) break;
          String display = prefix + " zai " + word.display;
          // Both a complete word and the native sequential reading must agree at this cut.
          if (!decodeCompleteDisplay(display).startsWith(clause)
              || !decodeCompleteDisplay(word.display).equals(word.text)) continue;
          Candidate candidate = new Candidate(clause + word.text,
              new Reading(display.replace(" ", "")), -1, score);
          candidate.display = display;
          alternatives.put(candidate.text, candidate);
          score = Math.nextUp(score);
        }
      if (!alternatives.isEmpty())
      {
        mergeWordEndings(alternatives);
        return;
      }
    }
  }

  private boolean hasSequentialEnding(String ending)
  {
    for (Candidate candidate : candidates)
      if (!candidate.partial && !candidate.corrected && candidate.text.startsWith("先")
          && candidate.text.length() >= ending.length() + 3 && candidate.text.endsWith(ending)) return true;
    return false;
  }

  private String decodeCompleteDisplay(String display)
  {
    String query = display.replace(' ', '\'');
    if (query.length() > PinyinDecoder.MAX_PINYIN_LENGTH) return "";
    decoder.reset();
    if (decoder.search(query) == 0) return "";
    int[] starts = decoder.getSpellingStarts();
    if (starts.length == 0 || starts[starts.length - 1] != query.length()
        || !formatSpelling(query, starts).equals(display)) return "";
    return decoder.getCandidate(0);
  }

  private String syllableReading(String[] syllables, int start, int end)
  {
    StringBuilder reading = new StringBuilder(syllables[start]);
    for (int i = start + 1; i < end; i++) reading.append(' ').append(syllables[i]);
    return reading.toString();
  }

  private void addWordEndings(String raw, Map<String, Candidate> unique, Set<String> seenReadings)
  {
    // Pick a dictionary word ending at a boundary the native decoder already found.
    Candidate best = Collections.min(unique.values(), CANDIDATE_ORDER);
    int separator = raw.lastIndexOf('\'');
    EndingCut preferred = null;
    List<EndingCut> explicitCuts = separator < 0 ? Collections.emptyList() : new ArrayList<>();
    for (int i = best.spellingStarts.length - 1; i > 0; i--)
    {
      int cut = best.reading.rawEnd(best.spellingStarts[i]);
      while (cut < raw.length() && raw.charAt(cut) == '\'') cut++;
      int tailLength = raw.length() - cut;
      if (cut < 3 || tailLength < 4 || tailLength > 12) continue;
      List<NineKeyLexicon.Entry> endings = lexicon.lookupWords(raw.substring(cut), ENDING_WORDS);
      if (endings.isEmpty()) continue;
      EndingCut choice = new EndingCut(cut, i, endings);
      if (preferred == null || endings.get(0).score < preferred.words.get(0).score) preferred = choice;
      // Recover the prefix after an explicit separator without guessing a different tail reading.
      if (separator >= 0 && cut > separator && !best.partial
          && best.display.endsWith(" " + endings.get(0).display)) explicitCuts.add(choice);
    }
    if (preferred == null) return;
    // Reuse the cached beam before the ending to recover spellings that were pruned later.
    List<Path> prefixes = searchPaths(raw.substring(0, preferred.rawEnd));
    Set<String> distinct = new HashSet<>();
    Map<String, Candidate> recovered = new LinkedHashMap<>();
    for (NineKeyLexicon.Entry ending : preferred.words)
    {
      if (!distinct.add(ending.display)) continue;
      for (int j = 0; j < Math.min(ENDING_PREFIXES, prefixes.size()); j++)
      {
        Reading reading = Reading.withWordEnding(prefixes.get(j).spelling, ending);
        if (reading.query.length() <= PinyinDecoder.MAX_PINYIN_LENGTH
            && !seenReadings.contains(reading.spelling) && seenReadings.add(reading.query))
          addNativeCandidates(recovered, reading, raw.length(), true);
      }
      if (distinct.size() >= ENDING_READINGS) break;
    }
    Candidate homophone = homophoneEnding(best, preferred.rawEnd, preferred.characters, preferred.words);
    if (homophone != null && !unique.containsKey(homophone.text) && !recovered.containsKey(homophone.text))
      recovered.put(homophone.text, homophone);
    EndingCut earlier = null;
    for (EndingCut cut : explicitCuts)
      if (cut.rawEnd < preferred.rawEnd
          && (earlier == null || cut.words.get(0).score < earlier.words.get(0).score)) earlier = cut;
    if (earlier != null) recoverEarlierEnding(raw, earlier, recovered, seenReadings);
    mergeWordEndings(recovered);
  }

  private void recoverEarlierEnding(String raw, EndingCut cut, Map<String, Candidate> recovered,
      Set<String> seenReadings)
  {
    NineKeyLexicon.Entry ending = cut.words.get(0);
    List<Path> prefixes = searchPaths(raw.substring(0, cut.rawEnd));
    for (int j = 0; j < Math.min(EARLIER_ENDING_PREFIXES, prefixes.size()); j++)
    {
      Reading reading = Reading.withWordEnding(prefixes.get(j).spelling, ending);
      if (reading.query.length() > PinyinDecoder.MAX_PINYIN_LENGTH || !seenReadings.add(reading.query)) continue;
      addNativeCandidates(recovered, reading, raw.length(), true);
      if (decoder.getCandidateCount() == 0) continue;
      Candidate decoded = recovered.get(decoder.getCandidate(0));
      if (decoded == null || decoded.reading != reading || !decoded.text.endsWith(ending.text)
          || !decoded.display.endsWith(" " + ending.display)) continue;
      int alternatives = 0;
      for (NineKeyLexicon.Entry word : cut.words)
      {
        if (!word.display.equals(ending.display) || word.text.length() != ending.text.length()
            || word.text.equals(ending.text)) continue;
        String text = decoded.text.substring(0, decoded.text.length() - ending.text.length()) + word.text;
        float difference = (word.frequencyCost - ending.frequencyCost) * word.text.length() / decoded.text.length();
        Candidate replacement = new Candidate(text, reading, -1, Math.max(Math.nextUp(decoded.score),
            decoded.score + difference));
        replacement.display = decoded.display;
        if (!recovered.containsKey(text)) recovered.put(text, replacement);
        if (++alternatives >= EARLIER_ENDING_WORDS) break;
      }
    }
  }

  private Candidate homophoneEnding(Candidate best, int prefixEnd, int prefixCharacters,
      List<NineKeyLexicon.Entry> endings)
  {
    if (best.partial || prefixCharacters >= best.text.length()) return null;
    String suffix = best.reading.spelling.substring(prefixEnd);
    String original = best.text.substring(prefixCharacters);
    String replacement = null;
    for (NineKeyLexicon.Entry ending : endings)
    {
      if (ending.text.length() != original.length() || !suffix.equals(ending.spelling)
          || !best.display.endsWith(" " + ending.display)) continue;
      if (ending.text.equals(original))
      {
        // Both endings must be words in the index, with the replacement ranked higher there.
        if (replacement == null) return null;
        String text = best.text.substring(0, prefixCharacters) + replacement;
        // Keep the original sentence ahead of its same-reading dictionary alternative.
        Candidate alternative = new Candidate(text, best.reading, -1, Math.nextUp(best.score));
        alternative.display = best.display;
        return alternative;
      }
      if (replacement == null) replacement = ending.text;
    }
    return null;
  }

  private void mergeWordEndings(Map<String, Candidate> recovered)
  {
    for (Candidate candidate : candidates) recovered.remove(candidate.text);
    if (recovered.isEmpty()) return;

    List<Candidate> firstPage = new ArrayList<>(candidates.subList(0,
        Math.min(CANDIDATE_PAGE_SIZE, candidates.size())));
    List<Candidate> ranked = new ArrayList<>(recovered.values());
    Collections.sort(ranked, CANDIDATE_ORDER);
    int insertion = 0;
    for (Candidate addition : ranked)
    {
      while (insertion < candidates.size()
          && CANDIDATE_ORDER.compare(candidates.get(insertion), addition) <= 0) insertion++;
      candidates.add(insertion++, addition);
    }

    // Full-sentence alternatives must not hide the partial choices already offered.
    for (int i = 0; i < firstPage.size(); i++)
    {
      Candidate previous = firstPage.get(i);
      if (!previous.partial) continue;
      int shifted = candidates.indexOf(previous);
      if (shifted > i) candidates.add(i, candidates.remove(shifted));
    }
  }

  private int[] addNativeCandidates(Map<String, Candidate> unique, Reading reading, int rawLength,
      boolean onlyBestComplete)
  {
    decoder.reset();
    int count = decoder.search(reading.query);
    int[] starts = decoder.getSpellingStarts();
    int[] spellingCosts = null;
    for (int i = 0; i < Math.min(count, onlyBestComplete ? 1 : PER_PATH); i++)
    {
      String text = decoder.getCandidate(i);
      if (text.isEmpty() || text.length() >= starts.length) continue;
      int queryEnd = starts[text.length()];
      int consumed = reading.rawEnd(queryEnd);
      if (consumed <= 0 || consumed > rawLength || (onlyBestComplete && consumed != rawLength)) continue;
      if (spellingCosts == null) spellingCosts = phoneticCosts(reading.spelling, false);
      int spellingCost = spellingCosts[consumed];
      if (spellingCost == IMPOSSIBLE) continue;
      float score = decoder.getCandidateScore(i) / Math.max(1, text.length()) + spellingCost;
      // Fully decoded words/sentences lead; partial choices remain available.
      boolean partial = consumed < rawLength;
      if (partial) score += 12000 + (rawLength - consumed) * 100;
      Candidate old = unique.get(text);
      if (old != null && old.score <= score) continue;
      Candidate candidate = new Candidate(text, reading, i, score);
      candidate.display = formatSpelling(reading.query, starts);
      candidate.spellingStarts = starts;
      candidate.partial = partial;
      unique.put(text, candidate);
    }
    return starts;
  }

  private void addCorrections(String remaining, boolean allowTransposition)
  {
    for (int i = 0; i < remaining.length(); i++)
      if (remaining.charAt(i) < '2' || remaining.charAt(i) > '9') return;
    boolean tryTransposition = allowTransposition && remaining.length() >= 5;
    List<Edit> edits = syllableGraph.edits(remaining);
    if (edits.isEmpty() && !tryTransposition) return;
    Map<String, Candidate> corrected = new LinkedHashMap<>();
    if (lexicon != null)
      for (Edit edit : edits)
        for (NineKeyLexicon.Entry entry : lexicon.lookupCorrections(edit.replace(remaining), 6))
          addCorrection(corrected, entry.text, entry.spelling, entry.display, entry.frequencyCost);
    if (!edits.isEmpty() && corrected.isEmpty()) addCorrectedSentences(corrected, edits);
    List<Candidate> ranked = new ArrayList<>(corrected.values());
    Collections.sort(ranked, (a, b) -> {
      int order = Double.compare(a.score, b.score);
      return order == 0 ? a.text.compareTo(b.text) : order;
    });

    int slot = Math.min(1, candidates.size());
    int promoted = 0;
    for (Candidate correction : ranked)
    {
      if (promoted == PROMOTED_CORRECTIONS) break;
      if (promoteCorrection(correction, slot))
      {
        slot++;
        promoted++;
      }
    }
    if (tryTransposition)
    {
      List<Candidate> transposed = transposedCandidates(remaining);
      if (!transposed.isEmpty() && candidateIndex(transposed.get(0).text) == candidates.size())
        candidates.add(slot, transposed.get(0));
      ranked.addAll(transposed);
    }
    if (ranked.isEmpty()) return;
    Set<String> offered = new HashSet<>();
    for (Candidate candidate : candidates) offered.add(candidate.text);
    for (Candidate correction : ranked)
      if (candidates.size() < MAX_CANDIDATES && offered.add(correction.text)) candidates.add(correction);
  }

  private boolean promoteCorrection(Candidate correction, int slot)
  {
    int index = candidateIndex(correction.text);
    if (index < slot) return false;
    if (index < candidates.size()) candidates.remove(index);
    candidates.add(slot, correction);
    return true;
  }

  private int candidateIndex(String text)
  {
    int index = 0;
    while (index < candidates.size() && !candidates.get(index).text.equals(text)) index++;
    return index;
  }

  private List<Candidate> transposedCandidates(String remaining)
  {
    List<Candidate> result = new ArrayList<>();
    for (NineKeyLexicon.Entry entry : lexicon.lookupTranspositions(remaining, TRANSPOSED_WORDS))
      result.add(completeCorrection(entry.text, entry.spelling, entry.display, entry.score));
    if (result.isEmpty() && !candidates.isEmpty() && !candidates.get(0).corrected)
    {
      Candidate sentence = transposedSuffix(remaining, candidates.get(0));
      if (sentence != null) result.add(sentence);
    }
    return result;
  }

  private Candidate transposedSuffix(String remaining, Candidate primary)
  {
    String[] syllables = primary.display.split(" ");
    // Preserve up to two complete leading syllables and recover the rest as a word.
    for (int count = Math.min(2, syllables.length - 1); count >= 1; count--)
    {
      String prefix = count == 1 ? syllables[0] : syllables[0] + "'" + syllables[1];
      String letters = prefix.replace("'", "");
      if (letters.isEmpty() || letters.length() + 5 > remaining.length()
          || !remaining.startsWith(digits(letters)) || syllableGraph.splits(prefix).isEmpty()) continue;
      String suffix = remaining.substring(letters.length());
      if (lexicon.firstWord(suffix) != null) continue;
      List<NineKeyLexicon.Entry> corrections = lexicon.lookupTranspositions(suffix, 1);
      if (corrections.isEmpty()) continue;
      String query = prefix + "'" + corrections.get(0).display.replace(' ', '\'');
      if (query.length() > PinyinDecoder.MAX_PINYIN_LENGTH) continue;
      decoder.reset();
      if (decoder.search(query) == 0) continue;
      String text = decoder.getCandidate(0);
      int[] starts = decoder.getSpellingStarts();
      if (text.length() >= 2 && text.length() < starts.length && starts[text.length()] == query.length())
        return completeCorrection(text, primary.reading.spelling, formatSpelling(query, starts),
            decoder.getCandidateScore(0) / text.length());
    }
    return null;
  }

  private void addCorrectedSentences(Map<String, Candidate> corrected, List<Edit> edits)
  {
    Set<String> queried = new HashSet<>();
    for (int i = 0; i < Math.min(paths.size(), CANDIDATE_PATHS); i++)
    {
      String spelling = paths.get(i).spelling;
      for (Edit edit : edits)
      {
        String query = edit.replace(spelling);
        if (query.length() > PinyinDecoder.MAX_PINYIN_LENGTH || syllableGraph.splits(query).isEmpty()
            || !queried.add(query)) continue;
        decoder.reset();
        int count = decoder.search(query);
        int[] starts = decoder.getSpellingStarts();
        for (int j = 0; j < Math.min(count, 6); j++)
        {
          String text = decoder.getCandidate(j);
          if (text.length() < starts.length && starts[text.length()] == query.length())
            addCorrection(corrected, text, spelling, formatSpelling(query, starts),
                decoder.getCandidateScore(j) / Math.max(1, text.length()));
        }
        if (queried.size() == FUZZY_NATIVE_PATHS) return;
      }
    }
  }

  private static void addCorrection(Map<String, Candidate> corrected, String text, String spelling,
      String display, float score)
  {
    if (text.length() < 2) return;
    Candidate previous = corrected.get(text);
    if (previous != null && previous.score <= score) return;
    corrected.put(text, completeCorrection(text, spelling, display, score));
  }

  private static Candidate completeCorrection(String text, String spelling, String display, float score)
  {
    Candidate candidate = new Candidate(text, new Reading(spelling), -1, score);
    candidate.display = display;
    candidate.corrected = true;
    return candidate;
  }
}
