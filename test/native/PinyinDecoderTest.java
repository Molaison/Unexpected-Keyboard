package juloo.keyboard2.pinyin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Exercises the actual bundled dictionary and JNI, without an Android mock. */
public final class PinyinDecoderTest
{
  private static void check(boolean value, String message)
  {
    if (!value) throw new AssertionError(message);
  }

  private static int find(PinyinDecoder decoder, String candidate)
  {
    for (int i = 0; i < decoder.getCandidateCount(); i++)
      if (candidate.equals(decoder.getCandidate(i))) return i;
    throw new AssertionError("Missing candidate: " + candidate + " for " + decoder.getPinyin());
  }

  private static void first(PinyinDecoder decoder, String spelling, String expected)
  {
    decoder.reset();
    check(decoder.search(spelling) > 0, "No candidates for " + spelling);
    String actual = decoder.getCandidate(0);
    System.out.println(spelling + " => " + actual + " (" + decoder.getCandidateCount() + " candidates)");
    check(expected.equals(actual), spelling + ": expected " + expected + ", got " + actual);
  }

  public static void main(String[] args) throws Exception
  {
    Path user = Files.createTempDirectory(Paths.get(args[1]), "user-").resolve("dictionary.dat");
    long start = System.nanoTime();
    try (PinyinDecoder decoder = new PinyinDecoder(args[0], user.toString()))
    {
      first(decoder, "nihao", "你好");
      first(decoder, "zhongguo", "中国");
      first(decoder, "woshizhongguoren", "我是中国人");
      first(decoder, "xi'an", "西安");
      first(decoder, "lvse", "绿色");
      first(decoder, "ren gong zhi neng".replace(" ", ""), "人工智能");
      first(decoder, "zhonghuarenmingongheguo", "中华人民共和国");
      decoder.reset();
      decoder.search("lve");
      find(decoder, "略");
      decoder.reset();
      decoder.search("nve");
      find(decoder, "虐");

      first(decoder, "nihaozhongguo", "你好中国");
      int phrase = find(decoder, "你好");
      check(phrase != 0, "Partial phrase should be separate from the whole sentence");
      decoder.choose(phrase);
      check(decoder.getFixedLength() == 2, "Partial selection did not fix two syllables");
      check(decoder.getCandidate(0).equals("你好中国"), "Fixed prefix lost");
      decoder.cancelLastChoice();
      check(decoder.getFixedLength() == 0, "Cancel selection did not restore editing");
      decoder.delete(decoder.getPinyin().length() - 1, false, false);
      check(decoder.getPinyin().equals("nihaozhonggu"), "Backspace deleted the wrong character");

      decoder.reset();
      decoder.search("nihao");
      decoder.choose(find(decoder, "尼"));
      decoder.choose(find(decoder, "豪"));
      check(decoder.getCandidate(0).equals("尼豪"), "Custom phrase selection failed");
      decoder.flush();
      check(Files.size(user) > 0, "User dictionary was not persisted");
      decoder.reset();
      String[] predictions = decoder.predict("中国");
      check(predictions.length > 0, "Expected following-word predictions");
      System.out.println("中国 predictions: " + predictions.length + ", first: " + predictions[0]);
      decoder.reset();
      check(decoder.search("") == 0, "Empty spelling has candidates");
      try { decoder.search("NIHAO"); throw new AssertionError("Uppercase input not rejected"); }
      catch (IllegalArgumentException expected) {}
      try { decoder.getCandidate(-1); throw new AssertionError("Negative index not rejected"); }
      catch (IllegalArgumentException expected) {}
      try { decoder.search(new String(new char[40]).replace('\0', 'a')); throw new AssertionError("Overflow not rejected"); }
      catch (IllegalArgumentException expected) {}
      try { new PinyinDecoder(args[0], user.toString()); throw new AssertionError("Second owner not rejected"); }
      catch (IllegalStateException expected) {}
    }
    try (PinyinDecoder decoder = new PinyinDecoder(args[0], user.toString()))
    {
      decoder.search("nihao");
      find(decoder, "尼豪");
      decoder.reset();
      long size = Files.size(user);
      decoder.setLearningEnabled(false);
      decoder.search("nihao");
      decoder.choose(find(decoder, "泥"));
      decoder.choose(find(decoder, "郝"));
      decoder.flush();
      check(Files.size(user) == size, "Private editor modified the user vocabulary");
    }
    try { new PinyinDecoder("/nonexistent/pinyin.dat", user.toString()); throw new AssertionError("Missing dictionary accepted"); }
    catch (IllegalStateException expected) {}
    try (PinyinDecoder decoder = new PinyinDecoder(args[0], user.toString()))
    {
      decoder.search("nihao");
      find(decoder, "你好");
    }
    Files.delete(user);
    Files.delete(user.getParent());
    System.out.printf("Real decoder/JNI tests passed in %.3f s%n", (System.nanoTime() - start) / 1e9);
  }
}
