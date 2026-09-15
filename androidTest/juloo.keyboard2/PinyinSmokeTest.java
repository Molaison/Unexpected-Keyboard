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
  private boolean voiceRegression;
  private boolean voiceMicrophone;

  @Override public void onCreate(Bundle arguments)
  {
    super.onCreate(arguments);
    benchmark = arguments != null && "true".equals(arguments.getString("benchmark"));
    voiceLive = arguments != null && "true".equals(arguments.getString("voice_live"));
    voiceFresh = arguments != null && "true".equals(arguments.getString("voice_fresh"));
    voiceRegression = arguments != null && "true".equals(arguments.getString("voice_regression"));
    voiceMicrophone = arguments != null && "true".equals(arguments.getString("voice_microphone"));
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
      if (voiceLive)
      {
        juloo.keyboard2.doubao.DoubaoLiveTest.run(this, voiceFresh);
        result.putString("stream", "DOUBAO_LIVE_OK\n");
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
      if (voiceMicrophone)
      {
        microphoneInput();
        result.putString("stream", "DOUBAO_MICROPHONE_OK checks=" + checks + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (onMain(() -> candidatesView().isShown())) throw new AssertionError("Empty Chinese candidate row occupies space");
      type("nihao");
      if (onMain(() -> candidatesView().getHeight()) > Math.round(
            48 * getTargetContext().getResources().getDisplayMetrics().density))
        throw new AssertionError("Chinese preedit and candidates exceed one compact row");
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
      tapCandidate(getTargetContext().getString(R.string.pinyin_more_candidates));
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
      result.putString("stream", "\nPINYIN_SMOKE_OK checks=" + checks + "\n");
      finish(Activity.RESULT_OK, result);
    }
    catch (Throwable error)
    {
      String trace = Log.getStackTraceString(error);
      Log.e("PinyinSmokeTest", "IME smoke test failed", error);
      result.putString("stream", "\nPINYIN_SMOKE_FAILED\n" + trace);
      result.putString("shortMsg", error.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
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
      ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(editor, 0);
      return null;
    });
    await("IME window", () -> keyboard() != null && keyboard().isShown() && keyboard().getWidth() > 0);
    idle();
  }

  /** Actual AudioRecord and keyboard touches on an emulator with a silent mic. */
  private void microphoneInput() throws Exception
  {
    if (getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != android.content.pm.PackageManager.PERMISSION_GRANTED)
      throw new AssertionError("Grant microphone permission before this opt-in test");
    clear();
    type("nihao");
    key("voice_typing");
    text(activity.plain, "你好");
    Object run = awaitRecording();
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

    key("voice_typing");
    Object cancelled = awaitRecording();
    type("a");
    await("manual typing cancels voice", () -> !voice().isActive());
    await("cancelled recorder release", () -> field(cancelled, "recorder") == null);
    text(activity.plain, "你好a");
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
      float[] point = onMain(() -> keyPoint("ctrl", true));
      swipe(point[0], point[1], point[2], point[3]);
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
