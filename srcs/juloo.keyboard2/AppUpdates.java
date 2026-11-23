package juloo.keyboard2;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.content.pm.PackageInfoCompat;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class AppUpdates
{
  private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
  private static final int MAX_METADATA_BYTES = 256 * 1024;

  private final LauncherActivity launcher;
  private final Button button;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final OkHttpClient http = new OkHttpClient.Builder()
      .followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build();
  private final SharedPreferences prefs;
  private final long installedVersion;
  private AppRelease available;
  private boolean checking;
  private boolean resumed;
  private boolean closed;

  AppUpdates(LauncherActivity launcher, Button button)
  {
    this.launcher = launcher;
    this.button = button;
    prefs = DirectBootAwarePreferences.get_protected_prefs(launcher, "app_updates");
    try
    {
      installedVersion = PackageInfoCompat.getLongVersionCode(
          launcher.getPackageManager().getPackageInfo(launcher.getPackageName(), 0));
    }
    catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    try { available = AppRelease.newerThan(prefs.getString("latest", ""), installedVersion); }
    catch (IOException ignored) {}
    button.setOnClickListener(view -> {
      if (available == null) check(true);
      else openDownload(available);
    });
    updateLabel();
  }

  void onResume()
  {
    resumed = true;
    if (Build.VERSION.SDK_INT >= 24 && !launcher.getSystemService(UserManager.class).isUserUnlocked())
      return;
    long age = System.currentTimeMillis() - prefs.getLong("last_check", 0);
    if (age < 0 || age >= CHECK_INTERVAL_MS) check(false);
  }

  void onPause() { resumed = false; }

  void close()
  {
    closed = true;
    http.dispatcher().cancelAll();
    worker.shutdownNow();
  }

  private void check(boolean manual)
  {
    if (checking || closed) return;
    checking = true;
    prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
    button.setEnabled(false);
    button.setText(R.string.update_checking);
    worker.execute(() -> {
      try
      {
        String metadata = latestMetadata(http);
        AppRelease newer = AppRelease.newerThan(metadata, installedVersion);
        prefs.edit().putString("latest", newer == null ? "" : metadata).apply();
        launcher.runOnUiThread(() -> {
          if (closed) return;
          available = newer;
          updateLabel();
          if (manual && newer == null) show(R.string.update_current);
        });
      }
      catch (IOException error)
      {
        launcher.runOnUiThread(() -> {
          if (closed) return;
          updateLabel();
          if (manual) show(R.string.update_failed);
        });
      }
    });
  }

  static String latestMetadata(OkHttpClient client) throws IOException
  {
    Request request = new Request.Builder().url(AppRelease.LATEST)
        .header("Accept", "application/vnd.github+json").build();
    try (Response response = client.newCall(request).execute())
    {
      if (response.code() == 404) return "";
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null || body.source().request(MAX_METADATA_BYTES + 1L))
        throw new IOException("Could not read GitHub release");
      return body.source().readUtf8();
    }
  }

  private void openDownload(AppRelease release)
  {
    try { launcher.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.url))); }
    catch (ActivityNotFoundException | SecurityException error) { show(R.string.update_failed); }
  }

  private void updateLabel()
  {
    checking = false;
    button.setEnabled(true);
    if (available == null) button.setText(R.string.update_check);
    else button.setText(launcher.getString(R.string.update_available, available.version));
  }

  private void show(int message)
  {
    if (resumed) Toast.makeText(launcher, message, Toast.LENGTH_SHORT).show();
  }
}
