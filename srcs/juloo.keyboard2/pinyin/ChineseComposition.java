package juloo.keyboard2.pinyin;

/** Editing contract shared by full-pinyin and ambiguous nine-key input. */
public interface ChineseComposition
{
  boolean isEmpty();
  boolean isFull();
  String getSpelling();
  int getCandidateCount();
  String getCandidate(int index);
  void append(char key);
  void backspace();
  String select(int index);
  String acceptBest();
  String acceptRaw();
  String getComposingText();
  String getDisplayText();
  void reset();
}
