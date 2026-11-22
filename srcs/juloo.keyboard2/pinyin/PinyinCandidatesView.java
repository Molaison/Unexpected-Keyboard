package juloo.keyboard2.pinyin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.Config;
import juloo.keyboard2.DirectBootAwarePreferences;
import juloo.keyboard2.KeyboardUi;
import juloo.keyboard2.R;
import juloo.keyboard2.suggestions.CandidatesView;

/** Mode options, readable preedit, and browsable candidates. */
public final class PinyinCandidatesView extends LinearLayout
{
  private static final int CANDIDATE_TEXT_SP = 20;
  private static final int CONTROL_DP = 48;
  private static final int READING_MENU_ROWS = 4;

  public interface Listener
  {
    void onCandidateSelected(int index);
    void onCommitRaw();
    void onMoreCandidates();
    default void onSpellingSelected(int index) {}
    default void onClipboard() {}
    default void onLayoutSwitch() {}
    default void onDictionarySwitch() {}
  }

  private final KeyboardUi ui;
  private Listener listener;
  private List<String> spellingOptions = new ArrayList<>();
  private final LinearLayout header;
  private final FrameLayout candidateRow;
  private final LinearLayout row;
  private final TextView voiceStatus;
  private final TextView preedit;
  private final HorizontalScrollView candidateScroll;
  private final LinearLayout items;
  private final ScrollView expandedScroll;
  private final LinearLayout expandedItems;
  private int candidateRowHeight;
  private CandidatesView latinCandidates;
  private boolean chinese;
  private boolean nineKey;
  private ListPopupWindow readingMenu;
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
    header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    preedit = ui.label("", 15, false);
    preedit.setSingleLine(true);
    preedit.setEllipsize(TextUtils.TruncateAt.START);
    preedit.setTextColor(ui.accent);
    preedit.setGravity(Gravity.CENTER_VERTICAL);
    preedit.setPadding(ui.dp(10), 0, ui.dp(6), 0);
    preedit.setFocusable(false);
    preedit.setOnClickListener(v -> {
      if (listener == null) return;
      if (previousComposition.isEmpty()) showOptions(true);
      else listener.onCommitRaw();
    });
    preedit.setOnLongClickListener(v -> {
      if (!chinese || previousComposition.isEmpty()) return false;
      showOptions(false);
      return true;
    });
    preedit.setLongClickable(false);
    preedit.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info)
      {
        super.onInitializeAccessibilityNodeInfo(host, info);
        if (host.isClickable() && previousComposition.isEmpty())
          labelAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK, R.string.ux_keyboard_options);
        if (host.isLongClickable())
          labelAction(info, AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK, R.string.ux_candidate_options);
      }
    });
    header.addView(preedit, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(CONTROL_DP)));

    row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    candidateScroll = new HorizontalScrollView(context);
    candidateScroll.setHorizontalScrollBarEnabled(false);
    items = new LinearLayout(context);
    items.setGravity(Gravity.CENTER_VERTICAL);
    candidateScroll.addView(items, new ViewGroup.LayoutParams(-2, -1));
    row.addView(candidateScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    expandedScroll = new ScrollView(context);
    expandedItems = new LinearLayout(context);
    expandedItems.setOrientation(VERTICAL);
    expandedScroll.addView(expandedItems);
    expandedScroll.setVisibility(GONE);
    row.addView(expandedScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    candidateRow = new FrameLayout(context);
    candidateRow.addView(row, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    voiceStatus = ui.label("", 15, true);
    voiceStatus.setPadding(ui.dp(10), 0, ui.dp(10), 0);
    voiceStatus.setMaxLines(2);
    voiceStatus.setEllipsize(TextUtils.TruncateAt.END);
    voiceStatus.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    voiceStatus.setVisibility(GONE);
    candidateRow.addView(voiceStatus, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    addView(candidateRow, new LayoutParams(LayoutParams.MATCH_PARENT, candidateRowHeight));
  }

  private void labelAction(AccessibilityNodeInfo info,
      AccessibilityNodeInfo.AccessibilityAction action, int label)
  {
    info.removeAction(action);
    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(action.getId(), getContext().getString(label)));
  }

  private void showOptions(boolean idle)
  {
    dismissReadingMenu();
    ListPopupWindow menu = new ListPopupWindow(getContext());
    readingMenu = menu;
    List<String> choices = idle ? Collections.emptyList() : spellingOptions;
    String composition = previousComposition;
    boolean wasChinese = chinese;
    boolean wasNineKey = nineKey;
    List<String> labels = new ArrayList<>();
    if (wasChinese)
      labels.add(getContext().getString(wasNineKey ? R.string.ux_candidate_layout_full : R.string.ux_candidate_layout_nine));
    boolean canToggle = wasChinese && (expanded || hasMore || previousCandidates.size() > 1);
    int expansionChoice = canToggle ? labels.size() : -1;
    if (canToggle)
      labels.add(getContext().getString(expanded ? R.string.ux_candidate_collapse : R.string.ux_more_candidates));
    int pagingChoice = wasChinese && expanded && hasMore ? labels.size() : -1;
    if (pagingChoice >= 0)
      labels.add(getContext().getString(R.string.pinyin_more_candidates));
    int spellingStart = labels.size();
    labels.addAll(choices);
    int dictionaryChoice = idle && canSwitchDictionary() ? labels.size() : -1;
    if (dictionaryChoice >= 0)
      labels.add(getContext().getString(R.string.dictionary_page_title));
    if (idle) labels.add(getContext().getString(R.string.ux_clipboard));
    menu.setAnchorView(preedit);
    menu.setWidth(Math.min(ui.dp(180), availableWidth(getWidth())));
    // Keep the editor's input focus while the user chooses a reading.
    menu.setModal(false);
    menu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, labels);
    menu.setAdapter(adapter);
    sizeReadingMenu(menu, adapter);
    Runnable unregisterBack = Build.VERSION.SDK_INT >= 33
        ? MenuBackCallback.register(preedit, menu) : () -> {};
    menu.setOnDismissListener(() -> {
      unregisterBack.run();
      if (readingMenu == menu) readingMenu = null;
    });
    menu.setOnItemClickListener((parent, view, choice, id) -> {
      menu.dismiss();
      if (listener == null || !composition.equals(previousComposition) || wasChinese != chinese
          || wasNineKey != nineKey) return;
      if (choice == expansionChoice)
      {
        if (expanded || hasMore || previousCandidates.size() > 1) setExpanded(!expanded);
        return;
      }
      if (choice == pagingChoice)
      {
        if (expanded && hasMore) listener.onMoreCandidates();
        return;
      }
      if (choice == dictionaryChoice)
      {
        if (canSwitchDictionary()) listener.onDictionarySwitch();
        return;
      }
      expanded = false;
      if (idle && choice == labels.size() - 1) listener.onClipboard();
      else if (choice == 0) listener.onLayoutSwitch();
      else
      {
        int index = spellingOptions.indexOf(choices.get(choice - spellingStart));
        if (index >= 0) listener.onSpellingSelected(index);
      }
    });
    menu.show();
  }

  private boolean canSwitchDictionary()
  {
    Config config = Config.globalConfig();
    return !chinese && latinCandidates != null && latinCandidates.isShown()
        && (config.should_show_dictionary_switch || config.current_dictionary == null);
  }

  private void sizeReadingMenu(ListPopupWindow menu, ArrayAdapter<String> adapter)
  {
    Rect frame = new Rect();
    preedit.getWindowVisibleDisplayFrame(frame);
    int[] anchor = new int[2];
    preedit.getLocationOnScreen(anchor);
    int spaceAbove = anchor[1] - frame.top;

    Rect padding = new Rect();
    Drawable background = menu.getBackground();
    if (background != null) background.getPadding(padding);
    int itemWidth = Math.max(0, menu.getWidth() - padding.left - padding.right);
    int widthSpec = MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY);
    int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
    int menuHeight = padding.top + padding.bottom;
    int rowCount = Math.min(READING_MENU_ROWS, adapter.getCount());
    int usableHeight = menuHeight;
    View item = null;
    for (int i = 0; i < rowCount; i++)
    {
      item = adapter.getView(i, item, this);
      item.measure(widthSpec, heightSpec);
      menuHeight += item.getMeasuredHeight();
      if (i < 2) usableHeight = menuHeight;
    }
    // Leave the editor visible when even the first two rows cannot fit above.
    boolean openAbove = spaceAbove >= usableHeight;
    int spaceBelow = frame.bottom - anchor[1];
    int height = Math.max(1, Math.min(menuHeight, openAbove ? spaceAbove : spaceBelow));
    menu.setHeight(height);
    // The platform positions a dropdown from the bottom of its anchor.
    menu.setVerticalOffset(-preedit.getHeight() - (openAbove ? height : 0));
  }

  public boolean dismissReadingMenu()
  {
    ListPopupWindow menu = readingMenu;
    if (menu == null) return false;
    readingMenu = null;
    menu.dismiss();
    return true;
  }

  @android.annotation.TargetApi(33)
  private static final class MenuBackCallback
  {
    static Runnable register(View anchor, ListPopupWindow menu)
    {
      OnBackInvokedDispatcher dispatcher = anchor.findOnBackInvokedDispatcher();
      if (dispatcher == null) return () -> {};
      OnBackInvokedCallback back = menu::dismiss;
      dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
      return () -> dispatcher.unregisterOnBackInvokedCallback(back);
    }
  }

  @Override protected void onDetachedFromWindow()
  {
    dismissReadingMenu();
    super.onDetachedFromWindow();
  }

  public void setListener(Listener listener) { this.listener = listener; }

  public void setVoiceStatus(int message)
  {
    row.setVisibility(message == 0 ? VISIBLE : INVISIBLE);
    voiceStatus.setVisibility(message == 0 ? GONE : VISIBLE);
    if (message != 0)
    {
      voiceStatus.setText(message);
      dismissReadingMenu();
    }
    updatePreeditActions();
    requestLayout();
  }

  private void updatePreeditActions()
  {
    boolean available = voiceStatus.getVisibility() != VISIBLE;
    preedit.setClickable(available);
    preedit.setLongClickable(available && chinese && !previousComposition.isEmpty());
  }

  public void setSpellingOptions(List<String> options)
  {
    if (spellingOptions.equals(options)) return;
    dismissReadingMenu();
    spellingOptions = new ArrayList<>(options);
  }

  public void attachLatinCandidates(CandidatesView view)
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
    ViewGroup.LayoutParams params = candidateRow.getLayoutParams();
    params.height = height;
    candidateRow.setLayoutParams(params);
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
    boolean nextNineKey = DirectBootAwarePreferences.get_shared_preferences(getContext())
        .getBoolean("chinese_nine_key", false);
    if (chinese != this.chinese || nineKey != nextNineKey) dismissReadingMenu();
    this.chinese = chinese;
    nineKey = nextNineKey;
    this.hasMore = hasMore;
    boolean changed = !composition.equals(previousComposition);
    if (changed || !chinese) dismissReadingMenu();
    if (changed) expanded = false;
    // Keep the key origin fixed: composition replaces the concise idle mode
    // label within the same context bar, above the reserved candidate row.
    preedit.setText(composition.isEmpty()
        ? getContext().getString(chinese ? R.string.pinyin_mode_chinese : R.string.pinyin_mode_english)
        : composition);
    preedit.setTextColor(composition.isEmpty() ? ui.secondary : ui.accent);
    preedit.setContentDescription(composition.isEmpty()
      ? getContext().getString(chinese ? R.string.pinyin_mode_chinese : R.string.pinyin_mode_english)
      : getContext().getString(R.string.pinyin_commit_raw, composition));
    if (chinese && latinCandidates != null) latinCandidates.setVisibility(GONE);
    // The candidate strip is a reserved row. Only its contents change, so
    // every keystroke has the same keyboard origin and cannot move the editor.
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
    updatePreeditActions();
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

  private void setExpanded(boolean value)
  {
    expanded = value;
    renderExpanded();
  }

  private void renderExpanded()
  {
    // Both presentations occupy the same row, so expansion leaves the editor
    // and typing keys in place. Hide invalid candidates in the input callback.
    candidateScroll.setVisibility(chinese && !expanded ? VISIBLE : GONE);
    expandedScroll.setVisibility(expanded ? VISIBLE : GONE);
    expandedWidth = -1;
    requestLayout();
  }

  private int availableWidth(int width)
  {
    return Math.max(1, width - getPaddingLeft() - getPaddingRight());
  }

  private int paddedTextWidth(TextView view, String label)
  {
    return Math.max(view.getMinWidth(), (int)Math.ceil(view.getPaint().measureText(label))
        + view.getPaddingLeft() + view.getPaddingRight());
  }

  private int compactHeaderWidth(int width)
  {
    Configuration config = getResources().getConfiguration();
    if (config.orientation != Configuration.ORIENTATION_LANDSCAPE || config.screenHeightDp > 480)
      return 0;
    int headerWidth = Math.max(width / 3, paddedTextWidth(preedit, "nihao"));
    // Keep space for candidate text.
    return width - headerWidth >= 4 * ui.dp(CONTROL_DP) ? headerWidth : 0;
  }

  private void configureLayout(int compactWidth)
  {
    boolean useCompact = compactWidth > 0;
    setOrientation(useCompact ? HORIZONTAL : VERTICAL);

    LayoutParams headerParams = (LayoutParams)header.getLayoutParams();
    int headerLayoutWidth = useCompact ? compactWidth : LayoutParams.MATCH_PARENT;
    int headerHeight = useCompact ? candidateRowHeight : ui.dp(CONTROL_DP);
    if (headerParams.width != headerLayoutWidth || headerParams.height != headerHeight)
    {
      headerParams.width = headerLayoutWidth;
      headerParams.height = headerHeight;
      header.setLayoutParams(headerParams);
    }

    LayoutParams rowParams = (LayoutParams)candidateRow.getLayoutParams();
    int rowLayoutWidth = useCompact ? 0 : LayoutParams.MATCH_PARENT;
    float rowWeight = useCompact ? 1 : 0;
    if (rowParams.width != rowLayoutWidth || rowParams.weight != rowWeight)
    {
      rowParams.width = rowLayoutWidth;
      rowParams.weight = rowWeight;
      candidateRow.setLayoutParams(rowParams);
    }
  }

  @Override protected void onMeasure(int widthSpec, int heightSpec)
  {
    int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
        ? getResources().getDisplayMetrics().widthPixels : MeasureSpec.getSize(widthSpec);
    int availableWidth = availableWidth(width);
    int compactWidth = compactHeaderWidth(availableWidth);
    configureLayout(compactWidth);
    int candidateWidth = Math.max(1, availableWidth - compactWidth);
    if (expanded && expandedWidth != candidateWidth) renderExpandedItems(candidateWidth);
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
        // Give a long phrase its own row and let it scroll sideways.
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
