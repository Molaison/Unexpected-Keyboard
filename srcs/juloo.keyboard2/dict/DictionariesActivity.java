package juloo.keyboard2.dict;

import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import juloo.keyboard2.R;

public class DictionariesActivity extends Activity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setTitle(R.string.dictionary_page_title);
    if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
    setContentView(R.layout.dictionaries_activity);
    if (((DictionaryListView)findViewById(R.id.dictionary_recommended_list)).getChildCount() == 0)
      findViewById(R.id.dictionary_recommended_heading).setVisibility(View.GONE);
  }

  @Override public boolean onOptionsItemSelected(MenuItem item)
  {
    if (item.getItemId() == android.R.id.home) { finish(); return true; }
    return super.onOptionsItemSelected(item);
  }
}
