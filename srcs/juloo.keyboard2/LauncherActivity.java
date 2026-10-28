package juloo.keyboard2;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.dict.DictionariesActivity;

/** Setup follows the actual Android IME state, including after returning here. */
public class LauncherActivity extends Activity implements Handler.Callback
{
  TextView _tryhere_text;
  EditText _tryhere_area;
  final List<Animatable> _animations = new ArrayList<>();
  Handler _handler;
  private boolean enabled;
  private boolean selected;
  private boolean gesturesExpanded;
  private boolean started;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (getActionBar() != null) getActionBar().hide();
    getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setContentView(R.layout.launcher_activity);
    _tryhere_text = findViewById(R.id.launcher_tryhere_text);
    _tryhere_area = findViewById(R.id.launcher_tryhere_area);
    if (VERSION.SDK_INT >= 28)
      _tryhere_area.addOnUnhandledKeyEventListener(this.new Tryhere_OnUnhandledKeyEventListener());
    _handler = new Handler(getMainLooper(), this);
    _animations.add(find_anim(R.id.launcher_anim_swipe));
    _animations.add(find_anim(R.id.launcher_anim_round_trip));
    _animations.add(find_anim(R.id.launcher_anim_circle));
    if (savedInstanceState != null && savedInstanceState.getBoolean("gestures_expanded"))
      toggle_gestures(null);
  }

  @Override
  public void onStart()
  {
    super.onStart();
    started = true;
    scheduleAnimations();
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    refreshSetupState();
  }

  @Override
  protected void onStop()
  {
    started = false;
    _handler.removeMessages(0);
    for (Animatable anim : _animations) anim.stop();
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle state)
  {
    state.putBoolean("gestures_expanded", gesturesExpanded);
    super.onSaveInstanceState(state);
  }

  private void refreshSetupState()
  {
    InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    String ownId = new ComponentName(this, Keyboard2.class).flattenToShortString();
    enabled = false;
    for (InputMethodInfo info : imm.getEnabledInputMethodList())
    {
      if (getPackageName().equals(info.getPackageName()))
      {
        enabled = true;
        ownId = info.getId();
        break;
      }
    }
    selected = enabled && ownId.equals(Settings.Secure.getString(
        getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
    // Ready needs only a confirmation and an explicit Try action. The setup
    // instructions remain visible for the two incomplete states.
    findViewById(R.id.launcher_setup_step).setVisibility(selected ? View.GONE : View.VISIBLE);
    findViewById(R.id.launcher_setup_detail).setVisibility(selected ? View.GONE : View.VISIBLE);
    ((TextView)findViewById(R.id.launcher_setup_step)).setText(selected
        ? R.string.launcher_step_ready : enabled ? R.string.launcher_step_select : R.string.launcher_step_enable);
    ((TextView)findViewById(R.id.launcher_setup_title)).setText(selected
        ? R.string.launcher_status_ready : enabled ? R.string.launcher_status_select : R.string.launcher_status_enable);
    ((TextView)findViewById(R.id.launcher_setup_detail)).setText(selected
        ? R.string.launcher_status_ready_detail : enabled ? R.string.launcher_status_select_detail : R.string.launcher_status_enable_detail);
    ((Button)findViewById(R.id.launcher_setup_action)).setText(selected
        ? R.string.launcher_action_try : enabled ? R.string.launcher_button_imepicker : R.string.launcher_button_imesettings);
    if (!selected && _tryhere_area.hasFocus())
    {
      imm.hideSoftInputFromWindow(_tryhere_area.getWindowToken(), 0);
      _tryhere_area.clearFocus();
    }
    // A preview before setup would open some other keyboard. Make the blocked
    // state visible and accessible without discarding a previous test entry.
    _tryhere_area.setEnabled(selected);
    _tryhere_area.setFocusable(selected);
    _tryhere_area.setFocusableInTouchMode(selected);
    _tryhere_area.setHint(selected ? R.string.launcher_try_hint : R.string.launcher_try_setup_hint);
    _tryhere_area.setContentDescription(selected ? null : getString(R.string.launcher_try_setup_hint));
  }

  public void continue_setup(View view)
  {
    refreshSetupState();
    if (!enabled) launch_imesettings(view);
    else if (!selected) launch_imepicker(view);
    else
    {
      // Opening the app or returning from Android settings never takes focus.
      // The keyboard appears only after an explicit Try action or editor tap.
      _tryhere_area.requestFocus();
      _tryhere_area.post(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
          .showSoftInput(_tryhere_area, InputMethodManager.SHOW_IMPLICIT));
    }
  }

  public void toggle_gestures(View view)
  {
    gesturesExpanded = !gesturesExpanded;
    findViewById(R.id.launcher_gestures).setVisibility(gesturesExpanded ? View.VISIBLE : View.GONE);
    ((TextView)findViewById(R.id.launcher_gesture_toggle)).setText(gesturesExpanded
        ? R.string.launcher_hide_gestures : R.string.launcher_show_gestures);
    if (VERSION.SDK_INT >= 30)
      findViewById(R.id.launcher_gesture_toggle).setStateDescription(getString(gesturesExpanded
          ? R.string.launcher_hide_gestures : R.string.launcher_show_gestures));
    scheduleAnimations();
  }

  private void scheduleAnimations()
  {
    _handler.removeMessages(0);
    if (started && gesturesExpanded) _handler.sendEmptyMessageDelayed(0, 250);
    else for (Animatable anim : _animations) anim.stop();
  }

  @Override
  public boolean handleMessage(Message msg)
  {
    if (!started || !gesturesExpanded) return true;
    for (Animatable anim : _animations) anim.start();
    _handler.sendEmptyMessageDelayed(0, 3000);
    return true;
  }

  public void launch_settings(View view)
  {
    startActivity(new Intent(this, SettingsActivity.class));
  }

  public void launch_imesettings(View view)
  {
    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
  }

  public void launch_imepicker(View view)
  {
    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
    // The picker is a dialog and may not pause/resume the activity. Observe its
    // selection as well as the normal onResume route back from Android settings.
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus)
  {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && _tryhere_area != null) refreshSetupState();
  }

  public void launch_dictionaries_activity(View view)
  {
    startActivity(new Intent(this, DictionariesActivity.class));
  }

  Animatable find_anim(int id)
  {
    return (Animatable)((ImageView)findViewById(id)).getDrawable();
  }

  final class Tryhere_OnUnhandledKeyEventListener implements View.OnUnhandledKeyEventListener
  {
    public boolean onUnhandledKeyEvent(View view, KeyEvent event)
    {
      if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(event.getKeyCode()))
        return false;
      StringBuilder text = new StringBuilder();
      if (event.isAltPressed()) text.append("Alt+");
      if (event.isShiftPressed()) text.append("Shift+");
      if (event.isCtrlPressed()) text.append("Ctrl+");
      if (event.isMetaPressed()) text.append("Meta+");
      text.append(KeyEvent.keyCodeToString(event.getKeyCode()).replaceFirst("^KEYCODE_", ""));
      _tryhere_text.setText(text.toString());
      return false;
    }
  }
}
