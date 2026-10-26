package juloo.keyboard2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Injects real window touch events into the installed IME and real EditTexts. */
public final class PinyinSmokeTest extends Instrumentation
{
  private PinyinTestActivity activity;
  private int checks;
  private boolean benchmark;
  private boolean voiceLive;
  private boolean voiceFresh;
  private boolean voiceContinuous;
  private boolean voiceRegression;
  private boolean voiceMicrophone;
  private boolean uiZh;
  private String voiceMicrophoneCase;

  @Override public void onCreate(Bundle arguments)
  {
    super.onCreate(arguments);
    benchmark = arguments != null && "true".equals(arguments.getString("benchmark"));
    voiceLive = arguments != null && "true".equals(arguments.getString("voice_live"));
    voiceFresh = arguments != null && "true".equals(arguments.getString("voice_fresh"));
    voiceContinuous = arguments != null && "true".equals(arguments.getString("voice_continuous"));
    voiceRegression = arguments != null && "true".equals(arguments.getString("voice_regression"));
    voiceMicrophone = arguments != null && "true".equals(arguments.getString("voice_microphone"));
    uiZh = arguments != null && "true".equals(arguments.getString("ui_zh"));
    voiceMicrophoneCase = arguments == null ? "all" : arguments.getString("voice_microphone_case", "all");
    start();
  }

  @Override public void onStart()
  {
    Bundle result = new Bundle();
    try
    {
      if (voiceRegression)
      {
        juloo.keyboard2.doubao.DoubaoRegressionTest.run(this);
        result.putString("stream", "DOUBAO_REGRESSION_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (voiceLive || voiceContinuous)
      {
        if (voiceContinuous)
          juloo.keyboard2.doubao.DoubaoLiveTest.runContinuous(this);
        else
          juloo.keyboard2.doubao.DoubaoLiveTest.run(this, voiceFresh);
        result.putString("stream", voiceContinuous ? "DOUBAO_CONTINUOUS_OK\n" : "DOUBAO_LIVE_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (benchmark)
      {
        benchmarkDecoder();
        result.putString("stream", "PINYIN_BENCHMARK_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (uiZh)
      {
        chineseUiScreenshotChecks(result);
        finish(Activity.RESULT_OK, result);
        return;
      }
      Intent intent = new Intent(getTargetContext(), PinyinTestActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (PinyinTestActivity)startActivitySync(intent);
      // Starting instrumentation may restart the IME's own process. Select it
      // again after that restart, before asking the focused editor to show it.
      String ime = getTargetContext().getPackageName() + "/juloo.keyboard2.Keyboard2";
      try (android.os.ParcelFileDescriptor.AutoCloseInputStream output =
          new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            getUiAutomation().executeShellCommand("ime set " + ime)))
      {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = output.read(buffer)) != -1) bytes.write(buffer, 0, n);
        String status = bytes.toString("UTF-8");
        if (!status.contains("selected")) throw new AssertionError(status);
      }
      focus(activity.plain);
      chinese(true);
      nineKey(false);
      if (voiceMicrophone)
      {
        microphoneInput();
        result.putString("stream", "DOUBAO_MICROPHONE_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      englishSuggestionChecks();
      if (!onMain(() -> candidatesView().isShown()
          && findDescription(candidatesView(), getTargetContext().getString(R.string.ux_clipboard)) != null))
        throw new AssertionError("Clipboard toolbar is not discoverable while idle");
      int idlePinyinHeight = onMain(() -> candidatesView().getHeight());
      float idleKeyY = onMain(() -> keyPoint("n", false)[1]);
      if (candidateRowVisibility() != View.GONE)
        throw new AssertionError("An empty candidate row still occupies idle space");
      type("nihao");
      int composingPinyinHeight = onMain(() -> candidatesView().getHeight());
      int visibleRowHeight = onMain(() -> ((View)field(candidatesView(), "row")).getHeight());
      if (candidateRowVisibility() != View.VISIBLE || visibleRowHeight <= 0
          || composingPinyinHeight != idlePinyinHeight + visibleRowHeight)
        throw new AssertionError("Composing must add exactly the visible candidate row");
      float composingKeyY = onMain(() -> keyPoint("n", false)[1]);
      if (Math.abs(composingKeyY - idleKeyY) > 1f || !onMain(() -> activity.plain.hasFocus()))
        throw new AssertionError("Candidate row expansion moved a key or stole editor focus");
      if (composingPinyinHeight > Math.round(
            96 * getTargetContext().getResources().getDisplayMetrics().density))
        throw new AssertionError("Chinese preedit and candidates exceed two separate rows");
      text(activity.plain, "nihao");
      tapCandidate("你好");
      text(activity.plain, "你好");
      passed("touch letters and tap a Chinese candidate in a NO_SUGGESTIONS editor");

      clear();
      type("woshizhongguoren");
      key("space");
      text(activity.plain, "我是中国人");
      passed("whole sentence and space commit");

      clear();
      type("nihaozhongguo");
      tapCandidate("你好");
      text(activity.plain, "你好zhongguo");
      key("space");
      text(activity.plain, "你好中国");
      passed("partial phrase selection preserves the remaining spelling");

      clear();
      type("nihaoz");
      key("backspace");
      text(activity.plain, "nihao");
      key("enter");
      text(activity.plain, "nihao");
      if (onMain(() -> activity.actions) != 0) throw new AssertionError("Enter submitted a composing editor");
      key("enter");
      await("editor action", () -> activity.actions == 1);
      passed("backspace, raw enter, then editor action");

      clear();
      chinese(false);
      type("hello");
      key("space");
      text(activity.plain, "hello ");
      chinese(true);
      type("nihao");
      key("space");
      text(activity.plain, "hello 你好");
      passed("Chinese/English switch and mixed text");

      clear();
      type("nh");
      tapCandidate("你好");
      text(activity.plain, "你好");
      clear();
      type("zongguo");
      tapCandidate("中国");
      text(activity.plain, "中国");
      passed("initial abbreviations and fuzzy pinyin candidates");

      clear();
      type("nihoa");
      text(activity.plain, "nihoa");
      key("space");
      text(activity.plain, "你好");
      clear();
      type("nihso");
      tapCandidate("你好");
      text(activity.plain, "你好");
      clear();
      type("nihaoo");
      tapCandidate("你好");
      text(activity.plain, "你好");
      passed("transposed, neighboring, and repeated letters preserve usable Chinese candidates");

      clear();
      type("nihoa");
      key("backspace");
      text(activity.plain, "niho");
      tap(onMain(() -> (View)field(candidatesView(), "preedit")));
      text(activity.plain, "niho");
      clear();
      type("nihaozhognguo");
      tapCandidate("你好");
      text(activity.plain, "你好zhognguo");
      tapCandidate("中国");
      text(activity.plain, "你好中国");
      passed("compact raw-pinyin commit and correction after a partial choice");

      focus(activity.password);
      type("nihao");
      text(activity.password, "nihao");
      if (onMain(() -> candidatesView().isShown())) throw new AssertionError("Chinese UI visible in password field");
      passed("password remains literal Latin");

      focus(activity.email);
      type("hello");
      text(activity.email, "hello");
      focus(activity.number);
      key("1"); key("2");
      text(activity.number, "12");
      passed("email and numeric editor policies");

      focus(activity.plain);
      clear();
      chinese(true);
      type("nihao");
      text(activity.plain, "nihao");
      onMain(() -> { activity.plain.setSelection(0); return null; });
      idle();
      key("space");
      text(activity.plain, " nihao");
      passed("moving the cursor preserves text and ends conversion");

      clear();
      type("shi");
      int beforeMore = onMain(() -> ((ViewGroup)field(candidatesView(), "items")).getChildCount());
      tap(onMain(() -> (View)field(candidatesView(), "more")));
      await("expanded candidate panel", () -> ((View)field(candidatesView(), "expandedScroll")).isShown());
      // Paging is a fixed action beside the scrolling candidate content. A
      // visible sliver in a moving ScrollView is not a reliable tap target.
      View loadMore = onMain(() -> findText(candidatesView(), getTargetContext().getString(R.string.pinyin_more_candidates)));
      if (loadMore == null) throw new AssertionError("More candidates action is missing");
      Rect pageBounds = onMain(() -> { Rect b=new Rect(); loadMore.getGlobalVisibleRect(b); return b; });
      Rect scrollBounds = onMain(() -> { Rect b=new Rect(); ((View)field(candidatesView(), "expandedScroll")).getGlobalVisibleRect(b); return b; });
      if (pageBounds.height() < Math.round(48 * getTargetContext().getResources().getDisplayMetrics().density)
          || Rect.intersects(pageBounds, scrollBounds))
        throw new AssertionError("Paging must have a full, stationary 48dp target outside the scroller");
      tap(loadMore);
      await("additional candidate cells", () ->
          ((ViewGroup)field(candidatesView(), "items")).getChildCount() > beforeMore);
      passed("stationary pagination adds real candidate cells");
      key("enter");
      text(activity.plain, "shi");

      clear();
      type("shi");
      tap(onMain(() -> (View)field(candidatesView(), "more")));
      await("expanded candidates for scrolling", () -> ((View)field(candidatesView(), "expandedScroll")).isShown());
      TextView lastCandidate = onMain(() -> {
        ViewGroup lines = (ViewGroup)field(candidatesView(), "expandedItems");
        ViewGroup lastLine = (ViewGroup)lines.getChildAt(lines.getChildCount()-1);
        return (TextView)lastLine.getChildAt(lastLine.getChildCount()-1);
      });
      String lastText = onMain(() -> lastCandidate.getText().toString());
      for (int attempt=0; !onMain(() -> lastCandidate.getGlobalVisibleRect(new Rect())); attempt++)
      {
        if (attempt >= 30) throw new AssertionError("Last candidate is unreachable by scrolling");
        Rect r = onMain(() -> { Rect b=new Rect(); ((View)field(candidatesView(), "expandedScroll")).getGlobalVisibleRect(b); return b; });
        swipe(r.exactCenterX(), r.bottom-10, r.exactCenterX(), r.top+10);
      }
      screenshot("pinyin-candidates-expanded.png");
      tap(lastCandidate);
      text(activity.plain, lastText);
      passed("scrolling expanded content selects the actual last candidate");

      clear();
      type("nihao");
      if (getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
          == android.content.pm.PackageManager.PERMISSION_GRANTED)
        throw new AssertionError("Use a disposable emulator without microphone permission for this test");
      key("voice_typing");
      text(activity.plain, "你好");
      if (!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        throw new AssertionError("Could not dismiss microphone permission request");
      await("return from voice permission", () -> activity.hasWindowFocus());
      focus(activity.plain);
      passed("voice handoff preserves Chinese before requesting microphone permission");

      clear();
      type("nihaozhongguo");
      await("screenshot candidate", () -> findText(candidatesView(), "你好中国") != null);
      idle();
      File screenshot = new File(getTargetContext().getCacheDir(), "pinyin-keyboard.png");
      Bitmap bitmap = getUiAutomation().takeScreenshot();
      try (FileOutputStream output = new FileOutputStream(screenshot))
      {
        if (bitmap == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
          throw new AssertionError("Unable to save keyboard screenshot");
      }
      key("space");
      text(activity.plain, "你好中国");
      passed("candidate UI screenshot and final commit");
      nineKeyChecks();
      clipboardChecks();
      emojiTouchChecks();
      candidateGeometryChecks();
      narrowKeyboardChecks();
      result.putString("stream", "\nPINYIN_SMOKE_OK checks=" + checks + "\n");
      finish(Activity.RESULT_OK, result);
    }
    catch (Throwable error)
    {
      try { screenshot("failure-ux.png"); } catch (Throwable screenshotError) { error.addSuppressed(screenshotError); }
      String trace = Log.getStackTraceString(error);
      Log.e("PinyinSmokeTest", "IME smoke test failed", error);
      result.putString("stream", "\nPINYIN_SMOKE_FAILED\n" + trace);
      result.putString("shortMsg", error.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  /** Exercise the production handler with real touches and editor state. The
      candidate is deterministic, so this test needs no downloaded dictionary. */
  private void englishSuggestionChecks() throws Exception
  {
    android.content.SharedPreferences prefs =
      DirectBootAwarePreferences.get_shared_preferences(getTargetContext());
    java.util.Map<String, ?> saved = prefs.getAll();
    try
    {
      onMain(() -> { prefs.edit().remove("suggestions_add_space")
        .putBoolean("space_bar_auto_complete", false).apply(); return null; });
      if (onMain(() -> Config.globalConfig().suggestions_add_space))
        throw new AssertionError("Automatic suggestion spacing must default to off");
      chinese(false);
      clear();
      type("teh");
      KeyEventHandler handler = onMain(() -> (KeyEventHandler)Config.globalConfig().handler);
      onMain(() -> { prefs.edit().putBoolean("space_bar_auto_complete", true).apply(); return null; });
      if (onMain(() -> Config.globalConfig().handler) != handler)
        throw new AssertionError("Setting change unexpectedly recreated the input handler");
      englishCandidate("the");
      key("space");
      text(activity.plain, "the ");
      key("backspace");
      text(activity.plain, "teh ");
      type("x");
      text(activity.plain, "teh x");
      passed("autocomplete setting applies immediately and undo preserves the separator");

      clear();
      type("teh");
      onMain(() -> { prefs.edit().putBoolean("space_bar_auto_complete", false).apply(); return null; });
      englishCandidate("the");
      key("space");
      text(activity.plain, "teh ");
      passed("disabling autocomplete applies without restarting the editor");

      clear();
      type("teh");
      onMain(() -> { prefs.edit().putBoolean("space_bar_auto_complete", true).apply();
        Config.globalConfig().handler.suggestion_entered("the"); return null; });
      text(activity.plain, "the");
      key("backspace");
      text(activity.plain, "teh");
      englishCandidate("the");
      key("space");
      text(activity.plain, "teh ");
      passed("candidate callback keeps default spacing and Space does not repeat an undone correction");

      clear();
      type("teh");
      onMain(() -> { Config.globalConfig().handler.suggestion_entered("the"); return null; });
      text(activity.plain, "the");
      onMain(() -> { activity.plain.setSelection(1); return null; });
      idle();
      key("backspace");
      text(activity.plain, "he");
      clear();
      focus(activity.plain);
      type("teh");
      onMain(() -> { Config.globalConfig().handler.suggestion_entered("the"); return null; });
      text(activity.plain, "the");
      onMain(() -> { activity.plain.setSelection(0, 3); return null; });
      idle();
      key("backspace");
      text(activity.plain, "");
      passed("moving the caret or selecting text invalidates candidate undo");

      clear();
      focus(activity.plain);
      type("teh");
      onMain(() -> { Config.globalConfig().handler.suggestion_entered("the"); return null; });
      text(activity.plain, "the");
      onMain(() -> { ((KeyEventHandler)Config.globalConfig().handler).paste_from_clipboard_pane("xyz"); return null; });
      text(activity.plain, "thexyz");
      key("backspace");
      text(activity.plain, "thexy");
      clear();
      focus(activity.plain);
      type("teh");
      onMain(() -> { Config.globalConfig().handler.suggestion_entered("😀"); return null; });
      text(activity.plain, "😀");
      key("backspace");
      text(activity.plain, "teh");
      passed("clipboard edits invalidate undo and emoji replacement uses UTF-16 cursor offsets");

      clear();
      focus(activity.plain);
      type("teh");
      onMain(() -> { prefs.edit().putBoolean("suggestions_add_space", true).apply();
        Config.globalConfig().handler.suggestion_entered("the"); return null; });
      text(activity.plain, "the ");
      key("backspace");
      text(activity.plain, "teh ");
      key("backspace");
      text(activity.plain, "teh");
      key("backspace");
      text(activity.plain, "te");
      passed("optional spacing applies immediately, undo keeps it, and subsequent backspaces delete normally");

      clear();
      type("teh");
      englishCandidate("the");
      key("space");
      text(activity.plain, "the ");
      passed("Space autocomplete adds exactly one separator with optional spacing enabled");

      clear();
      type("teh");
      onMain(() -> { activity.plain.setSelection(1); return null; });
      idle();
      englishCandidate("the");
      key("space");
      text(activity.plain, "t eh");
      clear();
      type("teh");
      onMain(() -> { activity.plain.setSelection(0, 3); return null; });
      idle();
      englishCandidate("the");
      key("space");
      // The keyboard's selection modifier maps a touched Space to cancel.
      text(activity.plain, "teh");
      onMain(() -> { activity.plain.setSelection(0, 3); return null; });
      idle();
      englishCandidate("the");
      // Also exercise SPACE_BAR itself with a selection, without remapping.
      onMain(() -> { Config.globalConfig().handler.key_up(
        KeyValue.getKeyByName("space"), Pointers.Modifiers.EMPTY); return null; });
      text(activity.plain, " ");
      passed("autocomplete respects cursor position and selection");

      chinese(true);
      clear();
      type("nihaoz");
      key("backspace");
      text(activity.plain, "nihao");
      key("space");
      text(activity.plain, "你好");
      clear();
      type("nihao");
      tapCandidate("你好");
      text(activity.plain, "你好");
      englishCandidate("the");
      key("space");
      text(activity.plain, "你好 ");
      passed("English options preserve full-pinyin composition, candidate selection, and Chinese Space");

      clear();
      nineKey(true);
      nineKeys("64426");
      key("space");
      text(activity.plain, "你好");
      key("backspace");
      text(activity.plain, "你");
      passed("English spacing and undo do not interfere with nine-key Space or backspace");
    }
    finally
    {
      onMain(() -> {
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : new String[]{"suggestions_add_space", "space_bar_auto_complete"})
        {
          if (saved.containsKey(key)) editor.putBoolean(key, (Boolean)saved.get(key));
          else editor.remove(key);
        }
        editor.apply();
        return null;
      });
    }
    nineKey(false);
    clear();
  }

  private void englishCandidate(String word) throws Exception
  {
    onMain(() -> {
      juloo.keyboard2.suggestions.Suggestions suggestions =
        ((KeyEventHandler)Config.globalConfig().handler)._suggestions;
      suggestions.suggestions[0] = word;
      suggestions.count = 1;
      return null;
    });
  }

  private void nineKey(boolean enabled) throws Exception
  {
    if (onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey()) == enabled) return;
    if (enabled)
    {
      float[] point = onMain(() -> keyPoint("shift", false));
      swipe(point[0], point[1], point[2], point[3]);
    }
    else tap(onMain(() -> (android.view.View)field(candidatesView(), "readings")));
    await("nine-key layout switch", () ->
      ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey() == enabled);
  }

  private void nineKeys(String keys) throws Exception
  {
    for (char key : keys.toCharArray()) key("pinyin_" + key);
  }

  private void nineKeyChecks() throws Exception
  {
    clear();
    nineKey(true);
    nineKeys("64426");
    text(activity.plain, "nihao");
    tapCandidate("你好");
    text(activity.plain, "你好");
    passed("real nine-key taps, readable pinyin preedit, Chinese candidate commit");

    onMain(() -> {
      Object index = field(((KeyEventHandler)Config.globalConfig().handler)._pinyin, "lexicon");
      if (!(index instanceof juloo.keyboard2.pinyin.NineKeyLexicon)
          || ((juloo.keyboard2.pinyin.NineKeyLexicon)index).size() != 882585)
        throw new AssertionError("The APK must actually map the full nine-key lexicon; fallback is not acceptance");
      return null;
    });
    clear();
    nineKeys("74363898394");
    text(activity.plain, "shenduxuexi");
    tapCandidate("深度学习");
    text(activity.plain, "深度学习");
    passed("packaged lexicon mapping and extended vocabulary through actual taps");

    clear();
    nineKeys("6442694664486");
    tapCandidate("你好");
    text(activity.plain, "你好zhongguo");
    key("space");
    text(activity.plain, "你好中国");
    passed("nine-key partial choice preserves remaining matched pinyin");

    clear();
    nineKeys("64426");
    key("backspace");
    key("enter");
    text(activity.plain, "niha");
    passed("nine-key backspace and raw Enter");

    clear();
    nineKeys("6464");
    tap(onMain(() -> (View)field(candidatesView(), "readings")));
    idle();
    long down;
    // The picker is a real PopupMenu window that must actually be on screen.
    android.view.accessibility.AccessibilityNodeInfo picker =
      getUiAutomation().getRootInActiveWindow();
    java.util.List<android.view.accessibility.AccessibilityNodeInfo> readings =
      picker.findAccessibilityNodeInfosByText("ming");
    if (readings.isEmpty())
      throw new AssertionError("Nine-key reading picker did not offer ming");
    // PopupMenu exposes the title as a non-clickable TextView; the containing
    // menu row owns the click. Tap its visible text like a user instead.
    Rect readingBounds = new Rect();
    readings.get(0).getBoundsInScreen(readingBounds);
    if (!readings.get(0).isVisibleToUser() || readingBounds.isEmpty())
      throw new AssertionError("The ming reading is not visible");
    down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, readingBounds.exactCenterX(), readingBounds.exactCenterY());
    injectTouch(down, MotionEvent.ACTION_UP, readingBounds.exactCenterX(), readingBounds.exactCenterY());
    idle();
    // The chosen reading remains visible in both preedit and editor. Testing
    // for absence of that text confuses a successful selection with an open
    // popup; verify the actual popup window identity instead.
    final int pickerWindowId = picker.getWindowId();
    await("reading picker window dismissed", () -> {
      android.view.accessibility.AccessibilityNodeInfo active = getUiAutomation().getRootInActiveWindow();
      return active != null && active.getWindowId() != pickerWindowId;
    });
    if (onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.getDisplayText()).contains("64"))
      throw new AssertionError("Choosing a reading did not change the composition");
    key("space");
    text(activity.plain, "明");
    passed("visible Pinyin button chooses an ambiguous reading");

    onMain(() -> { activity.password.setText(""); return null; });
    focus(activity.password);
    type("nihao");
    text(activity.password, "nihao");
    if (onMain(() -> candidatesView().isShown())) throw new AssertionError("Nine-key candidates in password");
    focus(activity.plain);
    clear();
    chinese(true);
    await("nine-key preference restored", () -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey());
    chinese(false);
    type("hello");
    chinese(true);
    nineKeys("64426");
    key("space");
    text(activity.plain, "hello你好");
    passed("nine-key preference survives password and English switches");

    clear();
    nineKeys("9674494664486736");
    text(activity.plain, "woshizhongguoren");
    File screenshot = new File(getTargetContext().getCacheDir(), "pinyin-nine-key.png");
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    try (FileOutputStream output = new FileOutputStream(screenshot))
    {
      if (bitmap == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        throw new AssertionError("Unable to save nine-key screenshot");
    }
    key("space");
    text(activity.plain, "我是中国人");
    // Native predictions see the entire editor context, not only the last choice.
    String context = onMain(() -> (String)field(((KeyEventHandler)Config.globalConfig().handler)._pinyin, "predictionContext"));
    if (!"我是中国人".equals(context)) throw new AssertionError("Truncated prediction context: " + context);
    onMain(() -> { activity.plain.setSelection(0); return null; });
    idle();
    if (onMain(() -> !((KeyEventHandler)Config.globalConfig().handler)._pinyin.getCandidates().isEmpty()))
      throw new AssertionError("Stale predictions survived cursor movement");
    nineKey(false);
    passed("nine-key sentence, context prediction, cursor invalidation and layout restoration");
  }

  private ClipboardPaneView clipboard() throws Exception
  {
    Object receiver=((KeyEventHandler)Config.globalConfig().handler)._recv;
    return (ClipboardPaneView)field(field(receiver,"this$0"),"_clipboard_pane");
  }

  private void menuText(String text) throws Exception
  {
    idle();
    android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
    java.util.List<android.view.accessibility.AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(text);
    for (android.view.accessibility.AccessibilityNodeInfo node:found)
    {
      Rect r=new Rect(); node.getBoundsInScreen(r);
      if(node.isVisibleToUser() && !r.isEmpty() && node.getText()!=null && text.equalsIgnoreCase(node.getText().toString())) { touch(r.exactCenterX(),r.exactCenterY()); return; }
    }
    throw new AssertionError("Menu item not visible: "+text);
  }

  private void screenshot(String name) throws Exception
  {
    idle();
    Bitmap bitmap=getUiAutomation().takeScreenshot();
    try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),name)))
    { if(bitmap==null || !bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new AssertionError("Screenshot failed"); }
  }

  private void clipboardChecks() throws Exception
  {
    clear();
    if (!java.util.Arrays.asList(getTargetContext().getResources().getStringArray(R.array.pref_clipboard_duration_values)).contains("60"))
      throw new AssertionError("Default clipboard retention must be selectable in settings");
    ClipboardHistoryService service=onMain(() -> ClipboardHistoryService.get_service(getTargetContext()));
    android.content.ClipboardManager cm=(android.content.ClipboardManager)getTargetContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    onMain(() -> { ClipboardHistoryService.set_history_enabled(true); service.clear_history_and_current();
      for(int i=0;i<35;i++) service.add_clip("Sample clip "+i);
      if(service.clear_expired_and_get_history().size()!=30) throw new AssertionError("History must be bounded at 30");
      service.add_clip("Sample clip 20");
      if(service.clear_expired_and_get_history().size()!=30 || !service.clear_expired_and_get_history().get(0).equals("Sample clip 20"))
        throw new AssertionError("Repeated clip must be promoted, not duplicated");
      service.clear_history();
      android.content.ClipData sensitive=android.content.ClipData.newPlainText("test","SENSITIVE_TEST_VALUE");
      android.os.PersistableBundle extras=new android.os.PersistableBundle();
      extras.putBoolean("android.content.extra.IS_SENSITIVE",true); sensitive.getDescription().setExtras(extras);
      cm.setPrimaryClip(sensitive); return null; });
    idle();
    if(onMain(() -> service.current_text()!=null || !service.clear_expired_and_get_history().isEmpty()))
      throw new AssertionError("Sensitive system clip was retained");
    passed("bounded, deduplicated clipboard history and sensitive-content exclusion");

    onMain(() -> { ClipboardHistoryService.set_history_enabled(false);
      cm.setPrimaryClip(android.content.ClipData.newPlainText("test","A plain current clip")); return null; });
    idle();
    tap(onMain(() -> findDescription(candidatesView(),getTargetContext().getString(R.string.ux_clipboard))));
    await("clipboard panel", () -> clipboard()!=null && clipboard().isShown());
    if (!onMain(() -> ((View)field(clipboard(), "recentTab")).isSelected()
        && !((View)field(clipboard(), "pinnedTab")).isSelected()))
      throw new AssertionError("Clipboard recent tab must expose its selected state");
    tap(onMain(() -> findText(clipboard(),"A plain current clip")));
    text(activity.plain,"A plain current clip");
    if(!onMain(() -> clipboard().isShown())) throw new AssertionError("Pasting unexpectedly closed the clipboard panel");
    if(!onMain(() -> service.clear_expired_and_get_history().isEmpty())) throw new AssertionError("Disabled recording saved a clip");
    passed("visible toolbar and whole-card paste work with history recording disabled");

    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_pin))));
    if(!onMain(() -> "A plain current clip".equals(service.current_text()))) throw new AssertionError("Pinning erased the system clipboard");
    tap(onMain(() -> (View)field(clipboard(),"pinnedTab")));
    if (!onMain(() -> ((View)field(clipboard(), "pinnedTab")).isSelected()
        && !((View)field(clipboard(), "recentTab")).isSelected()))
      throw new AssertionError("Clipboard pinned tab must expose its selected state");
    tap(onMain(() -> findText(clipboard(),"A plain current clip")));
    text(activity.plain,"A plain current clipA plain current clip");
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_clip_actions))));
    menuText(getTargetContext().getString(R.string.ux_preview));
    menuText(getTargetContext().getString(android.R.string.cancel));
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_clip_actions))));
    menuText(getTargetContext().getString(R.string.ux_delete));
    if(onMain(() -> ((java.util.List<?>)field(clipboard(),"pins")).contains("A plain current clip"))) throw new AssertionError("Pin was not removed");
    onMain(() -> {
      View undo = (View)field(clipboard(), "undoButton");
      Rect bounds = new Rect();
      if (!undo.getGlobalVisibleRect(bounds)) throw new AssertionError("Undo must be visible");
      if (android.os.Build.VERSION.SDK_INT >= 35)
      {
        android.util.DisplayMetrics screen = new android.util.DisplayMetrics();
        undo.getDisplay().getRealMetrics(screen);
        int nav = undo.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
        if (bounds.bottom > screen.heightPixels - nav)
          throw new AssertionError("Undo overlaps the navigation bar: " + bounds);
      }
      return null;
    });
    screenshot("clipboard-undo.png");
    tap(onMain(() -> (View)field(clipboard(),"undoButton")));
    if(!onMain(() -> ((java.util.List<?>)field(clipboard(),"pins")).contains("A plain current clip"))) throw new AssertionError("Undo did not restore pin");
    if(!getTargetContext().getSharedPreferences("pinned_clipboards",0).getString("pinned","").contains("A plain current clip"))
      throw new AssertionError("Pin persistence changed its existing storage key");
    passed("pin, repeated paste, delete and undo through visible touch controls");

    tap(onMain(() -> (View)field(clipboard(),"recentTab")));
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_clip_actions))));
    menuText(getTargetContext().getString(R.string.ux_delete));
    if(onMain(() -> service.current_text()!=null)) throw new AssertionError("Explicit delete left the current clip visible");
    tap(onMain(() -> (View)field(clipboard(),"undoButton")));
    if(!onMain(() -> service.clear_expired_and_get_history().contains("A plain current clip"))) throw new AssertionError("Undo failed for recent clip");
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_clear))));
    menuText(getTargetContext().getString(R.string.ux_clear));
    if(!onMain(() -> service.clear_expired_and_get_history().isEmpty())) throw new AssertionError("Clear did not clear history");
    if(!onMain(() -> ((java.util.List<?>)field(clipboard(),"pins")).contains("A plain current clip"))) throw new AssertionError("Clear erased a pin");
    passed("delete-current and confirmed clear preserve pinned snippets");

    onMain(() -> { ClipboardHistoryService.set_history_enabled(true);
      service.add_clip("The meeting has moved to 3:30 PM. I will send the notes afterwards.");
      service.add_clip("蛋白质设计 · 本周结果\n请复核候选结构和评分表。");
      service.add_clip("Could you send me the updated version? Thank you!"); return null; });
    idle();
    screenshot("clipboard-light.png");
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_keyboard))));
    await("return to keyboard", () -> keyboard().isShown());
    clear(); nineKey(true); nineKeys("64426");
    screenshot("nine-key-light.png");
    key("space");
    onMain(() -> { DirectBootAwarePreferences.get_shared_preferences(getTargetContext()).edit().putString("theme","soft_dark").apply(); return null; });
    idle(); focus(activity.plain); clear(); nineKeys("64426");
    screenshot("nine-key-dark.png");
    tap(onMain(() -> findDescription(candidatesView(),getTargetContext().getString(R.string.ux_clipboard))));
    await("dark clipboard panel", () -> clipboard().isShown());
    screenshot("clipboard-dark.png");
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_keyboard))));
    passed("light/dark surfaces and clipboard return preserve editor focus");
  }

  /** Focused Chinese-locale UI evidence: idle/composition/backspace, English
      suggestions, clipboard and emoji, in the app's real zh-CN resources. */
  @SuppressWarnings("deprecation") // startActivitySync is the stable fixture launch API here.
  private void chineseUiScreenshotChecks(Bundle result) throws Exception
  {
    final android.app.LocaleManager locales =
      getTargetContext().getSystemService(android.app.LocaleManager.class);
    if (locales == null) throw new AssertionError("LocaleManager is unavailable");
    final android.os.LocaleList saved = locales.getApplicationLocales();
    try
    {
      installZhResources(locales);
      launchFixture();
      chinese(true);
      nineKey(false);
      clear();
      ChineseLayoutChecks(true);
      ChineseLayoutChecks(false);
      englishRealCandidates();
      chinese(true);
      chinesePageScreenshots();
      clipboardUiScreenshot();
      emojiUiScreenshot();
      result.putString("stream", "\nUI_ZH_OK checks=" + checks + "\n");
    }
    finally
    {
      try
      {
        onMain(() -> { locales.setApplicationLocales(saved); return null; });
      }
      catch (Throwable restoreError)
      {
        throw new AssertionError("Unable to restore application locale", restoreError);
      }
    }
  }

  /** Apply zh-CN to this app and wait until the target resources match. */
  private void installZhResources(android.app.LocaleManager locales) throws Exception
  {
    final android.os.LocaleList zh = android.os.LocaleList.forLanguageTags("zh-CN");
    onMain(() -> { locales.setApplicationLocales(zh); return null; });
    await("zh-CN application locales", () ->
      locales.getApplicationLocales().toLanguageTags().contains("zh"));
    await("zh resources before fixture launch", () ->
      getTargetContext().getResources().getConfiguration().getLocales().get(0).getLanguage()
        .equals("zh"));
  }

  /** Start the debug editor fixture, reselect this IME and focus its editor. */
  private void launchFixture() throws Exception
  {
    Intent intent = new Intent(getTargetContext(), PinyinTestActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    activity = (PinyinTestActivity)startActivitySync(intent);
    String ime = getTargetContext().getPackageName() + "/juloo.keyboard2.Keyboard2";
    try (android.os.ParcelFileDescriptor.AutoCloseInputStream output =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
          getUiAutomation().executeShellCommand("ime set " + ime)))
    {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int n;
      while ((n = output.read(buffer)) != -1) bytes.write(buffer, 0, n);
      String status = bytes.toString("UTF-8");
      if (!status.contains("selected")) throw new AssertionError(status);
    }
    focus(activity.plain);
  }

  /** Idle -> composition -> backspace clear with a stable key origin. */
  private void ChineseLayoutChecks(boolean nine) throws Exception
  {
    nineKey(nine);
    clear();
    String probe = nine ? "pinyin_2" : "n";
    await("idle Chinese row hidden (" + (nine ? "nine" : "qwerty") + ")",
        () -> candidateRowVisibility() == View.GONE);
    final int[] coreIdle = coreKeyY(probe);
    float idleY = onMain(() -> keyPoint(probe, false)[1]);
    if (idleY <= 0) throw new AssertionError("Idle key position is not measurable");
    logLayoutMetrics((nine ? "nine" : "qwerty") + "-idle");
    screenshot(nine ? "ui-zh-idle-nine.png" : "ui-zh-idle-qwerty.png");
    if (nine) nineKeys("64"); else type("ni");
    await("Chinese candidates visible", () -> candidateRowVisibility() == View.VISIBLE);
    final int[] coreCompose = coreKeyY(probe);
    Log.i("PinyinSmokeTest", "CORE " + (nine ? "nine" : "qwerty") + " idle=" + coreIdle[1] + " compose=" + coreCompose[1] + " focus=" + onMain(() -> activity.plain.hasFocus()));
    float composingY = onMain(() -> keyPoint(probe, false)[1]);
    logLayoutMetrics((nine ? "nine" : "qwerty") + "-compose");
    if (Math.abs(composingY - idleY) > 1f)
      throw new AssertionError("Key moved while composing: " + idleY + " -> " + composingY);
    if (!onMain(() -> activity.plain.hasFocus()))
      throw new AssertionError("Editor lost focus while composing");
    // Backspace twice clears both probe keys and returns the strip to idle.
    key("backspace");
    key("backspace");
    await("empty composition (" + (nine ? "nine" : "qwerty") + ")",
        () -> onMain(() -> activity.plain.getText().toString().isEmpty())
          && candidateRowVisibility() == View.GONE);
    final int[] coreCleared = coreKeyY(probe);
    Log.i("PinyinSmokeTest", "CORE " + (nine ? "nine" : "qwerty") + " cleared=" + coreCleared[1] + " focus=" + onMain(() -> activity.plain.hasFocus()));
    float clearedY = onMain(() -> keyPoint(probe, false)[1]);
    logLayoutMetrics((nine ? "nine" : "qwerty") + "-cleared");
    if (Math.abs(clearedY - idleY) > 1f)
      throw new AssertionError("Key moved after clearing: " + idleY + " -> " + clearedY);
    if (!onMain(() -> activity.plain.hasFocus()))
      throw new AssertionError("Editor lost focus after clearing");
    passed("zh " + (nine ? "nine-key" : "QWERTY") + " idle, composition and backspace keep key origin and focus");
  }

  /** Real production path: the candidates widget receives empty, then a real
      candidate published asynchronously, then empty again. */
  private void englishRealCandidates() throws Exception
  {
    int originalInputType = onMain(() -> activity.plain.getInputType());
    // The normal fixture intentionally opts out of Latin suggestions. Use a
    // real editor that permits them for this asynchronous-publication test;
    // never bypass production editor policy by forcing a view visible.
    onMain(() -> { activity.plain.setInputType(android.text.InputType.TYPE_CLASS_TEXT); return null; });
    focus(activity.plain);
    try
    {
    chinese(false);
    clear();
    KeyEventHandler handler = onMain(() -> (KeyEventHandler)Config.globalConfig().handler);
    type("teh");
    onMain(() -> { handler._recv.set_suggestions(testSuggestions(handler, null)); return null; });
    await("empty English publish hides the row", () -> candidateRowVisibility() == View.GONE);
    // Inject only at the production suggestion-publication boundary, not the
    // view's visibility or state setter. This tests asynchronous UI delivery.
    handler._recv.getHandler().post(() ->
        handler._recv.set_suggestions(testSuggestions(handler, "the")));
    await("asynchronous English candidate is visible", () -> {
      View candidate = onMain(() -> findText(candidatesView(), "the"));
      return candidateRowVisibility() == View.VISIBLE
        && candidate != null && onMain(() -> candidate.isShown() && candidate.getHeight() > 0);
    });
    onMain(() -> { handler._recv.set_suggestions(testSuggestions(handler, null)); return null; });
    await("cleared English publish hides the row", () -> candidateRowVisibility() == View.GONE);
    View stale = onMain(() -> findText(candidatesView(), "the"));
    if (stale != null && onMain(() -> stale.isShown()))
      throw new AssertionError("Cleared English candidate remains visible");
    passed("English suggestion delivery empty -> async candidate -> empty updates the visible row");
    }
    finally
    {
      onMain(() -> { activity.plain.setInputType(originalInputType); return null; });
      focus(activity.plain);
    }
  }

  private juloo.keyboard2.suggestions.Suggestions testSuggestions(KeyEventHandler handler, String word)
  {
    juloo.keyboard2.suggestions.Suggestions result =
      new juloo.keyboard2.suggestions.Suggestions(handler._recv, Config.globalConfig());
    if (word != null) { result.suggestions[0] = word; result.count = 1; }
    return result;
  }

  private void clipboardUiScreenshot() throws Exception
  {
    clear();
    android.content.ClipboardManager cm = (android.content.ClipboardManager)
      getTargetContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    tap(onMain(() -> findDescription(candidatesView(),
        getTargetContext().getString(R.string.ux_clipboard))));
    await("clipboard pane", () -> clipboard() != null && clipboard().isShown());
    boolean original = onMain(() -> Config.globalConfig().clipboard_history_enabled);
    try
    {
      // Two actual visible menu clicks prove both transitions, independent of
      // the preference left by the earlier clipboard regression suite.
      toggleHistoryThroughMenu();
      toggleHistoryThroughMenu();
      if (!onMain(() -> Config.globalConfig().clipboard_history_enabled)) toggleHistoryThroughMenu();
      onMain(() -> { cm.setPrimaryClip(android.content.ClipData.newPlainText(
          "ui-zh", "明早九点提交评审材料，请提前核对数据。")); return null; });
      await("first clipboard sample", () -> onMain(() -> findText(clipboard(),
          "明早九点提交评审材料，请提前核对数据。") != null));
      onMain(() -> { cm.setPrimaryClip(android.content.ClipData.newPlainText(
          "ui-zh", "会议改到 3:30，稍后发纪要")); return null; });
      await("both clipboard samples", () -> onMain(() ->
          findText(clipboard(), "会议改到 3:30，稍后发纪要") != null
          && findText(clipboard(), "明早九点提交评审材料，请提前核对数据。") != null));
      screenshot("ui-zh-clipboard.png");
      clipboardHeaderReflow();
    }
    finally
    {
      if (onMain(() -> Config.globalConfig().clipboard_history_enabled) != original)
        toggleHistoryThroughMenu();
    }
    tap(onMain(() -> findDescription(clipboard(), getTargetContext().getString(R.string.ux_keyboard))));
    await("clipboard return", () -> keyboard().isShown() && activity.plain.hasFocus());
    passed("zh clipboard content, visible recording menu, two-row reflow and editor focus");
  }

  private void toggleHistoryThroughMenu() throws Exception
  {
    boolean before = onMain(() -> Config.globalConfig().clipboard_history_enabled);
    tap(onMain(() -> findDescription(clipboard(), getTargetContext().getString(R.string.ux_clipboard_menu))));
    String label = getTargetContext().getString(R.string.ux_record_history);
    final Rect[] target = { null };
    await("visible recording preference menu", () -> {
      android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
      if (root == null) return false;
      for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
        if (node.isVisibleToUser() && label.contentEquals(node.getText() == null ? "" : node.getText()))
        {
          Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
          if (!bounds.isEmpty()) { target[0] = bounds; return true; }
        }
      return false;
    });
    touch(target[0].exactCenterX(), target[0].exactCenterY());
    await("recording preference changed through menu", () ->
        onMain(() -> Config.globalConfig().clipboard_history_enabled) != before);
    idle();
  }

  private void clipboardHeaderReflow() throws Exception
  {
    onMain(() -> {
      ClipboardPaneView pane = new ClipboardPaneView(clipboard().getContext(), null);
      pane.show(1200, () -> {});
      float density = pane.getResources().getDisplayMetrics().density;
      for (int widthDp : new int[] { 360, 240, 360 })
      {
        pane.measure(View.MeasureSpec.makeMeasureSpec(Math.round(widthDp*density), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        pane.layout(0, 0, pane.getMeasuredWidth(), pane.getMeasuredHeight());
        ViewGroup header = (ViewGroup)field(pane, "header");
        int rows = widthDp == 240 ? 2 : 1;
        if (header.getChildCount() != rows
            || header.getHeight() != rows * (Integer)field(pane, "rowHeight"))
          throw new AssertionError("Clipboard header must use " + rows + " rows at " + widthDp + "dp");
      }
      return null;
    });
  }

  private void emojiUiScreenshot() throws Exception
  {
    clear();
    chinese(true);
    int keyboardHeight = onMain(() -> ((View)keyboard().getParent()).getHeight());
    float[] entry = onMain(() -> emojiEntryPoint());
    swipe(entry[0], entry[1], entry[2], entry[3]);
    await("emoji pane", () -> emojiPane() != null && emojiPane().isShown());
    await("emoji pane preserves keyboard height", () -> emojiPane().getHeight() == keyboardHeight);
    EmojiGridView grid = onMain(() -> emojiPane().findViewById(R.id.emoji_grid));
    await("default emoji group cells", () -> grid.getChildCount() > 0);
    // Capture the untouched first-open state: default group visible.
    screenshot("ui-zh-emoji.png");
    EmojiGroupButtonsBar bar = onMain(() -> (EmojiGroupButtonsBar)emojiPane().getChildAt(0));
    int openingGroup = onMain(() -> grid.current_group());
    Rect strip = onMain(() -> { Rect b=new Rect(); bar.getGlobalVisibleRect(b); return b; });
    swipe(strip.right-10, strip.left+10, strip.exactCenterY());
    if (onMain(() -> grid.current_group()) != openingGroup)
      throw new AssertionError("Dragging emoji categories selected a group on pointer down");
    Keyboard2View bottom = onMain(() -> (Keyboard2View)emojiPane().getChildAt(2));
    float[] back = onMain(() -> keyPoint(bottom, "switch_back_emoji", false));
    touch(back[0], back[1]);
    await("emoji return", () -> keyboard().isShown() && activity.plain.hasFocus());
    passed("zh emoji pane keeps keyboard height and returns focus");
  }

  /** Same geometry as the existing emoji entry probe. */
  private float[] emojiEntryPoint() throws Exception
  {
    Keyboard2View view = keyboard();
    KeyboardData data = (KeyboardData)field(view, "_keyboard");
    Theme.Computed theme = (Theme.Computed)field(view, "_tc");
    float unit = (Float)field(view, "_keyWidth");
    float margin = (Float)field(view, "_marginLeft");
    int[] location = new int[2];
    view.getLocationOnScreen(location);
    float y = theme.margin_top;
    for (KeyboardData.Row row : data.rows)
    {
      y += row.shift * theme.row_height;
      float x = margin + theme.margin_left;
      for (KeyboardData.Key key : row.keys)
      {
        x += key.shift * unit;
        float w = unit*key.width-theme.horizontal_margin;
        float h = row.height*theme.row_height-theme.vertical_margin;
        for (int i=1; i<9; i++)
          if (KeyValue.getKeyByName("switch_emoji").equals(key.keys[i]))
          {
            float dx = Keyboard2View.LABEL_POSITION_H[i] == android.graphics.Paint.Align.LEFT ? -.45f
              : Keyboard2View.LABEL_POSITION_H[i] == android.graphics.Paint.Align.RIGHT ? .45f : 0;
            float dy = Keyboard2View.LABEL_POSITION_V[i] == Keyboard2View.Vertical.TOP ? -.45f
              : Keyboard2View.LABEL_POSITION_V[i] == Keyboard2View.Vertical.BOTTOM ? .45f : 0;
            return new float[] {location[0]+x+w/2, location[1]+y+h/2,
              location[0]+x+w/2+dx*w, location[1]+y+h/2+dy*h};
          }
        x += key.width*unit;
      }
      y += row.height*theme.row_height;
    }
    throw new AssertionError("Emoji gesture entry is absent");
  }

  /** Real numeric page reached through its existing switch_numeric mapping. */
  private void chinesePageScreenshots() throws Exception
  {
    clear();
    nineKey(false);
    await("idle pinyin strip", () -> candidateRowVisibility() == View.GONE);
    logLayoutMetrics("qwerty");
    key("switch_numeric");
    await("numeric page", () -> hasKey("switch_greekmath"));
    screenshot("ui-zh-numbers.png");
    passed("zh numeric page captured through switch_numeric");
    key("switch_greekmath");
    await("symbol page", () -> hasKey("θ"));
    screenshot("ui-zh-symbols.png");
    passed("zh symbol page captured through switch_greekmath");
    key("switch_text");
    await("text page after symbols", () -> hasKey("n"));
  }

  private boolean hasKey(String name) throws Exception
  {
    KeyboardData layout = onMain(() -> (KeyboardData)field(keyboard(), "_keyboard"));
    KeyValue wanted = KeyValue.getKeyByName(name);
    for (KeyboardData.Row row : layout.rows)
      for (KeyboardData.Key key : row.keys)
        for (int i = 0; i < key.keys.length; i++)
          if (key.keys[i] != null && key.keys[i].equals(wanted)) return true;
    return false;
  }

  /** Record real screen/anchor metrics so screenshot scale can be matched. */
  private void logLayoutMetrics(String layoutName) throws Exception
  {
    android.util.DisplayMetrics dm = getTargetContext().getResources().getDisplayMetrics();
    String metrics = onMain(() -> {
      Keyboard2View view = keyboard();
      Theme.Computed theme = (Theme.Computed)field(view, "_tc");
      KeyboardData data = (KeyboardData)field(view, "_keyboard");
      int[] location = new int[2];
      view.getLocationOnScreen(location);
      int firstKeyTop = -1;
      if (!data.rows.isEmpty())
        firstKeyTop = location[1] + Math.round(theme.margin_top);
      View toolbar = candidatesView();
      int[] toolbarLocation = new int[2];
      toolbar.getLocationOnScreen(toolbarLocation);
      return "width=" + dm.widthPixels
        + " height=" + dm.heightPixels
        + " density=" + dm.density
        + " keyboardLeft=" + location[0] + " keyboardTop=" + location[1]
        + " keyboardWidth=" + view.getWidth() + " keyboardHeight=" + view.getHeight()
        + " toolbarTop=" + toolbarLocation[1] + " toolbarHeight=" + toolbar.getHeight()
        + " firstKeyTop=" + firstKeyTop
        + " rowHeight=" + theme.row_height + " marginTop=" + theme.margin_top
        + " keys=" + data.keysWidth + "x" + data.keysHeight
        + " hasFocus=" + activity.plain.hasFocus();
    });
    Log.i("PinyinSmokeTest", "METRICS layout=" + layoutName + " " + metrics);
  }
  /** Screen bounds [top, bottom] of the real drawn key for [name]. */
  private int[] coreKeyY(String name) throws Exception
  {
    return onMain(() -> {
      Keyboard2View view = keyboard();
      KeyboardData layout = (KeyboardData)field(view, "_keyboard");
      Theme.Computed theme = (Theme.Computed)field(view, "_tc");
      float width = (Float)field(view, "_keyWidth");
      float left = (Float)field(view, "_marginLeft");
      int[] location = new int[2];
      view.getLocationOnScreen(location);
      float y = theme.margin_top;
      KeyValue wanted = KeyValue.getKeyByName(name);
      for (KeyboardData.Row row : layout.rows)
      {
        y += row.shift * theme.row_height;
        for (KeyboardData.Key key : row.keys)
        {
          if (key.keys[0] != null && key.keys[0].equals(wanted))
          {
            int top = location[1] + Math.round(y);
            int bottom = location[1] + Math.round(y + row.height * theme.row_height);
            return new int[] {top, bottom};
          }
        }
        y += row.height * theme.row_height;
      }
      throw new AssertionError("Key is absent: " + name);
    });
  }

  /** Visibility of the real candidate strip row inside the pinyin view. */
  private int candidateRowVisibility() throws Exception
  {
    View row = onMain(() -> (View)field(candidatesView(), "row"));
    return onMain(() -> row.getVisibility());
  }

  /** Cold decoder timing on Android; separate from the real touch test. */
  private void benchmarkDecoder() throws Exception
  {
    long started = SystemClock.elapsedRealtime();
    File user = new File(getTargetContext().getCacheDir(), "pinyin-benchmark-user.dat");
    try (android.content.res.AssetFileDescriptor asset = getTargetContext().getAssets().openFd("pinyin/dict_pinyin.dat");
        juloo.keyboard2.pinyin.PinyinDecoder decoder = new juloo.keyboard2.pinyin.PinyinDecoder(
          asset.getParcelFileDescriptor().getFd(), asset.getStartOffset(), asset.getLength(), user.getAbsolutePath()))
    {
      timing("dictionary open", started);
      started = SystemClock.elapsedRealtime();
      juloo.keyboard2.pinyin.PinyinComposition input = new juloo.keyboard2.pinyin.PinyinComposition(decoder);
      timing("spelling rules initialization", started);
      for (char letter : "nihaozhognguo".toCharArray())
      {
        started = SystemClock.elapsedRealtime();
        input.append(letter);
        input.getCandidateCount();
        timing("key " + letter, started);
      }
    }
    if (!user.delete()) throw new AssertionError("Could not remove benchmark user dictionary");
  }

  private void timing(String phase, long started)
  {
    Bundle status = new Bundle();
    status.putString("stream", phase + ": " + (SystemClock.elapsedRealtime() - started) + " ms\n");
    sendStatus(0, status);
  }

  private void focus(EditText editor) throws Exception
  {
    await("editor window focus", () -> activity.hasWindowFocus());
    onMain(() -> {
      editor.requestFocus();
      InputMethodManager manager = (InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
      manager.restartInput(editor);
      manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
      return null;
    });
    // A real editor tap also handles asynchronous IME binding on a cold device.
    tap(editor);
    await("IME window", () -> keyboard() != null && keyboard().isShown() && keyboard().getWidth() > 0);
    idle();
  }

  /** Real AudioRecord/touches with a silent mic and synthetic ASR sentence replies. */
  private void microphoneInput() throws Exception
  {
    if (getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new AssertionError("Grant microphone permission before this opt-in test");
    if ("cancel".equals(voiceMicrophoneCase))
    {
      clear();
      onMain(() -> {
        activity.plain.setText("你好第一句。第二句。");
        activity.plain.setSelection(activity.plain.length());
        return null;
      });
      cancelMicrophoneInput();
      return;
    }
    if (!"all".equals(voiceMicrophoneCase))
      throw new IllegalArgumentException("Unknown microphone case: " + voiceMicrophoneCase);
    clear();
    type("nihao");
    key("voice_typing");
    text(activity.plain, "你好");
    Object run = awaitRecording();
    juloo.keyboard2.doubao.DoubaoAsrClient.Listener listener =
      (juloo.keyboard2.doubao.DoubaoAsrClient.Listener)run;
    listener.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"第一句\",\"is_interim\":true,\"is_vad_finished\":true,\"index\":0}]}"));
    listener.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"第一句。\",\"is_final\":true,\"is_vad_finished\":true,\"index\":0}]}"));
    text(activity.plain, "你好第一句。");
    long firstFrames = (Long)field(run, "audioFramesSent");
    await("microphone continues after sentence end", () -> {
      if (field(voice(), "activeRun") != run || (Boolean)field(run, "stopRequested"))
        throw new AssertionError("A sentence result stopped tap recording");
      return (Long)field(run, "audioFramesSent") >= firstFrames + 50;
    });
    listener.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"第二句。\",\"is_final\":true,\"is_vad_finished\":true,\"index\":1}]}"));
    text(activity.plain, "你好第一句。第二句。");
    if (!onMain(() -> voice().isActive()) || (Boolean)field(run, "stopRequested"))
      throw new AssertionError("The second sentence stopped tap recording");
    passed("sentence-end replies keep real microphone capture active and append the next sentence");
    key("voice_typing");
    await("microphone stop and final result", () -> !voice().isActive());
    await("recorder release", () -> field(run, "recorder") == null);
    passed("tap starts real microphone capture and a second tap releases it");

    float[] point = onMain(() -> keyPoint("voice_typing", false));
    long down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1]);
    Object held = awaitRecording();
    injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1]);
    await("voice hold release", () -> !voice().isActive());
    await("held recorder release", () -> field(held, "recorder") == null);
    passed("holding the microphone records until the finger is released");

    cancelMicrophoneInput();
  }

  private void cancelMicrophoneInput() throws Exception
  {
    key("voice_typing");
    Object cancelled = awaitRecording();
    type("a");
    await("manual typing cancels voice", () -> !voice().isActive());
    await("cancelled recorder release", () -> field(cancelled, "recorder") == null);
    text(activity.plain, "你好第一句。第二句。a");
    passed("manual pinyin input cancels microphone capture and keeps the editor text");
  }

  private Object awaitRecording() throws Exception
  {
    await("microphone frames sent", () -> {
      Object run = field(voice(), "activeRun");
      return run != null && (Long)field(run, "audioFramesSent") >= 3;
    });
    return onMain(() -> field(voice(), "activeRun"));
  }

  private juloo.keyboard2.doubao.DoubaoVoiceInput voice() throws Exception
  {
    Object receiver = ((KeyEventHandler)Config.globalConfig().handler)._recv;
    return (juloo.keyboard2.doubao.DoubaoVoiceInput)field(field(receiver, "this$0"), "_doubaoVoiceInput");
  }

  /** Actual Android widget measurement/rendering; separate from window touches. */
  private void emojiTouchChecks() throws Exception
  {
    clear();
    chinese(true);
    int keyboardHeight = onMain(() -> ((View)keyboard().getParent()).getHeight());
    float[] entry = onMain(() -> {
      Keyboard2View view = keyboard();
      KeyboardData data = (KeyboardData)field(view, "_keyboard");
      Theme.Computed theme = (Theme.Computed)field(view, "_tc");
      float unit = (Float)field(view, "_keyWidth");
      float margin = (Float)field(view, "_marginLeft");
      int[] location = new int[2]; view.getLocationOnScreen(location);
      float y = theme.margin_top;
      for (KeyboardData.Row row : data.rows)
      {
        y += row.shift * theme.row_height;
        float x = margin + theme.margin_left;
        for (KeyboardData.Key key : row.keys)
        {
          x += key.shift * unit;
          float w = unit*key.width-theme.horizontal_margin;
          float h = row.height*theme.row_height-theme.vertical_margin;
          for (int i=1; i<9; i++)
            if (KeyValue.getKeyByName("switch_emoji").equals(key.keys[i]))
            {
              float dx = Keyboard2View.LABEL_POSITION_H[i] == android.graphics.Paint.Align.LEFT ? -.45f
                : Keyboard2View.LABEL_POSITION_H[i] == android.graphics.Paint.Align.RIGHT ? .45f : 0;
              float dy = Keyboard2View.LABEL_POSITION_V[i] == Keyboard2View.Vertical.TOP ? -.45f
                : Keyboard2View.LABEL_POSITION_V[i] == Keyboard2View.Vertical.BOTTOM ? .45f : 0;
              return new float[] {location[0]+x+w/2, location[1]+y+h/2,
                location[0]+x+w/2+dx*w, location[1]+y+h/2+dy*h};
            }
          x += key.width*unit;
        }
        y += row.height*theme.row_height;
      }
      throw new AssertionError("Emoji gesture entry is absent");
    });
    swipe(entry[0], entry[1], entry[2], entry[3]);
    await("emoji pane", () -> emojiPane() != null && emojiPane().isShown());
    await("emoji pane preserves keyboard height", () -> emojiPane().getHeight() == keyboardHeight);
    EmojiGroupButtonsBar bar = onMain(() -> (EmojiGroupButtonsBar)emojiPane().getChildAt(0));
    ViewGroup groups = onMain(() -> (ViewGroup)field(bar, "_strip"));
    tap(onMain(() -> groups.getChildAt(1)));
    EmojiGridView grid = onMain(() -> emojiPane().findViewById(R.id.emoji_grid));
    if (!onMain(() -> groups.getChildAt(1).isSelected() && grid.current_group() == 0))
      throw new AssertionError("Emoji group selection is not synchronized");
    Rect strip = onMain(() -> { Rect b=new Rect(); bar.getGlobalVisibleRect(b); return b; });
    swipe(strip.right-10, strip.left+10, strip.exactCenterY());
    if (onMain(() -> grid.current_group()) != 0)
      throw new AssertionError("Dragging emoji categories selected a group on pointer down");
    await("visible emoji cells", () -> grid.getChildCount()>0);
    TextView emoji = onMain(() -> (TextView)grid.getChildAt(0));
    String value = onMain(() -> emoji.getText().toString());
    tap(emoji);
    text(activity.plain, value);
    screenshot("emoji-panel.png");
    Keyboard2View bottom = onMain(() -> (Keyboard2View)emojiPane().getChildAt(2));
    float[] back = onMain(() -> keyPoint(bottom, "switch_back_emoji", false));
    touch(back[0], back[1]);
    await("emoji return", () -> keyboard().isShown() && activity.plain.hasFocus());
    text(activity.plain, value);
    passed("emoji gesture, category drag cancellation, actual commit and focused return");
  }

  private ViewGroup emojiPane() throws Exception
  {
    Object receiver = ((KeyEventHandler)Config.globalConfig().handler)._recv;
    return (ViewGroup)field(field(receiver, "this$0"), "_emojiPane");
  }

  private void candidateGeometryChecks() throws Exception
  {
    final int[] normalTextHeight = new int[1];
    for (float scale : new float[] {1f, 2f})
    {
      final float fontScale = scale;
      onMain(() -> {
        android.content.res.Configuration config = new android.content.res.Configuration(getTargetContext().getResources().getConfiguration());
        config.fontScale = fontScale;
        android.content.Context context = new android.view.ContextThemeWrapper(
            getTargetContext().createConfigurationContext(config), R.style.SoftLight);
        juloo.keyboard2.pinyin.PinyinCandidatesView panel = new juloo.keyboard2.pinyin.PinyinCandidatesView(context, null);
        panel.setState(true, "shi", java.util.Arrays.asList("是", "事情", "时间", "世界", "实际", "使用", "十", "试", "市", "式"), true);
        int densityWidth = Math.round(640 * context.getResources().getDisplayMetrics().density);
        panel.measure(View.MeasureSpec.makeMeasureSpec(densityWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        ((View)field(panel, "more")).performClick();
        if (((View)field(panel, "more")).isLongClickable())
          throw new AssertionError("Full-height paging must not advertise an inactive long-click");
        // Reflow the same candidate data from wide to narrow without a re-query.
        int narrow = Math.round(320 * context.getResources().getDisplayMetrics().density);
        panel.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        panel.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        ViewGroup lines = (ViewGroup)field(panel, "expandedItems");
        if (lines.getChildCount() == 0) throw new AssertionError("Expanded candidates are empty");
        TextView firstCell = (TextView)((ViewGroup)lines.getChildAt(0)).getChildAt(0);
        if (fontScale == 1f) normalTextHeight[0] = firstCell.getLineHeight();
        else if (firstCell.getLineHeight() <= normalTextHeight[0])
          throw new AssertionError("Font scale did not affect the actual candidate glyph metrics");
        for (int rowIndex=0; rowIndex<lines.getChildCount(); rowIndex++)
        {
          ViewGroup line = (ViewGroup)lines.getChildAt(rowIndex);
          for (int i=0; i<line.getChildCount(); i++)
          {
            TextView cell = (TextView)line.getChildAt(i);
            android.graphics.Paint.FontMetricsInt fm = cell.getPaint().getFontMetricsInt();
            if (cell.getHeight() < fm.bottom-fm.top || cell.getRight() > lines.getWidth())
              throw new AssertionError("Candidate clipped at fontScale="+fontScale+" row="+rowIndex
                  +" cellHeight="+cell.getHeight()+" fontHeight="+(fm.bottom-fm.top)
                  +" cellRight="+cell.getRight()+" contentWidth="+lines.getWidth());
          }
        }
        Bitmap render = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
        panel.setBackgroundColor(new KeyboardUi(context).background);
        panel.draw(new android.graphics.Canvas(render));
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getCacheDir(),
            fontScale == 2f ? "pinyin-font-2.png" : "pinyin-font-1.png")))
        {
          if (!render.compress(Bitmap.CompressFormat.PNG, 100, output))
            throw new AssertionError("Unable to save candidate widget render");
        }
        render.recycle();
        final int[] pagingCalls = {0};
        panel.setListener(new juloo.keyboard2.pinyin.PinyinCandidatesView.Listener() {
          public void onCandidateSelected(int index) {}
          public void onCommitRaw() {}
          public void onMoreCandidates() { pagingCalls[0]++; }
        });
        Config liveConfig = Config.globalConfig();
        int savedHeight = liveConfig.screenHeightPixels;
        try
        {
          // Exercise a short-height configuration on this isolated widget,
          // without rotating the editor or changing system settings.
          liveConfig.screenHeightPixels = new KeyboardUi(context).dp(80);
          panel.refreshConfig(liveConfig);
          if ((Integer)field(panel, "expandedBudget") != 0)
            throw new AssertionError("Short-height expansion must not force an overflow floor");
          View arrow = (View)field(panel, "more");
          if (!arrow.isLongClickable() || !arrow.performLongClick() || pagingCalls[0] != 1)
            throw new AssertionError("Compact paging must remain available on the existing arrow");
        }
        finally { liveConfig.screenHeightPixels = savedHeight; }
        return null;
      });
    }
    passed("Android font-scale 1/2 measurement and wide-to-narrow candidate reflow");
  }

  private void narrowKeyboardChecks() throws Exception
  {
    onMain(() -> {
      Keyboard2View canvas = new Keyboard2View(keyboard().getContext(), null);
      KeyboardData data = (KeyboardData)field(keyboard(), "_keyboard");
      canvas.setKeyboard(data);
      int narrow = Math.round(240 * canvas.getResources().getDisplayMetrics().density);
      for (int mode : new int[] {View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST})
      {
        canvas.measure(View.MeasureSpec.makeMeasureSpec(narrow, mode),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (canvas.getMeasuredWidth() != narrow) throw new AssertionError("Keyboard ignored the parent's narrow width");
        Theme.Computed theme = (Theme.Computed)field(canvas, "_tc");
        float unit = (Float)field(canvas, "_keyWidth");
        float left = (Float)field(canvas, "_marginLeft");
        float y = Config.globalConfig().marginTop;
        java.lang.reflect.Method hit = Keyboard2View.class.getDeclaredMethod("getKeyAtPosition", float.class, float.class);
        hit.setAccessible(true);
        for (KeyboardData.Row row : data.rows)
        {
          y += row.shift * theme.row_height;
          float x = left;
          for (KeyboardData.Key key : row.keys)
          {
            x += key.shift * unit;
            if (x < 0 || x + key.width*unit > canvas.getMeasuredWidth() + 1)
              throw new AssertionError("A narrow key lies outside the measured canvas");
            if (hit.invoke(canvas, x + key.width*unit/2, y + row.height*theme.row_height/2) != key)
              throw new AssertionError("Narrow keyboard drawing and hit regions disagree");
            x += key.width * unit;
          }
          y += row.height * theme.row_height;
        }
      }
      return null;
    });
    passed("narrow parent measurement preserves every key center hit region");
  }

  private void clear() throws Exception
  {
    onMain(() -> { activity.plain.setText(""); activity.plain.requestFocus(); return null; });
    idle();
  }

  private void chinese(boolean enabled) throws Exception
  {
    if (onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isChinese()) != enabled)
    {
      boolean phone = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isChinese()
          && ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey());
      if (phone) key("switch_pinyin");
      else
      {
        float[] point = onMain(() -> keyPoint("ctrl", true));
        swipe(point[0], point[1], point[2], point[3]);
      }
      await("Ctrl northwest language switch", () ->
          ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isChinese() == enabled);
    }
  }

  private void type(String letters) throws Exception
  {
    for (char c : letters.toCharArray()) key(String.valueOf(c));
  }

  private void key(String name) throws Exception
  {
    float[] point = onMain(() -> keyPoint(name, false));
    touch(point[0], point[1]);
  }

  private float[] keyPoint(String name, boolean northwest) throws Exception
  {
    return keyPoint(keyboard(), name, northwest);
  }

  private float[] keyPoint(Keyboard2View view, String name, boolean northwest) throws Exception
  {
      KeyboardData layout = (KeyboardData)field(view, "_keyboard");
      Theme.Computed theme = (Theme.Computed)field(view, "_tc");
      float width = (Float)field(view, "_keyWidth");
      float left = (Float)field(view, "_marginLeft");
      int[] location = new int[2];
      view.getLocationOnScreen(location);
      float y = theme.margin_top;
      for (KeyboardData.Row row : layout.rows)
      {
        y += row.shift * theme.row_height;
        float x = left + theme.margin_left;
        for (KeyboardData.Key key : row.keys)
        {
          x += key.shift * width;
          KeyValue value = key.keys[0];
          boolean matches = value != null && value.equals(KeyValue.getKeyByName(name));
          if ("enter".equals(name) && value != null && value.getKind() == KeyValue.Kind.Event
              && value.getEvent() == KeyValue.Event.ACTION) matches = true;
          if (matches)
          {
            if (northwest && !KeyValue.getKeyByName("switch_pinyin").equals(key.keys[1]))
              throw new AssertionError("The language switch is not in Ctrl's northwest corner");
            float keyWidth = width * key.width - theme.horizontal_margin;
            float keyHeight = row.height * theme.row_height - theme.vertical_margin;
            return new float[] {location[0] + x + keyWidth / 2, location[1] + y + keyHeight / 2,
              location[0] + x + keyWidth * 0.05f, location[1] + y + keyHeight * 0.05f};
          }
          x += width * key.width;
        }
        y += row.height * theme.row_height;
      }
      throw new AssertionError("Key is absent: " + name);
  }

  private void tapCandidate(String text) throws Exception
  {
    await("candidate " + text, () -> findText(candidatesView(), text) != null);
    // Exercise the same horizontal drag as a user. requestRectangleOnScreen
    // is not a touch gesture and does not reliably scroll an IME window.
    for (int attempt = 0; !onMain(() -> findText(candidatesView(), text)
          .getGlobalVisibleRect(new Rect())); attempt++)
    {
      if (attempt == 32) throw new AssertionError("Candidate not reachable by scrolling: " + text);
      Rect bounds = onMain(() -> {
        View strip = (View)field(candidatesView(), "candidateScroll");
        Rect visible = new Rect();
        if (!strip.getGlobalVisibleRect(visible)) throw new AssertionError("Candidate strip is not visible");
        return visible;
      });
      swipe(bounds.right - bounds.width() * 0.1f,
          bounds.left + bounds.width() * 0.1f, bounds.exactCenterY());
    }
    tap(onMain(() -> findText(candidatesView(), text)));
  }

  private void tap(View view) throws Exception
  {
    float[] point = onMain(() -> {
      Rect bounds = new Rect();
      if (!view.getGlobalVisibleRect(bounds)) throw new AssertionError("View is not visible");
      return new float[] {bounds.exactCenterX(), bounds.exactCenterY()};
    });
    touch(point[0], point[1]);
  }

  private void touch(float x, float y) throws Exception
  {
    long down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, x, y);
    SystemClock.sleep(30);
    injectTouch(down, MotionEvent.ACTION_UP, x, y);
    idle();
  }

  private void swipe(float startX, float endX, float y) throws Exception
  {
    swipe(startX, y, endX, y);
  }

  private void swipe(float startX, float startY, float endX, float endY) throws Exception
  {
    long down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, startX, startY);
    for (int step = 1; step <= 12; step++)
    {
      SystemClock.sleep(20);
      injectTouch(down, MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 12,
          startY + (endY - startY) * step / 12);
    }
    // Stop before lifting the finger so the target does not move under the tap.
    SystemClock.sleep(150);
    injectTouch(down, MotionEvent.ACTION_UP, endX, endY);
    idle();
  }

  private void injectTouch(long down, int action, float x, float y)
  {
    MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
    boolean accepted = getUiAutomation().injectInputEvent(event, true);
    event.recycle();
    if (!accepted) throw new AssertionError("Touch event rejected: " + action);
  }

  private void text(EditText editor, String expected) throws Exception
  {
    try { await("text = " + expected, () -> editor.getText().toString().equals(expected)); }
    catch (AssertionError error)
    {
      throw new AssertionError("Expected [" + expected + "] but was ["
        + onMain(() -> editor.getText().toString()) + "]", error);
    }
  }

  private void idle() throws Exception { waitForIdleSync(); getUiAutomation().waitForIdle(100, 15000); }

  private void await(String description, Callable<Boolean> condition) throws Exception
  {
    long deadline = SystemClock.uptimeMillis() + 30000;
    while (SystemClock.uptimeMillis() < deadline)
    {
      if (onMain(condition)) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Timed out: " + description);
  }

  private <T> T onMain(Callable<T> action) throws Exception
  {
    // Conditions already run on the UI thread; nested helpers must not dispatch
    // synchronously back onto that same thread.
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action.call();
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(() -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } });
    if (failure.get() != null) throw new RuntimeException(failure.get());
    return result.get();
  }

  private Keyboard2View keyboard() throws Exception
  {
    Config config = Config.globalConfig();
    if (config == null || !(config.handler instanceof KeyEventHandler)) return null;
    Object receiver = ((KeyEventHandler)config.handler)._recv;
    Object service = field(receiver, "this$0");
    return (Keyboard2View)field(service, "_keyboard_layout_view");
  }

  private View candidatesView() throws Exception
  {
    return ((View)keyboard().getParent()).findViewById(R.id.pinyin_candidates_view);
  }

  private static Object field(Object object, String name) throws Exception
  {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(object);
  }

  private static View findText(View view, String text)
  {
    if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
      {
        View found = findText(((ViewGroup)view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static View findDescription(View view, String description)
  {
    if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
      {
        View found = findDescription(((ViewGroup)view).getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private void passed(String description)
  {
    checks++;
    Bundle status = new Bundle();
    status.putString("stream", "PASS " + checks + ": " + description + "\n");
    sendStatus(0, status);
  }
}
