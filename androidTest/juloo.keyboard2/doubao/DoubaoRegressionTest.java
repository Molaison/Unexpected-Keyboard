package juloo.keyboard2.doubao;

import android.app.Instrumentation;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.SurroundingText;
import android.widget.EditText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import juloo.keyboard2.Keyboard2;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
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
        fixture.session.awaitSessionFinished(100);
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
    checkCredentialCancellation(test);
    passed(test, "cancel interrupts a pending credential request and the next request can proceed");

    try (Transport fixture = new Transport())
    {
      fixture.receive("", 20000000, "OK", finalJson("测试"));
      if (fixture.session.awaitSessionFinished(100))
        throw new AssertionError("A sentence final completed the recording session");
      fixture.session.finish();
      fixture.receive("", 20000000, "OK", finalJson("最后一句"));
      if (fixture.session.awaitSessionFinished(100))
        throw new AssertionError("A sentence final skipped the remaining session drain");
      fixture.receive("SessionFinished", 20000000, "OK", "");
      if (!fixture.session.awaitSessionFinished(100))
        throw new AssertionError("SessionFinished did not complete the final drain");
    }
    try (Transport fixture = new Transport())
    {
      fixture.receive("SessionFinished", 20000000, "OK", "");
      fixture.session.finish();
      if (fixture.socket.sent != 0) throw new AssertionError("Finish was sent to an already finished session");
    }
    passed(test, "sentence finals do not complete the session; explicit stop drains through SessionFinished");

    try (Transport fixture = new Transport())
    {
      fixture.receive("", 20000000, "OK", finalJson("测试"));
      fixture.listener.onClosed(fixture.socket, 1000, "unexpected close");
      if (fixture.failure.get() == null)
        throw new AssertionError("An unexpected socket close after a sentence final was hidden");
    }
    try (Transport fixture = new Transport())
    {
      fixture.listener.onClosing(fixture.socket, 1000, "unexpected close");
      fixture.listener.onClosed(fixture.socket, 1000, "unexpected close");
      if (fixture.failure.get() == null)
        throw new AssertionError("A peer close without SessionFinished was hidden");
    }
    passed(test, "unexpected transport closure after a sentence is still reported");

    checkPeerCloseAfterSession(test);
    passed(test, "peer-initiated close after SessionFinished preserves the final text and completes normally");

    checkEditor(test);
    passed(test, "sentence boundaries keep recording; final revisions and subsequent utterances preserve editor text");
    checkWholeTranscript(test);
    passed(test, "stream, second pass, and final pass replace complete text without appending result fragments");
    checkIndexedUpdatesAfterSnapshot(test);
    passed(test, "indexed updates after a full snapshot can revise or append a sentence without losing the rest");
    checkBurstResults(test);
    passed(test, "rapid frames preserve transcript state and pending text reaches the editor before completion");
    checkOverlappingAndStoppedResults(test);
    passed(test, "a new fragment replaces its pending overlap, and stopping still accepts revisions before the final snapshot");
    checkFormattedAndPartialDetails(test);
    passed(test, "punctuated snapshots and shorter interim fragments preserve revision boundaries");
    checkManualInputHandoff(test);
    passed(test, "manual input commits the pending voice text before typing in the same editor");
    checkInterruptedConnection(test);
    passed(test, "a failed connection retains received voice text in its owner and reports the failure");
    checkContextPrivacy(test);
    passed(test, "context snapshots follow the cursor; private editors are never read");
    checkLateCallbacks(test);
    passed(test, "late callbacks and completion cannot modify a replacement editor or run");
    checkVoiceSelectionOrdering(test);
    passed(test, "delayed or coalesced selection updates preserve speech retractions and user cursor moves");
    passed(test, "DOUBAO_REGRESSION_OK checks=18");
  }

  private static void checkPeerCloseAfterSession(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, ""); Transport fixture = new Transport(editor.run))
    {
      String finalText = "最后一句。";
      fixture.receive("", 20000000, "OK", finalJson(finalText));
      fixture.receive("SessionFinished", 20000000, "OK", "");
      if (!fixture.session.awaitSessionFinished(100))
        throw new AssertionError("Expected a completed ASR session before closing");
      fixture.listener.onClosing(fixture.socket, 1000, "peer close");
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try
      {
        Future<?> close = worker.submit(() -> { fixture.session.close(); return null; });
        try
        {
          close.get(300, TimeUnit.MILLISECONDS);
          throw new AssertionError("Close returned before the peer handshake was acknowledged");
        }
        catch (TimeoutException expected) { }
        catch (ExecutionException error)
        {
          editor.complete(error.getCause());
          throw new AssertionError("Final text=" + editor.view.getText() + ", failures=" + editor.failures, error.getCause());
        }
        fixture.listener.onClosed(fixture.socket, 1000, "peer close");
        close.get(2, TimeUnit.SECONDS);
      }
      finally { worker.shutdownNow(); }
      editor.complete();
      if (fixture.socket.closeRequests != 1 || !finalText.equals(editor.text()))
        throw new AssertionError("Expected one close request and the final text in the editor");
    }
  }

  private static void checkCredentialCancellation(Instrumentation test) throws Exception
  {
    CountDownLatch pending = new CountDownLatch(1);
    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
      if (chain.request().url().encodedPath().equals("/pending"))
      {
        pending.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!chain.call().isCanceled() && System.nanoTime() < deadline)
          android.os.SystemClock.sleep(10);
        throw new IOException(chain.call().isCanceled() ? "cancelled test request" : "test request timed out");
      }
      return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
          .code(200).message("OK").body(ResponseBody.create("{}", null)).build();
    }).build();
    Class<?> credentialStore = Class.forName("juloo.keyboard2.doubao.DoubaoAsrClient$CredentialStore");
    Constructor<?> constructor = credentialStore.getDeclaredConstructor(android.content.Context.class, OkHttpClient.class);
    constructor.setAccessible(true);
    Object store = constructor.newInstance(test.getTargetContext(), client);
    Method execute = credentialStore.getDeclaredMethod("executeJson", Request.class, String.class,
        DoubaoAsrClient.CredentialRequests.class);
    execute.setAccessible(true);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    DoubaoAsrClient.CredentialRequests abandoned = new DoubaoAsrClient.CredentialRequests();
    try
    {
      Future<?> first = worker.submit(() -> requestCredentials(execute, store, "/pending", abandoned));
      if (!pending.await(2, TimeUnit.SECONDS)) throw new AssertionError("Credential request did not start");
      Future<?> next = worker.submit(() -> requestCredentials(execute, store, "/next",
          new DoubaoAsrClient.CredentialRequests()));
      abandoned.cancel();
      try
      {
        first.get(2, TimeUnit.SECONDS);
        throw new AssertionError("Cancelled credential request completed successfully");
      }
      catch (ExecutionException error)
      {
        if (!(error.getCause() instanceof IOException)
            || !"cancelled test request".equals(error.getCause().getMessage())) throw error;
      }
      if (!"{}".equals(String.valueOf(next.get(2, TimeUnit.SECONDS))))
        throw new AssertionError("The next voice run could not request credentials");
    }
    finally
    {
      abandoned.cancel();
      worker.shutdownNow();
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
  }

  private static Object requestCredentials(Method execute, Object store, String path,
      DoubaoAsrClient.CredentialRequests requests) throws Exception
  {
    Request request = new Request.Builder().url("https://localhost" + path).build();
    try { return execute.invoke(store, request, "test credentials", requests); }
    catch (InvocationTargetException error)
    {
      if (error.getCause() instanceof IOException) throw (IOException)error.getCause();
      throw error;
    }
  }

  private static void checkInterruptedConnection(Instrumentation test) throws Exception
  {
    String appended;
    String withdrawn;
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response(finalJson("今天")));
      test.runOnMainSync(() -> editor.run.lastAppliedTranscriptNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"天气\",\"is_interim\":true,\"index\":1}]}"));
      if (!editor.text().equals("前缀今天"))
        throw new AssertionError("Expected the newest frame to be pending before failure");
      appended = textAfterDisconnect(editor);
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response(finalJson("Hello.")));
      test.runOnMainSync(() -> editor.run.lastAppliedTranscriptNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"\",\"is_interim\":true},"
          + "{\"text\":\"\",\"is_interim\":true,\"index\":1}]}"));
      if (!editor.text().equals("Prefix: Hello."))
        throw new AssertionError("Expected the withdrawal to be pending before failure");
      withdrawn = textAfterDisconnect(editor);
    }
    if (!appended.equals("前缀今天天气") || !withdrawn.equals("Prefix: "))
      throw new AssertionError("Interrupted voice run produced appended=[" + appended + "], withdrawn=[" + withdrawn + "]");
  }

  private static String textAfterDisconnect(Editor editor) throws Exception
  {
    editor.complete(new IOException("Simulated ASR disconnect"));
    if (editor.voice.isActive() || editor.failures.size() != 1
        || !editor.failures.get(0).equals("Simulated ASR disconnect"))
      throw new AssertionError("The voice session did not report its connection failure");
    editor.failures.clear();
    return editor.text();
  }

  private static void checkManualInputHandoff(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, "前缀"))
    {
      AtomicReference<Keyboard2> keyboard = new AtomicReference<>();
      test.runOnMainSync(() -> keyboard.set(new Keyboard2()));
      set(keyboard.get(), "_doubaoVoiceInput", editor.voice);
      Keyboard2.Receiver receiver = keyboard.get().new Receiver();

      editor.run.onResponse(response(finalJson("今天")));
      test.runOnMainSync(() -> editor.run.lastAppliedTranscriptNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"天气\",\"is_interim\":true,\"index\":1}]}"));
      if (!editor.text().equals("前缀今天"))
        throw new AssertionError("Expected the newest frame to be pending before manual input");
      test.runOnMainSync(() -> {
        receiver.beforeManualInput();
        editor.currentConnection.commitText("！", 1);
      });
      editor.run.onResponse(response(finalJson("迟到结果")));
      if (!editor.text().equals("前缀今天天气！") || editor.voice.isActive())
        throw new AssertionError("Manual input handoff produced [" + editor.text() + "]");
    }
  }

  private static void checkVoiceSelectionOrdering(Instrumentation test) throws Exception
  {
    List<String> violations = new ArrayList<>();
    for (int retractions : new int[] { 1, 2 })
    {
      try (Editor editor = new Editor(test, "draft "))
      {
        for (int i = 0; i < retractions; i++)
        {
          editor.run.onResponse(response(finalJson("初始")));
          if (!editor.text().equals("draft 初始"))
            throw new AssertionError("Selection replay did not establish the initial transcript");
          editor.run.onResponse(response("{\"results\":[{\"text\":\"\",\"nonstream_result\":true}]}"));
          if (!editor.text().equals("draft "))
            throw new AssertionError("Selection replay did not retract the initial transcript");
        }
        if (retractions == 2)
          editor.run.onResponse(response(finalJson("继续")));
        test.runOnMainSync(() -> {
          for (int i = 0; i < retractions; i++)
          {
            editor.voice.cancelForSelectionChange(8, 8, 6, 8);
            editor.voice.cancelForSelectionChange(6, 6, -1, -1);
          }
          if (retractions == 2) editor.voice.cancelForSelectionChange(8, 8, 6, 8);
        });
        editor.run.onResponse(response(finalJson("恢复转写")));
        String result = editor.text();
        if (!editor.voice.isActive() || !result.equals("draft 恢复转写"))
          violations.add("Retraction with delayed updates=" + retractions
              + " ended dictation: [" + result + "]");
      }
    }

    for (boolean clearsComposition : new boolean[] { false, true })
    {
      try (Editor editor = new Editor(test, "draft "))
      {
        editor.run.onResponse(response(finalJson("初始")));
        if (!editor.text().equals("draft 初始"))
          throw new AssertionError("Cursor replay did not establish the initial transcript");
        test.runOnMainSync(() -> {
          editor.voice.cancelForSelectionChange(8, 8, 6, 8);
          if (clearsComposition) editor.currentConnection.finishComposingText();
          // The editor moved the cursor. Its selection notification is still in transit.
          editor.view.setSelection(0);
        });
        editor.run.onResponse(response(finalJson("晚到的定稿")));
        String result = editor.text();
        int[] cursor = new int[1];
        test.runOnMainSync(() -> cursor[0] = editor.view.getSelectionStart());
        if (editor.voice.isActive() || !result.equals("draft 初始") || cursor[0] != 0)
          violations.add("Late write with clearsComposition=" + clearsComposition
              + " yielded [" + result + "] at " + cursor[0]);
      }
    }

    // Without an absolute-offset read, a retraction and a move back to its
    // offset can report the same arguments. Moves elsewhere must also stop.
    for (boolean resumedAcknowledged : new boolean[] { false, true })
    {
      for (int cursor = -1; cursor <= 8; cursor++)
      {
        int reportedCursor = cursor < 0 ? 6 : cursor;
        int expectedCursor = cursor < 0 ? 8 : cursor;
        boolean userMoved = cursor >= 0;
        try (Editor editor = new Editor(test, "draft "))
        {
          editor.usePrivateConnection();
          editor.run.onResponse(response(finalJson("初始")));
          if (!editor.text().equals("draft 初始"))
            throw new AssertionError("Private replay did not establish the initial transcript");
          test.runOnMainSync(() -> editor.voice.cancelForSelectionChange(8, 8, 6, 8));
          editor.run.onResponse(response("{\"results\":[{\"text\":\"\",\"nonstream_result\":true}]}"));
          if (!editor.text().equals("draft "))
            throw new AssertionError("Private replay did not retract the initial transcript");
          editor.run.onResponse(response(finalJson("继续")));
          if (!editor.text().equals("draft 继续"))
            throw new AssertionError("Private replay did not resume the transcript");
          test.runOnMainSync(() -> {
            if (resumedAcknowledged) editor.voice.cancelForSelectionChange(8, 8, 6, 8);
            if (userMoved)
            {
              editor.currentConnection.finishComposingText();
              editor.view.setSelection(reportedCursor);
            }
            editor.voice.cancelForSelectionChange(reportedCursor, reportedCursor, -1, -1);
          });
          editor.run.onResponse(response(finalJson("晚到的定稿")));
          String result = editor.text();
          int[] actualCursor = new int[1];
          test.runOnMainSync(() -> actualCursor[0] = editor.view.getSelectionStart());
          if (editor.voice.isActive() || !result.equals("draft 继续") || actualCursor[0] != expectedCursor)
            violations.add("Private no-span update with reportedCursor=" + reportedCursor
                + ", userMoved=" + userMoved + ", resumedAcknowledged=" + resumedAcknowledged
                + " yielded [" + result + "] at " + actualCursor[0]);
        }
      }
    }
    if (!violations.isEmpty()) throw new AssertionError(violations.toString());
  }

  private static void checkFormattedAndPartialDetails(Instrumentation test) throws Exception
  {
    String formatted;
    String extended;
    String reformattedTail;
    String shortened;
    String expandedDetail;
    String revisedPunctuation;
    String emptyFinal;
    String emptyWhole;
    String resumed;
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"先说一句。你好，世界。再说一句。\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"先说一句。\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"你好世界。\",\"is_interim\":false,\"index\":2},"
          + "{\"text\":\"再说一句。\",\"is_interim\":false,\"index\":3}]}"));
      editor.run.onResponse(response(finalJson("你好，世界又见。", 2)));
      editor.complete();
      formatted = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"第一句。今天下雨\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"第一句。\",\"is_interim\":false,\"index\":1}]}"));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"今\",\"is_interim\":true,\"index\":2}]}"));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"今天\",\"is_interim\":true,\"index\":2}]}"));
      editor.run.onResponse(response(finalJson("今天下雨了。", 2)));
      editor.complete();
      extended = editor.text();
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"One. Today.\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"One.\",\"is_interim\":false,\"index\":1}]}"));
      editor.run.onResponse(response(finalJson("Today, it rains.", 2)));
      editor.complete();
      reformattedTail = editor.text();
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"One. Today it rains.\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"One.\",\"is_interim\":false,\"index\":1}]}"));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"Today\",\"is_interim\":false,\"index\":2}]}"));
      editor.complete();
      shortened = editor.text();
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"First. Today it rains. Tomorrow is sunny.\",\"is_interim\":true,\"index\":0},"
          + "{\"text\":\"First.\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"Today\",\"is_interim\":true,\"index\":2}]}"));
      editor.run.onResponse(response(finalJson("Today it snowed.", 2)));
      editor.complete();
      expandedDetail = editor.text();
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"Hello world.\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"Hello\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"world\",\"is_interim\":false,\"index\":2}]}"));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"earth!\",\"is_interim\":false,\"index\":2}]}"));
      editor.complete();
      revisedPunctuation = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response(finalJson("今天")));
      test.runOnMainSync(() -> editor.voice.toggle());
      editor.run.onResponse(response("{\"results\":[{\"nonstream_result\":true}]}"));
      if (!editor.text().equals("前缀今天"))
        throw new AssertionError("An event with no transcript cleared speech text");
      editor.run.onResponse(response("{\"results\":[{\"text\":\"\",\"nonstream_result\":true}]}"));
      editor.run.onResponse(response(finalJson("晚到。", 1)));
      editor.complete();
      emptyFinal = editor.text();
    }
    String whole = "{\"results\":["
        + "{\"text\":\"Hello.\",\"is_interim\":false},"
        + "{\"text\":\"Hello.\",\"is_interim\":false,\"index\":1}]}";
    String revoked = "{\"results\":["
        + "{\"text\":\"\",\"is_interim\":true},"
        + "{\"text\":\"\",\"is_interim\":true,\"index\":1}]}";
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response(whole));
      editor.run.onResponse(response(revoked));
      editor.complete();
      emptyWhole = editor.text();
    }
    try (Editor editor = new Editor(test, "Prefix: "))
    {
      editor.run.onResponse(response(whole));
      editor.run.onResponse(response(revoked));
      editor.run.onResponse(response(finalJson("Again.", 1)));
      editor.complete();
      resumed = editor.text();
    }
    if (!formatted.equals("前缀先说一句。你好，世界又见。再说一句。")
        || !extended.equals("前缀第一句。今天下雨了。")
        || !reformattedTail.equals("Prefix: One. Today, it rains.") || !shortened.equals("Prefix: One. Today")
        || !expandedDetail.equals("Prefix: First. Today it snowed. Tomorrow is sunny.")
        || !revisedPunctuation.equals("Prefix: Hello earth!") || !emptyFinal.equals("前缀")
        || !emptyWhole.equals("Prefix: ") || !resumed.equals("Prefix: Again."))
      throw new AssertionError("Fragment boundaries produced formatted=[" + formatted
          + "], extended=[" + extended + "], reformattedTail=[" + reformattedTail + "], shortened=[" + shortened
          + "], expandedDetail=[" + expandedDetail + "], revisedPunctuation=[" + revisedPunctuation
          + "], emptyFinal=[" + emptyFinal + "], emptyWhole=[" + emptyWhole + "], resumed=[" + resumed + "]");
  }

  private static void checkOverlappingAndStoppedResults(Instrumentation test) throws Exception
  {
    String overlap;
    String revisedWhileStopping;
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":[{\"text\":\"昨天。今天\",\"is_interim\":false},"
          + "{\"text\":\"昨天。\",\"is_interim\":false,\"index\":1}]}"));
      editor.run.onResponse(response(finalJson("今天下雨。", 2)));
      overlap = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      test.runOnMainSync(() -> editor.voice.toggle());
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"第一句。第二句。\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"第一句。\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"第二句。\",\"is_interim\":false,\"index\":2}]}"));
      editor.run.onResponse(response(finalJson("第二句修订。", 2)));
      editor.complete();
      revisedWhileStopping = editor.text();
    }
    if (!overlap.equals("前缀昨天。今天下雨。") || !revisedWhileStopping.equals("前缀第一句。第二句修订。"))
      throw new AssertionError("Overlap/stop updates produced overlap=[" + overlap
          + "], revised=[" + revisedWhileStopping + "]");
  }

  private static void checkBurstResults(Instrumentation test) throws Exception
  {
    String withFragment;
    String completed;
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":[{\"text\":\"第一句。\",\"is_interim\":true,\"index\":0}]}"));
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"第一句。第二句。\",\"is_interim\":true,\"index\":0},"
          + "{\"text\":\"第一句。\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"第二句。\",\"is_interim\":true,\"index\":2}]}"));
      editor.run.onResponse(response(finalJson("第三句。", 3)));
      withFragment = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":[{\"text\":\"临时\",\"is_interim\":true}]}"));
      editor.run.onResponse(response("{\"results\":[{\"text\":\"最后收到的文本\",\"is_interim\":true}]}"));
      editor.complete();
      completed = editor.text();
    }
    if (!withFragment.equals("前缀第一句。第二句。第三句。") || !completed.equals("前缀最后收到的文本"))
      throw new AssertionError("Burst updates produced live=[" + withFragment + "], completed=[" + completed + "]");
  }

  private static void checkIndexedUpdatesAfterSnapshot(Instrumentation test) throws Exception
  {
    String revised;
    String appended;
    String revisedOnlyFragment;
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"第一句。第二句。\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"第一句。\",\"is_interim\":false,\"index\":1},"
          + "{\"text\":\"第二句。\",\"is_interim\":false,\"index\":2}]}"));
      if (!editor.text().equals("前缀第一句。第二句。"))
        throw new AssertionError("Expected whole snapshot before the revision: " + editor.text());
      editor.run.onResponse(response(finalJson("第一句修订。", 0)));
      revised = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"今天散步吧。顺便\",\"is_interim\":false,\"index\":0},"
          + "{\"text\":\"今天散步吧。\",\"is_interim\":false,\"index\":1}]}"));
      if (!editor.text().equals("前缀今天散步吧。顺便"))
        throw new AssertionError("Expected whole snapshot before the next sentence: " + editor.text());
      editor.run.onResponse(response(finalJson("买水果。", 2)));
      editor.run.onResponse(response(finalJson("买点水果。", 2)));
      editor.complete();
      appended = editor.text();
    }
    try (Editor editor = new Editor(test, "前缀"))
    {
      editor.run.onResponse(response("{\"results\":["
          + "{\"text\":\"你好。\",\"is_interim\":true,\"index\":0},"
          + "{\"text\":\"你好。\",\"is_interim\":true,\"index\":1}]}"));
      editor.run.onResponse(response(finalJson("你好，世界。", 1)));
      editor.complete();
      revisedOnlyFragment = editor.text();
    }
    if (!revised.equals("前缀第一句修订。第二句。") || !appended.equals("前缀今天散步吧。顺便买点水果。")
        || !revisedOnlyFragment.equals("前缀你好，世界。"))
      throw new AssertionError("Indexed updates produced revised=[" + revised + "], appended=[" + appended
          + "], revisedOnlyFragment=[" + revisedOnlyFragment + "]");
  }

  private static void checkWholeTranscript(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, "前缀"))
    {
      DoubaoVoiceInput.SessionRun run = editor.run;
      run.onResponse(response(finalJson("今天散步吧。", 0)));
      run.onResponse(response(finalJson("顺便", 1)));
      if (!editor.text().equals("前缀今天散步吧。顺便"))
        throw new AssertionError("The indexed text did not establish the stream: " + editor.text());

      test.runOnMainSync(() -> editor.voice.toggle());
      run.onResponse(response("{\"results\":["
          + "{\"text\":\"今天散步吧，顺便买水果。\",\"is_interim\":true,\"index\":0},"
          + "{\"text\":\"顺便买水果。\",\"is_interim\":false,\"is_vad_finished\":true,\"index\":1}]}"));
      if (!editor.text().equals("前缀今天散步吧，顺便买水果。") || !editor.voice.isActive())
        throw new AssertionError("Two-pass refinement lost or duplicated the transcript: " + editor.text());

      run.onResponse(response("{\"results\":["
          + "{\"text\":\"今天散步吧，顺便买点水果。\",\"is_interim\":false,\"nonstream_result\":true,\"index\":0},"
          + "{\"text\":\"顺便买点水果。\",\"is_interim\":false,\"index\":1}]}"));
      if (!editor.text().equals("前缀今天散步吧，顺便买点水果。"))
        throw new AssertionError("The final pass did not replace the full transcript: " + editor.text());
      run.onResponse(response(finalJson("仅分句。", 1)));
      if (!editor.text().equals("前缀今天散步吧，顺便买点水果。"))
        throw new AssertionError("A segment update replaced the full transcript: " + editor.text());
      run.onResponse(response("{\"results\":[{\"text\":\"今天散步吧，顺便买点水果回来。\","
          + "\"is_interim\":true,\"nonstream_result\":true,\"index\":0}]}"));
      run.onResponse(DoubaoProtocol.parseResponse(packet("SessionFinished", 20000000, "OK", "")));
      editor.complete();
      if (editor.voice.isActive() || !editor.text().equals("前缀今天散步吧，顺便买点水果回来。"))
        throw new AssertionError("Session finish committed the wrong transcript: " + editor.text());
    }
  }

  private static void checkEditor(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, "前缀"))
    {
      DoubaoVoiceInput.SessionRun run = editor.run;
      run.onResponse(response("{\"results\":[{\"text\":\"你好\",\"is_interim\":true,\"index\":0}]}"));
      run.onResponse(response("{\"results\":[{\"text\":\"你好\",\"is_interim\":true,\"is_vad_finished\":true,\"index\":0}]}"));
      if (run.stopRequested) throw new AssertionError("VAD end stopped tap recording");
      run.onResponse(response(finalJson("你好。")));
      run.onResponse(response(finalJson("你好。")));
      if (run.stopRequested) throw new AssertionError("A final result stopped tap recording");
      // The real service advances index without sending a VAD_START event.
      run.onResponse(response(finalJson("第二句。", 1)));
      run.onResponse(response(finalJson("第二句。", 1)));
      if (!editor.text().equals("前缀你好。第二句。"))
        throw new AssertionError("A subsequent utterance overwrote or duplicated text: " + editor.text());
      // A delayed revision of an earlier utterance must retain the newer one.
      run.onResponse(response(finalJson("你好世界。", 0)));
      if (!editor.text().equals("前缀你好世界。第二句。"))
        throw new AssertionError("An earlier sentence revision lost the newer sentence: " + editor.text());
      if (!editor.voice.isActive() || run.stopRequested)
        throw new AssertionError("The second sentence ended recording without a tap");
      test.runOnMainSync(() -> editor.voice.toggle());
      if (!run.stopRequested) throw new AssertionError("The second tap did not request stop");
      editor.complete();
      if (!editor.text().equals("前缀你好世界。第二句。"))
        throw new AssertionError("Completing voice input changed the final text: " + editor.text());
      DoubaoVoiceInput.SessionRun cancelled = editor.voice.new SessionRun(run.connection);
      set(editor.voice, "activeRun", cancelled);
      test.runOnMainSync(() -> {
        editor.voice.cancel();
        editor.view.append("手动");
      });
      cancelled.onResponse(response(finalJson("迟到结果")));
      if (!editor.text().equals("前缀你好世界。第二句。手动"))
        throw new AssertionError("Cancelled voice changed the editor: " + editor.text());
    }
  }

  /** No start/open calls: this exercises the actual Android cursor API offline. */
  private static void checkContextPrivacy(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, "beforeAFTER"))
    {
      Method build = DoubaoVoiceInput.class.getDeclaredMethod("buildContextJson", InputConnection.class);
      build.setAccessible(true);
      EditorInfo info = new EditorInfo();
      info.inputType = InputType.TYPE_CLASS_TEXT;
      AtomicReference<String> context = new AtomicReference<>();
      test.runOnMainSync(() -> {
        editor.view.setSelection(6);
        editor.voice.setEditorInfo(info);
        editor.voice.setAppPackage("test.editor");
        try { context.set((String)build.invoke(editor.voice, editor.run.connection)); }
        catch (Exception error) { throw new AssertionError(error); }
      });
      com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(context.get()).getAsJsonObject();
      if (!"before".equals(json.get("before_context").getAsString())
          || !"AFTER".equals(json.get("after_context").getAsString()))
        throw new AssertionError("Context did not use the current cursor: " + context.get());

      InputConnection forbidden = (InputConnection)Proxy.newProxyInstance(
          InputConnection.class.getClassLoader(), new Class<?>[] {InputConnection.class},
          (proxy, method, args) -> { throw new AssertionError("Private editor was accessed: " + method.getName()); });
      int[] privateTypes = {
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        InputType.TYPE_NULL
      };
      test.runOnMainSync(() -> {
        try
        {
          for (int inputType : privateTypes)
          {
            info.inputType = inputType;
            editor.voice.setEditorInfo(info);
            if (!"".equals(build.invoke(editor.voice, forbidden)))
              throw new AssertionError("Private editor retained context");
          }
          info.inputType = InputType.TYPE_CLASS_TEXT;
          info.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
          editor.voice.setEditorInfo(info);
          if (!"".equals(build.invoke(editor.voice, forbidden)))
            throw new AssertionError("No-personalized-learning editor retained context");
          editor.voice.setEditorInfo(null);
          if (!"".equals(build.invoke(editor.voice, forbidden)))
            throw new AssertionError("Unknown editor retained context");
        }
        catch (Exception error) { throw new AssertionError(error); }
      });

      editor.usePrivateConnection();
      editor.run.onResponse(response(finalJson("初始")));
      if (!editor.text().equals("before初始AFTER"))
        throw new AssertionError("Private editor did not display the initial voice text");
      editor.run.onResponse(response(finalJson("继续")));
      if (!editor.text().equals("before继续AFTER"))
        throw new AssertionError("Private editor did not continue dictating via the callback path");
    }
  }

  private static void checkLateCallbacks(Instrumentation test) throws Exception
  {
    try (Editor editor = new Editor(test, "original"))
    {
      DoubaoVoiceInput.SessionRun old = editor.run;
      test.runOnMainSync(() -> editor.voice.cancel());
      DoubaoVoiceInput.SessionRun replacement = editor.voice.new SessionRun(old.connection);
      set(editor.voice, "activeRun", replacement);
      old.onResponse(response(finalJson("stale")));
      editor.complete(); // Completion from the cancelled run must not clear replacement.
      if (!editor.voice.isActive() || !editor.text().equals("original"))
        throw new AssertionError("A cancelled run affected its replacement");
      replacement.onResponse(response(finalJson("current")));
      if (!editor.text().equals("originalcurrent"))
        throw new AssertionError("The replacement stopped receiving results");
      AtomicReference<EditText> next = new AtomicReference<>();
      test.runOnMainSync(() -> {
        EditText view = new EditText(test.getTargetContext());
        view.setText("new editor");
        view.setSelection(view.length());
        next.set(view);
        editor.currentConnection = view.onCreateInputConnection(new EditorInfo());
      });
      replacement.onResponse(response(finalJson("wrong editor")));
      test.waitForIdleSync();
      if (editor.voice.isActive() || editor.failures.size() != 1
          || !editor.failures.get(0).contains("target editor changed"))
        throw new AssertionError("A changed editor did not invalidate the voice run");
      editor.failures.clear();
      test.runOnMainSync(() -> {
        if (!"new editor".equals(next.get().getText().toString()))
          throw new AssertionError("A stale voice callback changed the next editor");
      });
      if (!editor.text().equals("originalcurrent"))
        throw new AssertionError("A stale voice callback changed the previous editor");
    }
  }

  private static String finalJson(String text)
  {
    return finalJson(text, 0);
  }

  private static String finalJson(String text, int index)
  {
    return "{\"results\":[{\"text\":\"" + text + "\",\"is_final\":true,\"is_vad_finished\":true,\"index\":" + index + "}]}";
  }

  public static DoubaoProtocol.Response response(String json) throws DoubaoProtocol.ProtocolException
  {
    return DoubaoProtocol.parseResponse(packet("", 20000000, "OK", json));
  }

  public static DoubaoAsrClient.Listener attachEditorReplay(DoubaoVoiceInput voice) throws Exception
  {
    Field host = DoubaoVoiceInput.class.getDeclaredField("host");
    host.setAccessible(true);
    InputConnection editor = ((DoubaoVoiceInput.Host)host.get(voice)).getCurrentInputConnection();
    if (editor == null) throw new AssertionError("Cannot replay voice without a focused editor");
    DoubaoVoiceInput.SessionRun run = voice.new SessionRun(editor);
    set(voice, "activeRun", run);
    return run;
  }

  public static void replayBusyService(DoubaoVoiceInput voice, boolean afterSessionStart) throws Exception
  {
    DoubaoVoiceInput.SessionRun run = (DoubaoVoiceInput.SessionRun)attachEditorReplay(voice);
    try (Transport transport = new Transport(afterSessionStart ? run : null))
    {
      if (afterSessionStart) transport.receive("SessionStarted", 20000000, "OK", "");
      transport.receive("SessionFailed", 40200011, "concurrency quota exceeded: test backend", "");
      IOException failure = transport.failure.get();
      if (failure == null || !transport.socket.cancelled)
        throw new AssertionError("Quota rejection did not terminate the transport");
      if (!afterSessionStart)
      {
        completeVoice(voice, run, failure);
        return;
      }
      if (run.asyncFailure.get() == null)
        throw new AssertionError("The run did not observe the transport failure");
      // The audio loop checks this same stored error before sending each frame.
      // Running with an already-failed session consumes it without opening a real socket.
      ExecutorService voiceThread = Executors.newSingleThreadExecutor();
      try { voiceThread.submit(run).get(3, TimeUnit.SECONDS); }
      finally { voiceThread.shutdownNow(); }
    }
  }

  private static void completeVoice(DoubaoVoiceInput voice, DoubaoVoiceInput.SessionRun run,
      Throwable failure) throws Exception
  {
    Method complete = DoubaoVoiceInput.class.getDeclaredMethod("complete", DoubaoVoiceInput.SessionRun.class, Throwable.class);
    complete.setAccessible(true);
    complete.invoke(voice, run, failure);
  }

  /** The same real editor/voice listener used by deterministic and live checks. */
  static final class Editor implements AutoCloseable
  {
    final Instrumentation test;
    final List<String> failures = new ArrayList<>();
    EditText view;
    DoubaoVoiceInput voice;
    DoubaoVoiceInput.SessionRun run;
    InputConnection currentConnection;

    Editor(Instrumentation test, String prefix) throws Exception
    {
      this.test = test;
      test.runOnMainSync(() -> {
        view = new EditText(test.getTargetContext());
        view.setText(prefix);
        view.setSelection(view.length());
        EditorInfo info = new EditorInfo();
        InputConnection connection = view.onCreateInputConnection(info);
        currentConnection = connection;
        voice = new DoubaoVoiceInput(test.getTargetContext(), new DoubaoVoiceInput.Host() {
          @Override public InputConnection getCurrentInputConnection() { return currentConnection; }
          @Override public void onVoiceStateChanged(DoubaoVoiceInput.State state) { }
          @Override public void onVoiceFailure(String message) { failures.add(message); }
        });
        voice.setEditorInfo(info);
        run = voice.new SessionRun(connection);
      });
      set(voice, "activeRun", run);
    }

    String text()
    {
      AtomicReference<String> text = new AtomicReference<>();
      test.runOnMainSync(() -> text.set(view.getText().toString()));
      if (!failures.isEmpty()) throw new AssertionError("Voice editor failed: " + failures);
      return text.get();
    }

    void usePrivateConnection() throws Exception
    {
      test.runOnMainSync(() -> {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT;
        info.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        voice.setEditorInfo(info);
        currentConnection = new InputConnectionWrapper(currentConnection, false) {
          @Override public SurroundingText getSurroundingText(int before, int after, int flags)
          {
            throw new AssertionError("Private editor selection was queried during dictation");
          }
        };
        run = voice.new SessionRun(currentConnection);
      });
      set(voice, "activeRun", run);
    }

    void complete() throws Exception
    {
      complete(null);
    }

    void complete(Throwable problem) throws Exception
    {
      completeVoice(voice, run, problem);
      test.waitForIdleSync();
    }

    @Override public void close() { test.runOnMainSync(() -> voice.shutdown()); }
  }

  private static final class Transport implements AutoCloseable
  {
    final OkHttpClient client = new OkHttpClient();
    final Socket socket = new Socket();
    final AtomicReference<IOException> failure = new AtomicReference<>();
    final DoubaoAsrClient.Connection session;
    final WebSocketListener listener;

    Transport() throws Exception { this(null); }

    Transport(DoubaoAsrClient.Listener target) throws Exception
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
        @Override public void onResponse(DoubaoProtocol.Response response)
        {
          if (target != null) target.onResponse(response);
        }
        @Override public void onFailure(IOException error)
        {
          failure.set(error);
          if (target != null) target.onFailure(error);
        }
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
    volatile int closeRequests;
    @Override public Request request() { return new Request.Builder().url("https://localhost/").build(); }
    @Override public long queueSize() { return 0; }
    @Override public boolean send(String text) { sent++; firstSend.countDown(); return !cancelled; }
    @Override public boolean send(ByteString bytes) { sent++; firstSend.countDown(); return !cancelled; }
    @Override public synchronized boolean close(int code, String reason) { return ++closeRequests == 1; }
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
