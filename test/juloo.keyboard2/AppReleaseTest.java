package juloo.keyboard2;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;
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

  @Test public void readsLatestReleaseFromTheFork() throws IOException
  {
    OkHttpClient valid = new OkHttpClient.Builder().addInterceptor(chain -> {
      assertEquals(AppRelease.LATEST, chain.request().url().toString());
      return reply(chain.request(), 200, METADATA);
    }).build();
    assertEquals(URL, AppRelease.newerThan(AppUpdates.latestMetadata(valid), 57).url);
  }

  @Test public void handlesNoReleaseAndRejectsHttpErrors() throws IOException
  {
    OkHttpClient empty = new OkHttpClient.Builder().addInterceptor(chain ->
        reply(chain.request(), 404, "")).build();
    assertEquals("", AppUpdates.latestMetadata(empty));
    OkHttpClient rateLimited = new OkHttpClient.Builder().addInterceptor(chain ->
        reply(chain.request(), 403, "rate limit")).build();
    assertThrows(IOException.class, () -> AppUpdates.latestMetadata(rateLimited));
  }

  private static Response reply(okhttp3.Request request, int status, String body)
  {
    return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(status).message("fixture").body(ResponseBody.create(body, null)).build();
  }
}
