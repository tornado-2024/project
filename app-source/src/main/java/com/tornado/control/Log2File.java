package com.tornado.control;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Пишет логи в файл, доступный из Termux:
 *   /storage/emulated/0/Android/data/com.tornado.control/files/app.log
 *
 * Работает только в debug-сборке (android:debuggable="true").
 * Нужен, чтобы читать логи приложения (включая console.log со страницы)
 * без adb: logcat из обычного приложения видит только собственные записи.
 */
public final class Log2File {

    private static final String TAG = "AppLog";
    private static File file;
    private static Uri mediaUri;
    /** Хвост лога в памяти — чтобы показать его прямо в приложении. */
    private static final StringBuilder TAIL = new StringBuilder();
    private static final int MAX_TAIL = 96 * 1024;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Log2File() {
    }

    /** Включает запись, если приложение запущено в debuggable-режиме. */
    public static synchronized void init(Context ctx) {
        if (file != null) return;
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            if ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;

            // 1) «Загрузки» — видно из Termux сразу (если доступ разрешён)
            File[] candidates = new File[]{
                    new File("/storage/emulated/0/Download/tornado-app.log"),
                    null,   // external files dir — подставим ниже
                    null    // приватная папка — крайний случай
            };
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) ext = new File(ctx.getFilesDir(), "logs");
            if (!ext.exists()) {
                //noinspection ResultOfMethodCallIgnored
                ext.mkdirs();
            }
            candidates[1] = new File(ext, "app.log");
            candidates[2] = new File(new File(ctx.getFilesDir(), "logs"), "app.log");

            for (File c : candidates) {
                if (c == null) continue;
                File parent = c.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                // проверяем, что файл действительно пишется
                OutputStreamWriter probe = new OutputStreamWriter(
                        new FileOutputStream(c, true), StandardCharsets.UTF_8);
                probe.write("");
                probe.close();
                file = c;
                break;
            }
            ctxRef = ctx.getApplicationContext();
            mediaUri = openMediaLog(ctx);
        } catch (Exception e) {
            file = null;
        }
    }

    /**
     * Открывает/создаёт лог в «Загрузках» через MediaStore —
     * тогда его видно из Termux (обычная папка Android/data закрыта).
     */
    private static Uri openMediaLog(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, "tornado-app.log");
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            java.io.OutputStream os = cr.openOutputStream(uri, "wa");
            if (os != null) os.close();
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** Хвост лога (последние записи) — для показа в приложении и через JS. */
    public static synchronized String tail() {
        return TAIL.toString();
    }

    public static synchronized boolean isEnabled() {
        return file != null;
    }

    public static synchronized String path() {
        return file == null ? null : file.getAbsolutePath();
    }

    public static synchronized void i(String tag, String msg) {
        write("I", tag, msg, null);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        write("E", tag, msg, t);
    }

    private static void write(String level, String tag, String msg, Throwable t) {
        if (file == null && mediaUri == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(TIME.format(new Date()))
                    .append(' ').append(level).append('/').append(tag)
                    .append(": ").append(msg == null ? "" : msg);
            if (t != null) {
                sb.append('\n');
                sb.append(Log.getStackTraceString(t));
            }
            sb.append('\n');
            String line = sb.toString();
            TAIL.append(line);
            if (TAIL.length() > MAX_TAIL) {
                TAIL.delete(0, TAIL.length() - MAX_TAIL);
            }
            if (file != null) {
                Writer w = new OutputStreamWriter(new FileOutputStream(file, true),
                        StandardCharsets.UTF_8);
                try {
                    w.write(line);
                    w.flush();
                } finally {
                    try {
                        w.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (mediaUri != null) {
                ContentResolver cr = ctxRef != null ? ctxRef.getContentResolver() : null;
                if (cr != null) {
                    java.io.OutputStream os = cr.openOutputStream(mediaUri, "wa");
                    if (os != null) {
                        try {
                            os.write(line.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } finally {
                            os.close();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // логирование не должно ломать приложение
        }
    }

    /** Контекст для записи через MediaStore. */
    private static Context ctxRef;
}
