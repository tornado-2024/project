package com.tornado.control;

import org.json.JSONObject;

/**
 * Удалённые настройки приложения (JSON).
 *
 * Файл по умолчанию: settings.json в репозитории проекта.
 * Пример:
 * <pre>
 * {
 *   "controlUrl":     "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/control",
 *   "indexUrl":       "",
 *   "errorPageUrl":   "",
 *   "passwordUrl":    "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/password",
 *   "settingsEnabled": false,
 *   "requirePassword": true,
 *   "title":          "Tornado",
 *   "showHeader":     true,
 *   "refreshOnStart": true,
 *   "cacheEnabled":   true,
 *   "timeoutMs":      15000,
 *   "theme":          "dark",
 *   "statusBarColor": "#171B24",
 *   "accentColor":    "#4C8DFF",
 *   "userAgent":      ""
 * }
 * </pre>
 */
public final class RemoteSettings {

    public static final String DEFAULT_CONTROL_URL =
            "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/control";
    public static final String DEFAULT_SETTINGS_URL =
            "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/settings.json";
    public static final String DEFAULT_PASSWORD_URL =
            "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/password";

    /** Откуда берётся URL индекса (указатель). */
    public String controlUrl = DEFAULT_CONTROL_URL;
    /** Откуда берутся сами удалённые настройки. */
    public String settingsUrl = DEFAULT_SETTINGS_URL;
    /** Если задан — показываем сразу его, не спрашивая control. */
    public String indexUrl = "";
    /** Адрес страницы 404 (показывается офлайн). Пусто — вычислится автоматически. */
    public String errorPageUrl = "";
    /** Где лежит хэш пароля для доступа к разделу настроек. */
    public String passwordUrl = DEFAULT_PASSWORD_URL;
    /** Показывать ли кнопку настроек в шапке. */
    public boolean settingsEnabled = false;
    /** Требовать ли пароль при открытии настроек. */
    public boolean requirePassword = true;
    /** Название страницы в верхней строке. */
    public String title = "";
    /** Показывать ли верхнюю панель. */
    public boolean showHeader = true;
    /** Обновлять ли контент при запуске приложения. */
    public boolean refreshOnStart = true;
    /** Кэшировать ли страницы на диск. */
    public boolean cacheEnabled = true;
    /** Таймаут сетевых запросов, мс. */
    public int timeoutMs = 15000;
    /** Тема: dark | light | system. */
    public String theme = "dark";
    /** Цвет статус-бара. */
    public String statusBarColor = "#171B24";
    /** Акцентный цвет. */
    public String accentColor = "#4C8DFF";
    /** Свой User-Agent (пусто — стандартный). */
    public String userAgent = "";
    /** Активная иконка приложения: default | light | dark | beta | all (пусто — не менять). */
    public String iconVariant = "";

    public static RemoteSettings defaults() {
        return new RemoteSettings();
    }

    /** Разбирает JSON. Любые неизвестные/битые поля просто игнорируются. */
    public static RemoteSettings fromJson(String json) {
        RemoteSettings s = new RemoteSettings();
        s.applyJson(json);
        return s;
    }

    /**
     * Применяет только те ключи, которые реально есть в JSON.
     * Нужно для приоритетов: по умолчанию &lt; сохранённые &lt; удалённые &lt; локальные.
     */
    public void applyJson(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("controlUrl")) controlUrl = str(o, "controlUrl", controlUrl);
            if (o.has("settingsUrl")) settingsUrl = str(o, "settingsUrl", settingsUrl);
            if (o.has("indexUrl")) indexUrl = str(o, "indexUrl", indexUrl);
            if (o.has("errorPageUrl")) errorPageUrl = str(o, "errorPageUrl", errorPageUrl);
            if (o.has("passwordUrl")) passwordUrl = str(o, "passwordUrl", passwordUrl);
            if (o.has("settingsEnabled")) settingsEnabled = bool(o, "settingsEnabled", settingsEnabled);
            if (o.has("requirePassword")) requirePassword = bool(o, "requirePassword", requirePassword);
            if (o.has("title")) title = str(o, "title", title);
            if (o.has("showHeader")) showHeader = bool(o, "showHeader", showHeader);
            if (o.has("refreshOnStart")) refreshOnStart = bool(o, "refreshOnStart", refreshOnStart);
            if (o.has("cacheEnabled")) cacheEnabled = bool(o, "cacheEnabled", cacheEnabled);
            if (o.has("timeoutMs")) timeoutMs = intv(o, "timeoutMs", timeoutMs);
            if (o.has("theme")) theme = str(o, "theme", theme);
            if (o.has("statusBarColor")) statusBarColor = str(o, "statusBarColor", statusBarColor);
            if (o.has("accentColor")) accentColor = str(o, "accentColor", accentColor);
            if (o.has("userAgent")) userAgent = str(o, "userAgent", userAgent);
            if (o.has("iconVariant")) iconVariant = str(o, "iconVariant", iconVariant);
        } catch (Exception e) {
            // битый JSON — остаются текущие значения, но сообщаем об этом в лог
            Log2File.e("Settings", "settings.json не разбирается: " + e.getMessage(), e);
            android.util.Log.w("Settings", "settings.json не разбирается", e);
        }
    }

    /** Сериализация (для сохранения последних принятых настроек офлайн). */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("controlUrl", controlUrl);
            o.put("indexUrl", indexUrl);
            o.put("errorPageUrl", errorPageUrl);
            o.put("passwordUrl", passwordUrl);
            o.put("settingsEnabled", settingsEnabled);
            o.put("requirePassword", requirePassword);
            o.put("title", title);
            o.put("showHeader", showHeader);
            o.put("refreshOnStart", refreshOnStart);
            o.put("cacheEnabled", cacheEnabled);
            o.put("timeoutMs", timeoutMs);
            o.put("theme", theme);
            o.put("statusBarColor", statusBarColor);
            o.put("accentColor", accentColor);
            o.put("userAgent", userAgent);
            o.put("iconVariant", iconVariant);
            o.put("settingsUrl", settingsUrl);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(JSONObject o, String key, String def) {
        try {
            String v = o.optString(key, null);
            return (v == null || v.trim().isEmpty()) ? def : v.trim();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JSONObject o, String key, boolean def) {
        try {
            if (!o.has(key)) return def;
            Object v = o.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            String s = String.valueOf(v).trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int intv(JSONObject o, String key, int def) {
        try {
            if (!o.has(key)) return def;
            return o.optInt(key, def);
        } catch (Exception e) {
            return def;
        }
    }
}
