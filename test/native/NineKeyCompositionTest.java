package juloo.keyboard2.pinyin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Runs against the production JNI decoder and the actual packaged dictionary. */
public final class NineKeyCompositionTest
{
  private static int checks;
  private static final List<Long> timings = new ArrayList<>();

  private static void check(boolean condition, String message)
  {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static void type(NineKeyComposition c, String spelling)
  {
    for (char key : NineKeyComposition.digits(spelling).toCharArray())
    {
      long start = System.nanoTime();
      c.append(key);
      c.getCandidateCount();
      timings.add(System.nanoTime() - start);
    }
  }

  private static int find(NineKeyComposition c, String text)
  {
    for (int i = 0; i < c.getCandidateCount(); i++) if (text.equals(c.getCandidate(i))) return i;
    throw new AssertionError("Missing candidate " + text + " for " + c.getSpelling());
  }

  public static void main(String[] args) throws Exception
  {
    File user = new File(args[1], "nine-key-test-user.dat");
    Files.deleteIfExists(user.toPath());
    byte[] before;
    try (PinyinDecoder d = new PinyinDecoder(args[0], user.getAbsolutePath()))
    {
      d.setLearningEnabled(false);
      d.flush();
      before = Files.readAllBytes(user.toPath());
      NineKeyComposition c = new NineKeyComposition(d);
      for (String[] example : new String[][] {
        {"nihao", "你好"}, {"zhongguo", "中国"}, {"woaini", "我爱你"},
        {"xiexie", "谢谢"}, {"woshizhongguoren", "我是中国人"},
        {"beijing", "北京"}, {"shanghai", "上海"}, {"nihaozhongguo", "你好中国"}
      })
      {
        type(c, example[0]);
        check(c.getCandidate(0).equals(example[1]), "First candidate for " + example[0]);
        check(c.select(0).equals(example[1]), "Commit " + example[0]);
        check(c.isEmpty(), "Composition must end after full selection");
        check(d.getPinyin().isEmpty(), "Native workspace reset after selection");
        d.predict(example[1]); // Prediction and the next T9 search share a native workspace.
      }
      type(c, "nihaozhongguo");
      check(c.select(find(c, "你好")).isEmpty(), "Partial selection must not commit prematurely");
      check(c.getSpelling().equals("6442694664486"), "Retain exact raw keys separately from visible preedit");
      check(c.getComposingText().equals("你好zhongguo"), "Show matched pinyin after a fixed phrase");
      check(c.select(find(c, "中国")).equals("你好中国"), "Commit fixed prefix plus suffix");

      type(c, "nihaozhongguo");
      c.select(find(c, "你好"));
      for (int i = 0; i < 8; i++) c.backspace();
      check(c.getComposingText().equals("你好"), "Deletion reaches fixed choice");
      c.backspace();
      check(c.getSpelling().equals("64426") && c.getComposingText().equals("nihao"), "Backspace undoes a fixed choice, not a Hanzi fragment");
      c.backspace();
      String matched = c.getComposingText();
      check(c.getSpelling().equals("6442") && matched.matches("[a-z]+"), "Deletion retains raw keys but displays pinyin");
      check(c.acceptRaw().equals(matched), "Enter commits displayed pinyin after deletion");

      type(c, "xi'an");
      check(c.select(find(c, "西安")).equals("西安"), "Explicit syllable separator");
      type(c, "nv");
      check(c.select(find(c, "女")).equals("女"), "V represents umlaut U");
      type(c, "lv");
      check(c.select(find(c, "绿")).equals("绿"), "Ambiguous umlaut candidate remains accessible");

      type(c, "ming");
      List<String> options = c.getSpellings();
      check(options.contains("ming"), "Offer alternative readings");
      c.constrainSpelling(options.indexOf("ming"));
      check(c.getSpellings().size() == 1 && c.getSpellings().get(0).equals("ming"), "Constrain reading");
      check(c.getCandidate(0).equals("明"), "Selected reading changes Hanzi ordering");
      c.backspace();
      type(c, "g");
      check(c.getSpellings().contains("ming"), "Backspace and append after reading choice");
      c.reset();

      // Explicit corner letters disambiguate without a separate Latin mode.
      for (char key : "nihao".toCharArray()) c.append(key);
      check(c.acceptBest().equals("你好"), "Exact corner-letter spelling");
      c.append('\''); c.append('2');
      check(c.acceptBest().equals("'2"), "No parse preserves raw keys");

      for (int i = 0; i < PinyinDecoder.MAX_PINYIN_LENGTH; i++) c.append('2');
      check(c.isFull(), "Composition limit");
      boolean rejected = false;
      try { c.append('2'); } catch (IllegalStateException expected) { rejected = true; }
      check(rejected, "No unbounded composition");
      check(c.acceptRaw().length() == PinyinDecoder.MAX_PINYIN_LENGTH, "No truncation at limit");

      Random random = new Random(20261005L);
      for (int trial = 0; trial < 100; trial++)
      {
        for (int i = 0, n = 1 + random.nextInt(15); i < n; i++)
        {
          c.append((char)('2' + random.nextInt(8)));
          check(c.getCandidateCount() <= 256, "Bounded candidate set");
        }
        String raw = c.getSpelling();
        if (c.getCandidateCount() > 0)
        {
          String committed = c.select(random.nextInt(c.getCandidateCount()));
          if (committed.isEmpty()) check(c.getComposingText().codePointAt(0) >= 0x3400 && c.getSpelling().equals(raw), "Partial choice fixes Hanzi without discarding raw keys");
        }
        c.acceptRaw();
        check(!d.isLearningEnabled(), "Speculation and selection respect private mode");
      }
      c.reset();
      d.flush();
    }
    check(Arrays.equals(before, Files.readAllBytes(user.toPath())), "Private mode leaves user vocabulary byte-identical");
    Files.delete(user.toPath());
    timings.sort(Long::compare);
    System.out.println("NINE_KEY_OK checks=" + checks + " key_queries=" + timings.size()
      + " host_p95_ms=" + timings.get((timings.size() * 95) / 100) / 1e6
      + " host_max_ms=" + timings.get(timings.size() - 1) / 1e6);
  }
}
