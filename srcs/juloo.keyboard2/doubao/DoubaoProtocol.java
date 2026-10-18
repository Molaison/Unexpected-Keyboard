package juloo.keyboard2.doubao;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wire-level implementation of the Doubao IME ASR protobuf protocol.
 *
 * <p>Reconstructed from {@code doubaoime_v1.5.1_100501006_official_arm64_release.apk}.
 * The RPC envelope lives in {@code SdkImpl.java} and the payload semantics in
 * {@code libaudioeffect.so} ({@code sami_core}), see
 * {@code outputs/doubao-ime-ports-reverse.md} for the full evidence trail.
 */
public final class DoubaoProtocol
{
  public static final int FRAME_UNSPECIFIED = 0;
  public static final int FRAME_FIRST = 1;
  public static final int FRAME_MIDDLE = 3;
  public static final int FRAME_LAST = 9;

  private static final String SERVICE_NAME = "ASR";
  private static final String APP_NAME = "oime";

  /** Doubao's VAD smoothing window, {@code extra.end_smooth_window_ms}. */
  public static final int DEFAULT_END_SMOOTH_WINDOW_MS = 800;
  /** {@code finish_wait_timeout}: how long the server may keep refining. */
  public static final int DEFAULT_FINISH_WAIT_TIMEOUT_MS = 10000;

  public enum ResponseType
  {
    TASK_STARTED,
    SESSION_STARTED,
    SESSION_FINISHED,
    VAD_START,
    HEARTBEAT,
    /** Streaming, still changing. Never commit this as final text. */
    INTERIM_RESULT,
    /** Utterance closed by VAD; the segment is stable but may still be refined. */
    STABLE_RESULT,
    /** Final pass output; safe to commit. */
    FINAL_RESULT,
    /** Nothing to display (empty interim, duplicate VAD finish, ...). */
    DROPPED,
    ERROR,
    UNKNOWN
  }

  /** One entry of the server's {@code results} array. */
  public static final class Result
  {
    public final String text;
    public final boolean interim;
    public final boolean vadFinished;
    public final boolean offline;
    /** {@code nonstream_result}: the whole utterance delivered in one shot. */
    public final boolean nonstream;
    /** {@code is_final}: the server declares this entry terminal. */
    public final boolean finalEntry;
    public final int startTime;
    public final int endTime;

    Result(String text, boolean interim, boolean vadFinished, boolean offline,
        boolean nonstream, boolean finalEntry, int startTime, int endTime)
    {
      this.text = text;
      this.interim = interim;
      this.vadFinished = vadFinished;
      this.offline = offline;
      this.nonstream = nonstream;
      this.finalEntry = finalEntry;
      this.startTime = startTime;
      this.endTime = endTime;
    }
  }

  public static final class Response
  {
    public final ResponseType type;
    public final String text;
    public final boolean isFinal;
    public final boolean vadStart;
    public final boolean vadFinished;
    public final int packetNumber;
    public final int statusCode;
    public final String errorMessage;
    public final String rawJson;
    /** Per-segment detail, empty when the message carries no result. */
    public final List<Result> results;
    /**
     * The text of the last entry the server already marked non-interim, i.e. the
     * part of the transcript that will not change any more. Empty while the
     * whole utterance is still interim.
     */
    public final String stableText;
    /**
     * True when every entry of {@code results} is non-interim. Live traffic
     * shows this only for the final pass that follows {@code FinishSession}.
     */
    public final boolean allEntriesFinal;
    /** {@code extra.switch_flag}: the engine switched between online/offline. */
    public final boolean switchFlag;
    /** {@code extra.stream_asr_finish}: the stream is done producing text. */
    public final boolean streamAsrFinish;
    public final long seqId;
    public final boolean offlineResult;

    private Response(ResponseType type, String text, boolean isFinal,
        boolean vadStart, boolean vadFinished, int packetNumber,
        int statusCode, String errorMessage, String rawJson,
        List<Result> results, String stableText, boolean allEntriesFinal,
        boolean switchFlag, boolean streamAsrFinish, long seqId,
        boolean offlineResult)
    {
      this.type = type;
      this.text = text;
      this.isFinal = isFinal;
      this.vadStart = vadStart;
      this.vadFinished = vadFinished;
      this.packetNumber = packetNumber;
      this.statusCode = statusCode;
      this.errorMessage = errorMessage;
      this.rawJson = rawJson;
      this.results = results == null
          ? Collections.<Result>emptyList() : results;
      this.stableText = stableText == null ? "" : stableText;
      this.allEntriesFinal = allEntriesFinal;
      this.switchFlag = switchFlag;
      this.streamAsrFinish = streamAsrFinish;
      this.seqId = seqId;
      this.offlineResult = offlineResult;
    }

    static Response simple(ResponseType type)
    {
      return new Response(type, "", false, false, false, -1, 0, "", null,
          null, "", false, false, false, -1L, false);
    }

    static Response error(int statusCode, String message)
    {
      return new Response(ResponseType.ERROR, "", false, false, false, -1,
          statusCode, message, null, null, "", false, false, false, -1L,
          false);
    }
  }

  public static final class ProtocolException extends IOException
  {
    ProtocolException(String message)
    {
      super(message);
    }

    ProtocolException(String message, Throwable cause)
    {
      super(message, cause);
    }
  }

  /**
   * Everything the session needs, mirroring the {@code extra} object built in
   * {@code SdkImpl.l()} of the reference implementation.
   */
  public static final class SessionConfig
  {
    public String deviceId = "";
    public String appName = APP_NAME;
    public String inputMode = "tool";
    public String appVersion = "";
    public String romOsVersion = "";
    public String hwVendorModel = "";
    public String hwVendorBrand = "";
    /** The context JSON string; see {@link DoubaoTextContext}. */
    public String context = "";

    /** 停顿后的二次校正。官方为 true。 */
    public boolean enableTwopass = true;
    /** 结束后的整场定稿。官方为 true。 */
    public boolean enableThreepass = true;
    /** 二次校正失败时重试。官方为 true。 */
    public boolean useTwopassRetry = true;

    public int endSmoothWindowMs = DEFAULT_END_SMOOTH_WINDOW_MS;
    public boolean removeSpaceBetweenHanNum;
    public boolean removeSpaceBetweenHanEng;
    public boolean formatSpaceAtNewline = true;
    public boolean enablePrintChinese;
    public boolean disableUserWords;
    public boolean strongDdc;
    /** {@code asr_params} passthrough for the engine. */
    public String asrParams = "";

    public int cellCompressRate = 8;
    public int finishWaitOfflineTime;
    public int offlineWaitOnlineTime;
    public int maxWaitSwitchOfflineTime;
    public int offlineWaitOnlineIntervalTime;
    public int switchNetworkQualityThreshold;
    public int switchNetworkRttThreshold;
    public int switchNetworkPingTimeout;
    public List<Integer> retryServerCodes = Collections.emptyList();

    public boolean s2aSendEnable;
    public List<String> s2aSendCommands = Collections.emptyList();
    public String lastUid = "";

    /** Extra opaque fields appended verbatim, useful for A/B experiments. */
    public JsonObject extraOverrides;

    public SessionConfig deviceId(String value)
    {
      deviceId = value == null ? "" : value;
      return this;
    }

    public SessionConfig context(String value)
    {
      context = value == null ? "" : value;
      return this;
    }
  }

  private DoubaoProtocol() {}

  public static byte[] buildStartTask(String requestId, String token)
  {
    requireNonEmpty(requestId, "requestId");
    requireNonEmpty(token, "token");
    return request(requestId, token, "StartTask", "", null, FRAME_UNSPECIFIED);
  }

  public static byte[] buildStartSession(String requestId, String token,
      SessionConfig config)
  {
    requireNonEmpty(requestId, "requestId");
    requireNonEmpty(token, "token");
    if (config == null)
      throw new IllegalArgumentException("config must not be null");
    return request(requestId, token, "StartSession",
        buildSessionPayload(config), null, FRAME_UNSPECIFIED);
  }

  public static byte[] buildFinishSession(String requestId, String token)
  {
    requireNonEmpty(requestId, "requestId");
    requireNonEmpty(token, "token");
    return request(requestId, token, "FinishSession", "", null,
        FRAME_UNSPECIFIED);
  }

  public static byte[] buildAudioRequest(String requestId, byte[] opusFrame,
      int frameState, long timestampMs)
  {
    return buildAudioRequest(requestId, opusFrame, frameState, timestampMs,
        false, false);
  }

  /**
   * @param forceTwopass request {@code force_asr_twopass} on this frame
   * @param finishAudio  mark the end of the audio stream ({@code finish_audio})
   */
  public static byte[] buildAudioRequest(String requestId, byte[] opusFrame,
      int frameState, long timestampMs, boolean forceTwopass,
      boolean finishAudio)
  {
    requireNonEmpty(requestId, "requestId");
    if (opusFrame == null || opusFrame.length == 0)
      throw new IllegalArgumentException("opusFrame must not be empty");
    if (frameState != FRAME_FIRST && frameState != FRAME_MIDDLE
        && frameState != FRAME_LAST)
      throw new IllegalArgumentException("Invalid frameState: " + frameState);

    JsonObject extra = new JsonObject();
    if (forceTwopass)
      extra.addProperty("force_asr_twopass", true);
    if (finishAudio)
      extra.addProperty("finish_audio", true);

    JsonObject payload = new JsonObject();
    payload.add("extra", extra);
    payload.addProperty("timestamp_ms", timestampMs);
    return request(requestId, "", "TaskRequest", payload.toString(),
        opusFrame, frameState);
  }

  public static String buildSessionPayload(SessionConfig config)
  {
    JsonObject audioInfo = new JsonObject();
    audioInfo.addProperty("channel", 1);
    audioInfo.addProperty("format", "speech_opus");
    audioInfo.addProperty("sample_rate", 16000);

    JsonObject networkChange = new JsonObject();
    networkChange.addProperty("switch_network_quality_threshold",
        config.switchNetworkQualityThreshold);
    networkChange.addProperty("switch_network_rtt_threshold",
        config.switchNetworkRttThreshold);
    networkChange.addProperty("switch_network_ping_timeout",
        config.switchNetworkPingTimeout);

    JsonObject extra = new JsonObject();
    extra.addProperty("app_name", config.appName);
    if (!config.deviceId.isEmpty())
      extra.addProperty("did", config.deviceId);
    extra.addProperty("enable_asr_twopass", config.enableTwopass);
    extra.addProperty("enable_asr_threepass", config.enableThreepass);
    extra.addProperty("use_twopass_retry", config.useTwopassRetry);
    extra.addProperty("end_smooth_window_ms", config.endSmoothWindowMs);
    extra.addProperty("remove_space_between_han_num",
        config.removeSpaceBetweenHanNum);
    extra.addProperty("remove_space_between_han_eng",
        config.removeSpaceBetweenHanEng);
    extra.addProperty("format_space_at_newline", config.formatSpaceAtNewline);
    extra.addProperty("enable_print_chinese", config.enablePrintChinese);
    extra.addProperty("input_mode", config.inputMode);
    extra.addProperty("cell_compress_rate", config.cellCompressRate);
    extra.addProperty("finish_wait_offline_time",
        config.finishWaitOfflineTime);
    extra.addProperty("offline_wait_online_time", config.offlineWaitOnlineTime);
    extra.addProperty("max_wait_switch_offline_time",
        config.maxWaitSwitchOfflineTime);
    extra.addProperty("offline_wait_online_interval_time",
        config.offlineWaitOnlineIntervalTime);
    extra.add("network_change", networkChange);
    extra.addProperty("disable_user_words", config.disableUserWords);
    extra.addProperty("strong_ddc", config.strongDdc);
    extra.addProperty("s2a_send_enable", config.s2aSendEnable);
    if (!config.asrParams.isEmpty())
      extra.addProperty("asr_params", config.asrParams);
    if (!config.romOsVersion.isEmpty())
      extra.addProperty("rom_os_version", config.romOsVersion);
    if (!config.hwVendorModel.isEmpty())
      extra.addProperty("hw_vendor_model", config.hwVendorModel);
    if (!config.hwVendorBrand.isEmpty())
      extra.addProperty("hw_vendor_brand", config.hwVendorBrand);
    if (!config.appVersion.isEmpty())
      extra.addProperty("app_version", config.appVersion);
    if (!config.context.isEmpty())
      extra.addProperty("context", config.context);
    if (!config.lastUid.isEmpty())
      extra.addProperty("last_uid", config.lastUid);

    JsonArray sendCommands = new JsonArray();
    for (String command : config.s2aSendCommands)
      sendCommands.add(command);
    extra.add("s2a_send_commands", sendCommands);

    JsonArray retryCodes = new JsonArray();
    for (Integer code : config.retryServerCodes)
      retryCodes.add(code);
    extra.add("retry_server_code", retryCodes);

    if (config.extraOverrides != null)
    {
      for (java.util.Map.Entry<String, JsonElement> entry
          : config.extraOverrides.entrySet())
        extra.add(entry.getKey(), entry.getValue());
    }

    JsonObject root = new JsonObject();
    root.add("audio_info", audioInfo);
    root.addProperty("enable_punctuation", true);
    root.addProperty("enable_speech_rejection", false);
    root.addProperty("enable_vad", true);
    root.addProperty("low_latency", false);
    root.add("extra", extra);
    return root.toString();
  }

  public static Response parseResponse(byte[] data) throws ProtocolException
  {
    if (data == null || data.length == 0)
      throw new ProtocolException("Empty ASR protobuf response");

    ProtoReader reader = new ProtoReader(data);
    String messageType = "";
    int statusCode = 0;
    String statusMessage = "";
    String resultJson = "";

    while (reader.hasRemaining())
    {
      int tag = reader.readTag();
      int field = tag >>> 3;
      int wireType = tag & 7;
      switch (field)
      {
        case 4:
          messageType = reader.readString(wireType, field);
          break;
        case 5:
          statusCode = reader.readInt32(wireType, field);
          break;
        case 6:
          statusMessage = reader.readString(wireType, field);
          break;
        case 7:
          resultJson = reader.readString(wireType, field);
          break;
        default:
          reader.skip(wireType);
          break;
      }
    }

    switch (messageType)
    {
      case "TaskStarted":
        return Response.simple(ResponseType.TASK_STARTED);
      case "SessionStarted":
        return Response.simple(ResponseType.SESSION_STARTED);
      case "SessionFinished":
        return Response.simple(ResponseType.SESSION_FINISHED);
      case "TaskFailed":
      case "SessionFailed":
        return Response.error(statusCode,
            nonEmptyStatus(statusCode, statusMessage));
      default:
        break;
    }

    if (resultJson.isEmpty())
      return Response.simple(ResponseType.UNKNOWN);

    final JsonObject root;
    try
    {
      JsonElement parsed = JsonParser.parseString(resultJson);
      if (!parsed.isJsonObject())
        throw new ProtocolException("ASR result_json is not an object");
      root = parsed.getAsJsonObject();
    }
    catch (JsonParseException e)
    {
      throw new ProtocolException("Invalid ASR result_json", e);
    }

    JsonObject extra = optionalObject(root, "extra");
    boolean switchFlag = optionalBoolean(extra, "switch_flag", false);
    boolean streamAsrFinish = optionalBoolean(extra, "stream_asr_finish", false);
    long seqId = optionalLong(extra, "seq_id", -1L);
    boolean vadStart = optionalBoolean(extra, "vad_start", false);

    JsonElement resultsElement = root.get("results");
    if (resultsElement == null || resultsElement.isJsonNull())
    {
      int packetNumber = optionalInt(extra, "packet_number", -1);
      if (vadStart)
        return new Response(ResponseType.VAD_START, "", false, true, false,
            packetNumber, 0, "", resultJson, null, "", false, switchFlag,
            streamAsrFinish, seqId, false);
      return new Response(ResponseType.HEARTBEAT, "", false, false, false,
          packetNumber, 0, "", resultJson, null, "", false, switchFlag,
          streamAsrFinish, seqId, false);
    }

    List<Result> results = parseResults(resultsElement);
    String text = "";
    String stableText = "";
    boolean explicitFinal = false;
    boolean vadFinished = false;
    boolean nonstreamResult = false;
    boolean anyOffline = false;
    boolean allFinal = !results.isEmpty();

    for (int index = 0; index < results.size(); index++)
    {
      Result result = results.get(index);
      // Live traffic always puts the merged, full transcript in results[0];
      // later entries are the per-segment breakdown.
      if (index == 0)
        text = result.text;
      else if (text.isEmpty())
        text = result.text;

      if (!result.interim)
      {
        explicitFinal = true;
        if (!result.text.isEmpty())
          stableText = result.text;
      }
      else
      {
        allFinal = false;
      }
      if (result.finalEntry)
        explicitFinal = true;
      if (result.vadFinished)
        vadFinished = true;
      if (result.offline)
        anyOffline = true;
      if (result.nonstream)
        nonstreamResult = true;
    }
    if (optionalBoolean(root, "nonstream_result", false))
      nonstreamResult = true;
    if (optionalBoolean(extra, "nonstream_result", false))
      nonstreamResult = true;

    if (vadStart)
      return new Response(ResponseType.VAD_START, "", false, true, false, -1,
          0, "", resultJson, results, stableText, allFinal, switchFlag,
          streamAsrFinish, seqId, anyOffline);

    if (text.isEmpty() && !vadFinished)
    {
      // "text is empty interim result, not callback"
      return new Response(ResponseType.DROPPED, "", false, false, false, -1, 0,
          "", resultJson, results, stableText, allFinal, switchFlag,
          streamAsrFinish, seqId, anyOffline);
    }

    final ResponseType type;
    if (nonstreamResult || (explicitFinal && vadFinished && allFinal))
      type = ResponseType.FINAL_RESULT;
    else if (vadFinished)
      type = ResponseType.STABLE_RESULT;
    else
      type = ResponseType.INTERIM_RESULT;

    return new Response(type, text, type == ResponseType.FINAL_RESULT, false,
        vadFinished, -1, 0, "", resultJson, results, stableText, allFinal,
        switchFlag, streamAsrFinish, seqId, anyOffline);
  }

  private static List<Result> parseResults(JsonElement element)
      throws ProtocolException
  {
    List<Result> out = new ArrayList<Result>();
    for (JsonObject object : resultObjects(element))
    {
      String text = extractResultText(object);
      Boolean interim = firstBoolean(object, "is_interim", "interim");
      boolean isInterim = interim == null || interim.booleanValue();
      boolean vadFinished = firstBooleanValue(object, false,
          "is_vad_finished", "vad_finished");
      JsonObject resultExtra = optionalObject(object, "extra");
      boolean offline = firstBooleanValue(object, false, "is_offline_result",
          "offline_result")
          || optionalBoolean(resultExtra, "is_offline_result", false);
      boolean nonstream = firstBooleanValue(object, false, "nonstream_result")
          || optionalBoolean(resultExtra, "nonstream_result", false);
      boolean finalEntry = firstBooleanValue(object, false, "is_final")
          || optionalBoolean(resultExtra, "is_final", false);
      if (finalEntry)
        isInterim = false;
      int start = optionalInt(object, "start_time", -1);
      int end = optionalInt(object, "end_time", -1);
      out.add(new Result(text, isInterim, vadFinished, offline, nonstream,
          finalEntry, start, end));
    }
    return out;
  }

  /**
   * Server-side result hygiene reproduced from {@code asr_processor.cpp}:
   * empty interim results are never surfaced, a distinct VAD finish is only
   * delivered once, but an empty text with {@code vad_finished} still reaches
   * the caller so the segment can be closed.
   */
  public static final class ResultGate
  {
    private final Set<Long> deliveredVadFinishes = new LinkedHashSet<Long>();
    private long lastSeqId = -1L;

    public Response filter(Response response)
    {
      if (response.type == ResponseType.DROPPED
          || response.type == ResponseType.INTERIM_RESULT
          || response.type == ResponseType.STABLE_RESULT
          || response.type == ResponseType.FINAL_RESULT)
      {
        if (response.seqId >= 0)
        {
          if (response.seqId == lastSeqId && response.vadFinished)
            return withType(response, ResponseType.DROPPED);
          lastSeqId = response.seqId;
        }
      }
      if (response.vadFinished)
      {
        long key = response.seqId >= 0 ? response.seqId
            : (long)response.text.hashCode();
        if (!deliveredVadFinishes.add(key)
            && response.type == ResponseType.STABLE_RESULT)
          return withType(response, ResponseType.DROPPED);
      }
      return response;
    }

    public void reset()
    {
      deliveredVadFinishes.clear();
      lastSeqId = -1L;
    }

    private static Response withType(Response response, ResponseType type)
    {
      return new Response(type, response.text, response.isFinal,
          response.vadStart, response.vadFinished, response.packetNumber,
          response.statusCode, response.errorMessage, response.rawJson,
          response.results, response.stableText, response.allEntriesFinal,
          response.switchFlag, response.streamAsrFinish, response.seqId,
          response.offlineResult);
    }
  }

  private static byte[] request(String requestId, String token,
      String methodName, String payload, byte[] audioData, int frameState)
  {
    ProtoWriter writer = new ProtoWriter();
    writer.string(2, token);
    writer.string(3, SERVICE_NAME);
    writer.string(5, methodName);
    writer.string(6, payload);
    writer.bytes(7, audioData);
    writer.string(8, requestId);
    writer.int32(9, frameState);
    return writer.toByteArray();
  }

  private static String nonEmptyStatus(int statusCode, String statusMessage)
  {
    if (!statusMessage.isEmpty())
      return statusMessage;
    return "ASR returned status " + statusCode;
  }

  private static List<JsonObject> resultObjects(JsonElement results)
      throws ProtocolException
  {
    List<JsonObject> objects = new ArrayList<JsonObject>();
    if (results.isJsonObject())
    {
      objects.add(results.getAsJsonObject());
      return objects;
    }
    if (!results.isJsonArray())
      throw new ProtocolException("ASR results must be an object or array");

    JsonArray array = results.getAsJsonArray();
    for (JsonElement item : array)
    {
      if (!item.isJsonObject())
        throw new ProtocolException("ASR results array contains a non-object");
      objects.add(item.getAsJsonObject());
    }
    return objects;
  }

  private static String extractResultText(JsonObject result)
  {
    String direct = firstString(result, "text", "utterance", "transcript");
    if (!direct.isEmpty())
      return direct;

    for (String key : new String[]{"alternatives", "words", "utterances"})
    {
      JsonElement nested = result.get(key);
      if (nested == null || !nested.isJsonArray())
        continue;
      for (JsonElement item : nested.getAsJsonArray())
      {
        if (!item.isJsonObject())
          continue;
        String text = extractResultText(item.getAsJsonObject());
        if (!text.isEmpty())
          return text;
      }
    }
    return "";
  }

  private static String firstString(JsonObject object, String... keys)
  {
    for (String key : keys)
    {
      JsonElement value = object.get(key);
      if (value != null && value.isJsonPrimitive())
      {
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString() && !primitive.getAsString().isEmpty())
          return primitive.getAsString();
      }
    }
    return "";
  }

  private static Boolean firstBoolean(JsonObject object, String... keys)
  {
    for (String key : keys)
    {
      JsonElement value = object.get(key);
      if (value != null && value.isJsonPrimitive()
          && value.getAsJsonPrimitive().isBoolean())
        return value.getAsBoolean();
    }
    return null;
  }

  private static boolean firstBooleanValue(JsonObject object,
      boolean defaultValue, String... keys)
  {
    Boolean value = firstBoolean(object, keys);
    return value == null ? defaultValue : value.booleanValue();
  }

  private static boolean optionalBoolean(JsonObject object, String key,
      boolean defaultValue)
  {
    if (object == null)
      return defaultValue;
    return firstBooleanValue(object, defaultValue, key);
  }

  private static int optionalInt(JsonObject object, String key, int defaultValue)
  {
    if (object == null)
      return defaultValue;
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber())
      return defaultValue;
    return value.getAsInt();
  }

  private static long optionalLong(JsonObject object, String key,
      long defaultValue)
  {
    if (object == null)
      return defaultValue;
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber())
      return defaultValue;
    return value.getAsLong();
  }

  private static JsonObject optionalObject(JsonObject parent, String key)
      throws ProtocolException
  {
    if (parent == null)
      return null;
    JsonElement value = parent.get(key);
    if (value == null || value.isJsonNull())
      return null;
    if (!value.isJsonObject())
      throw new ProtocolException("ASR JSON field '" + key + "' is not an object");
    return value.getAsJsonObject();
  }

  private static void requireNonEmpty(String value, String name)
  {
    if (value == null || value.isEmpty())
      throw new IllegalArgumentException(name + " must not be empty");
  }

  private static final class ProtoWriter
  {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void string(int field, String value)
    {
      if (value == null || value.isEmpty())
        return;
      bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    void bytes(int field, byte[] value)
    {
      if (value == null || value.length == 0)
        return;
      varint((field << 3) | 2);
      varint(value.length);
      out.write(value, 0, value.length);
    }

    void int32(int field, int value)
    {
      if (value == 0)
        return;
      varint((field << 3) | 0);
      varint(value);
    }

    void varint(long value)
    {
      while ((value & ~0x7fL) != 0)
      {
        out.write(((int)value & 0x7f) | 0x80);
        value >>>= 7;
      }
      out.write((int)value);
    }

    byte[] toByteArray()
    {
      return out.toByteArray();
    }
  }

  private static final class ProtoReader
  {
    private final byte[] data;
    private int position;

    ProtoReader(byte[] data)
    {
      this.data = data;
    }

    boolean hasRemaining()
    {
      return position < data.length;
    }

    int readTag() throws ProtocolException
    {
      long tag = readVarint();
      if (tag == 0 || tag > Integer.MAX_VALUE)
        throw new ProtocolException("Invalid protobuf tag: " + tag);
      return (int)tag;
    }

    String readString(int wireType, int field) throws ProtocolException
    {
      byte[] value = readBytes(wireType, field);
      return new String(value, StandardCharsets.UTF_8);
    }

    byte[] readBytes(int wireType, int field) throws ProtocolException
    {
      requireWireType(wireType, 2, field);
      long lengthValue = readVarint();
      if (lengthValue > Integer.MAX_VALUE)
        throw new ProtocolException("Protobuf field is too large");
      int length = (int)lengthValue;
      if (length < 0 || position + length > data.length)
        throw new ProtocolException("Truncated protobuf length-delimited field");
      byte[] value = new byte[length];
      System.arraycopy(data, position, value, 0, length);
      position += length;
      return value;
    }

    int readInt32(int wireType, int field) throws ProtocolException
    {
      requireWireType(wireType, 0, field);
      return (int)readVarint();
    }

    long readVarint() throws ProtocolException
    {
      long value = 0;
      for (int shift = 0; shift < 64; shift += 7)
      {
        if (position >= data.length)
          throw new ProtocolException("Truncated protobuf varint",
              new EOFException());
        int b = data[position++] & 0xff;
        value |= (long)(b & 0x7f) << shift;
        if ((b & 0x80) == 0)
          return value;
      }
      throw new ProtocolException("Malformed protobuf varint");
    }

    void skip(int wireType) throws ProtocolException
    {
      switch (wireType)
      {
        case 0:
          readVarint();
          return;
        case 1:
          skipBytes(8);
          return;
        case 2:
          long length = readVarint();
          if (length > Integer.MAX_VALUE)
            throw new ProtocolException("Protobuf field is too large");
          skipBytes((int)length);
          return;
        case 5:
          skipBytes(4);
          return;
        default:
          throw new ProtocolException("Unsupported protobuf wire type: "
              + wireType);
      }
    }

    void skipBytes(int length) throws ProtocolException
    {
      if (length < 0 || position + length > data.length)
        throw new ProtocolException("Truncated protobuf field");
      position += length;
    }

    void requireWireType(int actual, int expected, int field)
        throws ProtocolException
    {
      if (actual != expected)
        throw new ProtocolException("Unexpected wire type " + actual
            + " for protobuf field " + field);
    }
  }
}
