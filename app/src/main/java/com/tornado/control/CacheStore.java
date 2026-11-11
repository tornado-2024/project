package com.tornado.control;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Дисковый кэш веб-контента: сохраняет «control», страницу 404 и все подресурсы,
 * чтобы приложение могло показать содержимое без интернета.
 */
public final class CacheStore {

    /** Максимальный суммарный объём кэша. */
    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    /** Максимальный размер одной записи. */
    private static final long MAX_ENTRY = 16L * 1024 * 1024;

    public static final class Entry {
        public String url;
        public String mime = "application/octet-stream";
        public String charset = "utf-8";
        public int status = 200;
        public long fetchedAt;
        public byte[] body;

        public String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }

        public boolean isHtml() {
            return mime != null && mime.contains("html");
        }
    }

    private final File dir;
    /** Текущий лимит кэша (байт). */
    private long maxBytes = MAX_TOTAL;

    public CacheStore(Context ctx) {
        dir = new File(ctx.getApplicationContext().getCacheDir(), "webcache");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private static String hash(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private File bodyFile(String url) {
        return new File(dir, hash(url) + ".dat");
    }

    private File metaFile(String url) {
        return new File(dir, hash(url) + ".meta");
    }

    /** Изменить лимит кэша (настройка maxCacheSizeMb). */
    public synchronized void setMaxBytes(long bytes) {
        if (bytes <= 0) bytes = MAX_TOTAL;
        this.maxBytes = bytes;
        trim();
    }

    public synchronized long getMaxBytes() {
        return maxBytes;
    }

    public synchronized void put(String url, int status, String mime, String charset, byte[] body) {
        if (url == null || body == null) return;
        if (body.length > MAX_ENTRY) return;
        try {
            OutputStream os = new FileOutputStream(bodyFile(url));
            try {
                os.write(body);
            } finally {
                os.close();
            }
            StringBuilder meta = new StringBuilder();
            meta.append("url=").append(escape(url)).append('\n');
            meta.append("status=").append(status).append('\n');
            meta.append("mime=").append(escape(mime == null ? "application/octet-stream" : mime)).append('\n');
            meta.append("charset=").append(escape(charset == null ? "utf-8" : charset)).append('\n');
            meta.append("fetchedAt=").append(System.currentTimeMillis()).append('\n');
            OutputStream ms = new FileOutputStream(metaFile(url));
            try {
                ms.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                ms.close();
            }
            trim();
        } catch (IOException ignored) {
        }
    }

    public synchronized Entry get(String url) {
        if (url == null) return null;
        File bf = bodyFile(url);
        File mf = metaFile(url);
        if (!bf.exists() || !mf.exists()) return null;
        try {
            Entry e = new Entry();
            e.body = readAll(bf);
            Properties props = readProps(mf);
            e.url = props.getProperty("url", url);
            e.status = parseInt(props.getProperty("status"), 200);
            e.mime = props.getProperty("mime", "application/octet-stream");
            e.charset = props.getProperty("charset", "utf-8");
            e.fetchedAt = parseLong(props.getProperty("fetchedAt"), 0L);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    public synchronized void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public synchronized long sizeBytes() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.length();
        return total;
    }

    public synchronized int entriesCount() {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.getName().endsWith(".dat")) n++;
        }
        return n;
    }

    /** Удаляет самые старые записи, пока кэш не уложится в лимит. */
    private void trim() {
        try {
            long total = sizeBytes();
            if (total <= maxBytes) return;
            List<File> metas = new ArrayList<>();
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().endsWith(".meta")) metas.add(f);
            }
            metas.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File m : metas) {
                if (total <= maxBytes) break;
                Properties props = readProps(m);
                String url = props.getProperty("url");
                if (url != null) {
                    File body = bodyFile(url);
                    long len = body.length();
                    //noinspection ResultOfMethodCallIgnored
                    body.delete();
                    total -= len;
                }
                //noinspection ResultOfMethodCallIgnored
                m.delete();
                total = sizeBytes();
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Мини-реализация Properties, чтобы не тянуть зависимости. */
    private static Properties readProps(File f) {
        Properties p = new Properties();
        try {
            String data = new String(readAll(f), StandardCharsets.UTF_8);
            for (String line : data.split("\n")) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                p.setProperty(line.substring(0, i), unescape(line.substring(i + 1)));
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String unescape(String v) {
        return v.replace("\\n", "\n").replace("\\\\", "\\");
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return v == null ? def : Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
