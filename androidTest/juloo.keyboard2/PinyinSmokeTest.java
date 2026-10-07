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
      if (!onMain(() -> candidatesView().isShown() && findText(candidatesView(), getTargetContext().getString(R.string.ux_clipboard)) != null))
        throw new AssertionError("Clipboard toolbar is not discoverable while idle");
      type("nihao");
      if (onMain(() -> candidatesView().getHeight()) > Math.round(
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
      View loadMore = onMain(() -> findText((View)field(candidatesView(), "expandedItems"), getTargetContext().getString(R.string.pinyin_more_candidates)));
      for (int attempt=0; !onMain(() -> loadMore.getGlobalVisibleRect(new Rect())); attempt++)
      {
        if (attempt >= 30) throw new AssertionError("More candidates button is unreachable");
        Rect r = onMain(() -> { Rect b=new Rect(); ((View)field(candidatesView(), "expandedScroll")).getGlobalVisibleRect(b); return b; });
        swipe(r.exactCenterX(), r.bottom-10, r.exactCenterX(), r.top+10);
      }
      tap(loadMore);
      await("additional candidate cells", () ->
          ((ViewGroup)field(candidatesView(), "items")).getChildCount() > beforeMore);
      key("enter");
      text(activity.plain, "shi");
      passed("scrolling to and loading additional candidates");

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
    else key("switch_pinyin_layout");
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
    android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    java.util.List<android.view.accessibility.AccessibilityNodeInfo> readings = root.findAccessibilityNodeInfosByText("ming");
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
    tap(onMain(() -> findText(candidatesView(),getTargetContext().getString(R.string.ux_clipboard))));
    await("clipboard panel", () -> clipboard()!=null && clipboard().isShown());
    tap(onMain(() -> findText(clipboard(),"A plain current clip")));
    text(activity.plain,"A plain current clip");
    if(!onMain(() -> clipboard().isShown())) throw new AssertionError("Pasting unexpectedly closed the clipboard panel");
    if(!onMain(() -> service.clear_expired_and_get_history().isEmpty())) throw new AssertionError("Disabled recording saved a clip");
    passed("visible toolbar and whole-card paste work with history recording disabled");

    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_pin))));
    if(!onMain(() -> "A plain current clip".equals(service.current_text()))) throw new AssertionError("Pinning erased the system clipboard");
    tap(onMain(() -> (View)field(clipboard(),"pinnedTab")));
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
    tap(onMain(() -> findText(clipboard(),getTargetContext().getString(R.string.ux_clear))));
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
    tap(onMain(() -> findText(clipboard(),getTargetContext().getString(R.string.ux_keyboard))));
    await("return to keyboard", () -> keyboard().isShown());
    clear(); nineKey(true); nineKeys("64426");
    screenshot("nine-key-light.png");
    key("space");
    onMain(() -> { DirectBootAwarePreferences.get_shared_preferences(getTargetContext()).edit().putString("theme","soft_dark").apply(); return null; });
    idle(); focus(activity.plain); clear(); nineKeys("64426");
    screenshot("nine-key-dark.png");
    tap(onMain(() -> findText(candidatesView(),getTargetContext().getString(R.string.ux_clipboard))));
    await("dark clipboard panel", () -> clipboard().isShown());
    screenshot("clipboard-dark.png");
    tap(onMain(() -> findText(clipboard(),getTargetContext().getString(R.string.ux_keyboard))));
    passed("light/dark surfaces and clipboard return preserve editor focus");
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
      Keyboard2View view = keyboard();
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
    await("text = " + expected, () -> editor.getText().toString().equals(expected));
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
