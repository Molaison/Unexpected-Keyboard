package juloo.keyboard2.doubao;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Deterministic failure and editor checks alongside the separate live gate. */
public final class DoubaoRegressionTest
{
  public static void run(Instrumentation test) throws Exception
  {
    try (OpusFrameEncoder encoder = new OpusFrameEncoder())
    {
      byte[] packet = encoder.encode(new short[320]);
      if (packet.length == 0 || packet.length >= 4000)
        throw new AssertionError("Native Opus did not encode a 20 ms frame");
    }
    passed(test, "native Opus encodes a 20 ms mono frame and releases the encoder");
    try (Transport fixture = new Transport())
    {
      fixture.receive("SessionFailed", 50700000, "service discovery failure", "");
      try
      {
        fixture.session.awaitFinalOrFinished(100);
        throw new AssertionError("A service error was reported as a successful final response");
      }
      catch (IOException expected)
      {
        if (!expected.getMessage().contains("50700000")) throw expected;
      }
      if (fixture.failure.get() == null || !fixture.socket.cancelled)
        throw new AssertionError("A service error did not terminate the transport");
      try
      {
        fixture.session.sendAudio(new byte[] {1}, DoubaoProtocol.FRAME_FIRST, 0);
        throw new AssertionError("Audio was accepted after a service error");
      }
      catch (IOException expected) { }
      if (fixture.socket.sent != 0) throw new AssertionError("A failed session sent more data");
    }
    passed(test, "service error is visible and stops further audio");

    try (Transport fixture = new Transport())
    {
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try
      {
        Future<?> pending = worker.submit(() -> { fixture.session.startSession(); return null; });
        if (!fixture.socket.firstSend.await(5, TimeUnit.SECONDS))
          throw new AssertionError("StartSession was not sent");
        fixture.session.cancel();
        try
        {
          pending.get(2, TimeUnit.SECONDS);
          throw new AssertionError("Cancelled handshake completed successfully");
        }
        catch (ExecutionException expected)
        {
          if (!(expected.getCause() instanceof IOException)) throw expected;
        }
      }
      finally { worker.shutdownNow(); }
    }
    passed(test, "cancel wakes a pending handshake instead of blocking the next recording");

    try (Transport fixture = new Transport())
    {
      fixture.receive("", 20000000, "OK", finalJson("测试"));
      fixture.session.finish();
      if (!fixture.session.awaitFinalOrFinished(100))
        throw new AssertionError("An early final result was lost before FinishSession");
    }
    try (Transport fixture = new Transport())
    {
      fixture.receive("SessionFinished", 20000000, "OK", "");
      fixture.session.finish();
      if (fixture.socket.sent != 0) throw new AssertionError("Finish was sent to an already finished session");
    }
    passed(test, "early final and server-finished sessions drain correctly");

    checkEditor(test);
    passed(test, "repeated and revised final text replaces one editor span; cancelled replies are ignored");
    passed(test, "DOUBAO_REGRESSION_OK checks=5");
  }

  private static void checkEditor(Instrumentation test) throws Exception
  {
    AtomicReference<EditText> editor = new AtomicReference<>();
    AtomicReference<DoubaoVoiceInput> voice = new AtomicReference<>();
    AtomicReference<DoubaoVoiceInput.SessionRun> run = new AtomicReference<>();
    List<String> failures = new ArrayList<>();
    test.runOnMainSync(() -> {
      EditText view = new EditText(test.getTargetContext());
      view.setText("前缀");
      view.setSelection(view.length());
      editor.set(view);
      InputConnection connection = view.onCreateInputConnection(new EditorInfo());
      DoubaoVoiceInput input = new DoubaoVoiceInput(test.getTargetContext(), new DoubaoVoiceInput.Host() {
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public void onVoiceStateChanged(DoubaoVoiceInput.State state) { }
        @Override public void onVoiceFailure(String message) { failures.add(message); }
      });
      voice.set(input);
      run.set(input.new SessionRun(connection));
    });
    try
    {
      set(voice.get(), "activeRun", run.get());
      run.get().onResponse(DoubaoProtocol.parseResponse(packet("", 20000000, "OK",
            "{\"results\":[{\"text\":\"你好\",\"is_interim\":true}]}")));
      run.get().onResponse(DoubaoProtocol.parseResponse(packet("", 20000000, "OK", finalJson("你好。"))));
      run.get().onResponse(DoubaoProtocol.parseResponse(packet("", 20000000, "OK", finalJson("你好。"))));
      run.get().onResponse(DoubaoProtocol.parseResponse(packet("", 20000000, "OK", finalJson("你好世界。"))));
      Method complete = DoubaoVoiceInput.class.getDeclaredMethod("complete", DoubaoVoiceInput.SessionRun.class, Throwable.class);
      complete.setAccessible(true);
      complete.invoke(voice.get(), run.get(), null);
      test.waitForIdleSync();
      test.runOnMainSync(() -> {
        if (!editor.get().getText().toString().equals("前缀你好世界。"))
          throw new AssertionError("Voice text was duplicated or did not replace interim text: " + editor.get().getText());
      });
      AtomicReference<DoubaoVoiceInput.SessionRun> cancelled = new AtomicReference<>();
      test.runOnMainSync(() -> cancelled.set(voice.get().new SessionRun(
          editor.get().onCreateInputConnection(new EditorInfo()))));
      set(voice.get(), "activeRun", cancelled.get());
      test.runOnMainSync(() -> {
        voice.get().cancel();
        editor.get().append("手动");
      });
      cancelled.get().onResponse(DoubaoProtocol.parseResponse(packet("", 20000000, "OK", finalJson("迟到结果"))));
      test.waitForIdleSync();
      test.runOnMainSync(() -> {
        if (!editor.get().getText().toString().equals("前缀你好世界。手动") || !failures.isEmpty())
          throw new AssertionError("Cancelled voice changed the editor: " + failures);
      });
    }
    finally { test.runOnMainSync(() -> voice.get().shutdown()); }
  }

  private static String finalJson(String text)
  {
    return "{\"results\":[{\"text\":\"" + text + "\",\"is_final\":true,\"is_vad_finished\":true}]}";
  }

  private static final class Transport implements AutoCloseable
  {
    final OkHttpClient client = new OkHttpClient();
    final Socket socket = new Socket();
    final AtomicReference<IOException> failure = new AtomicReference<>();
    final DoubaoAsrClient.Connection session;
    final WebSocketListener listener;

    Transport() throws Exception
    {
      Class<?> credentialsType = Class.forName("juloo.keyboard2.doubao.DoubaoAsrClient$Credentials");
      Constructor<?> credentialsConstructor = credentialsType.getDeclaredConstructor();
      credentialsConstructor.setAccessible(true);
      Object credentials = credentialsConstructor.newInstance();
      set(credentials, "deviceId", "test-device");
      set(credentials, "token", "test-token");
      Constructor<DoubaoAsrClient.Connection> constructor = DoubaoAsrClient.Connection.class.getDeclaredConstructor(
          OkHttpClient.class, credentialsType, DoubaoAsrClient.Listener.class);
      constructor.setAccessible(true);
      session = constructor.newInstance(client, credentials, new DoubaoAsrClient.Listener() {
        @Override public void onResponse(DoubaoProtocol.Response response) { }
        @Override public void onFailure(IOException error) { failure.set(error); }
      });
      set(session, "webSocket", socket);
      Constructor<?> socketListener = Class.forName("juloo.keyboard2.doubao.DoubaoAsrClient$Connection$SocketListener")
        .getDeclaredConstructor(DoubaoAsrClient.Connection.class);
      socketListener.setAccessible(true);
      listener = (WebSocketListener)socketListener.newInstance(session);
    }

    void receive(String type, int code, String message, String json)
    {
      listener.onMessage(socket, ByteString.of(packet(type, code, message, json)));
    }

    @Override public void close()
    {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  private static final class Socket implements WebSocket
  {
    final CountDownLatch firstSend = new CountDownLatch(1);
    volatile boolean cancelled;
    volatile int sent;
    @Override public Request request() { return new Request.Builder().url("https://localhost/").build(); }
    @Override public long queueSize() { return 0; }
    @Override public boolean send(String text) { sent++; firstSend.countDown(); return !cancelled; }
    @Override public boolean send(ByteString bytes) { sent++; firstSend.countDown(); return !cancelled; }
    @Override public boolean close(int code, String reason) { return true; }
    @Override public void cancel() { cancelled = true; }
  }

  private static void set(Object object, String name, Object value) throws Exception
  {
    Field field = object.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(object, value);
  }

  private static byte[] packet(String type, int status, String message, String json)
  {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    string(output, 4, type);
    varint(output, 5 << 3);
    varint(output, status);
    string(output, 6, message);
    string(output, 7, json);
    return output.toByteArray();
  }

  private static void string(ByteArrayOutputStream output, int field, String value)
  {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    varint(output, (field << 3) | 2);
    varint(output, bytes.length);
    output.write(bytes, 0, bytes.length);
  }

  private static void varint(ByteArrayOutputStream output, int value)
  {
    while ((value & ~127) != 0) { output.write((value & 127) | 128); value >>>= 7; }
    output.write(value);
  }

  private static void passed(Instrumentation test, String text)
  {
    Bundle status = new Bundle();
    status.putString("stream", text + "\n");
    test.sendStatus(0, status);
  }
}
