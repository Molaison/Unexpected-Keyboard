package juloo.keyboard2;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class EmojiGroupButtonsBar extends LinearLayout
{
  private EmojiGridView _emoji_grid = null;
  private final KeyboardUi _ui;

  public EmojiGroupButtonsBar(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _ui = new KeyboardUi(context);
    setBackgroundColor(_ui.background);
    Emoji.init(context.getResources());
    add_group(EmojiGridView.GROUP_LAST_USE, "\uD83D\uDD59",
        context.getString(R.string.emoji_recent_category));
    String[] names = getResources().getStringArray(R.array.emoji_category_names);
    for (int i = 0; i < Emoji.getNumGroups(); i++)
    {
      Emoji first = Emoji.getEmojisByGroup(i).get(0);
      add_group(i, first.kv().getString(), (i < names.length) ? names[i]
          : context.getString(R.string.emoji_category_fallback, i + 1));
    }
  }

  void add_group(int id, String symbol, String description)
  {
    TextView button = _ui.button(symbol, () -> get_emoji_grid().setEmojiGroup(id));
    button.setTextSize(24); // Emoji glyphs are distinct from UI text roles.
    button.setPadding(0, 0, 0, 0);
    button.setContentDescription(description);
    button.setTag(id);
    addView(button, new LayoutParams(_ui.dp(48), _ui.dp(48)));
  }

  EmojiGridView get_emoji_grid()
  {
    if (_emoji_grid == null)
      _emoji_grid = ((View)getParent().getParent()).findViewById(R.id.emoji_grid);
    return _emoji_grid;
  }

  @Override
  protected void onAttachedToWindow()
  {
    super.onAttachedToWindow();
    get_emoji_grid().setOnGroupChangedListener(() -> updateSelection());
    updateSelection();
  }

  private void updateSelection()
  {
    int group = get_emoji_grid().getEmojiGroup();
    for (int i = 0; i < getChildCount(); i++)
    {
      View button = getChildAt(i);
      button.setSelected(((Integer)button.getTag()).intValue() == group);
    }
  }
}
