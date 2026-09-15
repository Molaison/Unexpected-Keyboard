package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pinyin editing state, shared by the IME and tests of the real decoder. */
public final class PinyinComposition
{
  private final PinyinDecoder decoder;
  private String spelling = "";
  private String fixedText = "";
  private final PinyinSpelling spellingRules;
  private final List<Choice> fixedChoices = new ArrayList<>();
  private final List<Correction> corrections = new ArrayList<>();
  private int[] candidateOrder = new int[0];
  private boolean candidatesDirty = true;

  private static final class Choice
  {
    final String text;
    final int end;

    Choice(String text, int end) { this.text = text; this.end = end; }
  }

  private static final class Correction
  {
    final String spelling;
    final String text;
    final float score;

    Correction(String spelling, String text, float score)
    {
      this.spelling = spelling;
      this.text = text;
      this.score = score;
    }
  }

  public PinyinComposition(PinyinDecoder decoder)
  {
    this.decoder = decoder;
    spellingRules = new PinyinSpelling(decoder.getSpellings());
  }

  public boolean isEmpty() { return spelling.isEmpty(); }
  public boolean isFull() { return spelling.length() == PinyinDecoder.MAX_PINYIN_LENGTH; }
  public String getSpelling() { return spelling; }
  public int getCandidateCount() { prepareCandidates(); return candidateOrder.length; }

  public void append(char letter)
  {
    if (isFull()) throw new IllegalStateException("Commit the current pinyin before appending");
    // Prediction reuses the native search workspace. Start every new word with
    // a real reset, even when the previous candidate count was already zero.
    if (isEmpty()) decoder.reset();
    spelling += letter;
    decoder.search(spelling);
    if (!decoder.getPinyin().equals(spelling))
      throw new IllegalStateException("The pinyin decoder truncated the input");
    reconcileFixedText();
    candidatesDirty = true;
  }

  public void backspace()
  {
    if (isEmpty()) return;
    if (!fixedText.isEmpty() && spelling.length() == fixedSpellingLength())
      decoder.cancelLastChoice();
    else
      decoder.delete(spelling.length() - 1, false, false);
    spelling = decoder.getPinyin();
    reconcileFixedText();
    candidatesDirty = true;
    if (spelling.isEmpty()) reset();
  }

  /** Return committed text, or empty while selecting a prefix of a sentence. */
  public String select(int index)
  {
    prepareCandidates();
    int mapped = candidateOrder[index];
    if (mapped < 0)
    {
      Correction correction = corrections.get(-mapped - 1);
      boolean learning = decoder.isLearningEnabled();
      decoder.setLearningEnabled(false);
      try { restoreSearch(correction.spelling); }
      finally { decoder.setLearningEnabled(learning); }
      String committed = decoder.getCandidate(0);
      decoder.choose(0);
      reset();
      return committed;
    }
    index = mapped;
    String selected = decoder.getCandidate(index);
    int decodedLength = decoder.getDecodedLength();
    decoder.choose(index);
    fixedText = index == 0 ? selected : fixedText + selected;
    if (index != 0) fixedChoices.add(new Choice(selected, decoder.getSpellingStarts()[fixedText.length()]));
    candidatesDirty = true;
    if (decoder.getFixedLength() != fixedText.length())
      throw new IllegalStateException("The decoder and the selected prefix disagree");
    if (decoder.getFixedLength() != decoder.getSpellingStarts().length - 1)
      return "";

    String committed = fixedText;
    // A candidate covers the decoded prefix only; keep any unparsed tail.
    String remaining = spelling.substring(decodedLength);
    reset();
    if (!remaining.isEmpty())
    {
      spelling = remaining;
      decoder.search(spelling);
    }
    return committed;
  }

  /** Finish conversion before punctuation, a shortcut, or a language change. */
  public String acceptBest()
  {
    if (isEmpty()) return "";
    prepareCandidates();
    if (candidateOrder.length > 0 && candidateOrder[0] < 0) return select(0);
    String text;
    if (decoder.getCandidateCount() == 0)
      text = getComposingText();
    else
    {
      text = decoder.getCandidate(0) + spelling.substring(decoder.getDecodedLength());
      decoder.choose(0);
    }
    reset();
    return text;
  }

  /** Enter commits the unconverted spelling, preserving explicitly chosen Hanzi. */
  public String acceptRaw()
  {
    String text = getComposingText();
    reset();
    return text;
  }

  public String getCandidate(int index)
  {
    prepareCandidates();
    int mapped = candidateOrder[index];
    if (mapped < 0) return corrections.get(-mapped - 1).text;
    String text = decoder.getCandidate(mapped);
    return mapped == 0 ? text.substring(fixedText.length()) : text;
  }

  /** Text sent to InputConnection; separators added by the UI never enter it. */
  public String getComposingText()
  {
    return fixedText + spelling.substring(fixedSpellingLength());
  }

  public String getDisplayText()
  {
    return getComposingText();
  }

  public void reset()
  {
    decoder.reset();
    spelling = "";
    fixedText = "";
    fixedChoices.clear();
    corrections.clear();
    candidateOrder = new int[0];
    candidatesDirty = true;
  }

  private int fixedSpellingLength()
  {
    return fixedText.isEmpty() ? 0 : decoder.getSpellingStarts()[fixedText.length()];
  }

  private void reconcileFixedText()
  {
    fixedText = fixedText.substring(0, decoder.getFixedLength());
    int length = 0;
    for (int i = 0; i < fixedChoices.size(); i++)
    {
      length += fixedChoices.get(i).text.length();
      if (length > fixedText.length())
      {
        fixedChoices.subList(i, fixedChoices.size()).clear();
        break;
      }
    }
  }

  /** Query alternatives without changing raw text, selection, or learned words. */
  private void prepareCandidates()
  {
    if (!candidatesDirty) return;
    corrections.clear();
    int nativeCount = decoder.getCandidateCount();
    String original = nativeCount == 0 ? "" : decoder.getCandidate(0).substring(fixedText.length());
    float originalScore = nativeCount == 0 ? Float.POSITIVE_INFINITY : decoder.getCandidateScore(0);
    boolean fullyDecoded = decoder.getDecodedLength() == spelling.length();
    boolean exactWord = nativeCount > 0 && fullyDecoded && decoder.getUnfixedLemmaCount() <= 1;
    int fixedEnd = fixedSpellingLength();
    List<PinyinSpelling.Variant> variants = spellingRules.alternatives(spelling.substring(fixedEnd));
    if (!variants.isEmpty())
    {
      boolean learning = decoder.isLearningEnabled();
      decoder.setLearningEnabled(false);
      try
      {
        for (PinyinSpelling.Variant variant : variants)
        {
          String alternative = spelling.substring(0, fixedEnd) + variant.text;
          if (alternative.length() > PinyinDecoder.MAX_PINYIN_LENGTH) continue;
          restoreSearch(alternative);
          if (decoder.getCandidateCount() == 0 || decoder.getDecodedLength() != alternative.length()) continue;
          String text = decoder.getCandidate(0).substring(fixedText.length());
          if (text.isEmpty() || text.equals(original)) continue;
          float score = decoder.getCandidateScore(0) + variant.cost * 800;
          Correction correction = new Correction(alternative, text, score);
          int duplicate = -1;
          for (int i = 0; i < corrections.size(); i++)
            if (corrections.get(i).text.equals(text)) { duplicate = i; break; }
          if (duplicate == -1) corrections.add(correction);
          else if (score < corrections.get(duplicate).score) corrections.set(duplicate, correction);
        }
      }
      finally
      {
        try { restoreSearch(spelling); }
        finally { decoder.setLearningEnabled(learning); }
      }
      Collections.sort(corrections, (a, b) -> {
        int result = Float.compare(a.score, b.score);
        return result == 0 ? a.text.compareTo(b.text) : result;
      });
      if (corrections.size() > 8) corrections.subList(8, corrections.size()).clear();
      nativeCount = decoder.getCandidateCount();
    }
    // A complete dictionary word keeps first place. A correction may lead a
    // partial parse or an implausible multiword path, with an explicit cost.
    boolean promote = !corrections.isEmpty() && !exactWord
      && !spellingRules.isFullSpelling(spelling.substring(fixedEnd))
      && (!fullyDecoded || corrections.get(0).score + 400 < originalScore);
    candidateOrder = new int[nativeCount + corrections.size()];
    int pos = 0;
    if (promote) candidateOrder[pos++] = -1;
    if (nativeCount > 0) candidateOrder[pos++] = 0;
    for (int i = promote ? 1 : 0; i < corrections.size(); i++) candidateOrder[pos++] = -i - 1;
    for (int i = 1; i < nativeCount; i++) candidateOrder[pos++] = i;
    candidatesDirty = false;
  }

  private void restoreSearch(String input)
  {
    decoder.reset();
    String prefix = "";
    for (Choice choice : fixedChoices)
    {
      decoder.search(input.substring(0, choice.end));
      prefix += choice.text;
      int selected = -1;
      for (int i = 0; i < decoder.getCandidateCount(); i++)
        if (decoder.getCandidate(i).equals(i == 0 ? prefix : choice.text)) { selected = i; break; }
      if (selected == -1) throw new IllegalStateException("Cannot restore the selected pinyin prefix");
      decoder.choose(selected);
    }
    decoder.search(input);
    if (decoder.getFixedLength() != fixedText.length())
      throw new IllegalStateException("The selected pinyin prefix changed during correction");
  }
}
