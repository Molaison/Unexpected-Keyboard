package juloo.keyboard2;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AppUpdates
{
  private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
  private static final int MAX_METADATA_BYTES = 256 * 1024;
  private static final int ALLOW_INSTALL_REQUEST = 310;
  private static final int INSTALL_REQUEST = 311;

  private final LauncherActivity launcher;
  private final Button button;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final OkHttpClient http = new OkHttpClient.Builder()
      .followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build();
  private final SharedPreferences prefs;
  private final long installedVersion;
  private AppRelease available;
  private File ready;
  private boolean busy;
  private boolean resumed;
  private boolean installing;
  private boolean closed;

  AppUpdates(LauncherActivity launcher, Button button)
  {
    this.launcher = launcher;
    this.button = button;
    prefs = DirectBootAwarePreferences.get_protected_prefs(launcher, "app_updates");
    installedVersion = installedVersion(launcher);
    try { available = AppRelease.newerThan(prefs.getString("latest", ""), installedVersion); }
    catch (IOException ignored) {}
    button.setOnClickListener(view -> {
      if (ready != null) installReady();
      else if (available != null) requestDownload();
      else check(true);
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
    if (ready != null && !installing) ready.delete();
  }

  private void check(boolean manual)
  {
    if (busy || closed) return;
    prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
    begin(R.string.update_checking);
    worker.execute(() -> {
      try
      {
        String metadata = latestMetadata(http);
        AppRelease newer = AppRelease.newerThan(metadata, installedVersion);
        prefs.edit().putString("latest", newer == null ? "" : metadata).apply();
        launcher.runOnUiThread(() -> {
          if (closed) return;
          available = newer;
          finishWork();
          if (manual && newer == null) show(R.string.update_current);
        });
      }
      catch (IOException error) { failed(manual); }
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

  private void requestDownload()
  {
    if (busy || closed || installing || available == null) return;
    if (!canInstall())
    {
      askInstallPermission();
      return;
    }
    download(available);
  }

  private boolean canInstall()
  {
    return Build.VERSION.SDK_INT < 26 || launcher.getPackageManager().canRequestPackageInstalls();
  }

  private void askInstallPermission()
  {
    try
    {
      launcher.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.fromParts("package", launcher.getPackageName(), null)), ALLOW_INSTALL_REQUEST);
    }
    catch (ActivityNotFoundException | SecurityException error) { show(R.string.update_permission_hint); }
  }

  void onActivityResult(int requestCode)
  {
    if (requestCode == ALLOW_INSTALL_REQUEST)
    {
      if (!canInstall()) return;
      if (ready != null) installReady();
      else if (available != null) requestDownload();
    }
    else if (requestCode == INSTALL_REQUEST)
    {
      installing = false;
      if (ready != null) ready.delete();
      ready = null;
      updateLabel();
    }
  }

  private void download(AppRelease release)
  {
    begin(R.string.update_downloading);
    worker.execute(() -> {
      try
      {
        File directory = new File(launcher.getCacheDir(), "updates");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("No update cache");
        File[] previous = directory.listFiles((dir, name) -> name.startsWith("update-") && name.endsWith(".apk"));
        long expiry = System.currentTimeMillis() - CHECK_INTERVAL_MS;
        if (previous != null)
          for (File apk : previous)
            if (apk.lastModified() < expiry) apk.delete();
        File apk = downloadApk(http, release, directory);
        if (!matchesInstalledApp(apk, release))
        {
          apk.delete();
          throw new IOException("APK does not match the installed application");
        }
        launcher.runOnUiThread(() -> {
          if (closed) { apk.delete(); return; }
          ready = apk;
          finishWork();
          if (resumed) installReady();
        });
      }
      catch (IOException | SecurityException error) { failed(true); }
    });
  }

  static File downloadApk(OkHttpClient client, AppRelease release, File directory) throws IOException
  {
    File apk = File.createTempFile("update-", ".apk", directory);
    boolean complete = false;
    try (Response response = client.newCall(new Request.Builder().url(release.url).build()).execute())
    {
      ResponseBody body = response.body();
      if (!response.isSuccessful() || body == null) throw new IOException("Could not download update");
      MessageDigest hash = sha256();
      long total = 0;
      try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(apk))
      {
        byte[] buffer = new byte[32 * 1024];
        for (int read; (read = input.read(buffer)) != -1; )
        {
          total += read;
          if (total > release.bytes) throw new IOException("Update exceeds release asset size");
          hash.update(buffer, 0, read);
          output.write(buffer, 0, read);
        }
      }
      if (total != release.bytes || !release.sha256.equals(hex(hash.digest())))
        throw new IOException("Update integrity check failed");
      complete = true;
      return apk;
    }
    finally { if (!complete) apk.delete(); }
  }

  private boolean matchesInstalledApp(File apk, AppRelease release)
  {
    PackageManager pm = launcher.getPackageManager();
    try
    {
      PackageInfo installed = pm.getPackageInfo(launcher.getPackageName(), PackageManager.GET_SIGNATURES);
      PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
      return archive != null && installed.packageName.equals(archive.packageName)
          && PackageInfoCompat.getLongVersionCode(archive) == release.versionCode
          && release.versionCode > PackageInfoCompat.getLongVersionCode(installed)
          && installed.signatures != null && installed.signatures.length == 1
          && archive.signatures != null && archive.signatures.length == 1
          && installed.signatures[0].equals(archive.signatures[0]);
    }
    catch (PackageManager.NameNotFoundException error) { return false; }
  }

  private void installReady()
  {
    if (ready == null || installing || closed) return;
    if (!canInstall()) { askInstallPermission(); return; }
    Uri apk = FileProvider.getUriForFile(launcher, launcher.getPackageName() + ".updates", ready);
    Intent install = new Intent(Intent.ACTION_VIEW)
        .setDataAndType(apk, "application/vnd.android.package-archive")
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try
    {
      launcher.startActivityForResult(install, INSTALL_REQUEST);
      installing = true;
    }
    catch (ActivityNotFoundException | SecurityException error) { show(R.string.update_failed); }
  }

  private static long installedVersion(LauncherActivity launcher)
  {
    try
    {
      return PackageInfoCompat.getLongVersionCode(
          launcher.getPackageManager().getPackageInfo(launcher.getPackageName(), 0));
    }
    catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
  }

  private static MessageDigest sha256()
  {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
  }

  private static String hex(byte[] bytes)
  {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte value : bytes)
      hex.append(Character.forDigit((value & 0xFF) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
    return hex.toString();
  }

  private void begin(int text)
  {
    busy = true;
    button.setText(text);
    button.setEnabled(false);
  }

  private void finishWork()
  {
    busy = false;
    updateLabel();
  }

  private void updateLabel()
  {
    button.setEnabled(true);
    if (ready != null) button.setText(R.string.update_install);
    else if (available != null) button.setText(launcher.getString(R.string.update_available, available.version));
    else button.setText(R.string.update_check);
  }

  private void failed(boolean notify)
  {
    launcher.runOnUiThread(() -> {
      if (closed) return;
      finishWork();
      if (notify) show(R.string.update_failed);
    });
  }

  private void show(int message)
  {
    if (resumed) Toast.makeText(launcher, message, Toast.LENGTH_SHORT).show();
  }

  public static final class ApkProvider extends FileProvider {}
}
