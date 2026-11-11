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

    public static RemoteSettings defaults() {
        return new RemoteSettings();
    }

    /** Разбирает JSON. Любые неизвестные/битые поля просто игнорируются. */
    public static RemoteSettings fromJson(String json) {
        RemoteSettings s = new RemoteSettings();
        if (json == null || json.trim().isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.controlUrl = str(o, "controlUrl", s.controlUrl);
            s.indexUrl = str(o, "indexUrl", s.indexUrl);
            s.errorPageUrl = str(o, "errorPageUrl", s.errorPageUrl);
            s.passwordUrl = str(o, "passwordUrl", s.passwordUrl);
            s.settingsEnabled = bool(o, "settingsEnabled", s.settingsEnabled);
            s.requirePassword = bool(o, "requirePassword", s.requirePassword);
            s.title = str(o, "title", s.title);
            s.showHeader = bool(o, "showHeader", s.showHeader);
            s.refreshOnStart = bool(o, "refreshOnStart", s.refreshOnStart);
            s.cacheEnabled = bool(o, "cacheEnabled", s.cacheEnabled);
            s.timeoutMs = intv(o, "timeoutMs", s.timeoutMs);
            s.theme = str(o, "theme", s.theme);
            s.statusBarColor = str(o, "statusBarColor", s.statusBarColor);
            s.accentColor = str(o, "accentColor", s.accentColor);
            s.userAgent = str(o, "userAgent", s.userAgent);
            s.settingsUrl = str(o, "settingsUrl", s.settingsUrl);
        } catch (Exception ignored) {
            // битый JSON — остаются значения по умолчанию
        }
        return s;
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
