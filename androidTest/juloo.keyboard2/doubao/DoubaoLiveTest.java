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
    List<byte[]> encoded = new ArrayList<>();
    byte[] bytes = new byte[640];
    short[] samples = new short[320];
    long encodeStarted = SystemClock.elapsedRealtime();
    byte[] lastFrame;
    status(test, "Encoding synthetic fixture before opening the network session");
    try (OpusFrameEncoder encoder = new OpusFrameEncoder();
        FileInputStream input = new FileInputStream(fixture))
    {
      int read;
      while ((read = input.read(bytes)) != -1)
      {
        for (int i = 0; i < samples.length; i++)
          samples[i] = i * 2 + 1 < read
            ? (short)((bytes[i * 2] & 255) | bytes[i * 2 + 1] << 8) : 0;
        encoded.add(encoder.encode(samples));
      }
      java.util.Arrays.fill(samples, (short)0);
      lastFrame = encoder.encode(samples);
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
        if (!session.awaitFinalOrFinished(10000)) throw new AssertionError("No terminal ASR response");
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

  private static void status(Instrumentation test, String message)
  {
    Bundle bundle = new Bundle();
    bundle.putString("stream", message + "\n");
    test.sendStatus(0, bundle);
  }
}
