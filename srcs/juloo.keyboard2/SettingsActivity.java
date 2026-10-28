package juloo.keyboard2;

import android.app.Dialog;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.MenuItem;

/** A short task index, with the original settings and stored values behind it. */
public class SettingsActivity extends PreferenceActivity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);
    setTitle(R.string.settings_title);
    if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
    getListView().setBackgroundColor(getResources().getColor(R.color.app_canvas));
    findPreference("clipboard_history_enabled").setOnPreferenceChangeListener((preference, value) -> {
      // Use the existing service path so disabling history also forgets its
      // in-memory entries immediately. Before the IME first runs, no history exists.
      if (Config.globalConfig() != null)
        ClipboardHistoryService.set_history_enabled((Boolean)value);
      return true;
    });

    if (!FoldStateTracker.isFoldableDevice(this))
    {
      // Keep stored foldable values, but avoid irrelevant rows on a phone.
      removePreference(getPreferenceScreen(), "margin_bottom_portrait_unfolded");
      removePreference(getPreferenceScreen(), "margin_bottom_landscape_unfolded");
      removePreference(getPreferenceScreen(), "horizontal_margin_portrait_unfolded");
      removePreference(getPreferenceScreen(), "horizontal_margin_landscape_unfolded");
      removePreference(getPreferenceScreen(), "keyboard_height_unfolded");
      removePreference(getPreferenceScreen(), "keyboard_height_landscape_unfolded");
    }
  }

  private boolean removePreference(PreferenceGroup group, String key)
  {
    for (int i = 0; i < group.getPreferenceCount(); i++)
    {
      Preference child = group.getPreference(i);
      if (key.equals(child.getKey())) return group.removePreference(child);
      if (child instanceof PreferenceGroup && removePreference((PreferenceGroup)child, key))
        return true;
    }
    return false;
  }

  @Override
  public boolean onPreferenceTreeClick(PreferenceScreen parent, Preference preference)
  {
    // Framework PreferenceScreens use their own dialog window. Apply the same
    // safe-area treatment as the activity, including deeper orientation screens.
    if (preference instanceof PreferenceScreen)
    {
      Dialog dialog = ((PreferenceScreen)preference).getDialog();
      if (dialog != null && dialog.getWindow() != null)
      {
        dialog.getWindow().getDecorView().setFitsSystemWindows(true);
        dialog.getWindow().getDecorView().setBackgroundColor(getResources().getColor(R.color.app_canvas));
      }
    }
    return super.onPreferenceTreeClick(parent, preference);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item)
  {
    if (item.getItemId() == android.R.id.home) { finish(); return true; }
    return super.onOptionsItemSelected(item);
  }

  void fallbackEncrypted() { finish(); }

  @Override
  protected void onStop()
  {
    DirectBootAwarePreferences.copy_preferences_to_protected_storage(this,
        getPreferenceManager().getSharedPreferences());
    super.onStop();
  }
}
