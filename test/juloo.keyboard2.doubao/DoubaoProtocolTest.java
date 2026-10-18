package juloo.keyboard2.doubao;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import okhttp3.HttpUrl;
import org.junit.Test;

public class DoubaoProtocolTest
{
  @Test
  public void startTaskMatchesProtobufSchema()
  {
    byte[] request = DoubaoProtocol.buildStartTask("r", "t");
    assertArrayEquals(new byte[]{
        0x12, 0x01, 't',
        0x1a, 0x03, 'A', 'S', 'R',
        0x2a, 0x09, 'S', 't', 'a', 'r', 't', 'T', 'a', 's', 'k',
        0x42, 0x01, 'r'
    }, request);
  }

  @Test
  public void sessionPayloadEnablesTheReferenceMultipassPipeline()
  {
    DoubaoProtocol.SessionConfig config = new DoubaoProtocol.SessionConfig();
    config.deviceId = "123";
    config.context = "{\"before_context\":\"你好\"}";

    JsonObject payload = JsonParser.parseString(
        DoubaoProtocol.buildSessionPayload(config)).getAsJsonObject();
    JsonObject audio = payload.getAsJsonObject("audio_info");
    JsonObject extra = payload.getAsJsonObject("extra");

    assertEquals(1, audio.get("channel").getAsInt());
    assertEquals("speech_opus", audio.get("format").getAsString());
    assertEquals(16000, audio.get("sample_rate").getAsInt());
    assertTrue(payload.get("enable_punctuation").getAsBoolean());
    assertTrue(payload.get("enable_vad").getAsBoolean());
    assertFalse(payload.get("low_latency").getAsBoolean());

    assertEquals("oime", extra.get("app_name").getAsString());
    assertEquals("123", extra.get("did").getAsString());
    assertEquals("tool", extra.get("input_mode").getAsString());
    // The reference client sets all three; the previous implementation had them off.
    assertTrue(extra.get("enable_asr_twopass").getAsBoolean());
    assertTrue(extra.get("enable_asr_threepass").getAsBoolean());
    assertTrue(extra.get("use_twopass_retry").getAsBoolean());
    assertEquals(800, extra.get("end_smooth_window_ms").getAsInt());
    assertEquals("{\"before_context\":\"你好\"}", extra.get("context").getAsString());
    assertTrue(extra.has("network_change"));
    assertTrue(extra.has("retry_server_code"));
  }

  @Test
  public void sessionPayloadNeverEmitsDeadKeys()
  {
    JsonObject extra = JsonParser.parseString(
        DoubaoProtocol.buildSessionPayload(new DoubaoProtocol.SessionConfig()))
        .getAsJsonObject().getAsJsonObject("extra");

    assertFalse(extra.has("did"));
    assertFalse(extra.has("context"));
    assertFalse(extra.has("last_uid"));
    assertFalse(extra.has("interim_results"));
  }

  @Test
  public void audioRequestCarriesFrameAndTimestamp()
  {
    byte[] request = DoubaoProtocol.buildAudioRequest("request",
        new byte[]{1, 2, 3}, DoubaoProtocol.FRAME_LAST, 42);
    Map<Integer, byte[]> fields = decodeLengthDelimitedFields(request);

    assertEquals("ASR", utf8(fields.get(3)));
    assertEquals("TaskRequest", utf8(fields.get(5)));
    assertArrayEquals(new byte[]{1, 2, 3}, fields.get(7));
    assertEquals("request", utf8(fields.get(8)));
    assertTrue(utf8(fields.get(6)).contains("\"timestamp_ms\":42"));
    assertEquals(DoubaoProtocol.FRAME_LAST, decodeVarintField(request, 9));
  }

  @Test
  public void releaseFrameAsksForTwopassAndFinishesTheAudio()
  {
    byte[] request = DoubaoProtocol.buildAudioRequest("request",
        new byte[]{9}, DoubaoProtocol.FRAME_LAST, 7, true, true);
    String payload = utf8(decodeLengthDelimitedFields(request).get(6));

    assertTrue(payload.contains("\"force_asr_twopass\":true"));
    assertTrue(payload.contains("\"finish_audio\":true"));
  }

  @Test
  public void parsesLifecycleResponse() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("TaskStarted", 0, "", ""));
    assertEquals(DoubaoProtocol.ResponseType.TASK_STARTED, response.type);
  }

  @Test
  public void parsesArrayInterimResult() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"你好\","
        + "\"is_interim\":true}],\"extra\":{\"packet_number\":7}}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));

    assertEquals(DoubaoProtocol.ResponseType.INTERIM_RESULT, response.type);
    assertEquals("你好", response.text);
    assertFalse(response.isFinal);
  }

  @Test
  public void vadFinishedSegmentIsStableNotFinal() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"第一段\","
        + "\"is_vad_finished\":true}],\"extra\":{\"seq_id\":11}}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));

    assertEquals(DoubaoProtocol.ResponseType.STABLE_RESULT, response.type);
    assertFalse(response.isFinal);
    assertTrue(response.vadFinished);
    assertEquals(11L, response.seqId);
  }

  @Test
  public void parsesObjectFinalWithNestedAlternative() throws Exception
  {
    String json = "{\"results\":{\"alternatives\":[{\"transcript\":\"你好。\"}],"
        + "\"is_final\":true,\"is_vad_finished\":true}}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));

    assertEquals(DoubaoProtocol.ResponseType.FINAL_RESULT, response.type);
    assertEquals("你好。", response.text);
    assertTrue(response.isFinal);
    assertTrue(response.vadFinished);
  }

  @Test
  public void failureMessageTypeIsVisibleError() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("SessionFailed", 401, "bad token", ""));
    assertEquals(DoubaoProtocol.ResponseType.ERROR, response.type);
    assertEquals(401, response.statusCode);
    assertEquals("bad token", response.errorMessage);
  }

  @Test
  public void okStatusCodeDoesNotOverrideRecognitionPayload() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"端到端测试\","
        + "\"is_interim\":true}]}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 200, "OK", json));

    assertEquals(DoubaoProtocol.ResponseType.INTERIM_RESULT, response.type);
    assertEquals("端到端测试", response.text);
  }

  @Test
  public void emptyInterimIsDropped() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"\",\"is_interim\":true}]}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));

    assertEquals(DoubaoProtocol.ResponseType.DROPPED, response.type);
  }

  @Test
  public void emptyTextWithVadFinishedStillReachesTheCaller() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"\",\"is_vad_finished\":true}]}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));

    assertTrue(response.type == DoubaoProtocol.ResponseType.STABLE_RESULT
        || response.type == DoubaoProtocol.ResponseType.FINAL_RESULT);
    assertTrue(response.vadFinished);
  }

  @Test
  public void resultGateDropsRepeatedVadFinishes() throws Exception
  {
    String json = "{\"results\":[{\"text\":\"今天\",\"is_vad_finished\":true}],"
        + "\"extra\":{\"seq_id\":5}}";
    DoubaoProtocol.ResultGate gate = new DoubaoProtocol.ResultGate();

    DoubaoProtocol.Response first = gate.filter(
        DoubaoProtocol.parseResponse(response("", 0, "", json)));
    DoubaoProtocol.Response second = gate.filter(
        DoubaoProtocol.parseResponse(response("", 0, "", json)));

    assertEquals(DoubaoProtocol.ResponseType.STABLE_RESULT, first.type);
    assertEquals(DoubaoProtocol.ResponseType.DROPPED, second.type);
  }

  @Test
  public void vadStartIsReported() throws Exception
  {
    String json = "{\"results\":[],\"extra\":{\"vad_start\":true}}";
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 0, "", json));
    assertEquals(DoubaoProtocol.ResponseType.VAD_START, response.type);
    assertTrue(response.vadStart);
  }

  // The next fixtures are verbatim frames captured from
  // wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws while streaming
  // Windows TTS Chinese speech; they pin the real merge semantics.

  @Test
  public void liveHeartbeatCarriesPacketNumber() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":null,\"extra\":{\"packet_number\":3,\"heartbeat_num\":31}}"));
    assertEquals(DoubaoProtocol.ResponseType.HEARTBEAT, response.type);
    assertEquals(3, response.packetNumber);
  }

  @Test
  public void liveVadStartComesWithAnEmptyInterim() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":[{\"text\":\"\",\"is_interim\":true}],"
        + "\"extra\":{\"vad_start\":true}}"));
    assertEquals(DoubaoProtocol.ResponseType.VAD_START, response.type);
  }

  @Test
  public void liveInterimUsesTheFirstResultAsTheFullTranscript() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":["
        + "{\"text\":\"今天天气不错，我们去公园散步吧。顺便\",\"is_interim\":true},"
        + "{\"text\":\"今天天气不错，我们去公园散步吧。\",\"is_interim\":true},"
        + "{\"text\":\"顺便\",\"is_interim\":true}],"
        + "\"extra\":{\"seq_id\":4}}"));
    assertEquals(DoubaoProtocol.ResponseType.INTERIM_RESULT, response.type);
    // The last entry is only the current segment; results[0] is the transcript.
    assertEquals("今天天气不错，我们去公园散步吧。顺便", response.text);
    assertEquals(3, response.results.size());
  }

  @Test
  public void livePostReleaseTwopassIsStableNotFinal() throws Exception
  {
    // Captured 240 ms after the last audio packet: the earlier sentence
    // boundary "吧。" was rewritten to "吧，" by the second pass.
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":["
        + "{\"text\":\"今天天气不错，我们去公园散步吧，顺便买点水果回来。\","
        + "\"is_interim\":true},"
        + "{\"text\":\"今天天气不错，我们去公园散步吧，顺便买点水果回来。\","
        + "\"is_interim\":false,\"is_vad_finished\":true}],"
        + "\"extra\":{\"lastpkt_threepass_latency\":240}}"));
    assertEquals(DoubaoProtocol.ResponseType.STABLE_RESULT, response.type);
    assertFalse(response.isFinal);
    assertTrue(response.vadFinished);
    assertFalse(response.allEntriesFinal);
    assertEquals("今天天气不错，我们去公园散步吧，顺便买点水果回来。",
        response.stableText);
  }

  @Test
  public void liveFinalPassHasEveryEntryNonInterim() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":["
        + "{\"text\":\"今天天气不错，我们去公园散步吧。顺便买点水果回来。\","
        + "\"is_interim\":false,\"is_vad_finished\":true},"
        + "{\"text\":\"今天天气不错，我们去公园散步吧。顺便买点水果回来。\","
        + "\"is_interim\":false}]}"));
    assertEquals(DoubaoProtocol.ResponseType.FINAL_RESULT, response.type);
    assertTrue(response.isFinal);
    assertTrue(response.allEntriesFinal);
  }

  @Test
  public void liveInterimBeforeReleaseIsNeverFinal() throws Exception
  {
    // Same text, still interim: this is the "识别优化中" window, the text shown
    // here may still be rewritten, so it must not be committed.
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(response(
        "", 20000000, "",
        "{\"results\":[{\"text\":\"今天天气不错，我们去公园散步吧。\","
        + "\"is_interim\":true},{\"text\":\"今天天气不错，我们去公园散步吧。\","
        + "\"is_interim\":true}]}"));
    assertEquals(DoubaoProtocol.ResponseType.INTERIM_RESULT, response.type);
    assertFalse(response.isFinal);
    assertEquals("", response.stableText);
  }

  @Test
  public void contextJsonCarriesCursorAndHistory()
  {
    DoubaoTextContext context = new DoubaoTextContext();
    context.setEditor("前面的话", "后面的话", "com.example.chat");
    context.addTurn("昨天说了什么", false);
    context.setHotwords(Arrays.asList("豆包", "语音输入"));

    JsonObject root = JsonParser.parseString(context.build()).getAsJsonObject();
    assertEquals("前面的话", root.get("before_context").getAsString());
    assertEquals("后面的话", root.get("after_context").getAsString());

    JsonArray chat = root.getAsJsonArray("chat");
    assertEquals(1, chat.size());
    JsonObject turn = chat.get(0).getAsJsonObject();
    assertEquals("user_input", turn.get("type").getAsString());
    assertEquals("com.example.chat", turn.get("app_apk_name").getAsString());
    JsonObject data = JsonParser.parseString(turn.get("data").getAsString())
        .getAsJsonObject();
    assertEquals("昨天说了什么", data.get("text").getAsString());
    assertTrue(data.get("cursor_position").getAsInt() > 0);

    assertEquals(2, root.getAsJsonArray("hotwords").size());
  }

  @Test
  public void contextTruncatesAroundTheCursor()
  {
    DoubaoTextContext.Flags flags = new DoubaoTextContext.Flags();
    flags.maxBeforeLength = 4;
    flags.maxAfterLength = 4;
    DoubaoTextContext context = new DoubaoTextContext(flags);
    context.setEditor("0123456789", "abcdefghij", "app");

    JsonObject root = JsonParser.parseString(context.build()).getAsJsonObject();
    assertEquals("6789", root.get("before_context").getAsString());
    assertEquals("abcd", root.get("after_context").getAsString());
  }

  @Test
  public void okStatusWithoutPayloadIsNotAnError() throws Exception
  {
    DoubaoProtocol.Response response = DoubaoProtocol.parseResponse(
        response("", 200, "OK", ""));

    assertEquals(DoubaoProtocol.ResponseType.UNKNOWN, response.type);
  }

  @Test(expected = DoubaoProtocol.ProtocolException.class)
  public void malformedJsonFails() throws Exception
  {
    DoubaoProtocol.parseResponse(response("", 0, "", "{broken"));
  }

  @Test(expected = DoubaoProtocol.ProtocolException.class)
  public void malformedProtobufFails() throws Exception
  {
    DoubaoProtocol.parseResponse(new byte[]{0x3a, 0x05, '{'});
  }

  @Test
  public void encodesTwentyMillisecondOpusFrame() throws Exception
  {
    DoubaoVoiceInput.OpusFrameEncoder encoder =
        new DoubaoVoiceInput.OpusFrameEncoder();
    byte[] opus = encoder.encode(new short[320]);
    assertTrue(opus.length > 0);
    assertTrue(opus.length < 4000);
  }

  @Test
  public void webSocketHandshakeUsesHttpsUrlForOkHttp()
  {
    HttpUrl url = DoubaoAsrClient.Session.buildWebSocketUrl("device id");

    assertEquals("https", url.scheme());
    assertEquals("frontier-audio-ime-ws.doubao.com", url.host());
    assertEquals("401734", url.queryParameter("aid"));
    assertEquals("device id", url.queryParameter("device_id"));
  }

  @Test
  public void quicEndpointIsRecordedNextToTheWebSocketFallback()
  {
    assertEquals("wss://frontier-audio-ime-quic.doubao.com/api/v1/ws",
        DoubaoAsrClient.QUIC_URL);
    assertEquals(DoubaoAsrClient.QUIC_URL + ";"
        + "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws",
        DoubaoAsrClient.CONNECT_POOL_URLS);
  }

  @Test
  public void cloudPortPathsMatchTheReferenceClient()
  {
    assertEquals("/api/v3/context/ime/user_words", DoubaoCloudPorts.PATH_USER_WORDS);
    assertEquals("/api/v3/context/ime/ner", DoubaoCloudPorts.PATH_NER);
    assertEquals("/api/v3/context/ime/modify_pair/v2",
        DoubaoCloudPorts.PATH_MODIFY_PAIR_V2);
    assertEquals("/api/v1/asr/fmt", DoubaoCloudPorts.PATH_ASR_FMT);
    assertEquals("SYlxZr6LnvBaIVmF", DoubaoCloudPorts.API_APP_KEY);
    assertNotNull(new DoubaoCloudPorts());
  }

  @Test
  public void cloudPortUserObjectMatchesTheReferenceFields()
  {
    DoubaoCloudPorts ports = new DoubaoCloudPorts();
    ports.setDeviceId("did-1");
    ports.setUserId("uid-9");
    ports.setExperienceImprove(true);
    JsonObject user = ports.userObject();

    assertEquals("uid-9", user.get("uid").getAsString());
    assertEquals("did-1", user.get("did").getAsString());
    assertEquals("oime", user.get("app_name").getAsString());
    assertEquals("1.5.1", user.get("app_version").getAsString());
    assertEquals("", user.get("sdk_version").getAsString());
    assertEquals("android", user.get("platform").getAsString());
    assertTrue(user.get("experience_improve").getAsBoolean());
  }

  @Test
  public void cloudPayloadUsesUnpaddedBase64()
  {
    // The gateway decodes `data` with an unpadded decoder: padding is rejected
    // with "illegal base64 data" at the trailing "==".
    JsonObject inner = new JsonObject();
    inner.addProperty("version", 0);
    String encoded = DoubaoCloudPorts.base64Raw(inner);

    assertFalse(encoded.endsWith("="));
    // A raw base64 body is never 1 modulo 4.
    assertTrue(encoded.length() % 4 != 1);

    int padding = (4 - encoded.length() % 4) % 4;
    byte[] decoded = android.util.Base64.decode(encoded + repeat("=", padding),
        android.util.Base64.NO_WRAP);
    assertEquals(inner.toString(),
        new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String repeat(String value, int times)
  {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < times; index++)
      builder.append(value);
    return builder.toString();
  }

  @Test
  public void hotwordRouteMatchesTheReferencePath()
  {
    assertEquals("/obric/ime/cloud/gethotword", DoubaoCloudPorts.PATH_HOTWORD);
  }

  private static byte[] response(String messageType, int statusCode,
      String statusMessage, String resultJson)
  {
    TestProtoWriter writer = new TestProtoWriter();
    writer.string(4, messageType);
    writer.int32(5, statusCode);
    writer.string(6, statusMessage);
    writer.string(7, resultJson);
    return writer.toByteArray();
  }

  private static Map<Integer, byte[]> decodeLengthDelimitedFields(byte[] data)
  {
    Map<Integer, byte[]> fields = new HashMap<Integer, byte[]>();
    int[] position = new int[]{0};
    while (position[0] < data.length)
    {
      long tag = readVarint(data, position);
      int field = (int)(tag >>> 3);
      int wire = (int)(tag & 7);
      if (wire == 2)
      {
        int length = (int)readVarint(data, position);
        fields.put(field, Arrays.copyOfRange(data, position[0],
            position[0] + length));
        position[0] += length;
      }
      else if (wire == 0)
        readVarint(data, position);
      else
        throw new AssertionError("Unexpected wire type " + wire);
    }
    return fields;
  }

  private static int decodeVarintField(byte[] data, int wantedField)
  {
    int[] position = new int[]{0};
    while (position[0] < data.length)
    {
      long tag = readVarint(data, position);
      int field = (int)(tag >>> 3);
      int wire = (int)(tag & 7);
      if (wire == 0)
      {
        int value = (int)readVarint(data, position);
        if (field == wantedField)
          return value;
      }
      else if (wire == 2)
      {
        int length = (int)readVarint(data, position);
        position[0] += length;
      }
      else
        throw new AssertionError("Unexpected wire type " + wire);
    }
    throw new AssertionError("Missing field " + wantedField);
  }

  private static long readVarint(byte[] data, int[] position)
  {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7)
    {
      int b = data[position[0]++] & 0xff;
      value |= (long)(b & 0x7f) << shift;
      if ((b & 0x80) == 0)
        return value;
    }
    throw new AssertionError("Malformed varint");
  }

  private static String utf8(byte[] value)
  {
    return new String(value, StandardCharsets.UTF_8);
  }

  private static final class TestProtoWriter
  {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void string(int field, String value)
    {
      if (value.isEmpty())
        return;
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      varint((field << 3) | 2);
      varint(bytes.length);
      out.write(bytes, 0, bytes.length);
    }

    void int32(int field, int value)
    {
      if (value == 0)
        return;
      varint(field << 3);
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
}
