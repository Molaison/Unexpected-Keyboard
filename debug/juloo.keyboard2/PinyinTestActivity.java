package juloo.keyboard2;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local editor fixture; absent from release builds and not exported. */
public final class PinyinTestActivity extends Activity
{
  public EditText plain, password, email, number;
  public int actions;

  @Override public void onCreate(Bundle state)
  {
    super.onCreate(state);
    LinearLayout fields = new LinearLayout(this);
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = (int)(16 * getResources().getDisplayMetrics().density);
    fields.setPadding(padding, padding, padding, padding);
    TextView title = new TextView(this);
    title.setText("中文全拼输入验证");
    title.setTextSize(22);
    fields.addView(title);
    plain = field(fields, "普通文本", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    password = field(fields, "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    email = field(fields, "电子邮件", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    number = field(fields, "数字", InputType.TYPE_CLASS_NUMBER);
    plain.setOnEditorActionListener((view, action, event) -> { actions++; return true; });
    setContentView(fields);
    plain.requestFocus();
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  private EditText field(LinearLayout fields, String hint, int inputType)
  {
    EditText editor = new EditText(this);
    editor.setHint(hint);
    editor.setInputType(inputType);
    editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
    editor.setSingleLine(true);
    fields.addView(editor, new LinearLayout.LayoutParams(-1, -2));
    return editor;
  }
}
