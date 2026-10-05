package juloo.keyboard2;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** Shared, theme-aware styling for the candidate and clipboard surfaces. */
public final class KeyboardUi
{
  public final Context context;
  public final int background, surface, action, text, secondary, accent;

  public KeyboardUi(Context context)
  {
    this.context = context;
    TypedArray a = context.obtainStyledAttributes(new int[] {
      R.attr.colorKeyboard, R.attr.colorKey, R.attr.colorKeyAction,
      R.attr.colorLabel, R.attr.colorSubLabel, R.attr.colorLabelActivated });
    background = a.getColor(0, 0xfff2f4f8);
    surface = a.getColor(1, 0xffffffff);
    action = a.getColor(2, surface);
    text = a.getColor(3, 0xff1d2939);
    secondary = a.getColor(4, text);
    accent = a.getColor(5, text);
    a.recycle();
  }

  public int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }

  public GradientDrawable round(int color, float radius)
  {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  public void touchBackground(TextView v, int color)
  {
    v.setBackground(new RippleDrawable(ColorStateList.valueOf((accent & 0xffffff) | 0x33000000),
        round(color, 10), null));
  }

  public TextView label(CharSequence value, float size, boolean strong)
  {
    TextView v = new TextView(context);
    v.setText(value);
    v.setTextColor(text);
    v.setTextSize(size);
    v.setTypeface(Typeface.create("sans-serif", strong ? Typeface.BOLD : Typeface.NORMAL));
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  public TextView button(CharSequence value, Runnable action)
  {
    TextView v = label(value, 14, true);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(48));
    v.setMinWidth(dp(48));
    v.setPadding(dp(12), 0, dp(12), 0);
    v.setFocusable(false); // Do not steal focus from the application's editor.
    touchBackground(v, background);
    v.setOnClickListener(view -> action.run());
    return v;
  }
}
