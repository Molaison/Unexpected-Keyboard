package juloo.keyboard2;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AppReleaseTest
{
  private static final String ASSET = "Unexpected-Keyboard-Doubao-58.apk";
  private static final String URL = "https://github.com/Molaison/Unexpected-Keyboard/releases/download/v2.1.2/" + ASSET;
  private static final String SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String METADATA =
      "{\"tag_name\":\"v2.1.2\",\"draft\":false,\"prerelease\":false,\"assets\":[" +
      "{\"name\":\"" + ASSET + "\",\"state\":\"uploaded\",\"size\":3," +
      "\"digest\":\"sha256:" + SHA256 + "\",\"browser_download_url\":\"" + URL + "\"}]}";

  @Rule public TemporaryFolder files = new TemporaryFolder();

  @Test public void onlyOffersNewStableVersions() throws IOException
  {
    AppRelease update = AppRelease.newerThan(METADATA, 57);
    assertEquals("2.1.2", update.version);
    assertEquals(58, update.versionCode);
    assertEquals(URL, update.url);
    assertNull(AppRelease.newerThan(METADATA, 58));
    assertNull(AppRelease.newerThan(METADATA, 59));
    assertNull(AppRelease.newerThan(METADATA.replace("\"prerelease\":false", "\"prerelease\":true"), 57));
    assertNull(AppRelease.newerThan("", 57));
  }

  @Test public void rejectsDownloadOnDifferentRepositoryOrMissingDigest()
  {
    assertThrows(IOException.class, () -> AppRelease.newerThan(
        METADATA.replace(URL, "https://other.example/" + ASSET), 57));
    JsonObject release = JsonParser.parseString(METADATA).getAsJsonObject();
    release.getAsJsonArray("assets").get(0).getAsJsonObject().remove("digest");
    assertThrows(IOException.class, () -> AppRelease.newerThan(release.toString(), 57));
  }

  @Test public void readsLatestReleaseAndChecksDownloadBytes() throws IOException
  {
    OkHttpClient valid = new OkHttpClient.Builder().addInterceptor(chain -> {
      String url = chain.request().url().toString();
      assertTrue(AppRelease.LATEST.equals(url) || URL.equals(url));
      return reply(chain.request(), 200, AppRelease.LATEST.equals(url) ? METADATA : "abc");
    }).build();
    AppRelease release = AppRelease.newerThan(AppUpdates.latestMetadata(valid), 57);
    File directory = files.newFolder();
    File apk = AppUpdates.downloadApk(valid, release, directory);
    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), java.nio.file.Files.readAllBytes(apk.toPath()));
  }

  @Test public void doesNotLeaveFailedDownloadInCache() throws IOException
  {
    AppRelease release = AppRelease.newerThan(METADATA, 57);
    File directory = files.newFolder();
    for (String payload : new String[]{"ab", "abd", "abcd"})
    {
      OkHttpClient corrupt = new OkHttpClient.Builder().addInterceptor(chain ->
          reply(chain.request(), 200, payload)).build();
      assertThrows(IOException.class, () -> AppUpdates.downloadApk(corrupt, release, directory));
      assertEquals(0, directory.list().length);
    }
  }

  private static Response reply(okhttp3.Request request, int status, String body)
  {
    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(status).message("fixture").body(ResponseBody.create(body, null)).build();
  }
}
