package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EmojiGridView extends GridView
  implements GridView.OnItemClickListener
{
  public static final int GROUP_LAST_USE = -1;

  private static final String LAST_USE_PREF = "emoji_last_use";

  private List<Emoji> _emojiArray;
  private HashMap<Emoji, Integer> _lastUsed;
  private int _group;
  private Runnable _onGroupChanged;

  public EmojiGridView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    Emoji.init(context.getResources());
    setOnItemClickListener(this);
    loadLastUsed();
    setEmojiGroup((_lastUsed.size() == 0) ? 0 : GROUP_LAST_USE);
  }

  public void setEmojiGroup(int group)
  {
    _group = group;
    _emojiArray = (group == GROUP_LAST_USE) ? getLastEmojis() : Emoji.getEmojisByGroup(group);
    setAdapter(new EmojiViewAdpater(getContext(), _emojiArray));
    if (_onGroupChanged != null)
      _onGroupChanged.run();
  }

  public int getEmojiGroup() { return _group; }

  public void setOnGroupChangedListener(Runnable listener) { _onGroupChanged = listener; }

  @Override
  protected void onAttachedToWindow()
  {
    super.onAttachedToWindow();
    ViewGroup region = (ViewGroup)getParent();
    View empty = region.findViewById(R.id.emoji_empty_state);
    setEmptyView(empty);
    KeyboardUi ui = new KeyboardUi(getContext());
    TextView title = region.findViewById(R.id.emoji_empty_title);
    title.setTextColor(ui.text);
    TextView description = region.findViewById(R.id.emoji_empty_description);
    description.setTextColor(ui.secondary);
    TextView browse = region.findViewById(R.id.emoji_browse);
    browse.setTextColor(ui.accent);
    ui.touchBackground(browse, ui.surface);
    browse.setOnClickListener(v -> setEmojiGroup(0));
  }

  public void onItemClick(AdapterView<?> parent, View v, int pos, long id)
  {
    Config config = Config.globalConfig();
    Integer used = _lastUsed.get(_emojiArray.get(pos));
    _lastUsed.put(_emojiArray.get(pos), (used == null) ? 1 : used.intValue() + 1);
    config.handler.key_up(_emojiArray.get(pos).kv(), Pointers.Modifiers.EMPTY);
    saveLastUsed(); // TODO: opti
  }

  private List<Emoji> getLastEmojis()
  {
    List<Emoji> list = new ArrayList<>(_lastUsed.keySet());
    Collections.sort(list, new Comparator<Emoji>()
        {
          public int compare(Emoji a, Emoji b)
          {
            return _lastUsed.get(b) - _lastUsed.get(a);
          }
        });
    return list;
  }

  private void saveLastUsed()
  {
    SharedPreferences.Editor edit;
    try { edit = emojiSharedPreferences().edit(); }
    catch (Exception _e) { return; }
    HashSet<String> set = new HashSet<String>();
    for (Emoji emoji : _lastUsed.keySet())
      set.add(String.valueOf(_lastUsed.get(emoji)) + "-" + emoji.kv().getString());
    edit.putStringSet(LAST_USE_PREF, set);
    edit.apply();
  }

  private void loadLastUsed()
  {
    _lastUsed = new HashMap<Emoji, Integer>();
    SharedPreferences prefs;
    // Storage might not be available (eg. the device is locked), avoid
    // crashing.
    try { prefs = emojiSharedPreferences(); }
    catch (Exception _e) { return; }
    Set<String> lastUseSet = prefs.getStringSet(LAST_USE_PREF, null);
    if (lastUseSet != null)
      for (String emojiData : lastUseSet)
      {
        String[] data = emojiData.split("-", 2);
        Emoji emoji;
        if (data.length != 2)
          continue ;
        emoji = Emoji.getEmojiByString(data[1]);
        if (emoji == null)
          continue ;
        _lastUsed.put(emoji, Integer.valueOf(data[0]));
      }
  }

  SharedPreferences emojiSharedPreferences()
  {
    return getContext().getSharedPreferences("emoji_last_use", Context.MODE_PRIVATE);
  }

  static class EmojiView extends TextView
  {
    public EmojiView(Context context)
    {
      super(context);
      KeyboardUi ui = new KeyboardUi(context);
      setGravity(Gravity.CENTER);
      setTextSize(28); // Emoji glyphs; keep a 48dp touch cell independent of font scale.
      setTextColor(ui.text);
      setIncludeFontPadding(false);
      setFocusable(false);
      setLayoutParams(new android.widget.AbsListView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));
      ui.touchBackground(this, ui.background);
    }

    public void setEmoji(Emoji emoji)
    {
      setText(emoji.kv().getString());
    }
  }

  static class EmojiViewAdpater extends BaseAdapter
  {
    Context _button_context;

    List<Emoji> _emojiArray;

    public EmojiViewAdpater(Context context, List<Emoji> emojiArray)
    {
      _button_context = context;
      _emojiArray = emojiArray;
    }

    public int getCount()
    {
      if (_emojiArray == null)
        return (0);
      return (_emojiArray.size());
    }

    public Object getItem(int pos)
    {
      return (_emojiArray.get(pos));
    }

    public long getItemId(int pos)
    {
      return (pos);
    }

    public View getView(int pos, View convertView, ViewGroup parent)
    {
      EmojiView view = (EmojiView)convertView;

      if (view == null)
        view = new EmojiView(_button_context);
      view.setEmoji(_emojiArray.get(pos));
      return view;
    }
  }
}
