package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import juloo.keyboard2.pinyin.PredictionContext;
import static juloo.keyboard2.suggestions.SuggestionRanker.*;

/** Deterministic selection/ranking cases; no stubbed dictionary or Android APIs. */
public final class SuggestionRankingTest
{
  private static int checks;
  private static void eq(Object actual, Object expected)
  {
    checks++;
    if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
  }
  public static void main(String[] args)
  {
    eq(rank("cat", Arrays.asList(new Hit("cat", 1, EXACT), new Hit("cat", 1, PREFIX),
      new Hit("CAT", 15, CORRECTION), new Hit("cats", 8, PREFIX), new Hit("car", 9, CORRECTION)), 3),
      Arrays.asList("cat", "cats", "car"));
    eq(rank("hel", Arrays.asList(new Hit("help", 15, PREFIX), new Hit("hello", 12, PREFIX),
      new Hit("help", 15, CORRECTION), new Hit("hell", 2, PREFIX)), 3), Arrays.asList("help", "hello", "hell"));
    eq(rank("HE", Arrays.asList(new Hit("hello", 5, PREFIX), new Hit("help", 8, PREFIX)), 3),
      Arrays.asList("HELP", "HELLO"));
    eq(matchCase("Hello", "help"), "Help");
    eq(matchCase("iPhone", "iphone"), "iphone");
    eq(matchCase("", "word"), "word");
    eq(rank("a", Arrays.asList(new Hit("a", 0, EXACT)), 0).size(), 0);
    Locale before = Locale.getDefault();
    try { Locale.setDefault(new Locale("tr", "TR")); eq(matchCase("INPUT", "input"), "INPUT"); }
    finally { Locale.setDefault(before); }
    eq(PredictionContext.append("中华", "人民"), "中华人民");
    eq(PredictionContext.tail("previous app。中华人民共和国"), "中华人民共和国");
    eq(PredictionContext.tail("你好，"), "");
    eq(PredictionContext.tail("你好abc"), "");
    eq(PredictionContext.tail(null), "");
    eq(PredictionContext.append("中华人民共和国", "人民"), "人民共和国人民");
    System.out.println("SUGGESTION_RANKING_OK checks=" + checks);
  }
}
