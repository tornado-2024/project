package com.tornado.control;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Минимальный HTTP-клиент поверх HttpURLConnection.
 * Не тянет внешних зависимостей, чтобы APK можно было собирать без Gradle-зависимостей.
 */
public final class HttpClient {

    public static final int MAX_BODY = 12 * 1024 * 1024;

    public static final class Resp {
        /** HTTP-код ответа, -1 если ответа не было (сетевая ошибка). */
        public int status = -1;
        /** Итоговый URL после редиректов. */
        public String finalUrl;
        /** Content-Type ответа (может быть null). */
        public String contentType;
        /** Тело ответа. */
        public byte[] body;
        /** Текст сетевой ошибки (null, если ответа не было). */
        public String error;
        /** true — сервер не ответил, соединение упало/таймаут. */
        public boolean networkError;
        /** Заголовки ответа. */
        public final Map<String, String> headers = new LinkedHashMap<>();
        /** Время ответа в мс. */
        public long elapsedMs;

        public boolean isOk() {
            return status >= 200 && status < 300 && body != null;
        }

        public String text() {
            if (body == null) return "";
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private HttpClient() {
    }

    public static Resp get(String url, int timeoutMs) {
        return get(url, timeoutMs, null);
    }

    /** GET-запрос с возможностью задать свой User-Agent. */
    public static Resp get(String url, int timeoutMs, String userAgent) {
        Resp out = new Resp();
        long started = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent",
                    (userAgent == null || userAgent.trim().isEmpty())
                            ? "TornadoControl/1.0 (Android)" : userAgent.trim());
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toString());
            conn.setRequestMethod("GET");
            conn.connect();

            out.status = conn.getResponseCode();
            out.finalUrl = conn.getURL().toString();
            out.contentType = conn.getContentType();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                out.headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }

            InputStream in = out.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            out.body = readAll(in);
        } catch (Exception e) {
            out.networkError = true;
            out.error = describe(e);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
        out.elapsedMs = System.currentTimeMillis() - started;
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY) break;
            bos.write(buf, 0, n);
        }
        try {
            in.close();
        } catch (Exception ignored) {
        }
        return bos.toByteArray();
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    /** Достаёт mime-тип из Content-Type. */
    public static String mimeOf(String contentType) {
        if (contentType == null) return "application/octet-stream";
        int idx = contentType.indexOf(';');
        String m = (idx >= 0 ? contentType.substring(0, idx) : contentType).trim();
        if (m.isEmpty()) return "application/octet-stream";
        return m.toLowerCase(Locale.ROOT);
    }

    /** Достаёт charset из Content-Type (по умолчанию utf-8). */
    public static String charsetOf(String contentType) {
        if (contentType == null) return "utf-8";
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) return "utf-8";
        String cs = contentType.substring(idx + 8).trim();
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi);
        cs = cs.replace("\"", "").trim();
        return cs.isEmpty() ? "utf-8" : cs.toLowerCase(Locale.ROOT);
    }

    /** Похоже ли тело на HTML-документ. */
    public static boolean looksLikeHtml(String contentType, byte[] body) {
        if (contentType != null) {
            String m = mimeOf(contentType);
            if (m.contains("html") || m.contains("xhtml")) return true;
        }
        if (body == null || body.length == 0) return false;
        String head = new String(body, 0, Math.min(body.length, 512),
                java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html")
                || head.startsWith("<?xml") || head.startsWith("<head")
                || head.startsWith("<body") || head.startsWith("<script")
                || head.startsWith("<div");
    }
}
