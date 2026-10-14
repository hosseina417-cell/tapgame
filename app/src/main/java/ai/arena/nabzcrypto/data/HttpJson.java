package ai.arena.nabzcrypto.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

final class HttpJson {
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private HttpJson() {}

    static Object get(String rawUrl) throws ApiException {
        if (rawUrl == null || !rawUrl.startsWith("https://")) {
            throw new ApiException("نشانی ناامن یا نامعتبر است");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7_000);
            connection.setReadTimeout(12_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "NabzCrypto/5.0 Android");

            int status = connection.getResponseCode();
            InputStream raw = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (raw == null) {
                throw new ApiException("پاسخ خالی از سرویس بازار", status);
            }
            InputStream decoded = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                    ? new GZIPInputStream(raw) : raw;
            String body = readBounded(decoded);
            if (status < 200 || status >= 300) {
                String shortBody = body.replace('\n', ' ').trim();
                if (shortBody.length() > 140) shortBody = shortBody.substring(0, 140);
                if (status == 429 || status == 418) {
                    throw new ApiException("محدودیت تعداد درخواست؛ کمی بعد دوباره تلاش کنید", status);
                }
                if (status == 451) {
                    throw new ApiException("این منبع در موقعیت فعلی در دسترس نیست", status);
                }
                throw new ApiException("خطای سرویس بازار (" + status + ") " + shortBody, status);
            }
            Object parsed = new JSONTokener(body).nextValue();
            if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
                throw new ApiException("قالب پاسخ سرویس بازار معتبر نیست");
            }
            return parsed;
        } catch (ApiException e) {
            throw e;
        } catch (JSONException e) {
            throw new ApiException("پاسخ سرویس بازار قابل خواندن نیست");
        } catch (IOException e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) message = "خطای اتصال";
            throw new ApiException(message.length() > 120 ? message.substring(0, 120) : message);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream input) throws IOException, ApiException {
        try (InputStream in = new BufferedInputStream(input);
             ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new ApiException("پاسخ سرویس بیش از حد بزرگ است");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
