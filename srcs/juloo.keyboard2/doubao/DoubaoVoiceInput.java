package juloo.keyboard2.doubao;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import juloo.keyboard2.R;

/** Coordinates microphone capture, streaming ASR, and InputConnection updates. */
public final class DoubaoVoiceInput
{
  private static final String TAG = "Unexpected/DoubaoASR";
  private static final int SAMPLE_RATE = 16000;
  private static final int SAMPLES_PER_FRAME = 320;
  private static final int PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2;
  private static final int FRAME_DURATION_MS = 20;
  private static final int MAX_CAPTURE_FRAMES = 3000;
  private static final short[] CAPTURE_FINISHED = new short[0];
  private static final int ASR_CONCURRENCY_LIMIT = 40200011;
  private static final long FINAL_DRAIN_TIMEOUT_MS = 10000;
  private static final long INTERIM_UPDATE_INTERVAL_NS = 150000000L;
  private static final int CARET_UNAVAILABLE = -1;
  private static final int SELECTION_RANGE = -2;

  public enum State
  {
    IDLE,
    CONNECTING,
    LISTENING,
    FINISHING
  }

  public interface Host
  {
    InputConnection getCurrentInputConnection();
    void onVoiceStateChanged(State state);
    void onVoiceFailure(String message);
  }

  private final Context context;
  private final Host host;
  private final Handler mainHandler;
  private final DoubaoAsrClient asrClient;
  private final ExecutorService executor;
  private final Object lock = new Object();

  private SessionRun activeRun;
  private boolean destroyed;
  private final DoubaoTextContext textContext = new DoubaoTextContext();
  private EditorInfo pendingEditor;
  private EditorSelection latestSelection;
  private String pendingAppPackage = "";

  public DoubaoVoiceInput(Context context, Host host)
  {
    this.context = context.getApplicationContext();
    this.host = host;
    mainHandler = new Handler(Looper.getMainLooper());
    asrClient = new DoubaoAsrClient(this.context);
    executor = Executors.newSingleThreadExecutor(new ThreadFactory()
    {
      @Override
      public Thread newThread(Runnable runnable)
      {
        Thread thread = new Thread(runnable, "doubao-asr");
        thread.setDaemon(true);
        return thread;
      }
    });
  }

  public boolean isActive()
  {
    synchronized (lock)
    {
      return activeRun != null;
    }
  }

  public void toggle()
  {
    requireMainThread();
    synchronized (lock)
    {
      if (activeRun == null)
      {
        startLocked();
        return;
      }
      requestStopLocked();
    }
  }

  public void stop()
  {
    requireMainThread();
    synchronized (lock)
    {
      if (activeRun != null)
        requestStopLocked();
    }
  }

  public void start()
  {
    requireMainThread();
    synchronized (lock)
    {
      if (activeRun != null)
        throw new IllegalStateException("Doubao voice input is already active");
      startLocked();
    }
  }

  public void finishForManualInput()
  {
    requireMainThread();
    final SessionRun run;
    synchronized (lock) { run = activeRun; }
    if (run != null && host.getCurrentInputConnection() == run.connection)
      applyPendingTranscript(run);
    cancel();
  }

  public void cancelForSelectionChange(int start, int end, int composingStart, int composingEnd)
  {
    requireMainThread();
    if (start < 0 || end < 0) return;
    latestSelection = new EditorSelection(start, end);
    final SessionRun run;
    synchronized (lock) { run = activeRun; }
    if (run == null || host.getCurrentInputConnection() != run.connection)
      return;
    if (!run.hasComposingText)
    {
      if (run.initialSelection == null || !run.initialSelection.matches(latestSelection))
      {
        EditorSelection actual = run.initialSelection == null ? null : readEditorSelection(run);
        boolean stillAtStart = actual != null ? run.initialSelection.matches(actual)
            : compareEditorContext(run) == ContextMatch.SAME;
        if (!stillAtStart) cancel();
      }
      return;
    }
    if (composingEnd < 0 && start == end && run.pendingRetractionUpdates > 0
        && (run.retractionCaret == CARET_UNAVAILABLE || start == run.retractionCaret))
    {
      run.pendingRetractionUpdates--;
      // A retraction can be reported after a later write restored the span.
      if (run.lastWriteEmpty) return;
      int currentCaret = run.lastRenderedCaret >= 0 ? readEditorCaret(run) : CARET_UNAVAILABLE;
      if (currentCaret >= 0 && currentCaret == run.lastRenderedCaret) return;
      // A no-span callback can also be a real move. If the editor previously
      // reported a span, relinquish it even when its current caret is unavailable.
      if (currentCaret != CARET_UNAVAILABLE || run.reportsComposingBounds) cancel();
      return;
    }
    if (composingEnd >= 0 && start == composingEnd && end == composingEnd)
    {
      run.reportsComposingBounds = true;
      if (composingStart >= 0)
      {
        run.composingStart = composingStart;
        if (run.pendingRetractionUpdates > 0 && run.retractionCaret == CARET_UNAVAILABLE)
          run.retractionCaret = composingStart;
      }
      return;
    }
    if (run.lastWriteEmpty) return;
    // Some editors never report composing bounds. Once they report a span,
    // losing it outside a retraction is significant even before a later ack.
    if (composingEnd < 0 && !run.reportsComposingBounds) return;
    // The editor keeps the visible text at the old cursor. Drop pending results
    // before relinquishing the composing span so none can follow the new cursor.
    cancel();
  }

  public void cancel()
  {
    requireMainThread();
    final SessionRun run;
    synchronized (lock)
    {
      run = activeRun;
      if (run == null)
        return;
      activeRun = null;
      run.cancelled = true;
      run.stopRequested = true;
    }
    DoubaoAsrClient.Session session = run.session;
    if (session != null)
      session.cancel();
    else
      run.credentialRequests.cancel();
    clearPendingTranscript(run);
    if (run.hasComposingText)
      finishComposingText(run);
    host.onVoiceStateChanged(State.IDLE);
  }

  public void shutdown()
  {
    requireMainThread();
    synchronized (lock)
    {
      if (destroyed)
        return;
      destroyed = true;
    }
    cancel();
    executor.shutdownNow();
    asrClient.shutdown();
  }

  /** Shared context store; hotwords and correction pairs can be fed here. */
  public DoubaoTextContext textContext() { return textContext; }

  /**
   * Records the editor that will receive the transcript. Call it from
   * {@code onStartInputView}; the server re-ranks the transcript with the text
   * around the cursor and the target package.
   */
  public void setEditorInfo(EditorInfo info)
  {
    pendingEditor = info;
    latestSelection = info != null && info.initialSelStart >= 0 && info.initialSelEnd >= 0
        ? new EditorSelection(info.initialSelStart, info.initialSelEnd) : null;
  }

  public void setAppPackage(String packageName)
  {
    pendingAppPackage = packageName == null ? "" : packageName;
  }

  private String buildContextJson(InputConnection connection)
  {
    return buildContextJson(connection, null);
  }

  private String buildContextJson(InputConnection connection, SessionRun run)
  {
    // Never read or transmit surrounding text from sensitive/private editors.
    if (!allowsEditorContext())
    {
      textContext.clear();
      return "";
    }
    String before = cursorText(connection, true);
    String after = cursorText(connection, false);
    if (run != null)
    {
      run.initialBefore = before;
      run.initialAfter = after;
    }
    textContext.setEditor(before, after, pendingAppPackage);
    return textContext.build();
  }

  private String buildSessionContext(SessionRun run)
  {
    SurroundingText surrounding = readSurroundingText(run, 256, 256);
    if (surrounding != null)
    {
      int start = Math.min(surrounding.getSelectionStart(), surrounding.getSelectionEnd());
      int end = Math.max(surrounding.getSelectionStart(), surrounding.getSelectionEnd());
      CharSequence text = surrounding.getText();
      if (start >= 0 && end <= text.length())
      {
        EditorSelection current = absoluteSelection(surrounding);
        if (current != null) run.initialSelection = current;
        run.initialBefore = text.subSequence(Math.max(0, start - 256), start).toString();
        run.initialAfter = text.subSequence(end, end + Math.min(256, text.length() - end)).toString();
        textContext.setEditor(run.initialBefore, run.initialAfter, pendingAppPackage);
        return textContext.build();
      }
    }
    return buildContextJson(run.connection, run);
  }

  private boolean allowsEditorContext()
  {
    return pendingEditor != null && DoubaoTextContext.allowsEditorContext(
        pendingEditor.inputType, pendingEditor.imeOptions);
  }

  private static String cursorText(InputConnection connection, boolean before)
  {
    final CharSequence text;
    try
    {
      text = before ? connection.getTextBeforeCursor(256, 0)
                    : connection.getTextAfterCursor(256, 0);
    }
    catch (RuntimeException error)
    {
      return null;
    }
    return text == null ? null : text.toString();
  }

  private void startLocked()
  {
    if (destroyed)
      throw new IllegalStateException("Doubao voice input was destroyed");
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED)
      throw new SecurityException("Microphone permission is not granted");

    InputConnection connection = host.getCurrentInputConnection();
    if (connection == null)
      throw new IllegalStateException("No active InputConnection for voice input");

    SessionRun run = new SessionRun(connection);
    activeRun = run;
    Log.i(TAG, "voice_start_requested");
    host.onVoiceStateChanged(State.CONNECTING);
    executor.execute(run);
  }

  private void requestStopLocked()
  {
    if (activeRun.stopRequested)
      return;
    activeRun.stopRequested = true;
    host.onVoiceStateChanged(State.FINISHING);
  }

  private void handleTranscript(SessionRun run,
      DoubaoProtocol.Response response)
  {
    boolean stopping = run.stopRequested;
    mainHandler.post(() -> {
      if (!isCurrent(run) || run.cancelled
          || (response.text.isEmpty() && response.layout == DoubaoProtocol.TranscriptLayout.INDEXED))
        return;
      if (host.getCurrentInputConnection() != run.connection)
      {
        failInputConnection(run, "The target editor changed during voice input");
        return;
      }

      String text = run.transcript.update(response, stopping);
      if (text == null) return;
      run.pendingTranscript = text;
      long waitNs = INTERIM_UPDATE_INTERVAL_NS - (System.nanoTime() - run.lastAppliedTranscriptNs);
      if (response.isFinal || response.vadFinished || waitNs <= 0)
        applyPendingTranscript(run);
      else
      {
        // Consume every frame above. Only the editor update is rate-limited.
        mainHandler.removeCallbacks(run.renderTranscript);
        mainHandler.postDelayed(run.renderTranscript, (waitNs + 999999L) / 1000000L);
      }
    });
  }

  private void applyPendingTranscript(SessionRun run)
  {
    mainHandler.removeCallbacks(run.renderTranscript);
    if (!isCurrent(run) || run.cancelled || run.pendingTranscript == null) return;
    if (host.getCurrentInputConnection() != run.connection)
    {
      failInputConnection(run, "The target editor changed during voice input");
      return;
    }
    if (editorSelectionMoved(run))
    {
      cancel();
      return;
    }
    String text = run.pendingTranscript;
    run.pendingTranscript = null;
    // An empty result can retract our composition but must not replace the user's selection.
    if (text.isEmpty() && !run.hasComposingText) return;
    boolean retracts = text.isEmpty() && run.hasComposingText && !run.lastWriteEmpty;
    run.lastWriteEmpty = text.isEmpty();
    boolean accepted = run.connection.setComposingText(text, 1);
    run.hasComposingText = true;
    run.lastAppliedTranscriptNs = System.nanoTime();
    if (!accepted)
      failInputConnection(run, "The target editor rejected voice text");
    else
    {
      // Use the actual caret so filters or maxLength limits can change the text.
      run.lastRenderedCaret = readEditorCaret(run);
      if (retracts)
      {
        run.pendingRetractionUpdates++;
        run.retractionCaret = run.lastRenderedCaret >= 0
            ? run.lastRenderedCaret : run.composingStart;
      }
      Log.i(TAG, "transcript_applied chars=" + text.length());
    }
  }

  private boolean editorSelectionMoved(SessionRun run)
  {
    if (!run.hasComposingText)
    {
      EditorSelection actual = run.initialSelection == null ? null : readEditorSelection(run);
      if (actual != null) return !run.initialSelection.matches(actual);
      // Older editors may deliver their selection callback after the first ASR result.
      return compareEditorContext(run) == ContextMatch.CHANGED;
    }
    if (run.lastRenderedCaret == CARET_UNAVAILABLE) return false;
    int currentCaret = readEditorCaret(run);
    return currentCaret != CARET_UNAVAILABLE && currentCaret != run.lastRenderedCaret;
  }

  private enum ContextMatch { SAME, CHANGED, UNKNOWN }

  private static ContextMatch compareEditorContext(SessionRun run)
  {
    String before = run.initialBefore == null ? null : cursorText(run.connection, true);
    if (before != null && !before.equals(run.initialBefore)) return ContextMatch.CHANGED;
    String after = run.initialAfter == null ? null : cursorText(run.connection, false);
    if (after != null && !after.equals(run.initialAfter)) return ContextMatch.CHANGED;
    return before != null && after != null ? ContextMatch.SAME : ContextMatch.UNKNOWN;
  }

  private int readEditorCaret(SessionRun run)
  {
    EditorSelection selection = readEditorSelection(run);
    if (selection == null)
    {
      // Some editors report only a relative position. Use their callbacks.
      run.canReadEditorCaret = false;
      return CARET_UNAVAILABLE;
    }
    return selection.start == selection.end ? selection.start : SELECTION_RANGE;
  }

  private EditorSelection readEditorSelection(SessionRun run)
  {
    return absoluteSelection(readSurroundingText(run, 0, 0));
  }

  private SurroundingText readSurroundingText(SessionRun run, int before, int after)
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !run.canReadEditorCaret)
      return null;
    try
    {
      return run.connection.getSurroundingText(before, after, 0);
    }
    catch (RuntimeException ignored) { return null; }
  }

  private static EditorSelection absoluteSelection(SurroundingText text)
  {
    if (text == null || text.getOffset() < 0 || text.getSelectionStart() < 0
        || text.getSelectionEnd() < 0) return null;
    return new EditorSelection(text.getOffset() + text.getSelectionStart(),
        text.getOffset() + text.getSelectionEnd());
  }

  private static final class EditorSelection
  {
    final int start;
    final int end;

    EditorSelection(int start, int end)
    {
      this.start = Math.min(start, end);
      this.end = Math.max(start, end);
    }

    boolean matches(EditorSelection other) { return start == other.start && end == other.end; }
  }

  private void clearPendingTranscript(SessionRun run)
  {
    mainHandler.removeCallbacks(run.renderTranscript);
    run.pendingTranscript = null;
  }

  private void failInputConnection(SessionRun run, String message)
  {
    synchronized (lock)
    {
      if (activeRun != run)
        return;
      activeRun = null;
      run.cancelled = true;
      run.stopRequested = true;
    }
    DoubaoAsrClient.Session session = run.session;
    if (session != null)
      session.cancel();
    clearPendingTranscript(run);
    host.onVoiceFailure(message);
    host.onVoiceStateChanged(State.IDLE);
  }

  private void finishComposingText(SessionRun run)
  {
    if (!run.connection.finishComposingText())
      host.onVoiceFailure("The target editor rejected the final voice text");
    run.hasComposingText = false;
    run.transcript.clear();
  }

  private void complete(SessionRun run, Throwable problem)
  {
    mainHandler.post(() -> {
      if (!isCurrent(run)) return;
      if (problem == null || host.getCurrentInputConnection() == run.connection)
        applyPendingTranscript(run);
      synchronized (lock)
      {
        if (activeRun != run)
          return;
        activeRun = null;
      }
      clearPendingTranscript(run);
      if (run.hasComposingText)
        finishComposingText(run);
      if (problem != null)
      {
        Log.e(TAG, "Voice input failed", problem);
        host.onVoiceFailure(failureMessage(problem));
      }
      else
        Log.i(TAG, "voice_session_complete");
      host.onVoiceStateChanged(State.IDLE);
    });
  }

  private boolean isCurrent(SessionRun run)
  {
    synchronized (lock)
    {
      return activeRun == run;
    }
  }

  private void requireMainThread()
  {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("Voice input lifecycle must run on main thread");
  }

  private String failureMessage(Throwable problem)
  {
    if (problem instanceof DoubaoAsrClient.ServiceException
        && ((DoubaoAsrClient.ServiceException)problem).statusCode == ASR_CONCURRENCY_LIMIT)
      return context.getString(R.string.voice_failure_service_busy);
    String message = problem.getMessage();
    if (message == null || message.isEmpty())
      return problem.getClass().getSimpleName();
    return message;
  }

  final class SessionRun implements Runnable, DoubaoAsrClient.Listener
  {
    final InputConnection connection;
    /** {@code extra.context} captured on the main thread when the run starts. */
    final String contextJson;
    final AtomicReference<IOException> asyncFailure =
        new AtomicReference<IOException>();
    final DoubaoAsrClient.CredentialRequests credentialRequests =
        new DoubaoAsrClient.CredentialRequests();

    volatile DoubaoAsrClient.Session session;
    volatile AudioRecord recorder;
    volatile boolean stopRequested;
    volatile boolean cancelled;
    volatile long audioFramesSent;

    boolean hasComposingText;
    boolean reportsComposingBounds;
    boolean lastWriteEmpty;
    boolean canReadEditorCaret;
    EditorSelection initialSelection;
    String initialBefore;
    String initialAfter;
    int pendingRetractionUpdates;
    int composingStart = CARET_UNAVAILABLE;
    int lastRenderedCaret = CARET_UNAVAILABLE;
    int retractionCaret = CARET_UNAVAILABLE;
    final DoubaoTranscript transcript = new DoubaoTranscript();
    final Runnable renderTranscript = () -> applyPendingTranscript(this);
    long lastAppliedTranscriptNs;
    String pendingTranscript;

    SessionRun(InputConnection connection)
    {
      this.connection = connection;
      canReadEditorCaret = allowsEditorContext();
      initialSelection = latestSelection;
      contextJson = buildSessionContext(this);
    }

    @Override
    public void run()
    {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      Throwable problem = null;
      Capture capture = null;
      try
      {
        throwIfCancelledOrFailed();
        if (stopRequested) return;
        capture = startCapture();
        mainHandler.post(() -> {
          if (isCurrent(this) && !cancelled && !stopRequested)
            host.onVoiceStateChanged(State.LISTENING);
        });
        throwIfCancelledOrFailed();
        session = asrClient.open(this, credentialRequests);
        throwIfCancelledOrFailed();
        session.startSession(contextJson);
        throwIfCancelledOrFailed();
        streamAudio(capture);
        throwIfCancelledOrFailed();
        session.finish();
        boolean receivedFinal = session.awaitSessionFinished(
            FINAL_DRAIN_TIMEOUT_MS);
        if (!receivedFinal)
          throw new IOException("Timed out waiting for the final ASR result");
      }
      catch (Throwable error)
      {
        problem = error;
      }
      finally
      {
        problem = capture == null ? releaseRecorder(problem) : stopCapture(capture, problem);
        DoubaoAsrClient.Session currentSession = session;
        if (currentSession != null)
        {
          if (cancelled || problem != null)
            currentSession.cancel();
          else
          {
            try
            {
              currentSession.close();
            }
            catch (Throwable closeError)
            {
              if (problem == null)
                problem = closeError;
              else
                problem.addSuppressed(closeError);
            }
          }
        }
        if (!cancelled)
          complete(this, problem);
        if (problem instanceof InterruptedException)
          Thread.currentThread().interrupt();
      }
    }

    private final class Capture
    {
      // Reserve a slot to signal that the last PCM frame has been queued.
      final ArrayBlockingQueue<short[]> frames = new ArrayBlockingQueue<>(MAX_CAPTURE_FRAMES + 1);
      final AtomicReference<IOException> failure = new AtomicReference<>();
      final Thread thread;

      Capture(AudioRecord audioRecord)
      {
        thread = new Thread(() -> captureFrames(audioRecord, this), "doubao-microphone");
      }

      void throwIfFailed() throws IOException
      {
        IOException error = failure.get();
        if (error != null) throw error;
      }
    }

    private Capture startCapture() throws IOException
    {
      if (ContextCompat.checkSelfPermission(context,
          Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Microphone permission was revoked");

      int minimumBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimumBuffer <= 0)
        throw new IOException("Invalid AudioRecord buffer size: "
            + minimumBuffer);

      AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
          SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          Math.max(minimumBuffer, PCM_BYTES_PER_FRAME * 4));
      recorder = audioRecord;
      if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IOException("AudioRecord initialization failed");

      audioRecord.startRecording();
      if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("AudioRecord did not enter recording state");
      Capture capture = new Capture(audioRecord);
      capture.thread.start();
      return capture;
    }

    private void captureFrames(AudioRecord audioRecord, Capture capture)
    {
      try
      {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        while (!stopRequested && !cancelled)
        {
          short[] samples = new short[SAMPLES_PER_FRAME];
          int count = readFrame(audioRecord, samples);
          if (count > 0 && (capture.frames.remainingCapacity() <= 1 || !capture.frames.offer(samples)))
            throw new IOException("Voice processing fell more than 60 seconds behind microphone capture");
          if (count < SAMPLES_PER_FRAME) break;
        }
      }
      catch (IOException | RuntimeException error)
      {
        capture.failure.set(new IOException("Microphone capture failed", error));
      }
      finally
      {
        Throwable problem = releaseRecorder(capture.failure.get());
        if (problem != null)
          capture.failure.set(problem instanceof IOException ? (IOException)problem
              : new IOException("Could not release microphone capture", problem));
        capture.frames.add(CAPTURE_FINISHED);
      }
    }

    private void streamAudio(Capture capture) throws Exception
    {
      try (OpusFrameEncoder encoder = new OpusFrameEncoder())
      {
        long startedAtMs = System.currentTimeMillis();
        long nextFrameAtNs = System.nanoTime();
        long framePeriodNs = TimeUnit.MILLISECONDS.toNanos(FRAME_DURATION_MS);
        long frameIndex = 0;
        while (!cancelled && !session.isFinished())
        {
          throwIfCancelledOrFailed();
          capture.throwIfFailed();
          short[] pcmFrame = capture.frames.poll(100, TimeUnit.MILLISECONDS);
          if (pcmFrame == null) continue;
          boolean last = pcmFrame == CAPTURE_FINISHED;
          if (last) capture.throwIfFailed();

          // Frames recorded during the handshake must use the normal audio cadence.
          byte[] opus = encoder.encode(last ? new short[SAMPLES_PER_FRAME] : pcmFrame);
          long waitNs = nextFrameAtNs - System.nanoTime();
          if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs);
          throwIfCancelledOrFailed();
          if (session.isFinished()) break;
          int frameState = last ? DoubaoProtocol.FRAME_LAST : frameIndex == 0
              ? DoubaoProtocol.FRAME_FIRST : DoubaoProtocol.FRAME_MIDDLE;
          nextFrameAtNs = System.nanoTime() + framePeriodNs;
          // The terminal silence requests two-pass refinement on key release.
          session.sendAudio(opus, frameState, startedAtMs + frameIndex * FRAME_DURATION_MS, last, last);
          if (last)
          {
            Log.i(TAG, "audio_last_sent frames=" + frameIndex);
            break;
          }
          long sentAtNs = System.nanoTime();
          if (sentAtNs >= nextFrameAtNs)
            nextFrameAtNs = sentAtNs + framePeriodNs;
          frameIndex++;
          audioFramesSent = frameIndex;
          if (frameIndex == 1 || frameIndex % 50 == 0)
            Log.i(TAG, "audio_frames_sent=" + frameIndex);
        }
        capture.throwIfFailed();
      }
    }

    private Throwable stopCapture(Capture capture, Throwable problem)
    {
      stopRequested = true;
      try
      {
        capture.thread.join(2000);
        if (capture.thread.isAlive()) throw new IOException("Microphone capture did not stop");
      }
      catch (IOException | InterruptedException error)
      {
        if (problem == null) return error;
        problem.addSuppressed(error);
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      }
      IOException failure = capture.failure.get();
      if (failure != null)
      {
        if (problem == null) return failure;
        if (problem != failure) problem.addSuppressed(failure);
      }
      return problem;
    }

    private int readFrame(AudioRecord audioRecord, short[] pcmFrame)
        throws IOException
    {
      int offset = 0;
      while (offset < pcmFrame.length && !stopRequested && !cancelled)
      {
        int read = audioRecord.read(pcmFrame, offset,
            pcmFrame.length - offset);
        if (read < 0)
          throw new IOException("AudioRecord read failed: " + read);
        if (read == 0)
          throw new IOException("AudioRecord returned zero samples");
        offset += read;
      }
      return offset;
    }

    private void throwIfCancelledOrFailed() throws IOException
    {
      if (cancelled)
        throw new IOException("Voice input was cancelled");
      IOException failure = asyncFailure.get();
      if (failure != null)
        throw failure;
    }

    private Throwable releaseRecorder(Throwable problem)
    {
      AudioRecord audioRecord = recorder;
      if (audioRecord == null)
        return problem;

      try
      {
        if (audioRecord.getRecordingState()
            == AudioRecord.RECORDSTATE_RECORDING)
          audioRecord.stop();
      }
      catch (Throwable stopError)
      {
        if (problem == null)
          problem = stopError;
        else
          problem.addSuppressed(stopError);
      }
      try
      {
        audioRecord.release();
      }
      catch (Throwable releaseError)
      {
        if (problem == null)
          problem = releaseError;
        else
          problem.addSuppressed(releaseError);
      }
      recorder = null;
      return problem;
    }

    @Override
    public void onConnecting(DoubaoAsrClient.Session connecting)
    {
      session = connecting;
      if (cancelled) connecting.cancel();
    }

    @Override
    public void onResponse(DoubaoProtocol.Response response)
    {
      switch (response.type)
      {
        case INTERIM_RESULT:
        case FINAL_RESULT:
          handleTranscript(this, response);
          break;
        case SESSION_FINISHED:
          stopRequested = true;
          break;
        case ERROR:
          asyncFailure.compareAndSet(null,
              new DoubaoAsrClient.ServiceException(response.statusCode, response.errorMessage));
          stopRequested = true;
          break;
        default:
          break;
      }
    }

    @Override
    public void onFailure(IOException failure)
    {
      asyncFailure.compareAndSet(null, failure);
      stopRequested = true;
    }
  }

}
