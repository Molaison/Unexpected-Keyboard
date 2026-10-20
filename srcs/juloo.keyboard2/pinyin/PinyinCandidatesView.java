package juloo.keyboard2.pinyin;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.Config;
import juloo.keyboard2.KeyboardUi;
import juloo.keyboard2.R;

/** Visible tools, readable preedit, and a separate candidate row. */
public final class PinyinCandidatesView extends LinearLayout
{
  public interface Listener
  {
    void onCandidateSelected(int index);
    void onCommitRaw();
    void onMoreCandidates();
    default void onSpellingSelected(int index) {}
    default void onClipboard() {}
    default void onLayoutSwitch() {}
  }

  private final KeyboardUi ui;
  private Listener listener;
  private List<String> spellingOptions = new ArrayList<>();
  private final LinearLayout row;
  private final TextView preedit;
  private final TextView readings;
  private final TextView more;
  private final HorizontalScrollView candidateScroll;
  private final LinearLayout items;
  private final ScrollView expandedScroll;
  private final LinearLayout expandedItems;
  private View latinCandidates;
  private boolean expanded;
  private boolean hasMore;
  private String previousComposition = "";
  private List<String> previousCandidates = new ArrayList<>();

  public PinyinCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    ui = new KeyboardUi(context);
    setOrientation(VERTICAL);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    setPadding(ui.dp(6), 0, ui.dp(6), 0);
    LinearLayout header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton clipboard = new ImageButton(context);
    clipboard.setImageResource(R.drawable.ic_clipboard_paste);
    clipboard.setColorFilter(ui.text, PorterDuff.Mode.SRC_IN);
    clipboard.setContentDescription(context.getString(R.string.ux_clipboard));
    clipboard.setBackground(ui.round(ui.background, 10));
    clipboard.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
    clipboard.setMinimumWidth(ui.dp(48));
    clipboard.setMinimumHeight(ui.dp(48));
    clipboard.setOnClickListener(v -> {
      if (listener != null) listener.onClipboard();
    });
    header.addView(clipboard, new LayoutParams(ui.dp(48), LayoutParams.MATCH_PARENT));
    preedit = ui.label("", 15, false);
    preedit.setSingleLine(true);
    preedit.setEllipsize(TextUtils.TruncateAt.START);
    preedit.setTextColor(ui.accent);
    preedit.setGravity(Gravity.CENTER_VERTICAL);
    preedit.setPadding(ui.dp(10), 0, ui.dp(6), 0);
    preedit.setOnClickListener(v -> { if (listener != null && !previousComposition.isEmpty()) listener.onCommitRaw(); });
    preedit.setOnLongClickListener(v -> { showReadings(); return !spellingOptions.isEmpty(); });
    header.addView(preedit, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    readings = ui.button("⌄", () -> {
      if (spellingOptions.isEmpty()) { if (listener != null) listener.onLayoutSwitch(); }
      else showReadings();
    });
    readings.setTextSize(18);
    header.addView(readings);
    addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));

    row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    candidateScroll = new HorizontalScrollView(context);
    candidateScroll.setHorizontalScrollBarEnabled(false);
    items = new LinearLayout(context);
    items.setGravity(Gravity.CENTER_VERTICAL);
    candidateScroll.addView(items, new ViewGroup.LayoutParams(-2, -1));
    row.addView(candidateScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    more = ui.button("⌄", () -> {
      expanded = !expanded;
      renderExpanded();
    });
    more.setContentDescription(context.getString(R.string.ux_more_candidates));
    row.addView(more, new LayoutParams(ui.dp(48), LayoutParams.MATCH_PARENT));
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));
    expandedScroll = new ScrollView(context);
    expandedItems = new LinearLayout(context);
    expandedItems.setOrientation(VERTICAL);
    expandedScroll.addView(expandedItems);
    expandedScroll.setVisibility(GONE);
    addView(expandedScroll, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(144)));
  }

  private void showReadings()
  {
    if (spellingOptions.isEmpty()) return;
    PopupMenu menu = new PopupMenu(getContext(), readings);
    for (int i = 0; i < spellingOptions.size(); i++) menu.getMenu().add(0, i, i, spellingOptions.get(i));
    menu.setOnMenuItemClickListener(item -> {
      expanded = false;
      if (listener != null) listener.onSpellingSelected(item.getItemId());
      return true;
    });
    menu.show();
  }

  public void setListener(Listener listener) { this.listener = listener; }
  public void setSpellingOptions(List<String> options) { spellingOptions = new ArrayList<>(options); }

  public void attachLatinCandidates(View view)
  {
    if (view.getParent() != null) ((ViewGroup)view.getParent()).removeView(view);
    latinCandidates = view;
    row.addView(view, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
  }

  public void refreshConfig(Config config) {}

  public void setState(boolean chinese, String composition, List<String> candidates, boolean hasMore)
  {
    this.hasMore = hasMore;
    boolean changed = !composition.equals(previousComposition);
    if (changed) expanded = false;
    // Keep the preedit slot empty when idle. The mode is conveyed by the
    // keyboard itself and remains available to accessibility services here.
    preedit.setText(composition);
    preedit.setContentDescription(composition.isEmpty()
      ? getContext().getString(chinese ? R.string.pinyin_mode_chinese : R.string.pinyin_mode_english)
      : getContext().getString(R.string.pinyin_commit_raw, composition));
    readings.setText(spellingOptions.isEmpty() ? "⌨" : "⌄");
    readings.setContentDescription(getContext().getString(spellingOptions.isEmpty()
        ? R.string.ux_layout_switch : R.string.pinyin_choose_spelling));
    // INVISIBLE reserves the same width while the user switches languages.
    readings.setVisibility(chinese ? VISIBLE : INVISIBLE);
    if (chinese && latinCandidates != null) latinCandidates.setVisibility(GONE);
    candidateScroll.setVisibility(chinese ? VISIBLE : GONE);
    // The candidate strip is a reserved row. Only its contents change, so
    // every keystroke has the same keyboard origin and cannot move the editor.
    more.setVisibility(chinese ? VISIBLE : INVISIBLE);
    more.setEnabled(chinese && !candidates.isEmpty());
    more.setAlpha(chinese && !candidates.isEmpty() ? 1f : .35f);
    row.setVisibility(VISIBLE);
    boolean appended = !changed && candidates.size() >= previousCandidates.size()
      && candidates.subList(0, previousCandidates.size()).equals(previousCandidates);
    int scroll = appended ? candidateScroll.getScrollX() : 0;
    items.removeAllViews();
    for (int i = 0; i < candidates.size(); i++)
    {
      final int index = i;
      TextView item = candidate(candidates.get(i), index);
      items.addView(item, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }
    previousComposition = composition;
    previousCandidates = new ArrayList<>(candidates);
    candidateScroll.post(() -> candidateScroll.scrollTo(scroll, 0));
    if (!chinese) expanded = false;
    renderExpanded();
  }

  private TextView candidate(String text, int index)
  {
    TextView v = ui.button(text, () -> {
      expanded = false;
      if (listener != null) listener.onCandidateSelected(index);
    });
    v.setTextSize(20);
    v.setSingleLine(true);
    v.setTypeface(Typeface.create("sans-serif", index == 0 ? Typeface.BOLD : Typeface.NORMAL));
    v.setTextColor(index == 0 ? ui.accent : ui.text);
    ui.touchBackground(v, ui.background);
    return v;
  }

  private void renderExpanded()
  {
    more.setText(expanded ? "⌃" : "⌄");
    more.setEnabled(!previousCandidates.isEmpty());
    more.setAlpha(previousCandidates.isEmpty() ? .35f : 1f);
    expandedScroll.setVisibility(expanded ? VISIBLE : GONE);
    if (!expanded) return;
    expandedItems.removeAllViews();
    // Whole phrases get a whole row; short words share a row without tiny targets.
    LinearLayout line = null;
    int width = 0;
    int available = Math.max(ui.dp(240), getWidth() - getPaddingLeft() - getPaddingRight());
    for (int i = 0; i < previousCandidates.size(); i++)
    {
      TextView v = candidate(previousCandidates.get(i), i);
      int need = Math.min(available, Math.max(ui.dp(64), (int)v.getPaint().measureText(previousCandidates.get(i)) + ui.dp(28)));
      if (line == null || width + need > available)
      {
        line = new LinearLayout(getContext());
        expandedItems.addView(line, new LayoutParams(-1, ui.dp(48)));
        width = 0;
      }
      v.setEllipsize(TextUtils.TruncateAt.END);
      line.addView(v, new LayoutParams(need, -1));
      width += need;
    }
    if (hasMore)
      expandedItems.addView(ui.button(getContext().getString(R.string.pinyin_more_candidates), () -> {
        if (listener != null) listener.onMoreCandidates();
      }), new LayoutParams(-1, ui.dp(48)));
  }
}
