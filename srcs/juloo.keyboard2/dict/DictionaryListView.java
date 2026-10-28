package juloo.keyboard2.dict;

import android.content.Context;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import juloo.cdict.Cdict;
import juloo.keyboard2.DeviceLocales;
import juloo.keyboard2.Logs;
import juloo.keyboard2.R;
import juloo.keyboard2.Utils;

public class DictionaryListView extends LinearLayout
{
  List<DictView> _dict_views;
  Dictionaries _dictionaries;
  Set<String> _pending = new HashSet<String>();
  Set<String> _failed = new HashSet<String>();

  public DictionaryListView(Context ctx, AttributeSet attrs)
  {
    super(ctx, attrs);
    setOrientation(LinearLayout.VERTICAL);
    _dictionaries = Dictionaries.instance(ctx);
    _dict_views = new ArrayList<DictView>();
    boolean device_locales =
      attrs == null || attrs.getAttributeBooleanValue(null, "device_locales", true);
    if (device_locales)
      inflate_views_device_locales(ctx);
    else
      inflate_views_all(ctx);
    refresh();
  }

  void inflate_views_device_locales(Context ctx)
  {
    SupportedDictionaries ds = SupportedDictionaries.get(ctx.getResources());
    Set<String> recommended = recommended_dictionaries(ctx);
    for (int i = 0; i < ds.length(); i++)
      if (recommended.contains(ds.dict_name(i))) inflate_item(ctx, ds, i);
  }

  void inflate_views_all(Context ctx)
  {
    SupportedDictionaries ds = SupportedDictionaries.get(ctx.getResources());
    Set<String> recommended = recommended_dictionaries(ctx);
    for (int i = 0; i < ds.length(); i++)
      if (!recommended.contains(ds.dict_name(i))) inflate_item(ctx, ds, i);
  }

  static Set<String> recommended_dictionaries(Context ctx)
  {
    Set<String> names = new HashSet<>();
    for (DeviceLocales.Loc loc : DeviceLocales.load(ctx).installed)
      if (loc.dictionary != null) names.add(loc.dictionary);
    return names;
  }

  void inflate_item(Context ctx, SupportedDictionaries ds, int i)
  {
    View v = LayoutInflater.from(ctx)
      .inflate(R.layout.dictionary_download_item, this, false);
    _dict_views.add(this.new DictView(v, ds, i));
    addView(v);
  }

  /** Keep the action, progress, and inline result of each row in sync. */
  void refresh()
  {
    Set<String> installed = _dictionaries.get_installed();
    for (DictView d : _dict_views)
      d.refresh(installed);
  }

  void toggle_installed(String dict_name)
  {
    if (_pending.contains(dict_name)) return;
    if (_dictionaries.get_installed().contains(dict_name))
    {
      String language = SupportedDictionaries.get(getResources()).get_display_name(dict_name);
      new AlertDialog.Builder(getContext())
          .setTitle(getContext().getString(R.string.dictionary_remove_title, language))
          .setMessage(R.string.dictionary_remove_message)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(R.string.dictionary_remove, (dialog, which) ->
              run_dictionary_action(dict_name, () -> _dictionaries.uninstall(dict_name)))
          .show();
      return;
    }
    run_dictionary_action(dict_name, new Runnable()
        {
          public void run()
          {
            if (!install_dictionary_from_internet(dict_name))
              throw new IllegalStateException("Dictionary download failed");
          }
        });
  }

  /** Run action [r] for dictionary [name] if no action is already running for
      that dictionary. Calls [refresh] after the action completed. */
  void run_dictionary_action(String name, Runnable r)
  {
    if (_pending.contains(name))
      return;
    _pending.add(name);
    _failed.remove(name);
    (new Thread()
     {
       public void run()
       {
         boolean succeeded = false;
         try { r.run(); succeeded = true; }
         catch (Exception error) { Logs.exn("Dictionary action", error); }
         finally
         {
           final boolean completed = succeeded;
           new Handler(Looper.getMainLooper()).post(new Runnable()
             {
               public void run()
               {
                 _pending.remove(name);
                 if (!completed) _failed.add(name);
                 refresh();
               }
             });
         }
       }
     }).start();
    refresh();
  }

  final class DictView implements View.OnClickListener
  {
    public final String dict_name;
    public final Button download_button;
    public final TextView status;
    public final ProgressBar progress;
    private final String display_name;

    public DictView(View view, SupportedDictionaries ds, int dict_index)
    {
      dict_name = ds.dict_name(dict_index);
      display_name = ds.display_name(dict_index);
      float size_mb = ds.size(dict_index) / 1048576.f;
      ((TextView)view.findViewById(R.id.dictionary_download_locale))
        .setText(display_name);
      ((TextView)view.findViewById(R.id.dictionary_download_size))
        .setText(NumberFormat.getInstance().format(size_mb) + " MB");
      download_button =
        (Button)view.findViewById(R.id.dictionary_download_button);
      status = view.findViewById(R.id.dictionary_download_status);
      progress = view.findViewById(R.id.dictionary_download_progress);
      download_button.setOnClickListener(this);
    }

    public void refresh(Set<String> installed)
    {
      boolean pending = _pending.contains(dict_name);
      boolean available = installed.contains(dict_name);
      boolean failed = _failed.contains(dict_name);
      int action = pending ? (available ? R.string.dictionary_updating : R.string.dictionary_downloading)
          : failed ? R.string.dictionary_retry
          : available ? R.string.dictionary_remove : R.string.dictionary_download;
      download_button.setText(action);
      download_button.setContentDescription(getContext().getString(
          R.string.dictionary_action_description, getContext().getString(action), display_name));
      download_button.setEnabled(!pending);
      download_button.setAlpha(pending ? .65f : 1f);
      progress.setVisibility(pending ? VISIBLE : GONE);
      status.setText(failed ? R.string.dictionary_failed : R.string.dictionary_installed);
      status.setVisibility(!pending && (failed || available) ? VISIBLE : GONE);
    }

    @Override
    public void onClick(View v)
    {
      toggle_installed(dict_name);
    }
  }

  static final String DICT_REPO_URL =
    "https://raw.githubusercontent.com/Julow/Unexpected-Keyboard-dictionaries/refs/heads/main";

  static URL url_of_dictionary(String dict_name)
      throws MalformedURLException
  {
    int format_version = Cdict.format_version();
    return new URL(DICT_REPO_URL + "/v" + format_version + "/" + dict_name
        + ".dict");
  }

  /** Returns [true] on success. */
  boolean install_dictionary_from_internet(String dict_name)
  {
    URLConnection con = null;
    try
    {
      // Remote files are compressed with gzip at rest. Do not use server side
      // compression and force decompression.
      con = url_of_dictionary(dict_name).openConnection();
      con.setConnectTimeout(10000);
      con.setReadTimeout(20000);
      con.setRequestProperty("Accept-Encoding", "identity");
      try (InputStream response = con.getInputStream();
          GZIPInputStream stream = new GZIPInputStream(response))
      {
        byte[] data = Utils.read_all_bytes(stream);
        Cdict.of_bytes(data); // Only advertise an installed, validated dictionary.
        _dictionaries.install(dict_name, data);
      }
      return true;
    }
    catch (Exception e)
    {
      Logs.exn("", e);
      return false;
    }
    finally
    {
      if (con instanceof HttpURLConnection) ((HttpURLConnection)con).disconnect();
    }
  }
}
