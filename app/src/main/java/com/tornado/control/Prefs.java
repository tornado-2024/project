package com.tornado.control;

import android.content.Context;
import android.content.SharedPreferences;

/** Локальные настройки и кэш последних удалённых настроек. */
public final class Prefs {

    private static final String FILE = "control_prefs";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /* ------------------------ локальные переопределения ------------------- */

    /** Адрес control-эндпоинта (переопределение удалённого). */
    public String controlUrlOverride() {
        return sp.getString("control_url", "");
    }

    public void setControlUrlOverride(String v) {
        sp.edit().putString("control_url", v == null ? "" : v.trim()).apply();
    }

    /** Адрес файла удалённых настроек. */
    public String settingsUrlOverride() {
        return sp.getString("settings_url", "");
    }

    public void setSettingsUrlOverride(String v) {
        sp.edit().putString("settings_url", v == null ? "" : v.trim()).apply();
    }

    /** Локальный заголовок (пусто = брать из удалённых настроек). */
    public String titleOverride() {
        return sp.getString("title", "");
    }

    public void setTitleOverride(String v) {
        sp.edit().putString("title", v == null ? "" : v.trim()).apply();
    }

    /** Показывать ли раздел настроек принудительно (пусто = по удалённым настройкам). */
    public boolean forceSettingsVisible() {
        return sp.getBoolean("settings_visible", false);
    }

    public void setForceSettingsVisible(boolean v) {
        sp.edit().putBoolean("settings_visible", v).apply();
    }

    /* ------------------------ последние удалённые настройки --------------- */

    public String lastRemoteSettingsJson() {
        return sp.getString("remote_settings", "");
    }

    public void setLastRemoteSettingsJson(String v) {
        sp.edit().putString("remote_settings", v == null ? "" : v).apply();
    }

    /** URL индекса, полученный из control (для офлайна). */
    public String lastIndexUrl() {
        return sp.getString("last_index_url", "");
    }

    public void setLastIndexUrl(String v) {
        sp.edit().putString("last_index_url", v == null ? "" : v).apply();
    }

    /** Адрес страницы 404 (для офлайна). */
    public String lastErrorPageUrl() {
        return sp.getString("last_error_url", "");
    }

    public void setLastErrorPageUrl(String v) {
        sp.edit().putString("last_error_url", v == null ? "" : v).apply();
    }

    public long lastUpdate() {
        return sp.getLong("last_update", 0L);
    }

    public void setLastUpdate(long v) {
        sp.edit().putLong("last_update", v).apply();
    }
}
