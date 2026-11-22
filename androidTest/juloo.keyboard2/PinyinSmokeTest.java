package juloo.keyboard2;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import juloo.keyboard2.doubao.DoubaoVoiceInput;
import juloo.keyboard2.pinyin.PinyinCandidatesView;

/** Injects real window touch events into the installed IME and real EditTexts. */
public final class PinyinSmokeTest extends Instrumentation
{
  private PinyinTestActivity activity;
  private int checks;
  private boolean benchmark;
  private boolean uxCapture;
  private boolean largePinyinFont;
  private boolean lowerPinyinEditor;
  private boolean pinyinPagingSelection;
  private boolean pinyinToolbar;
  private boolean nineKeySplitting;
  private boolean nineKeyFuzzy;
  private boolean nineKeyTypo;
  private int landscapeEditorHeight;
  private boolean voiceLive;
  private boolean voiceFresh;
  private boolean voiceContinuous;
  private boolean voiceQuality;
  private boolean voiceRegression;
  private boolean voiceStatusLayout;
  private String voiceStatusTheme;
  private boolean voiceMicrophone;
  private String voiceMicrophoneCase;

  @Override public void onCreate(Bundle arguments)
  {
    super.onCreate(arguments);
    uxCapture = arguments != null && "true".equals(arguments.getString("ux_capture"));
    largePinyinFont = arguments != null && "true".equals(arguments.getString("large_pinyin_font"));
    lowerPinyinEditor = arguments != null && "true".equals(arguments.getString("lower_pinyin_editor"));
    pinyinPagingSelection = arguments != null && "true".equals(arguments.getString("pinyin_paging_selection"));
    pinyinToolbar = arguments != null && "true".equals(arguments.getString("pinyin_toolbar"));
    nineKeySplitting = arguments != null && "true".equals(arguments.getString("nine_key_splitting"));
    nineKeyFuzzy = arguments != null && "true".equals(arguments.getString("nine_key_fuzzy"));
    nineKeyTypo = arguments != null && "true".equals(arguments.getString("nine_key_typo"));
    landscapeEditorHeight = Integer.parseInt(arguments == null ? "0" : arguments.getString("landscape_editor_height", "0"));
    benchmark = arguments != null && "true".equals(arguments.getString("benchmark"));
    voiceLive = arguments != null && "true".equals(arguments.getString("voice_live"));
    voiceFresh = arguments != null && "true".equals(arguments.getString("voice_fresh"));
    voiceContinuous = arguments != null && "true".equals(arguments.getString("voice_continuous"));
    voiceQuality = arguments != null && "true".equals(arguments.getString("voice_quality"));
    voiceRegression = arguments != null && "true".equals(arguments.getString("voice_regression"));
    voiceStatusLayout = arguments != null && "true".equals(arguments.getString("voice_status_layout"));
    voiceStatusTheme = arguments == null ? null : arguments.getString("voice_status_theme");
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
      if (voiceQuality)
      {
        juloo.keyboard2.doubao.DoubaoLiveTest.runQuality(this);
        result.putString("stream", "DOUBAO_QUALITY_OK\n");
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
      awaitImeTouchTarget(onMain(() -> (View)field(candidatesView(), "preedit")));
      chinese(true);
      if (!nineKeySplitting) nineKey(false);
      if (voiceStatusLayout)
      {
        checkVoiceStatusLayout();
        result.putString("stream", "VOICE_STATUS_LAYOUT_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (nineKeySplitting)
      {
        checkNineKeySplitting();
        result.putString("stream", "NINE_KEY_SPLIT_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (nineKeyFuzzy)
      {
        checkNineKeyFuzzyPinyin();
        result.putString("stream", "NINE_KEY_FUZZY_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (nineKeyTypo)
      {
        checkNineKeyTypo();
        result.putString("stream", "NINE_KEY_TYPO_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (voiceMicrophone)
      {
        microphoneInput();
        result.putString("stream", "DOUBAO_MICROPHONE_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (uxCapture)
      {
        captureVisualStates();
        result.putString("stream", "UX_CAPTURE_OK\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (largePinyinFont)
      {
        checkLargePinyinWindow();
        result.putString("stream", "PINYIN_LARGE_FONT_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (landscapeEditorHeight > 0)
      {
        checkLandscapeEditor();
        result.putString("stream", "PINYIN_LANDSCAPE_EDITOR_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (pinyinToolbar)
      {
        checkPinyinToolbar();
        result.putString("stream", "PINYIN_TOOLBAR_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (pinyinPagingSelection)
      {
        clear();
        type("shi");
        String selected = selectScrolledCandidate(openExpandedPinyinCandidates(), 8);
        passed("scrolling to candidate 9 and tapping it commits " + selected);
        checkCandidatePaging(true);
        result.putString("stream", "PINYIN_PAGING_SELECTION_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      onMain(() -> {
        checkCustomKeyLabels(keyboard().getContext());
        return null;
      });
      passed("custom tap and swipe labels are drawn complete within narrow keys");
      if (onMain(() -> ((View)field(candidatesView(), "preedit")).isLongClickable()))
        throw new AssertionError("Idle mode label exposes a reading action without readings");
      englishSuggestionChecks();
      onMain(() -> { assertNoCandidateTools(candidatesView()); return null; });
      int idlePinyinHeight = onMain(() -> candidatesView().getHeight());
      float idleKeyY = onMain(() -> keyPoint("n", false)[1]);
      type("nihao");
      int composingPinyinHeight = onMain(() -> candidatesView().getHeight());
      if (composingPinyinHeight != idlePinyinHeight)
        throw new AssertionError("Pinyin strip height changed while composing: "
            + idlePinyinHeight + " -> " + composingPinyinHeight);
      if (Math.abs(onMain(() -> keyPoint("n", false)[1]) - idleKeyY) > 1f
          || !onMain(() -> activity.plain.hasFocus()))
        throw new AssertionError("Composition moved the typing target or stole editor focus");
      if (getTargetContext().getResources().getConfiguration().fontScale <= 1f
          && composingPinyinHeight > Math.round(
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

      checkCandidatePaging(false);

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
      numericLayerChecks();
      clipboardChecks();
      emojiChecks(false);
      narrowLayoutChecks();
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

  /** Deterministic real-window screenshots. Run only on a disposable emulator. */
  private void captureVisualStates() throws Exception
  {
    android.content.SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(getTargetContext());
    onMain(() -> { prefs.edit().putString("theme", "soft_light").apply(); return null; });
    focus(activity.plain); clear(); chinese(true); nineKey(false);
    screenshot("keyboard-idle.png");
    type("nihao"); screenshot("keyboard-light.png");
    openExpandedPinyinCandidates();
    screenshot("candidates-expanded.png");
    chooseCandidateExpansion(R.string.ux_candidate_collapse);
    key("space"); text(activity.plain, "你好");
    clear(); nineKey(true); nineKeys("64426"); screenshot("nine-key-light.png");
    onMain(() -> { prefs.edit().putString("theme", "soft_dark").apply(); return null; });
    focus(activity.plain); clear(); nineKeys("64426"); screenshot("nine-key-dark.png");
    onMain(() -> { prefs.edit().putString("theme", "soft_light").apply(); return null; });
    focus(activity.plain); clear(); nineKey(false);
    ClipboardHistoryService service = onMain(() -> ClipboardHistoryService.get_service(getTargetContext()));
    onMain(() -> { ClipboardHistoryService.set_history_enabled(true); service.clear_history_and_current();
      getTargetContext().getSharedPreferences("pinned_clipboards", 0).edit().clear().commit(); return null; });
    openClipboardFromMode();
    await("clipboard capture", () -> clipboard() != null && clipboard().isShown());
    screenshot("clipboard-empty.png");
    onMain(() -> { service.add_clip("The meeting has moved to 3:30 PM. I will send the notes afterwards.");
      service.add_clip("本周设计评审\n请复核候选词与键盘布局。");
      service.add_clip("Could you send me the updated version? Thank you!"); return null; });
    screenshot("clipboard-light.png");
    tap(onMain(() -> findText(clipboard(), getTargetContext().getString(R.string.ux_keyboard))));
    onMain(() -> { prefs.edit().putString("theme", "soft_dark").apply(); return null; });
    focus(activity.plain);
    openClipboardFromMode();
    await("dark clipboard capture", () -> clipboard() != null && clipboard().isShown());
    screenshot("clipboard-dark.png");
    tap(onMain(() -> findText(clipboard(), getTargetContext().getString(R.string.ux_keyboard))));
    onMain(() -> { prefs.edit().putString("theme", "soft_light").apply(); return null; });
    emojiChecks(true);
    captureDictionaryStates();
  }

  private void emojiChecks(boolean capture) throws Exception
  {
    focus(activity.plain);
    clear();
    chinese(true);
    nineKey(false);
    onMain(() -> {
      getTargetContext().getSharedPreferences("emoji_last_use", 0).edit().clear().commit();
      return null;
    });
    int keyboardHeight = onMain(() -> ((View)keyboard().getParent()).getHeight());
    // The alphabetic layout opens emoji from Fn's southwest corner.
    float[] entry = onMain(() -> keyPoint("fn", false));
    swipe(entry[0], entry[1], entry[2], 2 * entry[1] - entry[3]);
    await("emoji pane preserves keyboard height", () -> {
      ViewGroup pane = emojiPane();
      return pane != null && pane.isShown() && pane.getHeight() == keyboardHeight;
    });
    ViewGroup emojiPane = onMain(() -> emojiPane());
    if (capture) screenshot("emoji.png");
    String recentLabel = emojiPane.getContext().getString(R.string.emoji_recent_category);
    View recent = onMain(() -> findDescription(emojiPane, recentLabel));
    if (recent == null || (android.os.Build.VERSION.SDK_INT >= 26
        && !android.text.TextUtils.equals(recentLabel, onMain(() -> recent.getTooltipText()))))
      throw new AssertionError("The recent emoji icon needs a localized tooltip");
    tap(recent);
    await("recent emoji empty state", () -> emojiPane.findViewById(R.id.emoji_empty_state).isShown());
    if (capture) screenshot("emoji-empty.png");
    tap(onMain(() -> emojiPane.findViewById(R.id.emoji_browse)));
    await("browse smileys", () -> ((EmojiGridView)emojiPane.findViewById(R.id.emoji_grid)).getCount() > 0);
    EmojiGridView grid = (EmojiGridView)emojiPane.findViewById(R.id.emoji_grid);
    String emoji = onMain(() -> ((TextView)grid.getChildAt(0)).getText().toString());
    tap(onMain(() -> grid.getChildAt(0)));
    text(activity.plain, emoji);
    tap(onMain(() -> findDescription(emojiPane, getTargetContext().getString(R.string.emoji_recent_category))));
    await("recently inserted emoji", () -> grid.getCount() == 1);
    if (capture) screenshot("emoji-recent.png");
    float[] back = onMain(() -> keyPoint((Keyboard2View)emojiPane.getChildAt(2), "switch_back_emoji", false));
    touch(back[0], back[1]);
    await("emoji returns to typing", () -> keyboard().isShown());
    passed("emoji preserves keyboard height, uses real touches and returns to the editor");
  }

  private ViewGroup emojiPane() throws Exception
  {
    Object receiver = ((KeyEventHandler)Config.globalConfig().handler)._recv;
    return (ViewGroup)field(field(receiver, "this$0"), "_emojiPane");
  }

  private void narrowLayoutChecks() throws Exception
  {
    onMain(() -> {
      Keyboard2View keys = keyboard();
      Context context = keys.getContext();
      KeyboardUi ui = new KeyboardUi(context);
      int width = ui.dp(180);
      int narrow = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
      int natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
      Keyboard2View constrained = new Keyboard2View(context, null);
      constrained.setKeyboard((KeyboardData)field(keys, "_keyboard"));
      constrained.measure(narrow, natural);
      if (constrained.getMeasuredWidth() != width)
        throw new AssertionError("The keyboard ignored its parent width");

      PinyinCandidatesView panel = new PinyinCandidatesView(context, null);
      panel.setState(true, "ni", java.util.Arrays.asList("足够长的候选词组用来验证换行", "你好", "你知道"), false);
      panel.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(320), View.MeasureSpec.EXACTLY), natural);
      panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
      java.lang.reflect.Method setExpanded = PinyinCandidatesView.class.getDeclaredMethod("setExpanded", boolean.class);
      setExpanded.setAccessible(true);
      setExpanded.invoke(panel, true);
      panel.measure(narrow, natural);
      panel.layout(0, 0, width, panel.getMeasuredHeight());
      ViewGroup lines = (ViewGroup)field(panel, "expandedItems");
      if (lines.getChildCount() == 0) throw new AssertionError("No expanded candidates were laid out");
      for (int i = 0; i < lines.getChildCount(); i++)
      {
        ViewGroup row = (ViewGroup)lines.getChildAt(i);
        for (int j = 0; j < row.getChildCount(); j++)
          if (row.getChildAt(j).getRight() > width)
            throw new AssertionError("An expanded candidate extends beyond its viewport");
      }

      android.content.res.Configuration large = new android.content.res.Configuration(
          context.getResources().getConfiguration());
      large.fontScale = 2f;
      EmojiGridView grid = new EmojiGridView(context.createConfigurationContext(large), null);
      grid.setEmojiGroup(0);
      TextView emoji = (TextView)grid.getAdapter().getView(0, null, grid);
      if (emoji.getLayoutParams().height < emoji.getLineHeight()
          || grid.getRequestedColumnWidth() < emoji.getPaint().measureText(emoji.getText().toString()))
        throw new AssertionError("Large-font emoji is wider or taller than its cell");
      return null;
    });
    passed("constrained keyboard and candidate widths, and emoji at 2x font scale");
  }

  private void checkLandscapeEditor() throws Exception
  {
    if (!Config.globalConfig().orientation_landscape)
      throw new AssertionError("Set the emulator to landscape first");
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(getTargetContext());
    String pref = Config.globalConfig().foldable_unfolded
        ? "keyboard_height_landscape_unfolded" : "keyboard_height_landscape";
    boolean hasHeight = prefs.contains(pref);
    int originalHeight = prefs.getInt(pref, 50);
    try
    {
      ScrollView scroll = onMain(() -> {
        prefs.edit().putInt(pref, landscapeEditorHeight).apply();
        ViewGroup fields = (ViewGroup)activity.plain.getParent();
        ((ViewGroup)fields.getParent()).removeView(fields);
        ScrollView view = new ScrollView(activity);
        view.addView(fields);
        FrameLayout root = new FrameLayout(activity);
        FrameLayout.LayoutParams scrollArea = new FrameLayout.LayoutParams(-1, -1);
        root.addView(view, scrollArea);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
          Insets visible = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
          scrollArea.setMargins(visible.left, visible.top, visible.right, visible.bottom);
          view.setLayoutParams(scrollArea);
          return insets;
        });
        activity.setContentView(root);
        activity.plain.requestFocus();
        InputMethodManager manager = (InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        manager.restartInput(activity.plain);
        manager.showSoftInput(activity.plain, InputMethodManager.SHOW_IMPLICIT);
        return view;
      });
      await("IME window", () -> keyboard() != null && keyboard().isShown() && keyboard().getWidth() > 0);
      idle();
      View panel = onMain(this::candidatesView);
      float keyY = onMain(() -> keyPoint("n", false)[1]);
      type("nihao");
      await("Chinese candidates in scroll editor", () -> findText(panel, "你好") != null);
      onMain(() -> {
        Rect field = new Rect();
        activity.plain.getDrawingRect(field);
        activity.plain.requestRectangleOnScreen(field, true);
        return null;
      });
      idle();
      screenshot("landscape-scroll-editor.png");
      EditorVisibility state = onMain(() -> editorVisibility(panel));
      String geometry = "height=" + landscapeEditorHeight + " font=" + activity.getResources().getConfiguration().fontScale
          + " editorBottom=" + state.lineBottom + " panelTop=" + state.panelTop + " viewport=" + onMain(scroll::getHeight)
          + " keyCenterY=" + keyY;
      if (!state.lineVisible || !onMain(() -> activity.plain.hasFocus()))
        throw new AssertionError("Focused line is clipped even after scrolling. " + geometry);
      if (Math.abs(onMain(() -> keyPoint("n", false)[1]) - keyY) > 1f)
        throw new AssertionError("Typing moved the landscape keyboard. " + geometry);
      passed("composing line is visible above the keyboard. " + geometry);

      openExpandedPinyinCandidates();
      View expanded = onMain(() -> (View)field(panel, "expandedScroll"));
      await("landscape expanded candidate", () -> expanded.isShown() && findText(expanded, "你好") != null);
      if (Math.abs(onMain(() -> keyPoint("n", false)[1]) - keyY) > 1f)
        throw new AssertionError("Candidate expansion moved the landscape keyboard. " + geometry);
      passed("expanded landscape candidates leave editor and keys in place. "
          + onMain(() -> checkEditorAboveCandidates(panel, state)));
      tap(onMain(() -> findText(expanded, "你好")));
      text(activity.plain, "你好");
      if (!onMain(() -> activity.plain.hasFocus() && editorVisibility(panel).lineVisible))
        throw new AssertionError("Landscape candidate tap did not keep the editor visible");
      passed("real candidate tap commits 你好 in the visible editor");
    }
    finally
    {
      onMain(() -> {
        SharedPreferences.Editor edit = prefs.edit();
        if (hasHeight) edit.putInt(pref, originalHeight);
        else edit.remove(pref);
        edit.apply();
        return null;
      });
    }
  }

  private void checkLargePinyinWindow() throws Exception
  {
    View panel = onMain(this::candidatesView);
    if (panel.getResources().getConfiguration().fontScale != 2f)
      throw new AssertionError("Set the emulator's system font_scale to 2.0 first");
    if (lowerPinyinEditor)
    {
      onMain(() -> {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)activity.plain.getLayoutParams();
        params.topMargin = new KeyboardUi(activity).dp(100);
        activity.plain.setLayoutParams(params);
        return null;
      });
      idle();
    }
    float idleKeyY = onMain(() -> keyPoint("n", false)[1]);
    type("nihao");
    await("large-font candidate", () -> findText(panel, "你好") != null);
    onMain(() -> {
      if (Math.abs(keyPoint("n", false)[1] - idleKeyY) > 1f || !activity.plain.hasFocus())
        throw new AssertionError("Large-font composition moved the keyboard or editor focus");
      assertCandidateLineFits((TextView)findText(panel, "你好"));
      return null;
    });
    EditorVisibility collapsed = onMain(() -> editorVisibility(panel));
    screenshot("large-pinyin-inline.png");

    openExpandedPinyinCandidates();
    View expanded = onMain(() -> (View)field(panel, "expandedScroll"));
    await("large-font expanded candidate", () -> expanded.isShown() && findText(expanded, "你好") != null);
    screenshot("large-pinyin-expanded.png");
    String editorBounds = onMain(() -> {
      assertCandidateLineFits((TextView)findText(expanded, "你好"));
      return checkEditorAboveCandidates(panel, collapsed);
    });
    Rect list = onMain(() -> {
      Rect bounds = new Rect();
      if (!expanded.getGlobalVisibleRect(bounds)) throw new AssertionError("Expanded list is not visible");
      return bounds;
    });
    int bottom = list.bottom - list.height() / 8;
    int top = list.top + list.height() / 8;
    swipe(list.centerX(), bottom, list.centerX(), top, true);
    await("expanded list scrolls", () -> expanded.getScrollY() > 0);
    awaitCandidateScrollSettled(expanded);
    for (int i = 0; i < 3 && onMain(expanded::getScrollY) > 0; i++)
    {
      swipe(list.centerX(), top, list.centerX(), bottom, true);
      awaitCandidateScrollSettled(expanded);
    }
    await("expanded list returns to its first row", () -> expanded.getScrollY() == 0);
    tap(onMain(() -> findText(expanded, "你好")));
    text(activity.plain, "你好");
    if (Math.abs(onMain(() -> keyPoint("n", false)[1]) - idleKeyY) > 1f)
      throw new AssertionError("Expanded candidate selection changed the typing position");
    passed("real expanded pinyin candidates fit, scroll and commit at 2x system font scale; " + editorBounds);

    focus(activity.password);
    if (onMain(() -> candidatesView().isShown()))
      throw new AssertionError("Chinese candidates visible in a password editor at large font scale");
    focus(activity.plain);
    await("large-font candidate panel returns", () -> candidatesView().isShown());
    checkCandidatePaging(false);

    clear();
    type("nihao");
    View current = onMain(this::candidatesView);
    openExpandedPinyinCandidates();
    View oldResults = onMain(() -> (View)field(current, "expandedScroll"));
    await("expanded list before editing composition", () -> oldResults.isShown());
    onMain(() -> {
      Config.globalConfig().handler.key_up(KeyValue.getKeyByName("backspace"), Pointers.Modifiers.EMPTY);
      if (oldResults.isShown())
        throw new AssertionError("Previous candidate cells remain exposed after editing the composition");
      return null;
    });
    passed("editing a composition immediately hides its old expanded candidates");

    checkLongExpandedCandidate();
  }

  private void assertNoCandidateTools(View panel)
  {
    int[] labels = { R.string.ux_clipboard, R.string.ux_candidate_switch_full,
        R.string.ux_candidate_switch_nine, R.string.ux_more_candidates,
        R.string.ux_candidate_collapse, R.string.pinyin_more_candidates };
    for (int label : labels)
    {
      String description = panel.getContext().getString(label);
      View control = findDescription(panel, description);
      if (control != null && control.isShown())
        throw new AssertionError("Persistent tool in candidate strip: " + description);
    }
  }

  private void openClipboardFromMode() throws Exception
  {
    tap(onMain(() -> (View)field(candidatesView(), "preedit")));
    choosePinyinMenuItem(getTargetContext().getString(R.string.ux_clipboard));
  }

  private void checkPinyinToolbar() throws Exception
  {
    View panel = onMain(this::candidatesView);
    TextView preedit = onMain(() -> (TextView)field(panel, "preedit"));
    int panelHeight = onMain(panel::getHeight);
    float keyY = onMain(() -> keyPoint("n", false)[1]);
    screenshot("pinyin-toolbar-idle.png");
    onMain(() -> { assertNoCandidateTools(panel); return null; });

    type("nihao");
    screenshot("pinyin-toolbar-composing.png");
    int available = onMain(() -> {
      assertNoCandidateTools(panel);
      int width = assertReadableToolbarText(preedit, "nihao");
      if (panel.getHeight() != panelHeight || Math.abs(keyPoint("n", false)[1] - keyY) > 1f)
        throw new AssertionError("Composing resized the keyboard");
      return width;
    });
    ViewGroup expanded = openExpandedPinyinCandidates();
    await("expanded row lists 你好", () -> findText(expanded, "你好") != null);
    screenshot("pinyin-toolbar-expanded.png");
    onMain(() -> { assertNoCandidateTools(panel); return null; });
    chooseCandidateExpansion(R.string.ux_candidate_collapse);
    await("candidate row returns to the horizontal list",
        () -> ((View)field(panel, "candidateScroll")).isShown()
            && !((View)field(panel, "expandedScroll")).isShown());
    text(activity.plain, "nihao");
    passed("long-pressing spelling expands and collapses candidates without changing the editor");
    tap(preedit);
    text(activity.plain, "nihao");
    if (!onMain(() -> activity.plain.hasFocus()
        && !((KeyEventHandler)Config.globalConfig().handler)._pinyin.isComposing()))
      throw new AssertionError("Tapping preedit did not commit raw spelling in the editor");
    onMain(() -> { assertNoCandidateTools(panel); return null; });
    passed("preedit has " + available + "px and tools stay out of the idle, typing and post-commit rows");

    clear();
    openClipboardFromMode();
    await("idle mode menu opens clipboard", () -> clipboard() != null && clipboard().isShown());
    screenshot("pinyin-toolbar-clipboard.png");
    tap(onMain(() -> findText(clipboard(), getTargetContext().getString(R.string.ux_keyboard))));
    await("keyboard is visible after closing clipboard", () -> candidatesView().isShown());
    View idleMode = onMain(() -> (View)field(candidatesView(), "preedit"));
    tap(idleMode);
    screenshot("pinyin-toolbar-idle-menu.png");
    choosePinyinMenuItem(panel.getContext().getString(R.string.ux_candidate_layout_nine));
    await("mode menu switches to nine-key", () -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey());
    nineKeys("6464");
    screenshot("pinyin-toolbar-ambiguous.png");
    String current = onMain(() -> {
      String value = activity.plain.getText().toString();
      String displayed = preedit.getText().toString();
      assertNoCandidateTools(panel);
      if (value.length() < 4 || !displayed.replace(" ", "").equals(value) || !preedit.isLongClickable())
        throw new AssertionError("The ambiguous reading did not reach the toolbar");
      assertReadableToolbarText(preedit, displayed);
      return displayed;
    });
    if (!onMain(() -> {
      AccessibilityNodeInfo node = preedit.createAccessibilityNodeInfo();
      try
      {
        String label = panel.getContext().getString(R.string.ux_candidate_options);
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
          if (action.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK && label.contentEquals(action.getLabel())) return true;
        return false;
      }
      finally { node.recycle(); }
    })) throw new AssertionError("Missing accessible options on the preedit");
    longPressPreedit(preedit);
    screenshot("pinyin-toolbar-menu.png");
    String fullLayoutLabel = panel.getContext().getString(R.string.ux_candidate_layout_full);
    int menuWindowId = pinyinMenuItem(fullLayoutLabel).getWindowId();
    checkReadingMenuBounds(panel, preedit);
    if (!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
      throw new AssertionError("Could not send system Back");
    waitForPinyinMenuDismissal(menuWindowId);
    if (!onMain(() -> panel.isShown() && activity.plain.hasFocus()
        && ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isComposing()
        && current.replace(" ", "").contentEquals(activity.plain.getText())))
      throw new AssertionError("Back closed the keyboard or changed the composition: " + onMain(() -> activity.plain.getText().toString()));
    passed("system Back dismisses only the reading menu and keeps the composition");

    longPressPreedit(preedit);
    choosePinyinMenuItem("ming");
    text(activity.plain, "ming");
    key("space");
    text(activity.plain, "明");
    passed("the readable preedit exposes named options and lets the user choose and commit 明");

    clear();
    nineKeys("6464");
    longPressPreedit(preedit);
    choosePinyinMenuItem("nini");
    text(activity.plain, "nini");
    passed("scrolling the compact menu reaches the reading beyond its first four rows");
    onMain(() -> { activity.plain.setSelection(0); return null; });
    await("moving the cursor ends the selected spelling", () ->
        !((KeyEventHandler)Config.globalConfig().handler)._pinyin.isComposing());
    key("space");
    text(activity.plain, " nini");
    passed("moving the cursor after the picker leaves the original spelling alone");

    clear();
    nineKeys("6464");
    longPressPreedit(preedit);
    choosePinyinMenuItem(fullLayoutLabel);
    await("toolbar returns to 26-key", () -> !((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey());
    if (!onMain(() -> activity.plain.hasFocus())) throw new AssertionError("Layout switch stole editor focus");
    passed("both layout switch directions work while preserving the editor focus");
    checkEnglishCandidateTools();
    if (!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
      throw new AssertionError("Could not send system Back");
    await("Back without a menu still hides the keyboard", () -> !panel.isShown());
    passed("system Back still hides the keyboard when no reading menu is open");
  }

  private void longPressPreedit(View view) throws Exception
  {
    Rect bounds = onMain(() -> visibleTouchBounds(view));
    long down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
    SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100);
    injectTouch(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
  }

  private void checkReadingMenuBounds(View panel, View preedit) throws Exception
  {
    String geometry = onMain(() -> {
      android.widget.ListPopupWindow popup = (android.widget.ListPopupWindow)field(panel, "readingMenu");
      android.widget.ListView list = popup.getListView();
      Rect menuBounds = visibleTouchBounds(list);
      Rect preeditBounds = visibleTouchBounds(preedit);
      int visible = list.getChildCount();
      // The platform clamps the popup to the display frame with rounded pixel offsets.
      int tolerance = Math.round(2 * panel.getResources().getDisplayMetrics().density);
      int overlap = Math.min(menuBounds.bottom - preeditBounds.top, preeditBounds.top - menuBounds.top);
      if (visible > 4 || visible == 0 || overlap > tolerance)
        throw new AssertionError("Reading menu straddles the keyboard edge or has too many visible rows: "
            + menuBounds + " above " + preeditBounds + ", visible rows=" + visible);
      Rect frame = new Rect();
      preedit.getWindowVisibleDisplayFrame(frame);
      int above = preeditBounds.top - frame.top;
      int firstTwoRows = list.getChildAt(0).getHeight()
          + (visible > 1 ? list.getChildAt(1).getHeight() : 0);
      boolean openAbove = menuBounds.bottom <= preeditBounds.top + tolerance;
      if (openAbove != (above >= firstTwoRows))
        throw new AssertionError("Reading menu was placed on the wrong side of the keyboard: "
            + menuBounds + ", space above=" + above + ", first rows=" + firstTwoRows);
      return "popup=" + menuBounds + ", preedit=" + preeditBounds + ", visible rows=" + visible
          + ", opens above=" + openAbove;
    });
    passed("the reading menu is compact and stays at the keyboard edge; " + geometry);
  }

  private void checkEnglishCandidateTools() throws Exception
  {
    int previousType = onMain(activity.plain::getInputType);
    try
    {
      clear();
      onMain(() -> { activity.plain.setInputType(android.text.InputType.TYPE_CLASS_TEXT); return null; });
      focus(activity.plain);
      chinese(false);
      type("teh");
      englishCandidate("the");
      await("English suggestions row is visible", () -> {
        View panel = candidatesView();
        View latin = (View)field(panel, "latinCandidates");
        return latin.isShown();
      });
      onMain(() -> {
        juloo.keyboard2.suggestions.CandidatesView latin = (juloo.keyboard2.suggestions.CandidatesView)
            field(candidatesView(), "latinCandidates");
        latin.set_candidates(((KeyEventHandler)Config.globalConfig().handler)._suggestions);
        return null;
      });
      await("English candidate is rendered without toolbar controls", () -> {
        View panel = candidatesView();
        View candidate = findText(panel, "the");
        assertNoCandidateTools(panel);
        return candidate != null && candidate.isShown();
      });
      screenshot("pinyin-toolbar-english.png");
      passed("English suggestions appear without toolbar controls");
    }
    finally
    {
      onMain(() -> { activity.plain.setInputType(previousType); return null; });
      focus(activity.plain);
      chinese(true);
      clear();
    }
  }

  private int assertReadableToolbarText(TextView preedit, String text)
  {
    Rect bounds = new Rect();
    int usable = preedit.getWidth() - preedit.getCompoundPaddingLeft() - preedit.getCompoundPaddingRight();
    int needed = (int)Math.ceil(preedit.getPaint().measureText(text));
    if (!text.contentEquals(preedit.getText()) || !preedit.getGlobalVisibleRect(bounds)
        || usable < needed || preedit.getLayout().getEllipsisCount(0) != 0)
      throw new AssertionError("Preedit '" + text + "' gets " + usable + "px but needs " + needed + "px");
    return usable;
  }

  private void checkLongExpandedCandidate() throws Exception
  {
    String phrase = "中华人民共和国";
    clear();
    type("zhonghuarenmingongheguo");
    View panel = onMain(this::candidatesView);
    openExpandedPinyinCandidates();
    View results = onMain(() -> (View)field(panel, "expandedScroll"));
    await("long expanded candidate", () -> results.isShown() && findText(results, phrase) != null);
    TextView candidate = onMain(() -> (TextView)findText(results, phrase));
    View parent = onMain(() -> (View)candidate.getParent());
    Rect bounds = onMain(() -> {
      if (candidate.getLayout().getEllipsisCount(0) > 0)
        throw new AssertionError("Expanded candidates truncate " + phrase);
      Rect visible = new Rect();
      if (!candidate.getGlobalVisibleRect(visible)) throw new AssertionError("Long candidate is hidden");
      return visible;
    });
    int overflow = onMain(() -> candidate.getWidth() - bounds.width());
    if (overflow > 0)
    {
      for (int attempt = 0; attempt < 6 && onMain(() -> parent.getScrollX()) < overflow; attempt++)
        swipe(bounds.right - 10, bounds.left + 10, bounds.centerY());
      await("the whole phrase is reachable in the expanded list", () -> parent.getScrollX() >= overflow);
    }
    tap(candidate);
    text(activity.plain, phrase);
    passed("a long expanded candidate can be read in full and tapped (horizontal overflow " + overflow + "px)");
  }

  private static final class EditorVisibility
  {
    final int lineBottom;
    final int panelTop;
    final boolean lineVisible;

    EditorVisibility(int lineBottom, int panelTop, boolean lineVisible)
    {
      this.lineBottom = lineBottom;
      this.panelTop = panelTop;
      this.lineVisible = lineVisible;
    }
  }

  private EditorVisibility editorVisibility(View panel)
  {
    EditText editor = activity.plain;
    int[] origin = new int[2];
    editor.getLocationOnScreen(origin);
    int offset = editor.getTotalPaddingTop() - editor.getScrollY();
    int line = editor.getLayout().getLineForOffset(editor.getSelectionEnd());
    int top = offset + editor.getLayout().getLineTop(line);
    int bottom = offset + editor.getLayout().getLineBottom(line);
    int screenBottom = origin[1] + bottom;
    panel.getLocationOnScreen(origin);
    Rect visible = new Rect();
    boolean lineVisible = screenBottom <= origin[1] && editor.getLocalVisibleRect(visible)
        && visible.top <= top && visible.bottom >= bottom;
    return new EditorVisibility(screenBottom, origin[1], lineVisible);
  }

  private String checkEditorAboveCandidates(View panel, EditorVisibility collapsed)
  {
    EditorVisibility expanded = editorVisibility(panel);
    String bounds = "editor line ends at " + expanded.lineBottom + "px, candidate panel starts at "
        + expanded.panelTop + "px (collapsed: " + collapsed.panelTop + "px)";
    if (!activity.plain.hasFocus() || expanded.panelTop != collapsed.panelTop
        || (collapsed.lineVisible && !expanded.lineVisible))
      throw new AssertionError("Expanded candidates obscure the editor line: " + bounds);
    return bounds;
  }

  private void assertCandidateLineFits(TextView candidate)
  {
    int line = candidate.getLayout().getHeight();
    Rect bounds = new Rect();
    if (!candidate.getGlobalVisibleRect(bounds) || bounds.height() != candidate.getHeight()
        || candidate.getHeight() < line)
      throw new AssertionError("Large-font candidate '" + candidate.getText()
          + "' has " + candidate.getHeight() + "px for a " + line + "px line");
  }

  private ViewGroup openExpandedPinyinCandidates() throws Exception
  {
    chooseCandidateExpansion(R.string.ux_more_candidates);
    await("expanded candidate panel", () -> ((View)field(candidatesView(), "expandedScroll")).isShown());
    return onMain(() -> (ViewGroup)field(candidatesView(), "expandedItems"));
  }

  private void chooseCandidateExpansion(int label) throws Exception
  {
    longPressPreedit(onMain(() -> (View)field(candidatesView(), "preedit")));
    choosePinyinMenuItem(getTargetContext().getString(label));
  }

  private String selectScrolledCandidate(ViewGroup list, int index) throws Exception
  {
    TextView entry = onMain(() -> {
      int remaining = index;
      for (int i = 0; i < list.getChildCount(); i++)
      {
        ViewGroup line = (ViewGroup)list.getChildAt(i);
        if (remaining < line.getChildCount())
        {
          View cell = line.getChildAt(remaining);
          if (cell instanceof ViewGroup) cell = ((ViewGroup)cell).getChildAt(0);
          return (TextView)cell;
        }
        remaining -= line.getChildCount();
      }
      throw new AssertionError("Expanded candidate " + index + " is missing");
    });
    if (onMain(() -> candidateCenterVisible(entry)))
      throw new AssertionError("Candidate " + index + " should require scrolling");
    View scroll = onMain(() -> (View)field(candidatesView(), "expandedScroll"));
    Rect bounds = onMain(() -> {
      Rect visible = new Rect();
      if (!scroll.getGlobalVisibleRect(visible)) throw new AssertionError("Expanded list is hidden");
      return visible;
    });
    int margin = bounds.height() / 8;
    for (int i = 0; i < 2 * index + 8 && !onMain(() -> candidateCenterVisible(entry)); i++)
    {
      swipe(bounds.centerX(), bounds.bottom - margin, bounds.centerX(), bounds.top + margin);
      awaitCandidateScrollSettled(scroll);
    }
    await("the center of scrolled candidate " + index + " is visible", () -> candidateCenterVisible(entry));
    String shown = onMain(() -> entry.getText().toString());
    float[] point = onMain(() -> {
      Rect visible = new Rect();
      if (!entry.getGlobalVisibleRect(visible)) throw new AssertionError("Scrolled candidate is hidden");
      int[] origin = new int[2];
      entry.getLocationOnScreen(origin);
      return new float[] {visible.exactCenterX(), origin[1] + entry.getHeight() / 2f};
    });
    touch(point[0], point[1]);
    text(activity.plain, shown);
    if (!onMain(() -> activity.plain.hasFocus() && !((KeyEventHandler)Config.globalConfig().handler)._pinyin.isComposing()))
      throw new AssertionError("Tapping candidate " + index + " did not commit in the original editor");
    return shown;
  }

  private void awaitCandidateScrollSettled(View scroll) throws Exception
  {
    int[] lastPosition = {onMain(scroll::getScrollY)};
    long[] stableSince = {SystemClock.uptimeMillis()};
    await("candidate scroll settles", () -> {
      int position = scroll.getScrollY();
      long now = SystemClock.uptimeMillis();
      if (position != lastPosition[0])
      {
        lastPosition[0] = position;
        stableSince[0] = now;
      }
      return now - stableSince[0] >= 150;
    });
  }

  private boolean candidateCenterVisible(View candidate)
  {
    Rect bounds = new Rect();
    if (!candidate.getGlobalVisibleRect(bounds)) return false;
    int[] origin = new int[2];
    candidate.getLocationOnScreen(origin);
    int center = origin[1] + candidate.getHeight() / 2;
    int margin = Math.round(4 * candidate.getResources().getDisplayMetrics().density);
    return center >= bounds.top + margin && center < bounds.bottom - margin;
  }

  private void checkCandidatePaging(boolean selectNewCandidate) throws Exception
  {
    clear();
    type("shi");
    EditorVisibility collapsed = largePinyinFont || selectNewCandidate
      ? onMain(() -> editorVisibility(candidatesView())) : null;
    ViewGroup expanded = openExpandedPinyinCandidates();
    int beforeMore = onMain(() -> expandedCandidateCount(expanded));
    View panel = onMain(this::candidatesView);
    onMain(() -> { assertNoCandidateTools(panel); return null; });
    chooseCandidateExpansion(R.string.pinyin_more_candidates);
    await("additional expanded candidate cells", () -> expandedCandidateCount(expanded) > beforeMore);
    onMain(() -> {
      assertNoCandidateTools(panel);
      if (!activity.plain.hasFocus()) throw new AssertionError("Paging stole the editor focus");
      if (collapsed != null) checkEditorAboveCandidates(candidatesView(), collapsed);
      return null;
    });
    if (selectNewCandidate)
    {
      String selected = selectScrolledCandidate(expanded, beforeMore);
      passed("paging from the preedit menu and tapping candidate " + (beforeMore + 1) + " commits " + selected);
    }
    else
    {
      key("enter");
      text(activity.plain, "shi");
      passed("paging from the preedit menu loads additional candidates and preserves editor focus");
    }
  }

  private int expandedCandidateCount(ViewGroup list)
  {
    int count = 0;
    for (int i = 0; i < list.getChildCount(); i++)
      count += ((ViewGroup)list.getChildAt(i)).getChildCount();
    return count;
  }

  private void checkCustomKeyLabels(Context context) throws Exception
  {
    String tapLabel = "WWWWWWWWWWWW";
    String swipeLabel = "SWIPELABEL";
    KeyboardData layout = KeyboardData.load_string_exn("<keyboard width=\"10\" bottom_row=\"false\">"
        + "<row><key c=\"" + tapLabel + "\" nw=\"" + swipeLabel + "\"/>"
        + "<key c=\"a\" nw=\"b\"/></row></keyboard>");
    Keyboard2View keys = new Keyboard2View(context, null);
    keys.setKeyboard(layout);
    int width = new KeyboardUi(context).dp(180);
    keys.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    keys.layout(0, 0, keys.getMeasuredWidth(), keys.getMeasuredHeight());
    Theme.Computed theme = (Theme.Computed)field(keys, "_tc");
    float keyWidth = (Float)field(keys, "_keyWidth") - theme.horizontal_margin;
    java.util.Map<String, Float> widths = new java.util.HashMap<>();
    java.util.Map<String, Float> scales = new java.util.HashMap<>();
    Bitmap bitmap = Bitmap.createBitmap(keys.getWidth(), keys.getHeight(), Bitmap.Config.ARGB_8888);
    try
    {
      keys.draw(new Canvas(bitmap)
      {
        @Override public void drawText(String text, float x, float y, Paint paint)
        {
          widths.put(text, paint.measureText(text));
          scales.put(text, paint.getTextScaleX());
          super.drawText(text, x, y, paint);
        }

        @Override public void drawText(String text, int start, int end, float x, float y, Paint paint)
        {
          drawText(text.substring(start, end), x, y, paint);
        }
      });
    }
    finally { bitmap.recycle(); }
    if (!widths.containsKey(swipeLabel))
      throw new AssertionError("Custom swipe label was shortened. Drawn labels: " + widths.keySet());
    if (!widths.containsKey(tapLabel) || widths.get(tapLabel) > keyWidth + 0.01f
        || widths.get(swipeLabel) > keyWidth / 2f + 0.01f
        || widths.get(swipeLabel) <= 0f)
      throw new AssertionError("Custom labels did not fit their key and corner: " + widths);
    if (!Float.valueOf(1f).equals(scales.get("a")) || !Float.valueOf(1f).equals(scales.get("b")))
      throw new AssertionError("Label scaling carried over to the next key: " + scales);
  }

  /** Render production download states deterministically; these are view-state
      fixtures, not claims that a network download succeeded or failed. */
  @SuppressWarnings("unchecked")
  private void captureDictionaryStates() throws Exception
  {
    Intent intent = new Intent(getTargetContext(), juloo.keyboard2.dict.DictionariesActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Activity page = startActivitySync(intent);
    View list = page.findViewById(R.id.dictionary_recommended_list);
    java.util.List<?> rows = onMain(() -> (java.util.List<?>)field(list, "_dict_views"));
    if (rows.isEmpty()) throw new AssertionError("Use the English-locale capture fixture");
    String name = onMain(() -> (String)field(rows.get(0), "dict_name"));
    java.lang.reflect.Method refresh = list.getClass().getDeclaredMethod("refresh");
    refresh.setAccessible(true);
    onMain(() -> { ((java.util.Set<String>)field(list, "_pending")).add(name); refresh.invoke(list); return null; });
    screenshot("dictionaries-loading-fixture.png");
    if (onMain(() -> ((View)field(rows.get(0), "download_button")).isEnabled()))
      throw new AssertionError("A pending download must not be started twice");
    onMain(() -> { ((java.util.Set<String>)field(list, "_pending")).clear();
      ((java.util.Set<String>)field(list, "_failed")).add(name); refresh.invoke(list); return null; });
    screenshot("dictionaries-error-fixture.png");
    if (!onMain(() -> ((View)field(rows.get(0), "download_button")).isEnabled()))
      throw new AssertionError("A failed download must offer retry");
    onMain(() -> { page.finish(); return null; });
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
    else
    {
      View mode = onMain(() -> (View)field(candidatesView(), "preedit"));
      if (onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isComposing()))
        longPressPreedit(mode);
      else tap(mode);
      choosePinyinMenuItem(getTargetContext().getString(R.string.ux_candidate_layout_full));
    }
    await("nine-key layout switch", () ->
      ((KeyEventHandler)Config.globalConfig().handler)._pinyin.isNineKey() == enabled);
  }

  private void nineKeys(String keys) throws Exception
  {
    for (char key : keys.toCharArray())
    {
      if (key == '\'')
      {
        float[] point = onMain(() -> keyPoint("pinyin_2", false));
        swipe(point[0], point[1], point[2], 2 * point[1] - point[3]);
      }
      else key("pinyin_" + key);
    }
  }

  private void checkNineKeySplitting() throws Exception
  {
    clear();
    nineKey(true);
    String mixedPrefix = "5468426782426426724942";
    nineKeys(mixedPrefix);
    onMain(() -> {
      KeyEventHandler handler = (KeyEventHandler)Config.globalConfig().handler;
      handler.key_up(KeyValue.getKeyByName("o"), Pointers.Modifiers.EMPTY);
      juloo.keyboard2.pinyin.ChineseComposition current =
          (juloo.keyboard2.pinyin.ChineseComposition)field(handler._pinyin, "composition");
      if (!current.getSpelling().equals(mixedPrefix + "o"))
        throw new AssertionError("Nine-key input did not retain its literal letter");
      return null;
    });
    key("backspace");
    onMain(() -> {
      KeyEventHandler handler = (KeyEventHandler)Config.globalConfig().handler;
      juloo.keyboard2.pinyin.ChineseComposition current =
          (juloo.keyboard2.pinyin.ChineseComposition)field(handler._pinyin, "composition");
      if (!current.getSpelling().equals(mixedPrefix))
        throw new AssertionError("Nine-key backspace did not remove its literal letter");
      return null;
    });
    passed("nine-key long composition accepts and deletes a literal letter");
    for (String[] example : new String[][] {
        {"54684267824264267249426", "今天去长安拍照", "16"},
        {"64649262486365433324726", "明早出门记得带伞", "24"},
        {"94298743426524484", "下午四点开会", "16"},
        {"64648426789426524484", "明天去西安开会", "16"},
        {"74645465824542694", "请尽快联系", "8"},
        {"74645465824542694", "请尽快交易", "1"},
        {"48454294468924542694", "回家以后再联系", "8"},
        {"966369474244926326", "我们一起吃晚饭", "8"},
        {"966369474244926326", "我们一起吃完饭", "1"},
        {"96636546926244926326", "我们今晚吃晚饭", "8"},
        {"26649426986426843", "从西安坐高铁", "32"},
        {"2664942698646645426", "从西安坐公交", "16"},
        {"2664942698634843", "从西安坐地铁", "16"},
        {"94262343369247426248", "先备份再删除", "16"},
        {"942633645492424832", "先登记再出发", "16"},
        {"942646874269245469426", "先购票再进站", "16"},
        {"94264687426924787426", "先购票再取票", "16"}})
    {
      clear();
      nineKeys(example[0]);
      int position = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
          .getCandidates().indexOf(example[1]) + 1);
      if (position == 0 || position > Integer.parseInt(example[2]))
        throw new AssertionError("Nine-key sequence " + example[1] + " ranked " + position);
      tapCandidate(example[1]);
      text(activity.plain, example[1]);
      passed("nine-key " + example[1] + " commits from candidate " + position);
    }
    clear();
    nineKeys("94262343369247426248");
    if (onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
        .getCandidates().contains("先辈分再删除")))
      throw new AssertionError("Nine-key sequence accepted a conflicting middle word");
    passed("nine-key fallback rejects a conflicting same-reading middle word");
    clear();
    nineKeys("942624832");
    int rank = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
        .getCandidates().indexOf("西安出发") + 1);
    if (rank == 0 || rank > 5)
      throw new AssertionError("Nine-key alternate split 西安出发 ranked " + rank);
    tapCandidate("西安出发");
    text(activity.plain, "西安出发");

    clear();
    nineKeys("942624832");
    onMain(() -> {
      juloo.keyboard2.pinyin.PinyinInput input = ((KeyEventHandler)Config.globalConfig().handler)._pinyin;
      int prefix = input.getCandidates().indexOf("西安");
      while (prefix < 0 && input.getCandidates().size() < 256)
      {
        int previous = input.getCandidates().size();
        input.showMoreCandidates();
        prefix = input.getCandidates().indexOf("西安");
        if (input.getCandidates().size() == previous) break;
      }
      if (prefix < 0) throw new AssertionError("The alternate reading has no partial 西安 candidate");
      input.selectCandidate(prefix);
      return null;
    });
    for (int i = 0; i < 5; i++) key("backspace");
    text(activity.plain, "西安");
    key("backspace");
    await("the four original keys remain after reopening the first syllables", () ->
      ((KeyEventHandler)Config.globalConfig().handler)._pinyin.getCandidates().contains("西安"));

    clear();
    nineKeys("54684262664942624832");
    int sentenceRank = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
        .getCandidates().indexOf("今天从西安出发") + 1);
    if (sentenceRank == 0 || sentenceRank > 5)
      throw new AssertionError("Nine-key alternate split 今天从西安出发 ranked " + sentenceRank);
    tapCandidate("今天从西安出发");
    text(activity.plain, "今天从西安出发");

    StringBuilder missingPhrases = new StringBuilder();
    for (String[] example : new String[][] {
        {"5464'2646649826", "静安公园", "5"},
        {"94267823454649244849426", "先去北京再回西安", "64"},
        {"64648426966367874264424", "明天我们去上海", "5"},
        {"967894'262349426", "我去西安北站", "64"},
        {"94262262869242466474", "先保存再重启", "32"},
        {"7874264424924524484", "去上海再开会", "32"},
        {"784664982692472628", "去公园再散步", "32"},
        {"942694292492426948264", "先下载再安装", "32"},
        {"782345464924542694", "去北京再联系", "32"},
        {"782345464924542694", "去北京再交易", "32"},
        {"942623433692448438", "先备份再恢复", "32"},
        {"78466474924542694", "去公司再联系", "32"},
        {"78466474924542694", "去红旗再联系", "32"},
        {"9426385826924787426", "先付款再取票", "32"},
        {"9426385826924787426", "先付款再取钱", "32"},
        {"94263858269242487426", "先付款再出票", "10"},
        {"942622664649245426334", "先报名再缴费", "10"},
        {"9426947468924244326", "先洗手再吃饭", "10"},
        {"94262262869245426242", "先保存再检查", "10"},
        {"94262266464924783736", "先报名再确认", "10"},
        {"94266247426924787426", "先买票再取票", "32"},
        {"942634647426924385826", "先订票再付款", "32"},
        {"942672494269247426424826", "先拍照再上传", "32"}})
    {
      clear();
      nineKeys(example[0]);
      int limit = Integer.parseInt(example[2]);
      int phraseRank = onMain(() -> {
        juloo.keyboard2.pinyin.PinyinInput input = ((KeyEventHandler)Config.globalConfig().handler)._pinyin;
        while (input.getCandidates().size() < limit && !input.getCandidates().contains(example[1]))
        {
          int previous = input.getCandidates().size();
          input.showMoreCandidates();
          if (input.getCandidates().size() == previous) break;
        }
        return input.getCandidates().indexOf(example[1]) + 1;
      });
      if (phraseRank == 0 || phraseRank > limit)
        missingPhrases.append(" ").append(example[1]).append(" ranked ").append(phraseRank);
      else
      {
        tapCandidate(example[1]);
        text(activity.plain, example[1]);
      }
    }
    if (missingPhrases.length() != 0)
      throw new AssertionError("Nine-key long phrases:" + missingPhrases);
    passed("nine-key alternate split commits and backspace preserves the original key boundaries");
  }

  private void checkNineKeyFuzzyPinyin() throws Exception
  {
    clear();
    nineKey(true);
    for (String[] example : new String[][] {{"9664486", "中国"}, {"736474", "生日"},
        {"96249664486", "我爱中国"}, {"645426", "莉莉安"}})
    {
      clear();
      nineKeys(example[0]);
      int rank = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
          .getCandidates().indexOf(example[1]) + 1);
      if (rank == 0 || rank > 5)
        throw new AssertionError("Nine-key fuzzy candidate " + example[1] + " ranked " + rank);
      tapCandidate(example[1]);
      text(activity.plain, example[1]);
    }
    passed("nine-key candidates accept common approximate pronunciations through actual taps");
  }

  private void checkNineKeyTypo() throws Exception
  {
    clear();
    nineKey(true);
    for (String[] example : new String[][] {{"94646486", "中国"}, {"64246", "你好"},
        {"962494646486", "我爱中国"}})
    {
      clear();
      nineKeys(example[0]);
      int rank = onMain(() -> ((KeyEventHandler)Config.globalConfig().handler)._pinyin
          .getCandidates().indexOf(example[1]) + 1);
      if (rank == 0 || rank > 5)
        throw new AssertionError("Nine-key transposed candidate " + example[1] + " ranked " + rank);
      key("backspace");
      key("pinyin_" + example[0].charAt(example[0].length() - 1));
      tapCandidate(example[1]);
      text(activity.plain, example[1]);
    }
    passed("nine-key correction for transposed digits survives backspace and commits through a tap");
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

    checkNineKeySplitting();
    checkNineKeyFuzzyPinyin();
    checkNineKeyTypo();

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
    if (!onMain(() -> ((View)field(candidatesView(), "preedit")).isLongClickable()))
      throw new AssertionError("Preedit has readings but cannot open the picker by long press");
    longPressPreedit(onMain(() -> (View)field(candidatesView(), "preedit")));
    choosePinyinMenuItem("ming");
    key("space");
    text(activity.plain, "明");
    if (onMain(() -> ((View)field(candidatesView(), "preedit")).isLongClickable()))
      throw new AssertionError("Preedit still exposes the reading action after committing");
    passed("long-pressing preedit chooses an ambiguous reading");

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

  private void choosePinyinMenuItem(String label) throws Exception
  {
    scrollReadingMenuTo(label);
    AccessibilityNodeInfo option = pinyinMenuItem(label);
    Rect bounds = new Rect();
    option.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) throw new AssertionError("The menu choice is not visible: " + label);
    touch(bounds.exactCenterX(), bounds.exactCenterY());
    waitForPinyinMenuDismissal(option.getWindowId());
  }

  private void scrollReadingMenuTo(String label) throws Exception
  {
    ListView list = onMain(() -> {
      android.widget.ListPopupWindow popup =
          (android.widget.ListPopupWindow)field(candidatesView(), "readingMenu");
      if (popup == null || !popup.isShowing()) throw new AssertionError("The reading menu is closed");
      return popup.getListView();
    });
    int target = onMain(() -> {
      for (int i = 0; i < list.getCount(); i++)
        if (label.equals(list.getItemAtPosition(i))) return i;
      throw new AssertionError("The reading menu does not contain " + label);
    });
    for (int attempt = 0; attempt < 16; attempt++)
    {
      int direction = onMain(() -> {
        int first = list.getFirstVisiblePosition();
        if (target < first) return -1;
        View item = list.getChildAt(target - first);
        if (item == null) return 1;
        int center = item.getTop() + item.getHeight() / 2;
        if (center < 0) return -1;
        return center >= list.getHeight() ? 1 : 0;
      });
      if (direction == 0) return;
      Rect bounds = onMain(() -> visibleTouchBounds(list));
      int inset = bounds.height() / 8;
      int top = bounds.top + inset;
      int bottom = bounds.bottom - inset;
      swipe(bounds.centerX(), direction > 0 ? bottom : top,
          bounds.centerX(), direction > 0 ? top : bottom, true);
    }
    throw new AssertionError("Cannot scroll to reading " + label);
  }

  private void enableInteractiveWindows()
  {
    AccessibilityServiceInfo services = getUiAutomation().getServiceInfo();
    if ((services.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0)
    {
      services.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
      getUiAutomation().setServiceInfo(services);
    }
  }

  private void awaitImeTouchTarget(View target) throws Exception
  {
    enableInteractiveWindows();
    Region area = new Region();
    Rect windowBounds = new Rect();
    long deadline = SystemClock.uptimeMillis() + 30000;
    // A laid-out IME view can report its final position while the window is still sliding in.
    do
    {
      Rect bounds = onMain(() -> visibleTouchBounds(target));
      int targetWindow = onMain(() -> {
        AccessibilityNodeInfo node = target.createAccessibilityNodeInfo();
        try { return node.getWindowId(); }
        finally { node.recycle(); }
      });
      for (AccessibilityWindowInfo window : getUiAutomation().getWindows())
      {
        if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD || window.getId() != targetWindow)
          continue;
        if (Build.VERSION.SDK_INT >= 30)
          window.getRegionInScreen(area);
        else
        {
          window.getBoundsInScreen(windowBounds);
          area.set(windowBounds);
        }
        if (area.contains(bounds.centerX(), bounds.centerY())) return;
      }
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("The mode label is outside the touchable keyboard window");
  }

  private AccessibilityNodeInfo pinyinMenuItem(String label) throws Exception
  {
    enableInteractiveWindows();
    idle();
    // The popup can be showing before its accessibility window is published.
    long deadline = SystemClock.uptimeMillis() + 1500;
    do
    {
      for (AccessibilityWindowInfo window : getUiAutomation().getWindows())
      {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) continue;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
          if (node.isVisibleToUser() && label.equals(String.valueOf(node.getText())) && isPinyinMenuListItem(node))
            return node;
      }
      SystemClock.sleep(50);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Pinyin menu did not offer " + label);
  }

  private void waitForPinyinMenuDismissal(int menuWindowId) throws Exception
  {
    await("pinyin menu dismissed", () -> {
      for (AccessibilityWindowInfo window : getUiAutomation().getWindows())
        if (window.getId() == menuWindowId) return false;
      return true;
    });
  }

  private boolean isPinyinMenuListItem(AccessibilityNodeInfo node)
  {
    for (AccessibilityNodeInfo parent = node.getParent(); parent != null; parent = parent.getParent())
      if (android.widget.ListView.class.getName().contentEquals(parent.getClassName())) return true;
    return false;
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
    SystemClock.sleep(600); // Let the window compositor present the completed frame.
    Bitmap bitmap=getUiAutomation().takeScreenshot();
    try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),name)))
    { if(bitmap==null || !bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw new AssertionError("Screenshot failed"); }
  }

  private void numericLayerChecks() throws Exception
  {
    clear();
    nineKey(false);
    // Ctrl's southeast corner opens numbers. The numeric page exposes symbols.
    float[] ctrl = onMain(() -> keyPoint("ctrl", false));
    swipe(ctrl[0], ctrl[1], 2 * ctrl[0] - ctrl[2], 2 * ctrl[1] - ctrl[3]);
    key("7");
    text(activity.plain, "7");
    key("switch_greekmath");
    key("switch_numeric");
    key("switch_text");
    key("n");
    text(activity.plain, "7n");
    passed("Ctrl corner opens numbers, symbols and return to the same editor through real touches");
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
    int clipboardHeight = onMain(() -> {
      View keys = keyboard();
      return Math.max(new KeyboardUi(keys.getContext()).dp(280), ((View)keys.getParent()).getHeight());
    });
    openClipboardFromMode();
    await("clipboard panel", () -> clipboard()!=null && clipboard().isShown());
    await("clipboard preserves its requested height", () -> clipboard().getHeight() == clipboardHeight);
    String optionsLabel = onMain(() -> clipboard().getContext().getString(R.string.ux_clipboard_options));
    String rememberLabel = onMain(() -> clipboard().getContext().getString(R.string.ux_clipboard_remember));
    View options = onMain(() -> findDescription(clipboard(), optionsLabel));
    tap(options);
    menuText(rememberLabel);
    await("clipboard recording enabled from menu", () -> Config.globalConfig().clipboard_history_enabled);
    tap(options);
    menuText(rememberLabel);
    await("clipboard recording disabled from menu", () -> !Config.globalConfig().clipboard_history_enabled);
    tap(onMain(() -> findText(clipboard(),"A plain current clip")));
    text(activity.plain,"A plain current clip");
    if(!onMain(() -> clipboard().isShown())) throw new AssertionError("Pasting unexpectedly closed the clipboard panel");
    if(!onMain(() -> service.clear_expired_and_get_history().isEmpty())) throw new AssertionError("Disabled recording saved a clip");
    passed("visible toolbar and whole-card paste work with history recording disabled");

    // A previous run restores this fixture's pin when it verifies undo.
    View previousPin = onMain(() -> {
      View clip = findText(clipboard(), "A plain current clip");
      if (clip == null) throw new AssertionError("Current clip left the pane after paste");
      return findDescription((View)clip.getParent(), clip.getContext().getString(R.string.ux_unpin));
    });
    if (previousPin != null) tap(previousPin);
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
    tap(onMain(() -> findDescription(clipboard(),getTargetContext().getString(R.string.ux_clipboard_options))));
    menuText(getTargetContext().getString(R.string.ux_clear));
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
    key("space");
    text(activity.plain, "你好");
    openClipboardFromMode();
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
    if ("cursor_move".equals(voiceMicrophoneCase) || "cursor_clears_composition".equals(voiceMicrophoneCase))
    {
      checkVoiceCursorMove("cursor_clears_composition".equals(voiceMicrophoneCase));
      return;
    }
    if ("service_busy".equals(voiceMicrophoneCase) || "service_busy_stream".equals(voiceMicrophoneCase))
    {
      checkVoiceServiceBusy("service_busy_stream".equals(voiceMicrophoneCase));
      return;
    }
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
    if ("connecting_stop".equals(voiceMicrophoneCase) || "connecting_cancel".equals(voiceMicrophoneCase))
    {
      checkVoiceWhileConnecting("connecting_cancel".equals(voiceMicrophoneCase));
      return;
    }
    if ("capture_status".equals(voiceMicrophoneCase) || "capture_status_stop".equals(voiceMicrophoneCase))
    {
      checkVoiceCaptureStatus("capture_status_stop".equals(voiceMicrophoneCase));
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

  /** The dedicated emulator must delay ASR traffic beyond the recording window. */
  private void checkVoiceWhileConnecting(boolean cancel) throws Exception
  {
    clear();
    key("voice_typing");
    Object run = onMain(() -> field(voice(), "activeRun"));
    if (run == null) throw new AssertionError("Voice did not start");
    long stopAt = SystemClock.elapsedRealtime() + 700;
    boolean recording = false;
    while (SystemClock.elapsedRealtime() < stopAt)
    {
      android.media.AudioRecord microphone = (android.media.AudioRecord)field(run, "recorder");
      if (microphone != null && microphone.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING)
        recording = true;
      SystemClock.sleep(20);
    }
    if (cancel) type("a");
    else key("voice_typing");
    long framesAtStop = (Long)field(run, "audioFramesSent");
    if (!(Boolean)field(run, "stopRequested") || framesAtStop != 0)
      throw new AssertionError("ASR was already sending audio at stop, frames=" + framesAtStop);
    await("release microphone after closing the early recording",
        () -> !voice().isActive() && field(run, "recorder") == null);
    long frames = (Long)field(run, "audioFramesSent");
    Bundle status = new Bundle();
    status.putString("stream", (cancel ? "EARLY_VOICE_CANCEL" : "EARLY_VOICE_STOP")
        + " recording_before_stop=" + recording
        + " frames_at_stop=" + framesAtStop + " frames_after_session=" + frames + "\n");
    sendStatus(0, status);
    if (cancel)
    {
      text(activity.plain, "a");
      if (!recording || frames != 0)
        throw new AssertionError("Cancellation did not discard the early recording");
      passed("manual input cancels the connecting microphone and discards its buffered audio");
    }
    else
    {
      if (!recording)
        throw new AssertionError("Recording started too late to preserve the utterance");
      if (frames < 3)
        throw new AssertionError("The buffered recording did not reach ASR");
      passed("recording begins while connecting and early stop still sends captured audio");
    }
  }

  private void checkVoiceCursorMove(boolean clearCompositionOnTouch) throws Exception
  {
    clear();
    onMain(() -> {
      activity.plain.setText("draft ");
      activity.plain.setSelection(activity.plain.length());
      return null;
    });
    idle();
    juloo.keyboard2.doubao.DoubaoAsrClient.Listener replay = onMain(() ->
        juloo.keyboard2.doubao.DoubaoRegressionTest.attachEditorReplay(voice()));
    replay.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"试说\",\"index\":0,\"is_interim\":true}]}"));
    text(activity.plain, "draft 试说");
    idle();
    replay.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"\",\"nonstream_result\":true}]}"));
    text(activity.plain, "draft ");
    idle();
    replay.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"正常转写\",\"index\":0,\"is_interim\":true}]}"));
    text(activity.plain, "draft 正常转写");
    idle();
    if (!onMain(() -> voice().isActive()))
      throw new AssertionError("The editor's own composing update stopped voice input");

    float[] point = onMain(() -> {
      EditText editor = activity.plain;
      int[] location = new int[2];
      editor.getLocationOnScreen(location);
      float x = location[0] + editor.getTotalPaddingLeft() + 1 - editor.getScrollX();
      float y = location[1] + editor.getTotalPaddingTop()
          + editor.getLayout().getLineBottom(0) / 2f - editor.getScrollY();
      return new float[]{x, y};
    });
    if (clearCompositionOnTouch)
      onMain(() -> {
        activity.plain.setOnTouchListener((view, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) activity.plain.clearComposingText();
          return false;
        });
        return null;
      });
    try { touch(point[0], point[1]); }
    finally
    {
      if (clearCompositionOnTouch) onMain(() -> { activity.plain.setOnTouchListener(null); return null; });
    }
    await("editor tap moves the cursor to the start", () -> activity.plain.getSelectionStart() == 0);
    idle();
    replay.onResponse(juloo.keyboard2.doubao.DoubaoRegressionTest.response(
        "{\"results\":[{\"text\":\"晚到的定稿\",\"index\":0,\"is_final\":true,\"is_vad_finished\":true}]}"));
    idle();
    onMain(() -> {
      String text = activity.plain.getText().toString();
      int cursor = activity.plain.getSelectionStart();
      boolean active = voice().isActive();
      if (!"draft 正常转写".equals(text) || cursor != 0 || active)
        throw new AssertionError("After cursor move: text=" + text + ", cursor=" + cursor + ", active=" + active);
      return null;
    });
    passed("moving the cursor preserves the existing dictation and rejects a late ASR result");
  }

  private void checkVoiceServiceBusy(boolean afterSessionStart) throws Exception
  {
    clear();
    onMain(() -> {
      activity.plain.setText("draft");
      activity.plain.setSelection(activity.plain.length());
      return null;
    });
    idle();
    AtomicReference<String> message = new AtomicReference<>();
    String prefix = getTargetContext().getString(R.string.toast_voice_input_failed, "").trim();
    getUiAutomation().setOnAccessibilityEventListener(event -> {
      if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
      for (CharSequence text : event.getText())
        if (text.toString().startsWith(prefix)) message.set(text.toString());
    });
    try
    {
      onMain(() -> {
        juloo.keyboard2.doubao.DoubaoRegressionTest.replayBusyService(voice(), afterSessionStart);
        return null;
      });
      await("service rejection notice", () -> message.get() != null);
      if (uxCapture) screenshot("voice-service-busy.png");
      if (onMain(() -> voice().isActive())) throw new AssertionError("Rejected voice session remains active");
      text(activity.plain, "draft");
      boolean chinese = "zh".equals(getTargetContext().getResources().getConfiguration().locale.getLanguage());
      String expected = getTargetContext().getString(R.string.toast_voice_input_failed,
          chinese ? "豆包繁忙，请稍后再试。" : "Doubao is busy. Try again shortly.");
      if (!expected.equals(message.get()))
        throw new AssertionError("Service rejection notice: " + message.get());
      type("a");
      text(activity.plain, "drafta");
      passed("the provider busy notice explains how to retry and leaves the editor usable");
    }
    finally { getUiAutomation().setOnAccessibilityEventListener(null); }
  }

  /** Run with delayed ASR traffic so the recording prompt precedes transmission. */
  private void checkVoiceStatusLayout() throws Exception
  {
    if (voiceStatusTheme == null) { measureVoiceStatusLayout(); return; }
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(getTargetContext());
    String previous = prefs.getString("theme", null);
    try
    {
      onMain(() -> { prefs.edit().putString("theme", voiceStatusTheme).apply(); return null; });
      focus(activity.plain);
      measureVoiceStatusLayout();
    }
    finally
    {
      onMain(() -> {
        SharedPreferences.Editor edit = prefs.edit();
        if (previous == null) edit.remove("theme"); else edit.putString("theme", previous);
        edit.apply();
        return null;
      });
    }
  }

  private void measureVoiceStatusLayout() throws Exception
  {
    clear();
    float[] idleKey = onMain(() -> keyPoint("voice_typing", false));
    DoubaoVoiceInput.Host receiver = onMain(() -> (DoubaoVoiceInput.Host)field(voice(), "host"));
    StringBuilder clipped = new StringBuilder();
    try
    {
      for (DoubaoVoiceInput.State state : new DoubaoVoiceInput.State[] {
          DoubaoVoiceInput.State.CONNECTING, DoubaoVoiceInput.State.LISTENING, DoubaoVoiceInput.State.FINISHING})
      {
        onMain(() -> { receiver.onVoiceStateChanged(state); return null; });
        idle();
        String geometry = onMain(() -> {
          TextView status = (TextView)field(candidatesView(), "voiceStatus");
          android.text.Layout layout = status.getLayout();
          Rect visible = new Rect();
          if (!status.isShown() || !status.getGlobalVisibleRect(visible) || layout == null)
            throw new AssertionError("Voice label is not visible for " + state);
          if (!java.util.Arrays.equals(idleKey, keyPoint("voice_typing", false)))
            throw new AssertionError("Voice label moved the microphone key for " + state);
          int lines = layout.getLineCount();
          int ellipsis = 0;
          for (int line = 0; line < lines; line++) ellipsis += layout.getEllipsisCount(line);
          int height = Math.min(visible.height(), status.getHeight())
              - status.getCompoundPaddingTop() - status.getCompoundPaddingBottom();
          String info = state + " \"" + status.getText() + "\" width=" + status.getWidth()
              + " available_height=" + height + " text_height=" + layout.getHeight()
              + " lines=" + lines + " ellipsis=" + ellipsis;
          if (layout.getHeight() > height || ellipsis > 0) clipped.append(" [").append(info).append(']');
          return info;
        });
        screenshot("voice-status-" + state.name().toLowerCase(java.util.Locale.ROOT) + ".png");
        Bundle measured = new Bundle();
        measured.putString("stream", "VOICE_LABEL " + geometry + "\n");
        sendStatus(0, measured);
      }
      if (clipped.length() != 0) throw new AssertionError("Voice status text clipped:" + clipped);
      passed("all three voice states are fully visible with fixed microphone key coordinates");
    }
    finally { onMain(() -> { receiver.onVoiceStateChanged(DoubaoVoiceInput.State.IDLE); return null; }); }
    type("ni");
    tapCandidate("你");
    text(activity.plain, "你");
    passed("typing and candidate selection still commit after clearing the voice status");
  }

  private void checkVoiceCaptureStatus(boolean tapToStop) throws Exception
  {
    clear();
    String listening = getTargetContext().getString(R.string.voice_status_listening);
    String recognizing = getTargetContext().getString(R.string.voice_status_recognizing);
    AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
    info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    getUiAutomation().setServiceInfo(info);
    float[] voiceKey = onMain(() -> keyPoint("voice_typing", false));
    key("voice_typing");
    Object run = onMain(() -> field(voice(), "activeRun"));
    if (run == null) throw new AssertionError("Voice did not start");
    long deadline = SystemClock.elapsedRealtime() + 650;
    Rect prompt = voiceStatusBounds(listening);
    while (prompt == null && SystemClock.elapsedRealtime() < deadline)
    {
      SystemClock.sleep(25);
      prompt = voiceStatusBounds(listening);
    }
    if (uxCapture) screenshot("voice-capture-status.png");
    android.media.AudioRecord recorder = (android.media.AudioRecord)field(run, "recorder");
    boolean recording = recorder != null
        && recorder.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING;
    long sent = (Long)field(run, "audioFramesSent");
    float[] activeVoiceKey = onMain(() -> keyPoint("voice_typing", false));
    int keyboardTop = onMain(() -> {
      int[] position = new int[2];
      keyboard().getLocationOnScreen(position);
      return position[1];
    });
    if (tapToStop && prompt != null)
    {
      key("voice_typing");
      await("recognition status after tapping the microphone again",
          () -> voiceStatusBounds(recognizing) != null);
      if (!(Boolean)field(run, "stopRequested") || voiceStatusBounds(listening) != null)
        throw new AssertionError("The listening status continued after stopping the microphone");
      passed("the fixed microphone key stops recording and updates the finishing status");
    }
    type("a");
    await("release microphone after dismissing the prompt", () -> field(run, "recorder") == null);
    text(activity.plain, "a");
    if (!recording || sent != 0 || prompt == null || prompt.bottom > keyboardTop
        || !java.util.Arrays.equals(voiceKey, activeVoiceKey)
        || voiceStatusBounds(listening) != null || voiceStatusBounds(recognizing) != null)
      throw new AssertionError("Recording=" + recording + ", frames sent=" + sent
          + ", listening bounds=" + prompt + ", keys start at y=" + keyboardTop);
    passed("the accessible listening prompt stays above the fixed keys and clears on manual input");
  }

  private Rect voiceStatusBounds(String text)
  {
    for (AccessibilityWindowInfo window : getUiAutomation().getWindows())
    {
      if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null) continue;
      for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
        if (node.isVisibleToUser() && text.equals(String.valueOf(node.getText())))
        {
          Rect bounds = new Rect();
          node.getBoundsInScreen(bounds);
          if (!bounds.isEmpty()) return bounds;
        }
    }
    return null;
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
      float y = bounds.exactCenterY();
      swipe(bounds.right - bounds.width() * 0.1f, y,
          bounds.left + bounds.width() * 0.1f, y, true);
    }
    tap(onMain(() -> findText(candidatesView(), text)));
  }

  private void tap(View view) throws Exception
  {
    Rect bounds = onMain(() -> visibleTouchBounds(view));
    touch(bounds.exactCenterX(), bounds.exactCenterY());
  }

  private Rect visibleTouchBounds(View view)
  {
    if (view == null) throw new AssertionError("The requested touch target was not found");
    Rect bounds = new Rect();
    if (!view.isShown() || !view.getGlobalVisibleRect(bounds))
      throw new AssertionError("Touch target is not visible");
    // The clipped rectangle is root-relative. Injected events use screen coordinates.
    int[] origin = new int[2];
    view.getRootView().getLocationOnScreen(origin);
    bounds.offset(origin[0], origin[1]);
    return bounds;
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
    swipe(startX, startY, endX, endY, false);
  }

  private void swipe(float startX, float startY, float endX, float endY, boolean stopWithoutFling) throws Exception
  {
    long down = SystemClock.uptimeMillis();
    injectTouch(down, MotionEvent.ACTION_DOWN, startX, startY);
    for (int step = 1; step <= 12; step++)
    {
      SystemClock.sleep(20);
      injectTouch(down, MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 12,
          startY + (endY - startY) * step / 12);
    }
    if (stopWithoutFling)
    {
      // Older velocity trackers need stationary samples to finish a drag without a fling.
      for (int sample = 0; sample < 3; sample++)
      {
        SystemClock.sleep(60);
        injectTouch(down, MotionEvent.ACTION_MOVE, endX, endY);
      }
    }
    else SystemClock.sleep(150);
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
