package juloo.keyboard2.pinyin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sorted, read-only word index. No hundreds-of-thousands-of-Strings heap graph. */
public final class NineKeyLexicon
{
  private static final String KEYS = "22233344455566677778889999";
  private final ByteBuffer data;
  private final int count;
  private enum Order { INDEX, WORDS, FREQUENCY }

  public NineKeyLexicon(FileChannel channel, long offset, long length) throws IOException
  {
    if (length < 12 || length > Integer.MAX_VALUE) throw new IOException("Invalid nine-key index size");
    data = channel.map(FileChannel.MapMode.READ_ONLY, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    if (data.getInt(0) != 0x314c4b4e) throw new IOException("Unknown nine-key index format");
    count = data.getInt(4);
    if (count < 0 || count > 1500000 || 8L + (count + 1L) * 4 > length
        || data.getInt(8) != 8 + (count + 1) * 4 || data.getInt(8 + count * 4) != length)
      throw new IOException("Damaged nine-key index offsets");
  }

  public int size() { return count; }

  public static final class Entry
  {
    public final String text, spelling, display;
    public final float score;
    final float frequencyCost;
    Entry(String text, String spelling, String display, float frequencyCost)
    {
      this.text = text;
      this.spelling = spelling;
      this.display = display;
      this.frequencyCost = frequencyCost;
      this.score = frequencyCost + 800 * text.length();
    }
  }

  private int position(int index) { return data.getInt(8 + index * 4); }

  private int compareKey(int index, String key)
  {
    int p = position(index), n = data.get(p) & 255, k = 0;
    for (int i = 0; i < n; i++)
    {
      char c = (char)(data.get(p + 4 + i) & 255);
      if (c == ' ') continue;
      if (k == key.length()) return 1;
      int diff = KEYS.charAt(c - 'a') - key.charAt(k++);
      if (diff != 0) return diff;
    }
    return k - key.length();
  }

  /** Exact numeric spelling lookup, then respect explicit letters/separators. */
  public List<Entry> lookup(String raw, String constraint, int limit)
  {
    return lookup(raw, constraint, limit, Order.INDEX);
  }

  List<Entry> lookupCorrections(String raw, int limit)
  {
    return lookup(raw, "", limit, Order.FREQUENCY);
  }

  Entry firstWord(String digits)
  {
    List<Entry> matches = lookupWords(digits, 1);
    return matches.isEmpty() ? null : matches.get(0);
  }

  List<Entry> lookupWords(String raw, int limit)
  {
    return lookup(raw, "", limit, Order.WORDS);
  }

  List<Entry> lookupTranspositions(String digits, int limit)
  {
    Map<String, Entry> matches = new LinkedHashMap<>();
    char[] keys = digits.toCharArray();
    for (int i = 0; i + 1 < keys.length; i++)
    {
      char left = keys[i], right = keys[i + 1];
      if (left == right) continue;
      keys[i] = right;
      keys[i + 1] = left;
      for (Entry entry : lookup(new String(keys), "", limit, Order.WORDS))
      {
        Entry previous = matches.get(entry.text);
        if (previous == null || entry.score < previous.score) matches.put(entry.text, entry);
      }
      keys[i] = left;
      keys[i + 1] = right;
    }
    List<Entry> ranked = new ArrayList<>(matches.values());
    ranked.sort(Comparator.comparingDouble((Entry entry) -> entry.score).thenComparing(entry -> entry.text));
    if (ranked.size() > limit) ranked.subList(limit, ranked.size()).clear();
    return ranked;
  }

  private List<Entry> lookup(String raw, String constraint, int limit, Order order)
  {
    List<Entry> result = new ArrayList<>();
    if (raw.isEmpty() || limit <= 0) return result;
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < raw.length(); i++)
    {
      char c = raw.charAt(i);
      if (c == '\'') continue;
      if (c >= 'a' && c <= 'z') c = KEYS.charAt(c - 'a');
      if (c < '2' || c > '9') return result;
      key.append(c);
    }
    String digits = key.toString();
    int lo = 0, hi = count;
    while (lo < hi)
    {
      int mid = (lo + hi) >>> 1;
      if (compareKey(mid, digits) < 0) lo = mid + 1;
      else hi = mid;
    }
    boolean byFrequency = order == Order.FREQUENCY;
    // The compiler's word score includes length, unlike the phonetic correction order.
    for (int i = lo; i < count && (byFrequency || result.size() < limit) && compareKey(i, digits) == 0; i++)
    {
      int p = position(i), plen = data.get(p) & 255, tlen = data.get(p + 1) & 255;
      if (order != Order.INDEX && tlen < 2) continue;
      float frequencyCost = (data.getShort(p + 2) & 65535) / (float)Math.max(1, tlen);
      int insertion = result.size();
      if (byFrequency)
      {
        while (insertion > 0 && frequencyCost < result.get(insertion - 1).frequencyCost) insertion--;
        if (insertion >= limit) continue;
      }
      byte[] bytes = new byte[plen];
      for (int j = 0; j < plen; j++) bytes[j] = data.get(p + 4 + j);
      String display = new String(bytes, StandardCharsets.US_ASCII);
      String flat = display.replace(" ", "");
      StringBuilder spelling = new StringBuilder();
      int letter = 0;
      boolean match = true;
      for (int j = 0; j < raw.length(); j++)
      {
        char c = raw.charAt(j);
        if (c == '\'')
        {
          // A separator must coincide with a real syllable boundary.
          int boundary = 0;
          boolean atBoundary = false;
          for (String syllable : display.split(" "))
          { boundary += syllable.length(); if (boundary == letter && boundary < flat.length()) atBoundary = true; }
          if (!atBoundary) { match = false; break; }
          spelling.append(c);
        }
        else
        {
          char actual = flat.charAt(letter++);
          if (c >= 'a' && c <= 'z' && c != actual) { match = false; break; }
          spelling.append(actual);
        }
      }
      if (!match || !spelling.toString().startsWith(constraint)) continue;
      StringBuilder text = new StringBuilder();
      for (int j = 0; j < tlen; j++) text.append(data.getChar(p + 4 + plen + j * 2));
      result.add(insertion, new Entry(text.toString(), spelling.toString(), display, frequencyCost));
      if (result.size() > limit) result.remove(limit);
    }
    return result;
  }
}
