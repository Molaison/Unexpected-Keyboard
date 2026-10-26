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
  private final LinearLayout expandedPanel;
  private final ScrollView expandedScroll;
  private final LinearLayout expandedItems;
  // Paging lives outside the scroller so a swipe never moves it away from the
  // finger. It shares the fixed expanded budget instead of adding height.
  private final LinearLayout expandedFooter;
  private static final int EXPANDED_BUDGET_DP = 144;
  private static final int EXPANDED_FOOTER_DP = 48;
  private static final float CANDIDATE_SP = 20;
  private static final float HEADER_SP = 18;
  private static final float FOOTER_SP = 14;

  private boolean previousHasMore;
  private String renderedComposition;
  private List<String> renderedCandidates = new ArrayList<>();
  private boolean renderedHasMore;
  private int renderedWidth = -1;
  // Row height depends on the scaled font, so a font-scale or theme change must
  // invalidate the cached expanded layout exactly like a width change does.
  private int renderedRowHeight = -1;
  private int renderedHeaderHeight = -1;
  private int headerHeight;
  private int expandedBudget;
  private int candidateRowHeight;
  private int expandedRowHeight;
  private Config lastConfig;
  private int pendingBudgetRefresh;
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
    headerHeight = ui.rowHeight(HEADER_SP);
    expandedBudget = ui.dp(EXPANDED_BUDGET_DP);
    candidateRowHeight = ui.rowHeight(CANDIDATE_SP);
    expandedRowHeight = ui.rowHeight(CANDIDATE_SP);
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
    // The preedit is the header's dominant text, so it uses the header size
    // and keeps the 48dp row from clipping its ascent.
    preedit = ui.label("", HEADER_SP, false);
    preedit.setSingleLine(true);
    preedit.setEllipsize(TextUtils.TruncateAt.START);
    preedit.setTextColor(ui.accent);
    preedit.setGravity(Gravity.CENTER_VERTICAL);
    preedit.setPadding(ui.dp(10), 0, ui.dp(6), 0);
    preedit.setOnClickListener(v -> { if (listener != null && !previousComposition.isEmpty()) listener.onCommitRaw(); });
    preedit.setOnLongClickListener(v -> { showReadings(); return !spellingOptions.isEmpty(); });
    // Never take focus from the editor, but only accept taps and long presses
    // when there is something to act on.
    preedit.setFocusable(false);
    preedit.setLongClickable(false);
    preedit.setClickable(false);
    header.addView(preedit, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    readings = ui.button("⌄", () -> {
      if (spellingOptions.isEmpty()) { if (listener != null) listener.onLayoutSwitch(); }
      else showReadings();
    });
    readings.setTextSize(HEADER_SP);
    header.addView(readings);
    addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));

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
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, candidateRowHeight));
    expandedPanel = new LinearLayout(context);
    expandedPanel.setOrientation(VERTICAL);
    expandedScroll = new ScrollView(context);
    expandedItems = new LinearLayout(context);
    expandedItems.setOrientation(VERTICAL);
    expandedScroll.addView(expandedItems);
    expandedPanel.addView(expandedScroll,
        new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(EXPANDED_BUDGET_DP - EXPANDED_FOOTER_DP)));
    expandedFooter = new LinearLayout(context);
    expandedFooter.setOrientation(VERTICAL);
    // The empty footer must not steal taps from the content above it.
    expandedFooter.setVisibility(GONE);
    // Built once; visibility alone decides whether it is reachable.
    TextView loadMore = ui.button(context.getString(R.string.pinyin_more_candidates), () -> {
      if (listener != null) listener.onMoreCandidates();
    });
    loadMore.setTextSize(FOOTER_SP);
    expandedFooter.addView(loadMore, new LayoutParams(-1, -1));
    expandedPanel.addView(expandedFooter,
        new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(EXPANDED_FOOTER_DP)));
    expandedPanel.setVisibility(GONE);
    addView(expandedPanel, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(EXPANDED_BUDGET_DP)));
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

  public void refreshConfig(Config config)
  {
    if (config == null) return;
    // Candidate cells follow the scaled 18sp/20sp/14sp text through the shared
    // KeyboardUi row metric. The expanded panel is capped by what is left of
    // the screen above the keyboard; it reserves nothing while closed.
    int header = ui.rowHeight(HEADER_SP);
    int text = ui.rowHeight(CANDIDATE_SP);
    int budget = budgetFor(config, header, text, keyboardHeight(config));
    boolean changed = header != headerHeight || text != candidateRowHeight
      || budget != expandedBudget || text != expandedRowHeight;
    headerHeight = header;
    candidateRowHeight = text;
    expandedRowHeight = text;
    expandedBudget = budget;
    lastConfig = config;
    fitTextRows();
    if (changed)
    {
      renderedRowHeight = -1;
      renderedHeaderHeight = -1;
      applyExpandedLayout();
      renderExpanded();
    }
  }

  /** Measured height of the sibling keyboard, or 0 before its first measure. */
  private int keyboardHeight(Config config)
  {
    View keyboardView = getParent() instanceof ViewGroup
      ? ((ViewGroup)getParent()).findViewById(R.id.keyboard_view) : null;
    int measured = keyboardView != null ? keyboardView.getMeasuredHeight() : 0;
    if (measured > 0) return measured;
    // Config.keyboard_rows_height_pixels is one row: four is the common
    // keyboard height before the view has been laid out.
    return Math.min(Math.max(1, config.keyboard_rows_height_pixels) * 4,
        config.screenHeightPixels / 2);
  }

  /** Expanded panel height left between the header and the keyboard. */
  private int budgetFor(Config config, int header, int text, int keyboard)
  {
    int action = ui.rowHeight(FOOTER_SP);
    int screen = config.screenHeightPixels > 0
      ? config.screenHeightPixels : keyboard * 2;
    int available = Math.max(0, screen - keyboard - header - text);
    // Keep the paging target intact; short screens scroll a partial candidate
    // row rather than forcing extra rows beyond the window. A zero budget
    // leaves the horizontal strip untouched.
    return Math.min(Math.max(ui.dp(EXPANDED_BUDGET_DP), text + action), available);
  }

  /** Long-press paging exists only where the stationary footer cannot fit. */
  private void setCompactPaging(boolean compact)
  {
    if (!compact)
    {
      more.setOnLongClickListener(null);
      more.setLongClickable(false);
      return;
    }
    more.setOnLongClickListener(v ->
    {
      if (listener == null || !expanded || !hasMore
          || expandedBudget >= ui.rowHeight(FOOTER_SP) + expandedRowHeight)
        return false;
      listener.onMoreCandidates();
      return true;
    });
  }

  /** Coalesce size changes into one refresh with the keyboard's new height. */
  private void scheduleBudgetRefresh()
  {
    if (lastConfig == null || pendingBudgetRefresh == 1 || !isAttachedToWindow()) return;
    pendingBudgetRefresh = 1;
    post(() ->
    {
      pendingBudgetRefresh = 0;
      if (lastConfig == null || !isAttachedToWindow()) return;
      Config config = Config.globalConfig();
      if (config == null) return;
      refreshConfig(config);
    });
  }

  /** Apply the measured text rows without touching them when they already match. */
  private void fitTextRows()
  {
    View header = getChildAt(0);
    if (header != null && ((LayoutParams)header.getLayoutParams()).height != headerHeight)
      header.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));
    // The candidate strip is the child right after the header.
    View strip = getChildCount() > 1 ? getChildAt(1) : null;
    if (strip != null && ((LayoutParams)strip.getLayoutParams()).height != candidateRowHeight)
      strip.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, candidateRowHeight));
  }

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
    // The preedit is only actionable while composing; a long press opens the
    // readings menu when the nine-key composition offers alternatives.
    preedit.setClickable(!composition.isEmpty());
    preedit.setLongClickable(!spellingOptions.isEmpty());
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
    // A paging request that found nothing new reports the same state. Reuse
    // the existing cells instead of rebuilding the strip and resetting scroll.
    boolean identical = composition.equals(previousComposition)
      && candidates.equals(previousCandidates);
    boolean appended = !changed && candidates.size() >= previousCandidates.size()
      && candidates.subList(0, previousCandidates.size()).equals(previousCandidates);
    previousComposition = composition;
    previousCandidates = new ArrayList<>(candidates);
    previousHasMore = hasMore;
    if (!chinese) expanded = false;
    if (identical)
    {
      renderExpanded();
      return;
    }
    // The strip keeps every candidate at its full text; only the expanded grid
    // truncates very long phrases, and it exposes the full text there.
    fitTextRows();
    int scroll = appended ? candidateScroll.getScrollX() : 0;
    items.removeAllViews();
    for (int i = 0; i < candidates.size(); i++)
    {
      final int index = i;
      TextView item = candidate(candidates.get(i), index);
      items.addView(item, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }
    candidateScroll.post(() -> candidateScroll.scrollTo(scroll, 0));
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
    renderExpanded(Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight()));
  }

  private void renderExpanded(int available)
  {
    applyExpandedLayout();
    if (!expanded) return;
    boolean sameData = previousComposition.equals(renderedComposition)
      && previousCandidates.equals(renderedCandidates) && previousHasMore == renderedHasMore
      && available == renderedWidth && expandedRowHeight == renderedRowHeight
      && headerHeight == renderedHeaderHeight;
    if (sameData && expandedItems.getChildCount() > 0) return;
    renderedComposition = previousComposition;
    renderedCandidates = new ArrayList<>(previousCandidates);
    renderedHasMore = hasMore;
    renderedWidth = available;
    renderedRowHeight = expandedRowHeight;
    renderedHeaderHeight = headerHeight;
    expandedItems.removeAllViews();
    // Whole phrases get a whole row; short words share a row without tiny targets.
    LinearLayout line = null;
    int width = 0;
    for (int i = 0; i < previousCandidates.size(); i++)
    {
      TextView v = candidate(previousCandidates.get(i), i);
      int need = Math.min(available, Math.max(ui.dp(64), (int)v.getPaint().measureText(previousCandidates.get(i)) + ui.dp(28)));
      if (line == null || width + need > available)
      {
        line = new LinearLayout(getContext());
        expandedItems.addView(line, new LayoutParams(-1, expandedRowHeight));
        width = 0;
      }
      v.setEllipsize(TextUtils.TruncateAt.END);
      v.setContentDescription(previousCandidates.get(i));
      line.addView(v, new LayoutParams(need, -1));
      width += need;
    }
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec)
  {
    int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
      ? getResources().getDisplayMetrics().widthPixels : MeasureSpec.getSize(widthSpec);
    int available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
    // Rebuild rows before measuring their children. Doing this in onSizeChanged
    // alone creates unmeasured cells during the layout that changed the width.
    if (expanded && available != renderedWidth) renderExpanded(available);
    super.onMeasure(widthSpec, heightSpec);
  }

  @Override protected void onSizeChanged(int w, int h, int oldw, int oldh)
  {
    super.onSizeChanged(w, h, oldw, oldh);
    if (expanded) renderExpanded();
    // The keyboard view is measured after this size change; recompute the
    // expanded budget once from that fresh measurement instead of the stale
    // one captured during the previous refreshConfig.
    scheduleBudgetRefresh();
  }

  // The fixed footer only appears when paging is still possible. Without it
  // the scroller takes the whole budget instead of leaving an empty strip.
  private void applyExpandedLayout()
  {
    boolean open = expanded;
    // Reserve a complete action target only when it fits beside a full row.
    int footer = ui.rowHeight(FOOTER_SP);
    boolean showFooter = open && hasMore && expandedBudget >= footer + expandedRowHeight;
    more.setText(open ? "⌃" : "⌄");
    more.setContentDescription(getContext().getString(!open ? R.string.ux_more_candidates
        : hasMore && !showFooter ? R.string.ux_compact_candidate_paging : R.string.ux_collapse_candidates));
    // Selected state complements the explicit action label.
    more.setSelected(open);
    more.setEnabled(!previousCandidates.isEmpty());
    more.setAlpha(previousCandidates.isEmpty() ? .35f : 1f);
    // The long-press shortcut only exists where the stable footer cannot fit;
    // otherwise paging stays with the stationary footer and a tap collapses.
    setCompactPaging(open && hasMore && !showFooter);
    expandedPanel.setVisibility(open && expandedBudget > 0 ? VISIBLE : GONE);
    expandedPanel.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, expandedBudget));
    // The remaining height is scrollable content, without an overflow floor.
    expandedFooter.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, footer));
    expandedScroll.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
        showFooter ? expandedBudget - footer : expandedBudget));
    expandedFooter.setVisibility(showFooter ? VISIBLE : GONE);
  }
}
