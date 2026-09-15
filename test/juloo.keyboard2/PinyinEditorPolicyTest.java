package juloo.keyboard2;

import android.text.InputType;
import juloo.keyboard2.pinyin.PinyinInput;
import org.junit.Test;
import static org.junit.Assert.*;

public class PinyinEditorPolicyTest
{
  @Test public void passwordsAndNumbersNeverComposeChinese()
  {
    int[] blocked = {
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
      InputType.TYPE_CLASS_NUMBER,
      InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
      InputType.TYPE_CLASS_PHONE,
      InputType.TYPE_CLASS_DATETIME
    };
    for (int type : blocked) assertFalse(PinyinInput.permitsChinese(type));
  }

  @Test public void noSuggestionsFlagDoesNotDisableChineseConversion()
  {
    assertTrue(PinyinInput.permitsChinese(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
    assertTrue(PinyinInput.permitsChinese(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT));
  }

  @Test public void addressesAndTerminalsStartInLatinButAllowAnExplicitSwitch()
  {
    int[] latin = {
      InputType.TYPE_NULL,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
      InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    };
    for (int type : latin)
    {
      assertTrue(PinyinInput.permitsChinese(type));
      assertTrue(PinyinInput.prefersLatin(type));
    }
    assertFalse(PinyinInput.prefersLatin(InputType.TYPE_CLASS_TEXT));
  }
}
