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
import juloo.keyboard2.DirectBootAwarePreferences;
import juloo.keyboard2.KeyboardUi;
import juloo.keyboard2.R;

/** Visible tools, readable preedit, and a separate candidate row. */
public final class PinyinCandidatesView extends LinearLayout
{
  private static final int CANDIDATE_TEXT_SP = 20;
  private static final int CONTROL_DP = 48;

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
  private final TextView spellings;
  private final TextView more;
  private final HorizontalScrollView candidateScroll;
  private final LinearLayout items;
  private final ScrollView expandedScroll;
  private final LinearLayout expandedItems;
  private final TextView loadMore;
  private final String loadMoreLabel;
  private int candidateRowHeight;
  private View latinCandidates;
  private boolean chinese;
  private boolean expanded;
  private int expandedWidth;
  private boolean hasMore;
  private String previousComposition = "";
  private List<String> previousCandidates = new ArrayList<>();

  public PinyinCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    ui = new KeyboardUi(context);
    candidateRowHeight = measureCandidateRowHeight();
    setOrientation(VERTICAL);
    setBackgroundColor(ui.background);
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
    preedit.setFocusable(false);
    preedit.setOnClickListener(v -> { if (listener != null && !previousComposition.isEmpty()) listener.onCommitRaw(); });
    preedit.setOnLongClickListener(v -> { showReadings(); return !spellingOptions.isEmpty(); });
    preedit.setLongClickable(false);
    header.addView(preedit, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    spellings = ui.button(context.getString(R.string.ux_candidate_spellings), this::showReadings);
    spellings.setTextSize(13);
    spellings.setContentDescription(context.getString(R.string.ux_candidate_spellings_description));
    header.addView(spellings, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    readings = ui.button("26", () -> {
      if (listener != null) listener.onLayoutSwitch();
    });
    readings.setTextSize(14);
    header.addView(readings);
    addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(CONTROL_DP)));

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
    expandedScroll = new ScrollView(context);
    expandedItems = new LinearLayout(context);
    expandedItems.setOrientation(VERTICAL);
    expandedScroll.addView(expandedItems);
    loadMoreLabel = context.getString(R.string.pinyin_more_candidates);
    loadMore = ui.button(loadMoreLabel, () -> {
      if (listener != null) listener.onMoreCandidates();
    });
    loadMore.setContentDescription(loadMoreLabel);
    loadMore.setSingleLine(true);
    expandedScroll.setVisibility(GONE);
    loadMore.setVisibility(GONE);
    row.addView(expandedScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    row.addView(loadMore, new LayoutParams(ui.dp(CONTROL_DP), LayoutParams.MATCH_PARENT));
    row.addView(more, new LayoutParams(ui.dp(CONTROL_DP), LayoutParams.MATCH_PARENT));
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, candidateRowHeight));
  }

  private void showReadings()
  {
    if (spellingOptions.isEmpty()) return;
    PopupMenu menu = new PopupMenu(getContext(), spellings);
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

  public void refreshConfig(Config config)
  {
    int height = measureCandidateRowHeight();
    if (height == candidateRowHeight) return;
    candidateRowHeight = height;
    ViewGroup.LayoutParams params = row.getLayoutParams();
    params.height = height;
    row.setLayoutParams(params);
    expandedWidth = -1;
  }

  private int measureCandidateRowHeight()
  {
    // A Han glyph includes the CJK fallback font's line metrics. Reserve
    // the same height before composition so pressing a key never moves it.
    TextView sample = ui.label("你", CANDIDATE_TEXT_SP, true);
    sample.setSingleLine(true);
    int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    sample.measure(natural, natural);
    return Math.max(ui.dp(CONTROL_DP), sample.getMeasuredHeight() + ui.dp(8));
  }

  public void setState(boolean chinese, String composition, List<String> candidates, boolean hasMore)
  {
    this.chinese = chinese;
    this.hasMore = hasMore;
    boolean changed = !composition.equals(previousComposition);
    if (changed) expanded = false;
    // Keep the key origin fixed: composition replaces the concise idle mode
    // label within the same context bar, above the reserved candidate row.
    preedit.setText(composition.isEmpty()
        ? getContext().getString(chinese ? R.string.pinyin_mode_chinese : R.string.pinyin_mode_english)
        : composition);
    preedit.setTextColor(composition.isEmpty() ? ui.secondary : ui.accent);
    preedit.setClickable(!composition.isEmpty());
    preedit.setContentDescription(composition.isEmpty()
      ? getContext().getString(chinese ? R.string.pinyin_mode_chinese : R.string.pinyin_mode_english)
      : getContext().getString(R.string.pinyin_commit_raw, composition));
    boolean nineKey = DirectBootAwarePreferences.get_shared_preferences(getContext())
        .getBoolean("chinese_nine_key", false);
    readings.setText(getContext().getString(nineKey
        ? R.string.ux_candidate_layout_nine : R.string.ux_candidate_layout_full));
    readings.setContentDescription(getContext().getString(nineKey
        ? R.string.ux_candidate_switch_full : R.string.ux_candidate_switch_nine));
    boolean hasReadings = chinese && !spellingOptions.isEmpty();
    preedit.setLongClickable(hasReadings);
    spellings.setVisibility(hasReadings ? VISIBLE : GONE);
    // INVISIBLE reserves the same width while the user switches languages.
    readings.setVisibility(chinese ? VISIBLE : INVISIBLE);
    if (chinese && latinCandidates != null) latinCandidates.setVisibility(GONE);
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
    if (changed || (!expanded && !candidates.equals(previousCandidates)))
    {
      expandedItems.removeAllViews();
      expandedScroll.scrollTo(0, 0);
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
    v.setTextSize(CANDIDATE_TEXT_SP);
    v.setSingleLine(true);
    v.setTypeface(Typeface.create("sans-serif", index == 0 ? Typeface.BOLD : Typeface.NORMAL));
    v.setTextColor(index == 0 ? ui.accent : ui.text);
    ui.touchBackground(v, ui.background);
    return v;
  }

  private void renderExpanded()
  {
    more.setText(expanded ? "⌃" : "⌄");
    more.setContentDescription(getContext().getString(expanded
        ? R.string.ux_candidate_collapse : R.string.ux_more_candidates));
    more.setEnabled(!previousCandidates.isEmpty());
    more.setAlpha(previousCandidates.isEmpty() ? .35f : 1f);
    // Both presentations occupy the same row, so expansion leaves the editor
    // and typing keys in place. Hide invalid candidates in the input callback.
    candidateScroll.setVisibility(chinese && !expanded ? VISIBLE : GONE);
    expandedScroll.setVisibility(expanded ? VISIBLE : GONE);
    loadMore.setVisibility(expanded && hasMore ? VISIBLE : GONE);
    expandedWidth = -1;
    requestLayout();
  }

  private int availableWidth(int width)
  {
    return Math.max(1, width - getPaddingLeft() - getPaddingRight());
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec)
  {
    int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
        ? getResources().getDisplayMetrics().widthPixels : MeasureSpec.getSize(widthSpec);
    if (expanded)
    {
      int control = ui.dp(CONTROL_DP);
      int available = availableWidth(width) - control;
      if (hasMore)
      {
        int labelWidth = Math.max(control, (int)Math.ceil(loadMore.getPaint().measureText(loadMoreLabel))
            + loadMore.getPaddingLeft() + loadMore.getPaddingRight());
        // Keep space for the list. The accessible label stays the same when
        // the visual action switches from its full text to a plus sign.
        boolean showLabel = labelWidth <= available / 2;
        String label = showLabel ? loadMoreLabel : "+";
        if (!TextUtils.equals(label, loadMore.getText())) loadMore.setText(label);
        int pageWidth = showLabel ? labelWidth : control;
        loadMore.getLayoutParams().width = pageWidth;
        available -= pageWidth;
      }
      available = Math.max(1, available);
      if (expandedWidth != available) renderExpandedItems(available);
    }
    super.onMeasure(widthSpec, heightSpec);
  }

  private void renderExpandedItems(int available)
  {
    expandedWidth = available;
    expandedItems.removeAllViews();
    // Whole phrases get a whole row; short words share a row without tiny targets.
    LinearLayout line = null;
    int width = 0;
    for (int i = 0; i < previousCandidates.size(); i++)
    {
      TextView v = candidate(previousCandidates.get(i), i);
      int need = Math.max(ui.dp(64), (int)Math.ceil(v.getPaint().measureText(previousCandidates.get(i)))
          + v.getPaddingLeft() + v.getPaddingRight());
      int cellWidth = Math.min(available, need);
      if (line == null || width + cellWidth > available)
      {
        line = new LinearLayout(getContext());
        expandedItems.addView(line, new LayoutParams(-1, candidateRowHeight));
        width = 0;
      }
      if (need > available)
      {
        // Give a long phrase its own row and let it scroll sideways without
        // taking the paging or collapse controls out of view.
        HorizontalScrollView overflow = new HorizontalScrollView(getContext());
        overflow.setScrollbarFadingEnabled(false);
        overflow.addView(v, new ViewGroup.LayoutParams(need, LayoutParams.MATCH_PARENT));
        line.addView(overflow, new LayoutParams(cellWidth, LayoutParams.MATCH_PARENT));
      }
      else line.addView(v, new LayoutParams(cellWidth, LayoutParams.MATCH_PARENT));
      width += cellWidth;
    }
  }
}
