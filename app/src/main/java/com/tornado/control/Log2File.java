package com.tornado.control;

import android.content.Context;
import android.content.pm.ApplicationInfo;
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
        } catch (Exception e) {
            file = null;
        }
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
        if (file == null) return;
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
            Writer w = new OutputStreamWriter(new FileOutputStream(file, true),
                    StandardCharsets.UTF_8);
            try {
                w.write(sb.toString());
                w.flush();
            } finally {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // логирование не должно ломать приложение
        }
    }
}
