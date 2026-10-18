package juloo.keyboard2.doubao;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The HTTP side channels of the Doubao IME, reversed from {@code IRequests.java},
 * {@code IRecommends.java} and {@code AsrGlobalContext} ({@code m.java},
 * {@code n0.java}).
 *
 * <p>Request bodies reproduce the reference client field for field:
 *
 * <pre>
 * POST /api/v3/context/ime/user_words
 * {"user":{...}, "user_words":[...], "common_words":[...],
 *  "contact_list":[{"name":...,"number":...}], "at_words":[...], "additions":{}}
 *
 * POST /api/v3/context/ime/ner
 * {"user":{...}, "text":"...", "additions":{}}
 *
 * POST /api/v3/context/ime/modify_pair
 * {"user":{...}, "modify_data":{"src_text":..., "dst_text":...,
 *  "src_position":N, "dst_position":N, "additions":{"pt":"..."}}}
 *
 * POST /api/v1/user/get_config          -> {"data":{"sami_token":"..."}}
 * {"sami_app_key":"SYlxZr6LnvBaIVmF"}
 * </pre>
 *
 * <p>Everything the ASR session later consumes through {@code extra.context}
 * (personal words, hotwords, correction pairs) is uploaded here.
 */
public final class DoubaoCloudPorts
{
  public static final String SPEECH_BASE = "https://speech.bytedance.com";
  public static final String IME_BASE = "https://ime.doubao.com";
  public static final String CLOUD_BASE = "https://ime.oceancloudapi.com";

  public static final String PATH_USER_WORDS = "/api/v3/context/ime/user_words";
  public static final String PATH_NER = "/api/v3/context/ime/ner";
  public static final String PATH_MODIFY_PAIR = "/api/v3/context/ime/modify_pair";
  public static final String PATH_MODIFY_PAIR_V2 = "/api/v3/context/ime/modify_pair/v2";
  public static final String PATH_LOGIN = "/api/v3/context/ime/login";
  public static final String PATH_LOGOUT = "/api/v3/context/ime/logout";
  public static final String PATH_ASR_FMT = "/api/v1/asr/fmt";
  public static final String PATH_ASR_RECORD = "/api/v1/asr/record";
  public static final String PATH_AI_TRANSFORM = "/api/v1/ailab/transform";
  public static final String PATH_USER_CONFIG = "/api/v1/user/get_config";

  /**
   * Cloud hot-word dictionary. Reached over plain HTTPS on the CDN host; no
   * client certificate and no QUIC are required (verified against production).
   * The signed {@code Url} it returns points at a 67 KB engine dictionary.
   */
  public static final String PATH_HOTWORD = "/obric/ime/cloud/gethotword";

  /** {@code X-Api-App-Key} baked into the reference client. */
  public static final String API_APP_KEY = "SYlxZr6LnvBaIVmF";
  private static final String API_RESOURCE_ID = "asr.user.context";
  private static final String SDK_VERSION = "2";
  private static final String APP_ID = "401734";
  private static final String PLATFORM = "android";
  /** {@code AsrGlobalContext.a()} refreshes the token after ten hours. */
  private static final long TOKEN_TTL_MS = 36000000L;

  private static final MediaType JSON =
      MediaType.get("application/json; charset=utf-8");

  /** One entry of {@code contact_list}. */
  public static final class Contact
  {
    public final String name;
    public final String number;

    public Contact(String name, String number)
    {
      this.name = name == null ? "" : name;
      this.number = number == null ? "" : number;
    }
  }

  private final OkHttpClient httpClient;
  private final Object tokenLock = new Object();

  private volatile String deviceId = "";
  private volatile String userId = "";
  private volatile String appName = "oime";
  private volatile String appVersion = "1.5.1";
  private volatile boolean experienceImprove;
  private volatile String samiToken = "";
  private volatile long samiTokenFetchedAt;

  public DoubaoCloudPorts()
  {
    httpClient = new OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(10, TimeUnit.SECONDS)
      .build();
  }

  public void setDeviceId(String value)
  {
    deviceId = value == null ? "" : value;
  }

  public void setUserId(String value)
  {
    userId = value == null ? "" : value;
  }

  public void setAppName(String value)
  {
    if (value != null && !value.isEmpty())
      appName = value;
  }

  public void setAppVersion(String value)
  {
    if (value != null && !value.isEmpty())
      appVersion = value;
  }

  /** Mirrors the {@code experience_improve} toggle of the reference client. */
  public void setExperienceImprove(boolean enabled)
  {
    experienceImprove = enabled;
  }

  /**
   * The {@code X-Api-Token} used by every non-{@code /api/v3/context/ime/} call.
   * Refreshed on demand and cached for ten hours, like the official client.
   */
  public String samiToken() throws IOException
  {
    synchronized (tokenLock)
    {
      long now = System.currentTimeMillis();
      if (!samiToken.isEmpty() && now - samiTokenFetchedAt <= TOKEN_TTL_MS)
        return samiToken;

      JsonObject body = new JsonObject();
      body.addProperty("sami_app_key", API_APP_KEY);
      JsonObject root = executeJson(plainRequest(CLOUD_BASE + PATH_USER_CONFIG,
          body));
      JsonObject data = optionalObject(root, "data");
      if (data == null)
        data = optionalObject(root, "Data");
      String token = data == null ? "" : optionalString(data, "sami_token");
      if (token.isEmpty())
        throw new IOException("get_config returned no sami_token");
      samiToken = token;
      samiTokenFetchedAt = now;
      return token;
    }
  }

  /** Uploads personal words, common words, contacts and "@" words. */
  public String uploadUserWords(List<String> userWords, List<String> commonWords,
      List<Contact> contacts, List<String> atWords) throws IOException
  {
    JsonObject body = new JsonObject();
    body.add("user", userObject());
    if (userWords != null && !userWords.isEmpty())
      body.add("user_words", toArray(userWords));
    if (commonWords != null && !commonWords.isEmpty())
      body.add("common_words", toArray(commonWords));
    if (contacts != null && !contacts.isEmpty())
    {
      JsonArray array = new JsonArray();
      for (Contact contact : contacts)
      {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", contact.name);
        entry.addProperty("number", contact.number);
        array.add(entry);
      }
      body.add("contact_list", array);
    }
    if (atWords != null && !atWords.isEmpty())
      body.add("at_words", toArray(atWords));
    body.add("additions", new JsonObject());
    return contextPost(PATH_USER_WORDS, body);
  }

  /** Named-entity recognition for a piece of text. */
  public String requestNer(String text) throws IOException
  {
    JsonObject body = new JsonObject();
    body.add("user", userObject());
    body.addProperty("text", text == null ? "" : text);
    body.add("additions", new JsonObject());
    return contextPost(PATH_NER, body);
  }

  /**
   * Reports an "original result -> user correction" pair; this is what makes
   * the server learn the user's vocabulary over time.
   */
  public String reportModifyPair(String sourceText, String targetText,
      int sourcePosition, int targetPosition, String ptJson) throws IOException
  {
    JsonObject additions = new JsonObject();
    if (ptJson != null && !ptJson.isEmpty())
      additions.addProperty("pt", ptJson);

    JsonObject modifyData = new JsonObject();
    modifyData.addProperty("src_text", sourceText == null ? "" : sourceText);
    modifyData.addProperty("dst_text", targetText == null ? "" : targetText);
    modifyData.addProperty("src_position", sourcePosition);
    modifyData.addProperty("dst_position", targetPosition);
    modifyData.add("additions", additions);

    JsonObject body = new JsonObject();
    body.add("user", userObject());
    body.add("modify_data", modifyData);
    return contextPost(PATH_MODIFY_PAIR, body);
  }

  /** Server-side text formatting of a raw ASR transcript. */
  public String formatTranscript(JsonObject body) throws IOException
  {
    return execute(plainRequest(IME_BASE + PATH_ASR_FMT, body));
  }

  /**
   * The cloud hot-word dictionary.
   *
   * <p>Verified against production: the CDN answers
   * {@code {"Md5":"...","Url":"https://lf*-ime-sign.oceancloudapi.com/...","Version":N}}.
   * The {@code Url} is pre-signed and expires, so fetch it on demand.
   *
   * @param knownVersion the version already cached by the caller, 0 to force
   * @return the parsed response, holding {@code Md5}, {@code Url}, {@code Version}
   */
  public JsonObject fetchHotword(int knownVersion) throws IOException
  {
    JsonObject inner = new JsonObject();
    inner.addProperty("version", knownVersion);
    JsonObject body = new JsonObject();
    body.addProperty("aid", "ime-cloud");
    body.addProperty("version", Long.toString(System.currentTimeMillis()));
    body.addProperty("data", base64Raw(inner));
    body.addProperty("did", deviceId);
    body.addProperty("app_version", appVersion);
    body.addProperty("app_id", APP_ID);
    body.addProperty("os_type", PLATFORM);

    Request request = new Request.Builder()
      .url(CLOUD_BASE + PATH_HOTWORD + "?version=" + knownVersion)
      .header("Content-Type", "application/json")
      .post(RequestBody.create(body.toString(), JSON))
      .build();
    return executeJson(request);
  }

  /** Per-user ASR configuration, including {@code asr_config.app_key}. */
  public JsonObject fetchUserConfig() throws IOException
  {
    JsonObject body = new JsonObject();
    body.addProperty("sami_app_key", API_APP_KEY);
    return executeJson(plainRequest(CLOUD_BASE + PATH_USER_CONFIG, body));
  }

  public void shutdown()
  {
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
  }

  /** The {@code user} object shared by every context request. */
  public JsonObject userObject()
  {
    JsonObject user = new JsonObject();
    user.addProperty("uid", userId);
    user.addProperty("did", deviceId);
    user.addProperty("app_name", appName);
    user.addProperty("app_version", appVersion);
    user.addProperty("sdk_version", "");
    user.addProperty("platform", PLATFORM);
    user.addProperty("experience_improve", experienceImprove);
    return user;
  }

  private static JsonArray toArray(List<String> values)
  {
    JsonArray array = new JsonArray();
    for (String value : values)
      array.add(value);
    return array;
  }

  /**
   * Unpadded base64, which is what the cloud gateway's decoder expects; the
   * padded form is rejected with "illegal base64 data".
   */
  static String base64Raw(JsonObject value)
  {
    String encoded = okio.ByteString.encodeUtf8(value.toString()).base64();
    int end = encoded.length();
    while (end > 0 && encoded.charAt(end - 1) == '=')
      end--;
    return encoded.substring(0, end);
  }

  private String contextPost(String path, JsonObject body) throws IOException
  {
    Request.Builder builder = new Request.Builder()
      .url(SPEECH_BASE + path)
      .header("Content-Type", "application/json")
      .header("app_version", appVersion)
      .header("app_id", APP_ID)
      .header("os_type", PLATFORM)
      .header("X-Api-Resource-Id", API_RESOURCE_ID)
      .header("X-Api-App-Key", API_APP_KEY)
      .header("X-Api-Request-Id", UUID.randomUUID().toString())
      .header("X-Api-Sequence", "-1");

    if (path.startsWith("/api/v3/context/ime/"))
      builder.header("sdk-version", SDK_VERSION);
    else
      builder.header("X-Api-Token", safeToken());

    builder.post(RequestBody.create(body.toString(), JSON));
    return execute(builder.build());
  }

  private String safeToken()
  {
    try
    {
      return samiToken();
    }
    catch (IOException error)
    {
      return "";
    }
  }

  private Request plainRequest(String url, JsonObject body)
  {
    return new Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .header("app_version", appVersion)
      .header("app_id", APP_ID)
      .header("os_type", PLATFORM)
      .header("did", deviceId)
      .post(RequestBody.create(body.toString(), JSON))
      .build();
  }

  private String execute(Request request) throws IOException
  {
    try (Response response = httpClient.newCall(request).execute())
    {
      String body = response.body() == null ? "" : response.body().string();
      if (!response.isSuccessful())
        throw new IOException("Doubao request to " + request.url().encodedPath()
            + " failed with HTTP " + response.code() + ": " + body);
      return body;
    }
  }

  private JsonObject executeJson(Request request) throws IOException
  {
    String body = execute(request);
    try
    {
      JsonElement parsed = JsonParser.parseString(body);
      if (!parsed.isJsonObject())
        throw new IOException("Doubao returned a non-object JSON body");
      return parsed.getAsJsonObject();
    }
    catch (JsonParseException e)
    {
      throw new IOException("Doubao returned invalid JSON", e);
    }
  }

  private static JsonObject optionalObject(JsonObject parent, String key)
  {
    if (parent == null)
      return null;
    JsonElement value = parent.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static String optionalString(JsonObject parent, String key)
  {
    if (parent == null)
      return "";
    JsonElement value = parent.get(key);
    if (value == null || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString())
      return "";
    return value.getAsString();
  }
}
