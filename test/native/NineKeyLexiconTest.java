package juloo.keyboard2.pinyin;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** Production mmap index and real native decoder, including old-beam recall comparison. */
public final class NineKeyLexiconTest
{
  private static int checks;
  private static void check(boolean value, String message)
  { checks++; if (!value) throw new AssertionError(message); }
  private static void type(NineKeyComposition c, String spelling)
  { for (char key : NineKeyComposition.digits(spelling).toCharArray()) c.append(key); }
  private static int rank(NineKeyComposition c, String text)
  { for (int i = 0; i < c.getCandidateCount(); i++) if (c.getCandidate(i).equals(text)) return i; return -1; }

  public static void main(String[] args) throws Exception
  {
    String user = args[1] + "/lexicon-user.dat";
    Files.deleteIfExists(Paths.get(user));
    try (FileInputStream f = new FileInputStream(args[2]);
        PinyinDecoder d = new PinyinDecoder(args[0], user))
    {
      NineKeyLexicon lex = new NineKeyLexicon(f.getChannel(), 0, f.getChannel().size());
      check(lex.size() == 882585, "Pinned production index count");
      d.setLearningEnabled(false); d.flush();
      byte[] privateBefore = Files.readAllBytes(Paths.get(user));
      NineKeyComposition c = new NineKeyComposition(d, lex);
      for (String[] pair : new String[][] {{"nihao","你好"},{"zhongguo","中国"},
          {"woaini","我爱你"},{"xiexie","谢谢"},{"woshizhongguoren","我是中国人"},
          {"beijing","北京"},{"shanghai","上海"},{"rengongzhineng","人工智能"}})
      {
        type(c, pair[0]);
        check(c.getCandidate(0).equals(pair[1]), "Common phrase remains first: " + pair[0]);
        check(c.getComposingText().equals(pair[0]), "Editor preedit is pinyin, not phone digits");
        check(c.getDisplayText().replace(" ", "").equals(pair[0]), "Display syllables cover the spelling");
        check(c.select(0).equals(pair[1]), "Commit exact candidate");
      }
      type(c, "nihaozhongguo");
      int prefix = rank(c, "你好"); check(prefix >= 0, "Partial prefix available");
      check(c.select(prefix).isEmpty(), "Prefix selection does not commit early");
      check(c.getComposingText().equals("你好zhongguo"), "Hanzi prefix plus matched pinyin");
      check(c.acceptBest().equals("你好中国"), "Finish selected prefix");
      check(lex.lookup("x'i'an", "", 32).isEmpty(), "Reject invalid syllable boundary");
      List<NineKeyLexicon.Entry> xian = lex.lookup("94'26", "xi'an", 32);
      check(xian.stream().anyMatch(e -> e.text.equals("西安")), "Literal separator respected");
      check(lex.lookup("64426", "ming", 32).isEmpty(), "Reading constraint is not ignored");
      type(c,"ming");
      check(c.getSpellings().contains("ming"), "Reading picker retains alternatives");
      c.constrainSpelling(c.getSpellings().indexOf("ming"));
      check(c.getComposingText().equals("ming"), "Readings update editor preedit");
      check(c.acceptBest().equals("明"), "Selected reading commits matching Hanzi");
      int total=0, oldHits=0, newHits=0;
      for (String row : Files.readAllLines(Paths.get(args[3])))
      {
        if (row.startsWith("#") || row.isEmpty()) continue;
        String[] fields = row.split("\t");
        String spelling=fields[0], word=fields[1];
        List<NineKeyLexicon.Entry> exact=lex.lookup(spelling,"",256);
        check(exact.stream().anyMatch(e -> e.text.equals(word)), "Exact lexical recall: "+spelling);
        NineKeyComposition old = new NineKeyComposition(d);
        type(old,spelling); int before=rank(old,word); old.reset();
        type(c,spelling); int after=rank(c,word);
        if (before>=0) oldHits++; if(after>=0) newHits++;
        if(after>=0) check(c.select(after).equals(word), "Direct or native selection returns displayed word");
        c.reset(); total++;
      }
      d.flush();
      check(Arrays.equals(privateBefore, Files.readAllBytes(Paths.get(user))), "No personalized writes during private queries/choices");
      check(newHits >= oldHits, "Fixed recall sample must not regress");
      System.out.println("NINE_KEY_LEXICON_OK checks="+checks+" sample="+total+" old_top256="+oldHits+" new_top256="+newHits);
    }
    Files.deleteIfExists(Paths.get(user));
  }
}
