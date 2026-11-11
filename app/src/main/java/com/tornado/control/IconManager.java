package com.tornado.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Несколько иконок приложения.
 *
 * Каждый вариант — activity-alias на MainActivity со своей иконкой
 * (см. AndroidManifest.xml). Варианты можно:
 *   • держать включёнными все сразу — тогда в лаунчере будет несколько иконок;
 *   • переключать — оставляем включённым только выбранный алиас.
 *
 * Переключение реализовано через PackageManager.setComponentEnabledSetting:
 * выключенный компонент просто исчезает из лаунчера, само приложение работает.
 */
public final class IconManager {

    private static final String TAG = "IconManager";

    /** id варианта → класс алиаса в манифесте. */
    private static final Map<String, String> VARIANTS = new LinkedHashMap<>();

    static {
        VARIANTS.put("default", ".LauncherDefault");
        VARIANTS.put("light", ".LauncherLight");
        VARIANTS.put("dark", ".LauncherDark");
        VARIANTS.put("beta", ".LauncherBeta");
    }

    /** Человеческие названия вариантов. */
    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("default", "Стандартная (синяя)");
        TITLES.put("light", "Светлая");
        TITLES.put("dark", "Тёмная");
        TITLES.put("beta", "Beta (оранжевая)");
        TITLES.put("all", "Все сразу (несколько иконок)");
    }

    private IconManager() {
    }

    public static String[] ids() {
        return VARIANTS.keySet().toArray(new String[0]);
    }

    public static String title(String id) {
        String t = TITLES.get(id);
        return t == null ? id : t;
    }

    /** Список вариантов в JSON (для JS и настроек). */
    public static String toJson(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, String> e : VARIANTS.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("title", title(e.getKey()));
                o.put("component", e.getValue());
                o.put("enabled", isEnabled(ctx, e.getKey()));
                arr.put(o);
            }
            JSONObject all = new JSONObject();
            all.put("id", "all");
            all.put("title", title("all"));
            all.put("component", "");
            all.put("enabled", countEnabled(ctx) > 1);
            arr.put(all);
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    /** Включён ли вариант (видна ли его иконка в лаунчере). */
    public static boolean isEnabled(Context ctx, String id) {
        if (ctx == null) return false;
        String cls = VARIANTS.get(id);
        if (cls == null) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            int state = pm.getComponentEnabledSetting(new ComponentName(ctx.getPackageName(), cls));
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Сколько вариантов сейчас включено. */
    public static int countEnabled(Context ctx) {
        int n = 0;
        for (String id : VARIANTS.keySet()) {
            if (isEnabled(ctx, id)) n++;
        }
        return n;
    }

    /** Текущий активный вариант (если включён ровно один — он и есть активный). */
    public static String current(Context ctx) {
        String found = "default";
        int n = 0;
        for (String id : VARIANTS.keySet()) {
            if (isEnabled(ctx, id)) {
                found = id;
                n++;
            }
        }
        return n == 1 ? found : (n > 1 ? "all" : found);
    }

    /**
     * Переключает иконку.
     *
     * @param variant id варианта или "all" — включить все
     * @return true, если переключение выполнено
     */
    public static boolean apply(Context ctx, String variant) {
        if (ctx == null || variant == null) return false;
        String v = variant.trim().toLowerCase(Locale.ROOT);
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            if ("all".equals(v)) {
                for (String id : VARIANTS.keySet()) {
                    set(pm, pkg, VARIANTS.get(id), true);
                }
                Log.i(TAG, "включены все варианты иконки");
                Log2File.i(TAG, "иконки: включены все варианты");
                return true;
            }
            if (!VARIANTS.containsKey(v)) return false;
            // сначала включаем нужный, потом выключаем остальные (чтобы не остаться без иконки)
            set(pm, pkg, VARIANTS.get(v), true);
            for (String id : VARIANTS.keySet()) {
                if (!id.equals(v)) set(pm, pkg, VARIANTS.get(id), false);
            }
            Log.i(TAG, "активная иконка: " + v);
            Log2File.i(TAG, "иконка переключена: " + v);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "не удалось переключить иконку", e);
            Log2File.e(TAG, "setIconVariant failed", e);
            return false;
        }
    }

    private static void set(PackageManager pm, String pkg, String cls, boolean enabled) {
        try {
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(new ComponentName(pkg, cls), state,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "setComponentEnabledSetting " + cls, e);
        }
    }
}
