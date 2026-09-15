package juloo.keyboard2.pinyin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PinyinCompositionTest
{
  private static void equal(String expected, String actual)
  {
    if (!expected.equals(actual))
      throw new AssertionError("Expected " + expected + ", got " + actual);
  }

  private static void type(PinyinComposition composition, String text)
  {
    for (char c : text.toCharArray()) composition.append(c);
  }

  public static void main(String[] args) throws Exception
  {
    Path user = Files.createTempDirectory(Paths.get(args[1]), "composition-").resolve("user.dat");
    try (PinyinDecoder decoder = new PinyinDecoder(args[0], user.toString()))
    {
      PinyinComposition composition = new PinyinComposition(decoder);
      type(composition, "nihao");
      equal("nihao", composition.getComposingText());
      equal("nihao", composition.getDisplayText());
      equal("你好", composition.acceptBest());
      equal("", composition.getComposingText());
      type(composition, "zhongguo");
      composition.backspace();
      equal("zhonggu", composition.getComposingText());
      equal("zhonggu", composition.acceptRaw());
      type(composition, "nihaozhongguo");
      int prefix = -1;
      for (int i = 0; i < composition.getCandidateCount(); i++)
        if (composition.getCandidate(i).equals("你好")) { prefix = i; break; }
      if (prefix < 0) throw new AssertionError("Missing partial candidate");
      equal("", composition.select(prefix));
      equal("你好zhongguo", composition.getComposingText());
      equal("中国", composition.getCandidate(0));
      equal("你好zhongguo", composition.acceptRaw());
      type(composition, "nihao'");
      equal("nihao'", composition.acceptRaw());
      type(composition, "nihaovvv");
      String unparsed = composition.getSpelling().substring(decoder.getDecodedLength());
      String chosen = composition.select(0);
      equal("你好", chosen);
      equal(unparsed, composition.getSpelling());
      equal(unparsed, composition.acceptRaw());
      // More than the historical nine-syllable AOSP limit, still within the
      // public 39-letter composition contract. No silently truncated letters.
      type(composition, "nihaonihaonihaonihaonihaonihao");
      equal("nihaonihaonihaonihaonihaonihao", composition.acceptRaw());
      type(composition, "woaizhongguo");
      equal("我爱中国", composition.select(0));
      equal("", composition.getComposingText());

      // Invalid prefixes, repeated separators and the 39-letter boundary must
      // remain editable, even when the decoder can only parse an early prefix.
      java.util.Random random = new java.util.Random(20260915);
      String alphabet = "abcdefghijklmnopqrstuvwxyz'";
      for (int trial = 0; trial < 200; trial++)
      {
        StringBuilder input = new StringBuilder();
        int length = 1 + random.nextInt(PinyinDecoder.MAX_PINYIN_LENGTH);
        for (int i = 0; i < length; i++)
        {
          char letter = alphabet.charAt(random.nextInt(alphabet.length()));
          input.append(letter);
          if (Boolean.getBoolean("pinyin.trace")) System.out.println("EDIT " + trial + ": " + input);
          composition.append(letter);
          equal(input.toString(), composition.getSpelling());
        }
        while (input.length() > 0)
        {
          composition.backspace();
          input.setLength(input.length() - 1);
          equal(input.toString(), composition.getSpelling());
        }
      }
    }
    Files.delete(user);
    Files.delete(user.getParent());
    System.out.println("Real pinyin composition tests passed");
  }
}
