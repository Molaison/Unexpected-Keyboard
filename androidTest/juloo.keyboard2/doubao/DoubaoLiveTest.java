package juloo.keyboard2.doubao;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in online test using a synthetic PCM fixture, never microphone audio. */
public final class DoubaoLiveTest
{
  public static void run(Instrumentation test, boolean freshCredentials) throws Exception
  {
    File fixture = new File(test.getTargetContext().getCacheDir(), "voice-test.pcm");
    if (!fixture.isFile() || fixture.length() < 32000)
      throw new AssertionError("Missing synthetic 16 kHz mono PCM fixture");
    AtomicReference<IOException> failure = new AtomicReference<>();
    AtomicReference<String> transcript = new AtomicReference<>("");
    List<byte[]> encoded;
    long encodeStarted = SystemClock.elapsedRealtime();
    byte[] lastFrame;
    status(test, "Encoding synthetic fixture before opening the network session");
    try (OpusFrameEncoder encoder = new OpusFrameEncoder())
    {
      encoded = encodeFixture(encoder, fixture);
      lastFrame = encoder.encode(new short[320]);
    }
    status(test, "Encoded " + encoded.size() + " frames in "
        + (SystemClock.elapsedRealtime() - encodeStarted) + " ms on this device");
    android.content.Context clientContext = test.getTargetContext();
    if (freshCredentials)
    {
      // Isolate this diagnostic identity; never delete the app's saved identity.
      clientContext = new android.content.ContextWrapper(clientContext) {
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode)
        {
          return super.getSharedPreferences("voice_diagnostic_" + name, mode);
        }
      };
      status(test, "Using isolated diagnostic credentials; app credentials remain unchanged");
    }
    DoubaoAsrClient client = new DoubaoAsrClient(clientContext);
    DoubaoAsrClient.Session session = null;
    boolean complete = false;
    try
    {
      for (int pass = 1; pass <= 2; pass++)
      {
        complete = false;
        failure.set(null);
        transcript.set("");
        android.content.SharedPreferences preferences = clientContext.getSharedPreferences(
            "doubao_asr_credentials", android.content.Context.MODE_PRIVATE);
        java.util.Map<String, ?> credentialsBefore = preferences.getAll();
        status(test, "Opening Doubao connection, pass=" + pass);
        session = client.open(new DoubaoAsrClient.Listener() {
          @Override public void onFailure(IOException error) { failure.set(error); }
          @Override public void onResponse(DoubaoProtocol.Response response)
          {
            if (!response.text.isEmpty()) transcript.set(response.text);
            status(test, "response=" + response.type + " chars=" + response.text.length()
                + " vadFinished=" + response.vadFinished);
            if (response.type == DoubaoProtocol.ResponseType.ERROR)
            {
              IOException error = new IOException("ASR status " + response.statusCode + ": " + response.errorMessage);
              failure.set(error);
              status(test, error.getMessage());
            }
          }
        });
        session.startSession();
        status(test, "Session started; sending synthetic speech");
        long started = SystemClock.elapsedRealtime();
        long timestamp = System.currentTimeMillis();
        int frames = 0;
        for (byte[] frame : encoded)
        {
          if (failure.get() != null) throw failure.get();
          session.sendAudio(frame, frames == 0
              ? DoubaoProtocol.FRAME_FIRST : DoubaoProtocol.FRAME_MIDDLE, timestamp + frames * 20);
          frames++;
          long wait = started + frames * 20 - SystemClock.elapsedRealtime();
          if (wait > 0) SystemClock.sleep(wait);
        }
        session.sendAudio(lastFrame, DoubaoProtocol.FRAME_LAST, timestamp + frames * 20);
        session.finish();
        if (!session.awaitSessionFinished(10000)) throw new AssertionError("No terminal ASR response");
        if (failure.get() != null) throw failure.get();
        String text = transcript.get();
        status(test, "Synthetic transcript: " + text);
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().replaceAll(" +", " ");
        if (!normalized.equals("this is a voice test"))
          throw new AssertionError("Synthetic speech was not recognized: " + text);
        session.close();
        complete = true;
        boolean credentialsChanged = !credentialsBefore.equals(preferences.getAll());
        if (pass == 2 && credentialsChanged)
          throw new AssertionError("The second session did not reuse the saved credentials");
        status(test, "DOUBAO_LIVE_PASS pass=" + pass + " frames=" + frames
            + " credentials_changed=" + credentialsChanged);
      }
      status(test, "DOUBAO_LIVE_OK passes=2");
    }
    finally
    {
      if (session != null && !complete) session.cancel();
      client.shutdown();
    }
  }

  /** Two real service utterances, each followed by silence before explicit stop. */
  public static void runContinuous(Instrumentation test) throws Exception
  {
    String[] names = {"first", "second"};
    CountDownLatch[] endpoints = {new CountDownLatch(1), new CountDownLatch(1)};
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<IOException> failure = new AtomicReference<>();
    List<List<byte[]>> utterances = new ArrayList<>();
    byte[] lastFrame;
    try (OpusFrameEncoder encoder = new OpusFrameEncoder())
    {
      for (String name : names)
      {
        List<byte[]> frames = encodeFixture(encoder,
            new File(test.getTargetContext().getCacheDir(), "voice-" + name + ".pcm"));
        for (int i = 0; i < 150; i++) frames.add(encoder.encode(new short[320]));
        utterances.add(frames);
      }
      lastFrame = encoder.encode(new short[320]);
    }
    DoubaoAsrClient client = new DoubaoAsrClient(test.getTargetContext());
    DoubaoAsrClient.Session session = null;
    boolean complete = false;
    try (DoubaoRegressionTest.Editor editor = new DoubaoRegressionTest.Editor(test, ""))
    {
      session = client.open(new DoubaoAsrClient.Listener() {
        @Override public void onFailure(IOException error)
        {
          failure.set(error);
          editor.run.onFailure(error);
        }
        @Override public void onResponse(DoubaoProtocol.Response response)
        {
          editor.run.onResponse(response);
          if (response.type != DoubaoProtocol.ResponseType.HEARTBEAT)
            status(test, "continuous response=" + response.type
                + " vadFinished=" + response.vadFinished + " index=" + response.utteranceIndex
                + " text=" + response.text);
          if (response.type == DoubaoProtocol.ResponseType.SESSION_FINISHED) finished.countDown();
          if (response.type == DoubaoProtocol.ResponseType.ERROR)
            failure.set(new IOException("ASR status " + response.statusCode + ": " + response.errorMessage));
          if (response.isFinal || response.vadFinished)
            for (int i = 0; i < names.length; i++)
              if (response.text.toLowerCase(Locale.ROOT).contains(names[i] + " sentence"))
                endpoints[i].countDown();
        }
      });
      session.startSession();
      long nextTimestamp = 0;
      int frames = 0;
      String expectedEditor = "";
      for (int utterance = 0; utterance < utterances.size(); utterance++)
      {
        long started = SystemClock.elapsedRealtime();
        long timestamp = System.currentTimeMillis();
        int utteranceFrames = 0;
        status(test, "Sending " + names[utterance] + " phrase and silence, frames=" + utterances.get(utterance).size());
        for (byte[] frame : utterances.get(utterance))
        {
          if (failure.get() != null) throw failure.get();
          if (session.isFinished()) throw new AssertionError("Service ended before explicit stop, utterance=" + utterance);
          session.sendAudio(frame, frames == 0 ? DoubaoProtocol.FRAME_FIRST : DoubaoProtocol.FRAME_MIDDLE,
              timestamp + utteranceFrames * 20);
          frames++;
          utteranceFrames++;
          nextTimestamp = timestamp + utteranceFrames * 20;
          long wait = started + utteranceFrames * 20 - SystemClock.elapsedRealtime();
          if (wait > 0) SystemClock.sleep(wait);
        }
        boolean receivedEndpoint = endpoints[utterance].await(10, TimeUnit.SECONDS);
        if (failure.get() != null) throw failure.get();
        if (session.isFinished()) throw new AssertionError("Service ended after a sentence without explicit stop");
        if (!receivedEndpoint)
          throw new AssertionError("No sentence-end result after " + names[utterance] + " phrase and three seconds of silence");
        expectedEditor += "thisisthe" + names[utterance] + "sentence";
        String editorText = editor.text();
        if (!editorText.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").equals(expectedEditor))
          throw new AssertionError("Live editor overwrote or repeated a sentence: " + editorText);
        if (!editor.voice.isActive() || editor.run.stopRequested)
          throw new AssertionError("A real service sentence end stopped tap recording");
        status(test, "Continuous editor text: " + editorText);
        status(test, "DOUBAO_CONTINUOUS_PHRASE phrase=" + names[utterance] + " recording_active=true");
      }
      test.runOnMainSync(() -> editor.voice.toggle());
      if (!editor.run.stopRequested) throw new AssertionError("Explicit toggle did not stop recording");
      session.sendAudio(lastFrame, DoubaoProtocol.FRAME_LAST, nextTimestamp);
      session.finish();
      if (!finished.await(10, TimeUnit.SECONDS))
        throw new AssertionError("No SessionFinished after explicit stop");
      if (failure.get() != null) throw failure.get();
      if (!session.awaitSessionFinished(1000)) throw new AssertionError("Final ASR result was not drained");
      session.close();
      editor.complete();
      if (editor.voice.isActive()) throw new AssertionError("Voice stayed active after SessionFinished");
      if (!editor.text().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").equals(expectedEditor))
        throw new AssertionError("Explicit stop lost final editor text: " + editor.text());
      complete = true;
      status(test, "DOUBAO_CONTINUOUS_OK phrases=2 silence_seconds=3 explicit_stop=true");
    }
    finally
    {
      if (session != null && !complete) session.cancel();
      client.shutdown();
    }
  }

  private static List<byte[]> encodeFixture(OpusFrameEncoder encoder, File fixture) throws IOException
  {
    if (!fixture.isFile() || fixture.length() < 32000)
      throw new AssertionError("Missing synthetic 16 kHz mono PCM fixture: " + fixture.getName());
    List<byte[]> encoded = new ArrayList<>();
    byte[] bytes = new byte[640];
    short[] samples = new short[320];
    try (FileInputStream input = new FileInputStream(fixture))
    {
      int read;
      while ((read = input.read(bytes)) != -1)
      {
        for (int i = 0; i < samples.length; i++)
          samples[i] = i * 2 + 1 < read
            ? (short)((bytes[i * 2] & 255) | bytes[i * 2 + 1] << 8) : 0;
        encoded.add(encoder.encode(samples));
      }
    }
    return encoded;
  }

  private static void status(Instrumentation test, String message)
  {
    Bundle bundle = new Bundle();
    bundle.putString("stream", message + "\n");
    test.sendStatus(0, bundle);
  }
}
