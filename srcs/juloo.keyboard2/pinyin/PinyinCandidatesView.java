package juloo.keyboard2.pinyin;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.Config;
import juloo.keyboard2.R;

/** A single compact row for raw pinyin and scrollable candidates. */
public final class PinyinCandidatesView extends LinearLayout
{
  public interface Listener
  {
    void onCandidateSelected(int index);
    void onCommitRaw();
    void onMoreCandidates();
  }

  private Listener listener;
  private final LinearLayout row;
  private final TextView preedit;
  private final HorizontalScrollView candidateScroll;
  private final LinearLayout items;
  private View latinCandidates;
  private int labelColor;
  private int secondaryColor;
  private float candidateTextSize;
  private String previousComposition = "";
  private List<String> previousCandidates = new ArrayList<>();

  public PinyinCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    setOrientation(VERTICAL);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    candidateTextSize = 20 * getResources().getDisplayMetrics().scaledDensity;
    readColors();
    row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    preedit = new TextView(context);
    preedit.setSingleLine(true);
    preedit.setEllipsize(TextUtils.TruncateAt.END);
    preedit.setMaxWidth(dp(88));
    preedit.setGravity(Gravity.CENTER_VERTICAL);
    preedit.setPadding(dp(8), 0, dp(8), 0);
    preedit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    preedit.setTextColor(secondaryColor);
    preedit.setBackgroundResource(R.drawable.suggestions_item_background);
    preedit.setOnClickListener(v -> { if (listener != null) listener.onCommitRaw(); });
    row.addView(preedit, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    candidateScroll = new HorizontalScrollView(context);
    candidateScroll.setHorizontalScrollBarEnabled(false);
    candidateScroll.setFillViewport(true);
    items = new LinearLayout(context);
    items.setGravity(Gravity.CENTER_VERTICAL);
    candidateScroll.addView(items, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    row.addView(candidateScroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
  }

  public void setListener(Listener listener) { this.listener = listener; }

  /** Reuse the original English suggestions in this same row. */
  public void attachLatinCandidates(View view)
  {
    if (view.getParent() != null) ((ViewGroup)view.getParent()).removeView(view);
    latinCandidates = view;
    row.addView(view, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
  }

  public void refreshConfig(Config config)
  {
    readColors();
    // Candidate height does not grow with the main keyboard's row height.
    candidateTextSize = 20 * getResources().getDisplayMetrics().scaledDensity;
    preedit.setTextColor(secondaryColor);
  }

  public void setState(boolean chinese, String composition, List<String> candidates, boolean hasMore)
  {
    preedit.setVisibility(chinese && !composition.isEmpty() ? VISIBLE : GONE);
    preedit.setText(composition);
    preedit.setContentDescription(getContext().getString(R.string.pinyin_commit_raw, composition));
    if (chinese && latinCandidates != null) latinCandidates.setVisibility(GONE);
    candidateScroll.setVisibility(chinese ? VISIBLE : GONE);

    boolean appended = composition.equals(previousComposition)
      && candidates.size() >= previousCandidates.size()
      && candidates.subList(0, previousCandidates.size()).equals(previousCandidates);
    int scroll = appended ? candidateScroll.getScrollX() : 0;
    items.removeAllViews();
    for (int i = 0; i < candidates.size(); i++)
    {
      final int index = i;
      TextView item = new TextView(getContext());
      styleItem(item);
      item.setText(candidates.get(i));
      if (i == 0) item.setTypeface(Typeface.DEFAULT_BOLD);
      item.setOnClickListener(v -> { if (listener != null) listener.onCandidateSelected(index); });
      items.addView(item, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }
    if (hasMore)
    {
      TextView more = new TextView(getContext());
      styleItem(more);
      more.setText(R.string.pinyin_more_candidates);
      more.setOnClickListener(v -> { if (listener != null) listener.onMoreCandidates(); });
      items.addView(more, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }
    previousComposition = composition;
    previousCandidates = new ArrayList<>(candidates);
    candidateScroll.post(() -> candidateScroll.scrollTo(scroll, 0));
  }

  private void styleItem(TextView view)
  {
    view.setSingleLine(true);
    view.setGravity(Gravity.CENTER);
    view.setMinWidth(dp(48));
    view.setMinHeight(dp(48));
    view.setPadding(dp(12), 0, dp(12), 0);
    view.setTextColor(labelColor);
    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, candidateTextSize);
    view.setBackgroundResource(R.drawable.suggestions_item_background);
    view.setFocusable(false);
  }

  private void readColors()
  {
    TypedArray colors = getContext().obtainStyledAttributes(new int[] {R.attr.colorLabel, R.attr.colorSubLabel});
    labelColor = colors.getColor(0, 0);
    secondaryColor = colors.getColor(1, 0);
    colors.recycle();
  }

  private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
