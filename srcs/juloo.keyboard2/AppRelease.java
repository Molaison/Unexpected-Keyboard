package juloo.keyboard2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppRelease
{
  private static final String REPOSITORY = "Molaison/Unexpected-Keyboard";
  static final String LATEST = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
  static final long MAX_APK_BYTES = 128L * 1024 * 1024;
  private static final Pattern TAG = Pattern.compile("v[0-9]+\\.[0-9]+\\.[0-9]+");
  private static final Pattern APK = Pattern.compile("Unexpected-Keyboard-Doubao-([1-9][0-9]*)\\.apk");
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  final String version;
  final int versionCode;
  final String url;
  private AppRelease(String tag, int versionCode, String url)
  {
    this.version = tag.substring(1);
    this.versionCode = versionCode;
    this.url = url;
  }

  static AppRelease newerThan(String metadata, long installedVersion) throws IOException
  {
    if (metadata.isEmpty()) return null;
    try
    {
      JsonObject release = JsonParser.parseString(metadata).getAsJsonObject();
      if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) return null;
      String tag = release.get("tag_name").getAsString();
      if (!TAG.matcher(tag).matches()) return null;
      AppRelease newer = null;
      for (JsonElement entry : release.getAsJsonArray("assets"))
      {
        JsonObject asset = entry.getAsJsonObject();
        String name = asset.get("name").getAsString();
        Matcher apk = APK.matcher(name);
        if (!apk.matches()) continue;
        int versionCode = Integer.parseInt(apk.group(1));
        if (versionCode <= installedVersion) continue;
        String url = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
        String digest = asset.get("digest").getAsString();
        long size = asset.get("size").getAsLong();
        if (newer != null || !"uploaded".equals(asset.get("state").getAsString())
            || !url.equals(asset.get("browser_download_url").getAsString())
            || !DIGEST.matcher(digest).matches() || size <= 0 || size > MAX_APK_BYTES)
          throw new IOException("Invalid APK in GitHub release");
        newer = new AppRelease(tag, versionCode, url);
      }
      return newer;
    }
    catch (RuntimeException error)
    {
      throw new IOException("Invalid GitHub release metadata", error);
    }
  }
}
