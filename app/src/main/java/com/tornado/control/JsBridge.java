package com.tornado.control;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Мост JavaScript -> Android.
 *
 * В WebView объект доступен как {@code window.Android} и {@code window.Native}.
 * Пример: <pre>
 *   Android.vibrate(120);
 *   Android.vibratePattern("[0,40,80,40]", -1);
 *   Android.toast("Привет из JS");
 * </pre>
 */
public class JsBridge {

    public static final String TAG = "TornadoBridge";
    private static final String CHANNEL_ID = "control_bridge";

    private final MainActivity act;

    public JsBridge(MainActivity act) {
        this.act = act;
    }

    /* ------------------------------------------------------------------ */
    /* Вибрация                                                            */
    /* ------------------------------------------------------------------ */

    /** Вибрация указанной длительности (мс). */
    @JavascriptInterface
    public void vibrate(long milliseconds) {
        long ms = Math.max(0, Math.min(5000, milliseconds));
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    /**
     * Вибрация по паттерну. pattern — JSON-массив вида "[0,50,120,50]" (мс),
     * где чётные элементы — паузы, нечётные — вибрация. repeat = -1 — повторять бесконечно.
     */
    @JavascriptInterface
    public boolean vibratePattern(String pattern, int repeat) {
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return false;
        try {
            long[] steps = parsePattern(pattern);
            if (steps.length == 0) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(steps, repeat));
            } else {
                //noinspection deprecation
                v.vibrate(steps, repeat);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "vibratePattern failed", e);
            return false;
        }
    }

    /** Готовые эффекты: click, tick, heavy, notification, success, error. */
    @JavascriptInterface
    public boolean vibrateEffect(String type) {
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            vibrate(60);
            return true;
        }
        try {
            int amp = VibrationEffect.DEFAULT_AMPLITUDE;
            long[] timings;
            String t = type == null ? "click" : type.toLowerCase(Locale.ROOT);
            switch (t) {
                case "tick":
                    timings = new long[]{10, 60, 20};
                    amp = 60;
                    break;
                case "heavy":
                    timings = new long[]{0, 120};
                    amp = 255;
                    break;
                case "notification":
                    timings = new long[]{0, 60, 80, 60};
                    amp = 200;
                    break;
                case "success":
                    timings = new long[]{0, 40, 90, 60};
                    amp = 180;
                    break;
                case "error":
                    timings = new long[]{0, 120, 60, 120};
                    amp = 255;
                    break;
                case "click":
                default:
                    timings = new long[]{0, 25};
                    amp = 120;
                    break;
            }
            v.vibrate(VibrationEffect.createWaveform(timings, amp));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "vibrateEffect failed", e);
            return false;
        }
    }

    /** Отключить вибрацию. */
    @JavascriptInterface
    public void cancelVibration() {
        Vibrator v = vibrator();
        if (v != null) {
            try {
                v.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    /** Есть ли на устройстве вибрировать мотор. */
    @JavascriptInterface
    public boolean hasVibrator() {
        Vibrator v = vibrator();
        return v != null && v.hasVibrator();
    }

    private Vibrator vibrator() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) act.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm == null ? null : vm.getDefaultVibrator();
            }
            return (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private long[] parsePattern(String json) {
        if (json == null || json.trim().isEmpty()) return new long[0];
        try {
            JSONArray arr = new JSONArray(json);
            long[] out = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                out[i] = Math.max(0, arr.optLong(i, 0));
            }
            return out;
        } catch (Exception e) {
            return new long[0];
        }
    }

    /* ------------------------------------------------------------------ */
    /* Интерфейс                                                           */
    /* ------------------------------------------------------------------ */

    /** Всплывающее уведомление (Toast). */
    @JavascriptInterface
    public void toast(final String message) {
        final String msg = message == null ? "" : message;
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }

    /** Меняет заголовок приложения в верхней панели. */
    @JavascriptInterface
    public void setTitle(final String title) {
        act.runOnUiThread(() -> act.setAppTitle(title == null ? "" : title));
    }

    /** Меняет цвет статус-бара (hex, например "#10131A"). */
    @JavascriptInterface
    public void setStatusBarColor(final String hex) {
        final int color = parseColor(hex, 0xFF10131A);
        act.runOnUiThread(() -> {
            try {
                Window w = act.getWindow();
                w.setStatusBarColor(color);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    w.setNavigationBarColor(color);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Открыть внешнюю ссылку в системном браузере. */
    @JavascriptInterface
    public void openUrl(final String url) {
        if (url == null || url.trim().isEmpty()) return;
        act.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Exception e) {
                Toast.makeText(act, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Копировать текст в буфер обмена. */
    @JavascriptInterface
    public boolean copy(final String text) {
        if (text == null) return false;
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("TornadoControl", text));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Системный диалог «Поделиться». */
    @JavascriptInterface
    public void share(final String text, final String title) {
        final String t = text == null ? "" : text;
        act.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t);
                if (title != null) i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(Intent.createChooser(i, title == null ? "Поделиться" : title));
            } catch (Exception ignored) {
            }
        });
    }

    /** Системное уведомление. */
    @JavascriptInterface
    public void notify(final String title, final String text) {
        final String t = title == null ? "" : title;
        final String b = text == null ? "" : text;
        act.runOnUiThread(() -> act.showNotification(t, b));
    }

    /* ------------------------------------------------------------------ */
    /* Состояние приложения                                                 */
    /* ------------------------------------------------------------------ */

    /** Информация об устройстве и приложении (JSON). */
    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("brand", Build.BRAND);
            o.put("androidVersion", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("package", act.getPackageName());
            o.put("versionName", act.getAppVersionName());
            o.put("versionCode", act.getAppVersionCode());
            o.put("online", Net.isOnline(act));
            o.put("controlUrl", act.getSettings().controlUrl);
            o.put("errorPageUrl", act.getPrefs().lastErrorPageUrl());
            o.put("cacheSize", act.getCacheSizeBytes());
            o.put("cacheEntries", act.getCacheEntriesCount());
            o.put("bridge", "Android");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Есть ли сеть прямо сейчас. */
    @JavascriptInterface
    public boolean isOnline() {
        return Net.isOnline(act);
    }

    /** Текущий адрес control-эндпоинта. */
    @JavascriptInterface
    public String getControlUrl() {
        return act.getSettings().controlUrl;
    }

    /** Адрес сохранённой страницы 404. */
    @JavascriptInterface
    public String getErrorPageUrl() {
        return act.getPrefs().lastErrorPageUrl();
    }

    /** Действующие удалённые настройки (JSON). */
    @JavascriptInterface
    public String getSettings() {
        try {
            return act.getSettings().toJson();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** URL индексной страницы, полученный из control. */
    @JavascriptInterface
    public String getIndexUrl() {
        return act.getPrefs().lastIndexUrl();
    }

    /** Перезагрузить текущую страницу из сети. */
    @JavascriptInterface
    public void reload() {
        act.runOnUiThread(act::reloadCurrent);
    }

    /** Полностью обновить контент с сервера (control + 404). */
    @JavascriptInterface
    public void refresh() {
        act.runOnUiThread(act::refresh);
    }

    /** Открыть диалог настроек. */
    @JavascriptInterface
    public void showSettings() {
        act.runOnUiThread(act::showSettingsDialog);
    }

    /** Записать сообщение в logcat. */
    @JavascriptInterface
    public void log(String message) {
        Log.i(TAG, message == null ? "" : message);
        Log2File.i("JS", message == null ? "" : message);
    }

    /** Закрыть приложение. */
    @JavascriptInterface
    public void exitApp() {
        act.runOnUiThread(act::finish);
    }

    /* ------------------------------------------------------------------ */

    private static int parseColor(String hex, int def) {
        if (hex == null) return def;
        String h = hex.trim();
        if (!h.startsWith("#")) h = "#" + h;
        try {
            return Color.parseColor(h);
        } catch (Exception e) {
            return def;
        }
    }
}
