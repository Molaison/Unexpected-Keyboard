package juloo.keyboard2.pinyin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Sorted, read-only word index. No hundreds-of-thousands-of-Strings heap graph. */
public final class NineKeyLexicon
{
  private static final String KEYS = "22233344455566677778889999";
  private final ByteBuffer data;
  private final int count;

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
    Entry(String text, String spelling, String display, float score)
    { this.text = text; this.spelling = spelling; this.display = display; this.score = score; }
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
    // An equal-key range is already frequency/length ordered by the compiler.
    for (int i = lo; i < count && result.size() < limit && compareKey(i, digits) == 0; i++)
    {
      int p = position(i), plen = data.get(p) & 255, tlen = data.get(p + 1) & 255;
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
      float score = (data.getShort(p + 2) & 65535) / (float)Math.max(1, tlen) + 800 * tlen;
      result.add(new Entry(text.toString(), spelling.toString(), display, score));
    }
    return result;
  }
}
