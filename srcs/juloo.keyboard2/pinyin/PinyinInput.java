package juloo.keyboard2.pinyin;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import juloo.keyboard2.KeyValue;

/** Owns Chinese composition for one Android editor at a time. */
public final class PinyinInput implements AutoCloseable
{
  public interface Host
  {
    InputConnection getCurrentInputConnection();
    void beforeManualInput();
    void onPinyinChanged();
  }

  private final Context context;
  private final Host host;
  private PinyinDecoder decoder;
  private PinyinComposition composition;
  private InputConnection composingConnection;
  private boolean available;
  private boolean chinese;
  private boolean inlineComposition;
  private boolean learningEnabled;
  private int candidateLimit = 32;
  private boolean hasMore;
  private String[] predictions = new String[0];
  private final List<String> candidates = new ArrayList<>();
  private final List<Integer> candidateIds = new ArrayList<>();

  public PinyinInput(Context context, Host host)
  {
    this.context = context;
    this.host = host;
  }

  public boolean isAvailable() { return available; }
  public boolean isChinese() { return chinese; }
  public boolean isComposing() { return composition != null && !composition.isEmpty(); }
  public boolean hasMoreCandidates() { return hasMore; }
  public List<String> getCandidates() { return Collections.unmodifiableList(candidates); }
  public String getDisplayText() { return isComposing() ? composition.getDisplayText() : ""; }

  public static boolean permitsChinese(int inputType)
  {
    int kind = inputType & InputType.TYPE_MASK_CLASS;
    if (kind == InputType.TYPE_NULL) return true; // Terminals use the preedit row.
    if (kind != InputType.TYPE_CLASS_TEXT) return false;
    int variation = inputType & InputType.TYPE_MASK_VARIATION;
    return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
      && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
      && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
  }

  public static boolean prefersLatin(int inputType)
  {
    if ((inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL) return true;
    int variation = inputType & InputType.TYPE_MASK_VARIATION;
    return variation == InputType.TYPE_TEXT_VARIATION_URI
      || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
      || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
  }

  public void start(EditorInfo info, boolean preferChinese)
  {
    InputConnection connection = host.getCurrentInputConnection();
    if (connection != null) connection.finishComposingText();
    // Never commit the preceding editor's pending text into this new editor.
    resetWithoutEditor();
    available = permitsChinese(info.inputType);
    chinese = available && preferChinese && !prefersLatin(info.inputType);
    inlineComposition = (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
    learningEnabled = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    if (decoder != null) decoder.setLearningEnabled(learningEnabled);
    publish();
  }

  public void setChinese(boolean enabled)
  {
    finish();
    chinese = available && enabled;
    publish();
  }

  public boolean handleKey(KeyValue key, int metaState)
  {
    if (!chinese) return false;
    if ((metaState & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON)) != 0)
    {
      finish();
      return false;
    }
    switch (key.getKind())
    {
      case Char:
        char c = key.getChar();
        if ((c >= 'a' && c <= 'z') || (c == '\'' && isComposing()))
        {
          if (host.getCurrentInputConnection() == null) return false;
          host.beforeManualInput();
          ensureDecoder();
          if (composition.isFull()) finish();
          predictions = new String[0];
          composition.append(c);
          candidateLimit = 32;
          updateEditor();
          publish();
          return true;
        }
        if (c == ' ' && isComposing()) { selectFirst(); return true; }
        break;
      case Editing:
        switch (key.getEditing())
        {
          case BACKSPACE: if (backspace()) return true; break;
          case SPACE_BAR: if (isComposing()) { selectFirst(); return true; } break;
          default: break;
        }
        break;
      case Keyevent:
        switch (key.getKeyevent())
        {
          case KeyEvent.KEYCODE_DEL: if (backspace()) return true; break;
          case KeyEvent.KEYCODE_SPACE: if (isComposing()) { selectFirst(); return true; } break;
          case KeyEvent.KEYCODE_ENTER: if (isComposing()) { commitRaw(); return true; } break;
          case KeyEvent.KEYCODE_ESCAPE: if (isComposing()) { cancel(); return true; } break;
          default: break;
        }
        break;
      case Event:
        if (key.getEvent() == KeyValue.Event.ACTION && isComposing())
        {
          commitRaw();
          return true;
        }
        break;
      case Stateful:
        int candidate = -1;
        switch (key.getStateful())
        {
          case Complete_first: candidate = 0; break;
          case Complete_second: candidate = 1; break;
          case Complete_third: candidate = 2; break;
          default: break;
        }
        if (candidate >= 0)
        {
          if (candidate < candidates.size()) selectCandidate(candidate);
          return true;
        }
        break;
      case Modifier:
        return false;
      default: break;
    }
    finish();
    return false;
  }

  private boolean backspace()
  {
    if (!isComposing()) return false;
    composition.backspace();
    candidateLimit = 32;
    updateEditor();
    publish();
    return true;
  }

  private void selectFirst()
  {
    if (composition.getCandidateCount() == 0) commitRaw();
    else selectNativeCandidate(0);
  }

  public void selectCandidate(int index)
  {
    if (index < 0 || index >= candidates.size())
      throw new IllegalArgumentException("Invalid displayed pinyin candidate");
    host.beforeManualInput();
    if (isComposing())
      selectNativeCandidate(candidateIds.get(index));
    else
    {
      String text = candidates.get(index);
      commit(text);
      predict(text);
      publish();
    }
  }

  private void selectNativeCandidate(int id)
  {
    String text = composition.select(id);
    if (!text.isEmpty()) commit(text);
    if (isComposing()) updateEditor();
    else predict(text);
    candidateLimit = 32;
    publish();
  }

  public void showMoreCandidates()
  {
    candidateLimit += 32;
    publish();
  }

  public void commitRaw()
  {
    if (!isComposing()) return;
    commit(composition.acceptRaw());
    predictions = new String[0];
    publish();
  }

  /** End composition before navigation, punctuation, another input source, or hiding. */
  public void finish()
  {
    if (!isComposing() && predictions.length == 0) return;
    if (isComposing())
    {
      if (composingConnection != null && composingConnection != host.getCurrentInputConnection())
        resetWithoutEditor();
      else
        commit(composition.acceptBest());
    }
    predictions = new String[0];
    if (decoder != null) decoder.flush();
    publish();
  }

  public void cancel()
  {
    if (composition != null) composition.reset();
    updateEditor();
    predictions = new String[0];
    publish();
  }

  public void selectionUpdated(int oldStart, int oldEnd, int start, int end,
                               int composingStart, int composingEnd)
  {
    if (!isComposing()) return;
    boolean moved = inlineComposition
      ? start != composingEnd || end != composingEnd
      : start != oldStart || end != oldEnd;
    if (!moved) return;
    // The editor now owns the displayed raw text at the old cursor. Finishing
    // its span preserves that text; committing here would replace another range.
    if (composingConnection != null && composingConnection == host.getCurrentInputConnection())
      composingConnection.finishComposingText();
    resetWithoutEditor();
    publish();
  }

  public void resetWithoutEditor()
  {
    if (composition != null) composition.reset();
    composingConnection = null;
    predictions = new String[0];
    candidates.clear();
    candidateIds.clear();
    hasMore = false;
  }

  private void updateEditor()
  {
    InputConnection connection = host.getCurrentInputConnection();
    if (connection == null) throw new IllegalStateException("No editor for pinyin input");
    if (composingConnection != null && composingConnection != connection)
      throw new IllegalStateException("The editor changed during pinyin input");
    composingConnection = connection;
    if (inlineComposition)
    {
      if (!connection.setComposingText(composition.getComposingText(), 1))
        throw new IllegalStateException("The editor rejected pinyin composing text");
      if (composition.isEmpty()) connection.finishComposingText();
    }
    if (composition.isEmpty()) composingConnection = null;
  }

  private void commit(String text)
  {
    InputConnection connection = host.getCurrentInputConnection();
    if (connection == null || (composingConnection != null && composingConnection != connection))
      throw new IllegalStateException("The target editor changed before pinyin commit");
    if (!connection.commitText(text, 1))
      throw new IllegalStateException("The editor rejected Chinese text");
    connection.finishComposingText();
    composingConnection = null;
  }

  private void predict(String text)
  {
    decoder.reset();
    predictions = text.isEmpty() ? new String[0] : decoder.predict(text);
  }

  private void publish()
  {
    candidates.clear();
    candidateIds.clear();
    hasMore = false;
    if (chinese)
    {
      HashSet<String> seen = new HashSet<>();
      int total = isComposing() ? composition.getCandidateCount() : predictions.length;
      int i = 0;
      while (i < total && candidates.size() < candidateLimit)
      {
        String text = isComposing() ? composition.getCandidate(i) : predictions[i];
        if (!text.isEmpty() && seen.add(text))
        {
          candidates.add(text);
          candidateIds.add(i);
        }
        i++;
      }
      hasMore = i < total;
    }
    host.onPinyinChanged();
  }

  private void ensureDecoder()
  {
    if (decoder != null) return;
    File directory = new File(context.getFilesDir(), "pinyin");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IllegalStateException("Cannot create the pinyin user dictionary directory");
    File user = new File(directory, "user-rime-59fcb4a6-v1.dat");
    try (AssetFileDescriptor asset = context.getAssets().openFd("pinyin/dict_pinyin.dat"))
    {
      decoder = new PinyinDecoder(asset.getParcelFileDescriptor().getFd(),
          asset.getStartOffset(), asset.getLength(), user.getAbsolutePath());
    }
    catch (IOException error)
    {
      throw new IllegalStateException("Cannot open the packaged pinyin dictionary", error);
    }
    decoder.setLearningEnabled(learningEnabled);
    composition = new PinyinComposition(decoder);
  }

  @Override
  public void close()
  {
    if (decoder != null)
    {
      decoder.close();
      decoder = null;
      composition = null;
    }
  }
}
