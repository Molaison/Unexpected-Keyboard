package juloo.keyboard2.doubao;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Builds the {@code extra.context} JSON string described in
 * {@code AsrContext.java} of the reference APK.
 *
 * <p>Structure reversed from the official client:
 * <pre>
 * {
 *   "before_context": "...",   // text before the cursor, from KeyboardJni.GetContextStr
 *   "after_context":  "...",   // text after the cursor
 *   "chat":           [ { "type": "user_input"|"asr_input",
 *                         "data": "{\"cursor_position\":N,\"text\":\"...\"}",
 *                         "time": "&lt;ms&gt;",
 *                         "app_apk_name": "&lt;package&gt;" } ],
 *   "hotwords":       [ "..." ],
 *   "modify_pair_info": {...}
 * }
 * </pre>
 *
 * <p>Each key is optional and gated by a user setting in the reference client
 * ({@code AsrContext.x(index, place, enabled)}); the same gating is exposed here
 * through {@link Flags}.
 */
public final class DoubaoTextContext
{
  /** Mirrors the per-key toggles the official client applies. */
  public static final class Flags
  {
    public boolean beforeContext = true;
    public boolean afterContext = true;
    public boolean chat = true;
    public boolean hotwords = true;
    public boolean modifyPairInfo = true;

    /** Upper bound on {@code before_context} length, from {@code context_max_length}. */
    public int maxBeforeLength = 256;
    public int maxAfterLength = 256;
    /** {@code AsrContext} keeps at most 20 chat turns. */
    public int maxChatTurns = 20;
  }

  /** One entry of the {@code chat} array. */
  public static final class ChatTurn
  {
    public static final String TYPE_USER_INPUT = "user_input";
    public static final String TYPE_ASR_INPUT = "asr_input";

    public final String type;
    public final String text;
    public final int cursorPosition;
    public final long timeMs;
    public final String appPackage;

    public ChatTurn(String type, String text, int cursorPosition, long timeMs,
        String appPackage)
    {
      this.type = type == null ? TYPE_USER_INPUT : type;
      this.text = text == null ? "" : text;
      this.cursorPosition = cursorPosition;
      this.timeMs = timeMs;
      this.appPackage = appPackage == null ? "" : appPackage;
    }
  }

  private final Flags flags;
  private final Deque<ChatTurn> chatTurns = new ArrayDeque<ChatTurn>();
  private final List<String> hotwords = new ArrayList<String>();

  private String beforeContext = "";
  private String afterContext = "";
  private String modifyPairInfo = "";
  private String appPackage = "";

  public DoubaoTextContext()
  {
    this(new Flags());
  }

  public DoubaoTextContext(Flags flags)
  {
    this.flags = flags == null ? new Flags() : flags;
  }

  public Flags flags()
  {
    return flags;
  }

  /**
   * Records the editor state for the next recognition. Called on every
   * selection update, exactly like {@code AsrContext.C()}.
   */
  public synchronized void setEditor(String before, String after,
      String appPackage)
  {
    this.beforeContext = trimTail(before, flags.maxBeforeLength);
    this.afterContext = trimHead(after, flags.maxAfterLength);
    if (appPackage != null && !appPackage.isEmpty())
      this.appPackage = appPackage;
  }

  public synchronized void setModifyPairInfo(String json)
  {
    this.modifyPairInfo = json == null ? "" : json;
  }

  /**
   * Appends a finished turn. {@code asrInput} selects
   * {@link ChatTurn#TYPE_ASR_INPUT}, otherwise the turn came from typing.
   */
  public synchronized void addTurn(String text, boolean asrInput)
  {
    if (text == null || text.isEmpty())
      return;
    chatTurns.addLast(new ChatTurn(
        asrInput ? ChatTurn.TYPE_ASR_INPUT : ChatTurn.TYPE_USER_INPUT,
        text, text.length(), System.currentTimeMillis(), appPackage));
    while (chatTurns.size() > flags.maxChatTurns)
      chatTurns.removeFirst();
  }

  public synchronized void setHotwords(List<String> words)
  {
    hotwords.clear();
    if (words != null)
      hotwords.addAll(words);
  }

  public synchronized void clear()
  {
    chatTurns.clear();
    hotwords.clear();
    beforeContext = "";
    afterContext = "";
    modifyPairInfo = "";
  }

  /** @return the context JSON, or an empty string when nothing is available. */
  public synchronized String build()
  {
    JsonObject root = new JsonObject();
    boolean any = false;

    if (flags.beforeContext && !beforeContext.isEmpty())
    {
      root.addProperty("before_context", beforeContext);
      any = true;
    }
    if (flags.afterContext && !afterContext.isEmpty())
    {
      root.addProperty("after_context", afterContext);
      any = true;
    }
    if (flags.chat && !chatTurns.isEmpty())
    {
      JsonArray array = new JsonArray();
      for (ChatTurn turn : chatTurns)
      {
        JsonObject data = new JsonObject();
        data.addProperty("cursor_position", turn.cursorPosition);
        data.addProperty("text", turn.text);

        JsonObject entry = new JsonObject();
        entry.addProperty("type", turn.type);
        entry.addProperty("data", data.toString());
        entry.addProperty("time", Long.toString(turn.timeMs));
        entry.addProperty("app_apk_name", turn.appPackage);
        array.add(entry);
      }
      root.add("chat", array);
      any = true;
    }
    if (flags.hotwords && !hotwords.isEmpty())
    {
      JsonArray array = new JsonArray();
      for (String word : hotwords)
        array.add(word);
      root.add("hotwords", array);
      any = true;
    }
    if (flags.modifyPairInfo && !modifyPairInfo.isEmpty())
    {
      root.addProperty("modify_pair_info", modifyPairInfo);
      any = true;
    }

    return any ? root.toString() : "";
  }

  private static String trimTail(String value, int max)
  {
    if (value == null)
      return "";
    if (max <= 0 || value.length() <= max)
      return value;
    return value.substring(value.length() - max);
  }

  private static String trimHead(String value, int max)
  {
    if (value == null)
      return "";
    if (max <= 0 || value.length() <= max)
      return value;
    return value.substring(0, max);
  }
}
