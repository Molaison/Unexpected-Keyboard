package juloo.keyboard2.pinyin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercises the production correction path against the packaged dictionary. */
public final class PinyinToleranceTest
{
  private static final List<Long> keyTimes = new ArrayList<>();

  private static void equal(String expected, String actual)
  {
    if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  private static void type(PinyinComposition input, String text)
  {
    for (char letter : text.toCharArray())
    {
      long start = System.nanoTime();
      input.append(letter);
      input.getCandidateCount(); // Same query made by the Android candidate row.
      keyTimes.add(System.nanoTime() - start);
    }
  }

  private static int candidate(PinyinComposition input, String text)
  {
    for (int i = 0; i < input.getCandidateCount(); i++)
      if (input.getCandidate(i).equals(text)) return i;
    return -1;
  }

  public static void main(String[] args) throws Exception
  {
    Path directory = Files.createTempDirectory(Paths.get(args[1]), "tolerance-");
    Path user = directory.resolve("user.dat");
    try (PinyinDecoder decoder = new PinyinDecoder(args[0], user.toString()))
    {
      PinyinComposition input = new PinyinComposition(decoder);
      String[][] cases = {
        {"nh", "你好"}, {"zg", "中国"},
        {"nihoa", "你好"}, {"zhognguo", "中国"}, {"nihso", "你好"},
        {"nihaoo", "你好"}, {"nnihao", "你好"}, {"nhao", "你好"},
        {"zongguo", "中国"}, {"beijin", "北京"}, {"sengri", "生日"},
        {"woaizongguo", "我爱中国"}, {"woaizhognguo", "我爱中国"},
        {"zhon", "中"}, {"lue", "略"}
      };
      int missing = 0;
      for (String[] test : cases)
      {
        input.reset();
        type(input, test[0]);
        equal(test[0], input.getComposingText());
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < Math.min(8, input.getCandidateCount()); i++)
          preview.append(i == 0 ? "" : " / ").append(input.getCandidate(i));
        int index = candidate(input, test[1]);
        System.out.println(test[0] + " => " + preview + " (target index " + index + ")");
        if (index < 0 || index >= 16) { missing++; continue; }
        equal(test[1], input.select(index));
        equal("", input.getComposingText());
      }
      if (missing != 0) throw new AssertionError(missing + " expected tolerance candidates missing from the first 16");

      // Mixed full spelling and initials remain ambiguous. Select the first
      // phrase, then disambiguate the abbreviated suffix in the actual decoder.
      type(input, "nihaozg");
      equal("", input.select(candidate(input, "你好")));
      equal("你好zg", input.getComposingText());
      equal("你好中国", input.select(candidate(input, "中国")));

      for (String[] exact : new String[][] {{"woaizhongguo", "我爱中国"}, {"shanghai", "上海"},
          {"nihao", "你好"}, {"zhongguo", "中国"}, {"woshizhongguoren", "我是中国人"}})
      {
        type(input, exact[0]);
        equal(exact[1], input.getCandidate(0));
        equal(exact[1], input.acceptBest());
      }

      type(input, "nihoa");
      equal("nihoa", input.acceptRaw());
      type(input, "zhognguo");
      input.backspace();
      input.getCandidateCount();
      equal("zhogngu", input.getComposingText());
      equal("zhogngu", input.acceptRaw());

      type(input, "nihaozhognguo");
      equal("", input.select(candidate(input, "你好")));
      equal("你好zhognguo", input.getComposingText());
      equal("你好中国", input.select(candidate(input, "中国")));

      type(input, "nihaozhognguo");
      equal("", input.select(candidate(input, "你好")));
      for (int i = 0; i < "zhognguo".length(); i++)
      {
        input.backspace();
        input.getCandidateCount();
      }
      input.backspace(); // Undo the fixed prefix, preserving its raw letters.
      input.getCandidateCount();
      equal("nihao", input.acceptRaw());

      decoder.flush();
      byte[] before = Files.readAllBytes(user);
      decoder.setLearningEnabled(false);
      type(input, "nihaozhognguo");
      equal("", input.select(candidate(input, "你好")));
      equal("你好中国", input.select(candidate(input, "中国")));
      decoder.flush();
      if (!Arrays.equals(before, Files.readAllBytes(user)))
        throw new AssertionError("Correction or prefix replay modified a no-learning dictionary");

      java.util.Random random = new java.util.Random(20260915);
      for (int trial = 0; trial < 40; trial++)
      {
        String text = cases[random.nextInt(cases.length)][0] + "'" + cases[random.nextInt(cases.length)][0];
        type(input, text);
        while (!input.isEmpty())
        {
          input.backspace();
          input.getCandidateCount();
        }
      }
    }
    Files.delete(user);
    Files.delete(directory);
    Collections.sort(keyTimes);
    System.out.printf("Real tolerance tests passed; %d key queries, host p95 %.1f ms, max %.1f ms%n",
        keyTimes.size(), keyTimes.get(keyTimes.size() * 95 / 100) / 1e6,
        keyTimes.get(keyTimes.size() - 1) / 1e6);
  }
}
