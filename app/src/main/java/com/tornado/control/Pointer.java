package com.tornado.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор «control»-указателя и проверка хэша пароля.
 * Класс намеренно без зависимостей от Android — его можно тестировать на обычной JVM.
 */
public final class Pointer {

    private static final Pattern META_URL =
            Pattern.compile("(?i)url\\s*=\\s*['\"]?([^'\"\\s>]+)");
    private static final Pattern JSON_FIELD =
            Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"]*)\"");

    /** Ключи, в которых может лежать URL индекса. */
    private static final String[] JSON_KEYS =
            {"url", "index", "indexUrl", "link", "page", "href", "target", "site"};

    private Pointer() {
    }

    /**
     * Достаёт URL индексной страницы из ответа control.
     * Поддерживается: plain-текст с URL, JSON с полем url/index/..., HTML с meta-refresh.
     */
    public static String extractUrl(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return "";

        if (text.startsWith("{")) {
            Matcher m = JSON_FIELD.matcher(text);
            while (m.find()) {
                String key = m.group(1);
                String val = m.group(2).trim();
                if (val.isEmpty()) continue;
                for (String k : JSON_KEYS) {
                    if (k.equalsIgnoreCase(key)) return val;
                }
            }
            return "";
        }

        if (text.startsWith("<")) {
            Matcher m = META_URL.matcher(text);
            if (m.find()) return m.group(1).trim();
            return "";
        }

        for (String part : text.split("\\s+")) {
            if (part.contains("://")) return part.trim();
        }
        return "";
    }

    /**
     * Сверяет пароль с содержимым файла password.
     * Поддерживаемые форматы: sha256:&lt;hex&gt;, sha1:&lt;hex&gt;, md5:&lt;hex&gt;, plain:&lt;текст&gt;
     * или просто hex (64 символа — sha256, 40 — sha1).
     */
    public static boolean verify(String rawHash, String input) {
        if (rawHash == null) return true;
        String raw = rawHash.trim();
        if (raw.isEmpty()) return true;

        String algo = "";
        String value = raw;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String maybe = raw.substring(0, colon).toLowerCase(Locale.ROOT);
            if (maybe.matches("(sha-?256|sha-?1|md5|plain)")) {
                algo = maybe;
                value = raw.substring(colon + 1).trim();
            }
        }
        if ("plain".equals(algo)) {
            return value.equals(input);
        }
        String alg = algo.isEmpty()
                ? (value.length() == 40 ? "sha1" : "sha256")
                : algo.replace("-", "");
        String computed = digest(alg, input);
        return computed != null && computed.equalsIgnoreCase(value);
    }

    /** SHA-256/sha1/md5 в hex. */
    public static String digest(String alg, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
