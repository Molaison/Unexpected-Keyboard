package juloo.keyboard2;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Group picker for the emoji grid: 48dp touch targets and a visible selected
    group. Narrow screens scroll the strip instead of squeezing the buttons. */
public class EmojiGroupButtonsBar extends HorizontalScrollView
{
  // The emoji groups, in the order [Emoji.getEmojisByGroup] returns them.
  private static final int[] GROUP_NAME_IDS = {
    R.string.emoji_group_smileys, R.string.emoji_group_people,
    R.string.emoji_group_animals, R.string.emoji_group_food,
    R.string.emoji_group_travel, R.string.emoji_group_activities,
    R.string.emoji_group_objects,
    R.string.emoji_group_symbols, R.string.emoji_group_flags,
  };

  private EmojiGridView _emoji_grid = null;
  private final LinearLayout _strip;
  private int _selected = EmojiGridView.GROUP_LAST_USE;

  public EmojiGroupButtonsBar(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    setHorizontalScrollBarEnabled(false);
    setFillViewport(true);
    // Never take focus from the application's editor.
    setFocusable(false);
    setFocusableInTouchMode(false);
    _strip = new LinearLayout(context);
    _strip.setOrientation(LinearLayout.HORIZONTAL);
    addView(_strip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    Emoji.init(context.getResources());
    add_group(EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59");
    int groups = Emoji.getNumGroups();
    for (int i = 0; i < groups; i++)
    {
      Emoji first = Emoji.getEmojisByGroup(i).get(0);
      add_group(i, first.kv().getString());
    }
    // The grid decides the opening group (recent or first); show it as active.
    post(this::sync_with_grid);
  }

  void add_group(int id, String symbol)
  {
    _strip.addView(this.new EmojiGroupButton(getContext(), id, symbol),
        new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
  }

  private String group_name(Context context, int group_id)
  {
    if (group_id == EmojiGridView.GROUP_LAST_USE)
      return context.getString(R.string.emoji_group_recent);
    if (group_id >= 0 && group_id < GROUP_NAME_IDS.length)
      return context.getString(GROUP_NAME_IDS[group_id]);
    return Integer.toString(group_id);
  }

  /** Follow the grid's current group, including the group it starts with. */
  void select(int group)
  {
    _selected = group;
    for (int i = 0; i < _strip.getChildCount(); i++)
      ((EmojiGroupButton)_strip.getChildAt(i)).refresh_selected();
  }

  /** After a click, scroll the selected group into view if it was off-screen. */
  private void reveal_selected()
  {
    final EmojiGroupButton button = find_button(_selected);
    if (button == null) return;
    post(() -> smoothScrollTo(Math.max(0, button.getLeft() - dp(8)), 0));
  }

  private EmojiGroupButton find_button(int group)
  {
    for (int i = 0; i < _strip.getChildCount(); i++)
    {
      EmojiGroupButton b = (EmojiGroupButton)_strip.getChildAt(i);
      if (b._group_id == group) return b;
    }
    return null;
  }

  private void sync_with_grid()
  {
    EmojiGridView grid = get_emoji_grid();
    if (grid != null)
    {
      select(grid.current_group());
      reveal_selected();
    }
  }

  EmojiGridView get_emoji_grid()
  {
    if (_emoji_grid == null)
      _emoji_grid = (EmojiGridView)((ViewGroup)(getParent())).findViewById(R.id.emoji_grid);
    return _emoji_grid;
  }

  private int dp(float n)
  {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  class EmojiGroupButton extends Button implements View.OnClickListener
  {
    final int _group_id;
    private final int _selected_color;
    private final int _unselected_color;

    public EmojiGroupButton(Context context, int group_id, String symbol)
    {
      super(new ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0);
      _group_id = group_id;
      TypedArray colors = context.obtainStyledAttributes(
          new int[] { R.attr.colorKeyActivated, R.attr.emoji_button_bg });
      _selected_color = colors.getColor(0, 0xffd2deff);
      _unselected_color = colors.getColor(1, 0);
      colors.recycle();
      setText(symbol);
      setContentDescription(group_name(context, group_id));
      setGravity(Gravity.CENTER);
      setMinWidth(dp(48));
      setMinimumWidth(dp(48));
      setMinHeight(dp(48));
      setMinimumHeight(dp(48));
      setPadding(dp(14), 0, dp(14), 0);
      // Clicking is intentional; focus stays with the editor.
      setFocusable(false);
      setFocusableInTouchMode(false);
      setOnClickListener(this);
      refresh_selected();
    }

    void refresh_selected()
    {
      boolean selected = _group_id == _selected;
      GradientDrawable base = new GradientDrawable();
      base.setColor(selected ? _selected_color : _unselected_color);
      base.setCornerRadius(dp(12));
      setBackground(new RippleDrawable(
          ColorStateList.valueOf(0x33000000 | (selected ? 0xffffff : 0)),
          base, null));
      setTypeface(Typeface.create("sans-serif", selected ? Typeface.BOLD : Typeface.NORMAL));
      setAlpha(selected ? 1f : .7f);
      setSelected(selected);
    }

    public void onClick(View view)
    {
      EmojiGridView grid = get_emoji_grid();
      if (grid == null) return;
      grid.setEmojiGroup(_group_id);
      select(_group_id);
      reveal_selected();
    }
  }
}
