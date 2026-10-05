package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  boolean _enabled;
  private String _lastWord;
  private Cdict _lastDictionary, _lastEmojiDictionary;
  private static final int SEARCH_COUNT = 12;

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  public void started()
  {
    _enabled = _config.suggestions_enabled && _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void currently_typed_word(String word)
  {
    if (!_enabled)
      return;
    if (word.length() < 2 || _config.current_dictionary == null)
      clear();
    else
      query_suggestions(word);
    _callback.set_suggestions(this);
  }

  void clear()
  {
    _lastWord = null;
    _lastDictionary = null;
    _lastEmojiDictionary = null;
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
  }

  int query_suggestions(String word)
  {
    Cdict dict = _config.current_dictionary;
    if (word.equals(_lastWord) && dict == _lastDictionary && _config.emoji_dictionary == _lastEmojiDictionary)
      return count;
    clear();
    String normalized = apply_substitutions(word);
    Cdict.Result r = dict.find(normalized);
    List<SuggestionRanker.Hit> pool = new ArrayList<>();
    if (r.found)
      pool.add(new SuggestionRanker.Hit(dict.word(r.index), dict.freq(r.index), SuggestionRanker.EXACT));
    for (int id : dict.suffixes(r, SEARCH_COUNT))
      pool.add(new SuggestionRanker.Hit(dict.word(id), dict.freq(id), SuggestionRanker.PREFIX));
    // Short prefixes are too ambiguous for useful edit-distance correction.
    if (normalized.codePointCount(0, normalized.length()) >= 3)
      for (int id : dict.distance(normalized, 1, SEARCH_COUNT))
        pool.add(new SuggestionRanker.Hit(dict.word(id), dict.freq(id), SuggestionRanker.CORRECTION));
    List<String> ranked = SuggestionRanker.rank(word, pool, MAX_COUNT);
    count = ranked.size();
    for (int i = 0; i < count; i++) suggestions[i] = ranked.get(i);
    emoji_suggestion = query_emoji(normalized);
    _lastWord = word;
    _lastDictionary = dict;
    _lastEmojiDictionary = _config.emoji_dictionary;
    return count;
  }

  static void capitalize_results(String[] s, int count)
  {
    for (int i = 0; i < count; i++)
      s[i] = SuggestionRanker.matchCase("Word", s[i]);
  }

  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
