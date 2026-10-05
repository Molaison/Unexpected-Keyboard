package juloo.keyboard2;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Clipboard as a thumb-friendly, recycled list; no nested expanding lists. */
public final class ClipboardPaneView extends LinearLayout
  implements ClipboardHistoryService.OnClipboardHistoryChange
{
  private final KeyboardUi ui;
  private final ClipboardHistoryService service;
  private final List<String> pins = new ArrayList<>();
  private final List<String> entries = new ArrayList<>();
  private final SharedPreferences store;
  private final TextView recentTab, pinnedTab, status, undoButton, empty;
  private final Switch recording;
  private final BaseAdapter adapter;
  private boolean pinned;
  private String undoneText;
  private boolean undonePin;
  private int undonePosition;
  private Runnable back = () -> {};

  public ClipboardPaneView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    ui = new KeyboardUi(context);
    service = ClipboardHistoryService.get_service(context);
    setOrientation(VERTICAL);
    setBackgroundColor(ui.background);
    setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4));
    SharedPreferences storage = null;
    try
    {
      // Preserve the exact existing preference file/key; no destructive migration.
      storage = context.getSharedPreferences("pinned_clipboards", Context.MODE_PRIVATE);
      List<String> old = new ArrayList<>();
      ClipboardPinView.load_from_prefs(storage, old);
      for (String s : old) if (!pins.contains(s)) pins.add(s);
    }
    catch (RuntimeException unavailableDuringDirectBoot) {}
    store = storage;

    LinearLayout header = line();
    TextView returnKey = ui.button(context.getString(R.string.ux_keyboard), () -> back.run());
    returnKey.setContentDescription(context.getString(R.string.ux_keyboard));
    header.addView(returnKey);
    TextView title = ui.label(context.getString(R.string.ux_clipboard), 18, true);
    title.setGravity(Gravity.CENTER);
    header.addView(title, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    header.addView(ui.button(context.getString(R.string.ux_clear), this::confirmClear));
    addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));

    LinearLayout tabs = line();
    recentTab = ui.button("", () -> { pinned = false; refresh(); });
    pinnedTab = ui.button("", () -> { pinned = true; refresh(); });
    tabs.addView(recentTab, new LayoutParams(0, ui.dp(48), 1));
    tabs.addView(pinnedTab, new LayoutParams(0, ui.dp(48), 1));
    addView(tabs);
    recording = new Switch(context);
    recording.setText(R.string.ux_record_history);
    recording.setTextColor(ui.secondary);
    recording.setTextSize(13);
    recording.setMinHeight(ui.dp(48));
    recording.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    recording.setChecked(Config.globalConfig().clipboard_history_enabled);
    recording.setOnCheckedChangeListener((v, checked) -> {
      ClipboardHistoryService.set_history_enabled(checked);
      refresh();
    });
    addView(recording, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));

    android.widget.FrameLayout body = new android.widget.FrameLayout(context);
    ListView list = new ListView(context);
    list.setDivider(null);
    list.setClipToPadding(false);
    list.setPadding(0, ui.dp(4), 0, ui.dp(4));
    adapter = new BaseAdapter() {
      public int getCount() { return entries.size(); }
      public Object getItem(int position) { return entries.get(position); }
      public long getItemId(int position) { return position; }
      public View getView(int position, View recycled, ViewGroup parent)
      {
        LinearLayout card;
        if (recycled instanceof LinearLayout) card = (LinearLayout)recycled;
        else
        {
          card = line();
          card.setPadding(ui.dp(12), ui.dp(3), ui.dp(4), ui.dp(3));
          card.setBackground(ui.round(ui.surface, 12));
          TextView content = ui.label("", 15, false);
          content.setMinHeight(ui.dp(68));
          content.setMaxLines(3);
          content.setEllipsize(TextUtils.TruncateAt.END);
          content.setPadding(0, ui.dp(10), ui.dp(6), ui.dp(10));
          card.addView(content, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
          card.addView(ui.button("☆", () -> {}), new LayoutParams(ui.dp(48), ui.dp(56)));
          card.addView(ui.button("⋮", () -> {}), new LayoutParams(ui.dp(48), ui.dp(56)));
          list.setDividerHeight(ui.dp(6));
        }
        final String clip = entries.get(position); // Capture content, never a stale index.
        TextView content = (TextView)card.getChildAt(0);
        content.setText(clip);
        content.setContentDescription(context.getString(R.string.ux_paste_clip, clip));
        content.setOnClickListener(v -> paste(clip));
        card.setOnClickListener(v -> paste(clip));
        content.setOnLongClickListener(v -> { preview(clip); return true; });
        TextView pin = (TextView)card.getChildAt(1);
        boolean isPinned = pins.contains(clip);
        pin.setText(isPinned ? "★" : "☆");
        pin.setTextColor(isPinned ? ui.accent : ui.secondary);
        pin.setContentDescription(context.getString(isPinned ? R.string.ux_unpin : R.string.ux_pin));
        ui.touchBackground(pin, ui.surface);
        pin.setOnClickListener(v -> togglePin(clip));
        TextView menu = (TextView)card.getChildAt(2);
        ui.touchBackground(menu, ui.surface);
        menu.setContentDescription(context.getString(R.string.ux_clip_actions));
        menu.setOnClickListener(v -> actions(menu, clip));
        return card;
      }
    };
    list.setAdapter(adapter);
    body.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
    empty = ui.label("", 14, false);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16));
    empty.setTextColor(ui.secondary);
    body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
    list.setEmptyView(empty);
    addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout footer = line();
    status = ui.label("", 12, false);
    status.setTextColor(ui.secondary);
    status.setMaxLines(2);
    footer.addView(status, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    undoButton = ui.button(context.getString(R.string.ux_undo), this::undo);
    footer.addView(undoButton);
    addView(footer, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));
  }

  public void show(int height, Runnable onBack)
  {
    back = onBack;
    setLayoutParams(new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(ui.dp(280), height)));
    undoneText = null;
    recording.setChecked(Config.globalConfig().clipboard_history_enabled);
    if (service != null) service.capture_current_clip();
    refresh();
  }

  @Override public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets)
  {
    // Keyboard2's window draws behind system bars on API 35+. Unlike the key
    // canvas, this replacement pane must reserve its own bottom/side insets.
    // Otherwise the footer (including Undo) is covered by navigation buttons.
    if (android.os.Build.VERSION.SDK_INT >= 35)
    {
      android.graphics.Insets safe = insets.getInsets(
          android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
      setPadding(ui.dp(8) + safe.left, 0, ui.dp(8) + safe.right, ui.dp(4) + safe.bottom);
    }
    return super.onApplyWindowInsets(insets);
  }

  @Override protected void onAttachedToWindow()
  {
    super.onAttachedToWindow();
    if (service != null) service.set_on_clipboard_history_change(this);
    refresh();
  }

  @Override protected void onDetachedFromWindow()
  {
    if (service != null) service.remove_on_clipboard_history_change(this);
    undoneText = null;
    super.onDetachedFromWindow();
  }

  @Override public void on_clipboard_history_change() { post(this::refresh); }

  private LinearLayout line()
  {
    LinearLayout l = new LinearLayout(getContext());
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void refresh()
  {
    List<String> recent = service == null ? new ArrayList<>() : service.clear_expired_and_get_history();
    // A current, non-sensitive clip is usable even when recording is disabled.
    String current = service == null ? null : service.current_text();
    if (current != null && !recent.contains(current)) recent.add(0, current);
    entries.clear();
    entries.addAll(pinned ? pins : recent);
    recentTab.setText(getContext().getString(R.string.ux_recent_count, recent.size()));
    pinnedTab.setText(getContext().getString(R.string.ux_pinned_count, pins.size()));
    recentTab.setTextColor(!pinned ? ui.accent : ui.secondary);
    pinnedTab.setTextColor(pinned ? ui.accent : ui.secondary);
    ui.touchBackground(recentTab, !pinned ? ui.surface : ui.background);
    ui.touchBackground(pinnedTab, pinned ? ui.surface : ui.background);
    empty.setText(pinned ? R.string.ux_pins_empty : R.string.ux_history_empty);
    status.setText(undoneText != null ? R.string.ux_deleted : R.string.ux_clipboard_privacy);
    undoButton.setVisibility(undoneText == null ? GONE : VISIBLE);
    adapter.notifyDataSetChanged();
  }

  private void paste(String clip)
  {
    ClipboardHistoryService.paste(clip);
    status.setText(R.string.ux_pasted);
    // Stay open: people frequently paste several clips consecutively.
  }

  private void savePins()
  {
    if (store != null) store.edit().putString("pinned", new JSONArray(pins).toString()).apply();
  }

  private void togglePin(String clip)
  {
    if (store == null) { status.setText(R.string.ux_unlock_to_pin); return; }
    if (!pins.remove(clip)) pins.add(0, clip);
    savePins();
    refresh(); // Pinning must never clear the Android clipboard.
  }

  private void actions(View anchor, String clip)
  {
    PopupMenu popup = new PopupMenu(getContext(), anchor);
    popup.getMenu().add(0, 0, 0, R.string.ux_preview);
    popup.getMenu().add(0, 1, 1, pins.contains(clip) ? R.string.ux_unpin : R.string.ux_pin);
    popup.getMenu().add(0, 2, 2, R.string.ux_delete);
    popup.setOnMenuItemClickListener(item -> {
      switch (item.getItemId())
      {
        case 0: preview(clip); break;
        case 1: togglePin(clip); break;
        case 2: delete(clip); break;
      }
      return true;
    });
    popup.show();
  }

  private void preview(String clip)
  {
    TextView text = ui.label(clip, 16, false);
    text.setTextIsSelectable(true);
    text.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    ScrollView scroll = new ScrollView(getContext());
    scroll.addView(text);
    AlertDialog dialog = new AlertDialog.Builder(getContext())
      .setTitle(R.string.ux_preview).setView(scroll)
      .setPositiveButton(R.string.ux_paste, (d, which) -> paste(clip))
      .setNegativeButton(android.R.string.cancel, null).create();
    Utils.show_dialog_on_ime(dialog, getWindowToken());
  }

  private void delete(String clip)
  {
    undoneText = clip;
    undonePin = pinned;
    undonePosition = Math.max(0, entries.indexOf(clip));
    if (pinned) { pins.remove(clip); savePins(); }
    else if (service != null) service.delete_history_entry(clip);
    refresh();
  }

  private void undo()
  {
    if (undoneText == null) return;
    String text = undoneText;
    undoneText = null;
    if (undonePin)
    {
      if (!pins.contains(text)) pins.add(Math.min(undonePosition, pins.size()), text);
      savePins();
    }
    else if (service != null) service.restore_history_entry(text);
    refresh();
  }

  private void confirmClear()
  {
    AlertDialog dialog = new AlertDialog.Builder(getContext())
      .setTitle(R.string.ux_clear_history_title)
      .setMessage(R.string.ux_clear_history_message)
      .setPositiveButton(R.string.ux_clear, (d, which) -> {
        undoneText = null;
        if (service != null) service.clear_history_and_current();
        refresh();
      })
      .setNegativeButton(android.R.string.cancel, null).create();
    Utils.show_dialog_on_ime(dialog, getWindowToken());
  }
}
