package com.tornado.control;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



/**
 * Главный экран приложения.
 *
 * Схема работы:
 *  1. Загружаются удалённые настройки (JSON) — см. {@link RemoteSettings}.
 *  2. Скачивается «control» — это указатель (URL) на индексную страницу.
 *  3. Скачивается и показывается индексная страница.
 *  4. Скачивается и кэшируется страница 404 — она показывается, когда сети нет.
 *  5. Раздел настроек скрыт по умолчанию и открывается по паролю
 *     (хэш лежит в файле password, путь задаётся настройкой passwordUrl).
 */
public class MainActivity extends Activity {

    private static final String TAG = "TornadoControl";
    private static final String CHANNEL_ID = "control_bridge";
    /** android.net.conn.CONNECTIVITY_CHANGE (в API 33 константа скрыта). */
    private static final String ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";
    /** Префикс локальных ресурсов из assets. */
    private static final String ASSET_PREFIX = "file:///android_asset/";
    /** Сколько тапов по заголовку открывает скрытый доступ к настройкам. */
    private static final int HIDDEN_TAPS = 5;

    private WebView web;
    private View topBar;
    private TextView titleView;
    private TextView statusView;
    private TextView badgeView;
    private ProgressBar progress;
    private ImageButton btnSettings;

    private Prefs prefs;
    private CacheStore cache;
    /** Добавлен ли JS-мост в WebView. */
    private boolean bridgeInstalled = false;

    /** Действующие удалённые настройки. */
    private RemoteSettings settings = RemoteSettings.defaults();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Предзагруженное тело главной страницы — чтобы не качать её дважды. */
    private volatile byte[] pendingMain;
    private volatile String pendingMainUrl;
    private volatile String pendingMainContentType;

    private boolean offlineMode = false;
    /** Настройки с сервера получены (до этого шестерёнку не показываем). */
    private volatile boolean remoteSettingsReady = false;
    /** Состояние сети на момент последней проверки — чтобы не дублировать загрузку. */
    private boolean wasOnline = true;

    /** Счётчик тапов для скрытого доступа к настройкам. */
    private int tapCount;
    private long lastTapTime;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initApp();
        } catch (Throwable t) {
            // Вместо «вылета» показываем текст ошибки прямо на экране.
            Log.e(TAG, "Ошибка при запуске", t);
            renderFatal(t);
        }
    }

    private void initApp() {
        prefs = new Prefs(this);
        cache = new CacheStore(this);
        Log2File.init(this);
        Log2File.i(TAG, "старт приложения, debug=" + Log2File.isEnabled()
                + (Log2File.path() == null ? "" : ", лог: " + Log2File.path()));

        setContentView(R.layout.activity_main);
        topBar = findViewById(R.id.topbar);
        titleView = findViewById(R.id.title);
        statusView = findViewById(R.id.status);
        badgeView = findViewById(R.id.badge);
        progress = findViewById(R.id.progress);
        web = findViewById(R.id.web);
        // В layout это ImageButton (наследник ImageView), а не Button — каст неверный бы ломался
        ImageButton refreshBtn = findViewById(R.id.btn_refresh);
        btnSettings = findViewById(R.id.btn_settings);
        refreshBtn.setOnClickListener(v -> refresh());
        btnSettings.setOnClickListener(v -> openSettings());

        // Скрытый доступ к настройкам: 5 быстрых тапов по заголовку
        titleView.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTapTime > 3000) tapCount = 0;
            lastTapTime = now;
            tapCount++;
            if (tapCount >= HIDDEN_TAPS) {
                tapCount = 0;
                openSettings();
            }
        });

        setupWebView();
        registerNetworkReceiver();
        refresh();
    }

    /** Применяет «прочие» настройки: полный экран, экран, зум, ссылки, таймер, кэш, мост. */
    private void applyExtraSettings(RemoteSettings s) {
        try {
            // полноэкранный режим
            if (s.fullscreen) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            // не гасить экран
            if (s.keepScreenOn) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            // нижняя строка статуса приложения
            statusView.setVisibility(s.hideStatusLine ? View.GONE : View.VISIBLE);
            // масштабирование страницы
            web.getSettings().setSupportZoom(s.allowZoom);
            web.getSettings().setBuiltInZoomControls(s.allowZoom);
            web.getSettings().setDisplayZoomControls(false);
            // JS-мост
            if (s.enableBridge) {
                if (!bridgeInstalled) {
                    JsBridge bridge = new JsBridge(this);
                    web.addJavascriptInterface(bridge, "Android");
                    web.addJavascriptInterface(bridge, "Native");
                    bridgeInstalled = true;
                }
            } else if (bridgeInstalled) {
                web.removeJavascriptInterface("Android");
                web.removeJavascriptInterface("Native");
                bridgeInstalled = false;
            }
            // лимит кэша
            if (s.maxCacheSizeMb > 0) {
                cache.setMaxBytes(s.maxCacheSizeMb * 1024L * 1024L);
            }
            // автообновление
            scheduleAutoRefresh(s.refreshIntervalSec);
        } catch (Exception e) {
            Log.w(TAG, "applyExtraSettings failed", e);
        }
    }

    /** Таймер автообновления контента. */
    private void scheduleAutoRefresh(final int seconds) {
        ui.removeCallbacks(autoRefresh);
        if (seconds <= 0) return;
        ui.postDelayed(autoRefresh, seconds * 1000L);
    }

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            RemoteSettings s = settings;
            int sec = s == null ? 0 : s.refreshIntervalSec;
            if (sec > 0) {
                refresh();
                ui.postDelayed(this, sec * 1000L);
            }
        }
    };

    /**
     * Переключает иконку приложения: запоминает выбор (он важнее удалённых настроек)
     * и мягко перезапускает активити — отключение компонента, на котором мы запущены,
     * иначе выглядит как падение.
     */
    public void setIconVariant(final String variant) {
        final String v = variant == null ? "" : variant.trim();
        ui.post(() -> {
            boolean ok = IconManager.apply(this, v);
            if (!"all".equals(v)) {
                prefs.setIconVariant(v);
            } else {
                prefs.setIconVariant("");
            }
            Toast.makeText(this, ok ? "Иконка: " + IconManager.title(v) : "Не удалось сменить иконку",
                    Toast.LENGTH_SHORT).show();
            // перезапускаем активити, чтобы переход выглядел плавно
            // (отключение компонента, на котором мы запущены, убивает текущую задачу)
            ui.postDelayed(() -> {
                try {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                } catch (Exception ignored) {
                }
                finish();
            }, 200);
        });
    }

    /** Сохраняет переключатели поведения в локальный переопределитель настроек. */
    private void saveLocalExtra(boolean fullscreen, boolean keepScreenOn, boolean allowZoom,
                                boolean openLinksExternally, boolean enableBridge, int refreshSec) {
        try {
            RemoteSettings cur = RemoteSettings.fromJson(prefs.localSettingsJson());
            cur.fullscreen = fullscreen;
            cur.keepScreenOn = keepScreenOn;
            cur.allowZoom = allowZoom;
            cur.openLinksExternally = openLinksExternally;
            cur.enableBridge = enableBridge;
            cur.refreshIntervalSec = refreshSec;
            prefs.setLocalSettingsJson(cur.toJson());
        } catch (Exception e) {
            Log.w(TAG, "saveLocalExtra failed", e);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Показывает стектрейс ошибки вместо падения приложения. */
    private void renderFatal(Throwable t) {
        try {
            TextView tv = new TextView(this);
            tv.setTextSize(11);
            tv.setPadding(32, 32, 32, 32);
            tv.setTextColor(0xFFFFB74D);
            tv.setText("Ошибка при запуске:\n\n" + Log.getStackTraceString(t));
            setContentView(tv);
        } catch (Throwable ignore) {
            try {
                Toast.makeText(this, String.valueOf(t), Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // ничего не осталось сделать
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* WebView                                                              */
    /* ------------------------------------------------------------------ */

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF0E1116);

        // мост по умолчанию включён; настройка enableBridge может его убрать позже
        JsBridge bridge = new JsBridge(this);
        web.addJavascriptInterface(bridge, "Android");
        web.addJavascriptInterface(bridge, "Native");
        bridgeInstalled = true;

        web.setWebViewClient(new AppWebViewClient());
        web.setWebChromeClient(new AppWebChromeClient());
    }

    private class AppWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl() != null ? request.getUrl().toString() : "";
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            return fetchThroughCache(url, request.isForMainFrame());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl() != null ? request.getUrl().toString() : "";
            return handleUrl(url);
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (offlineMode) {
                setStatus("Оффлайн · показана сохранённая страница", true);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectBridge();
            syncTitle(view.getTitle());
        }
    }

    private class AppWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            String msg = "JS[" + cm.messageLevel() + "] " + cm.message() + " @" + cm.lineNumber();
            Log.i(TAG, msg);
            Log2File.i("JS", cm.message() + "  (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
            return true;
        }
    }

    /** Отдельные схемы (intent://, tel:, mailto:, market:) уходят в систему. */
    private boolean handleUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("intent://")) {
            try {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                actStart(i);
            } catch (Exception ignored) {
            }
            return true;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tel:") || lower.startsWith("mailto:") || lower.startsWith("market:")
                || lower.startsWith("tg:") || lower.startsWith("whatsapp:")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {
            }
            return true;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // белый список доменов: пусто — разрешены все
            String hosts = settings == null ? "" : settings.allowedHosts;
            if (hosts != null && !hosts.trim().isEmpty() && !hostAllowed(url, hosts)) {
                openInBrowser(url);
                return true;
            }
            // внешние ссылки — в системный браузер
            if (settings != null && settings.openLinksExternally
                    && !url.equals(pendingMainUrl) && !url.equals(prefs.lastIndexUrl())) {
                openInBrowser(url);
                return true;
            }
            web.loadUrl(url);
            return true;
        }
        return false;
    }

    /** Разрешён ли домен по белому списку (через запятую). */
    private boolean hostAllowed(String url, String list) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            for (String h : list.split(",")) {
                String t = h.trim().toLowerCase();
                if (t.isEmpty()) continue;
                if (host.equalsIgnoreCase(t) || host.toLowerCase().endsWith("." + t)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void openInBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void actStart(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /* Сеть + кэш                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Отдаёт ресурс: из сети (и кладёт в кэш) или из кэша, если сети нет.
     */
    private WebResourceResponse fetchThroughCache(String url, boolean mainFrame) {
        CacheStore.Entry cached = cache.get(url);

        if (!Net.isOnline(this)) {
            if (cached != null) return toResponse(cached, 200);
            if (mainFrame) {
                CacheStore.Entry err = cache.get(prefs.lastErrorPageUrl());
                if (err != null) return toResponse(err, 200);
            }
            return blocked();
        }

        // тело главной страницы уже скачано нативно — отдаём его без повторного запроса
        if (url.equals(pendingMainUrl) && pendingMain != null) {
            byte[] body = pendingMain;
            pendingMain = null;
            pendingMainUrl = null;
            String mime = HttpClient.mimeOf(pendingMainContentType);
            String cs = HttpClient.charsetOf(pendingMainContentType);
            if (settings.cacheEnabled) cache.put(url, 200, mime, cs, body);
            return new WebResourceResponse(mime, cs, 200, "OK", new HashMap<>(),
                    new ByteArrayInputStream(body));
        }

        HttpClient.Resp r = HttpClient.get(url, settings.timeoutMs, settings.userAgent);
        if (r.isOk()) {
            String mime = HttpClient.mimeOf(r.contentType);
            String cs = HttpClient.charsetOf(r.contentType);
            if (settings.cacheEnabled) cache.put(url, r.status, mime, cs, r.body);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, cs, r.status, "OK", headers,
                    new ByteArrayInputStream(r.body));
        }
        if (cached != null) return toResponse(cached, 200);
        if (mainFrame) {
            CacheStore.Entry err = cache.get(prefs.lastErrorPageUrl());
            if (err != null) return toResponse(err, 200);
        }
        return blocked();
    }

    private WebResourceResponse toResponse(CacheStore.Entry e, int status) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(e.mime, e.charset, status, "OK", headers,
                new ByteArrayInputStream(e.body == null ? new byte[0] : e.body));
    }

    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 504, "Offline", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    /* ------------------------------------------------------------------ */
    /* Основной сценарий: настройки -> control -> индекс                    */
    /* ------------------------------------------------------------------ */

    public void refresh() {
        // Локальный режим (для тестов и офлайн-демо): file:///android_asset/app/demo.html
        final String localControl = prefs.controlUrlOverride();
        if (localControl.startsWith(ASSET_PREFIX)) {
            final String assetPath = localControl.substring(ASSET_PREFIX.length());
            final String html = readAsset(assetPath);
            progress.setVisibility(View.GONE);
            setOffline(false);
            if (html == null) {
                setStatus("Ассет не найден: " + assetPath, true);
                showFallback("asset not found", false);
                return;
            }
            Log2File.i(TAG, "локальный режим: " + assetPath);
            web.loadDataWithBaseURL(localControl, html, "text/html", "utf-8", null);
            setStatus("Локальный режим · " + assetPath, false);
            return;
        }

        Log2File.i(TAG, "refresh: control=" + localControl);
        setStatus("Загрузка…", false);
        progress.setVisibility(View.VISIBLE);

        io.execute(() -> {
            RemoteSettings s = loadRemoteSettings();

            // 1. control — указатель на индексную страницу
            String control = s.controlUrl;
            String override = prefs.controlUrlOverride();
            if (!override.isEmpty()) control = override;

            String indexUrl = s.indexUrl;
            if (indexUrl.isEmpty() && !control.isEmpty()) {
                HttpClient.Resp ctrl = HttpClient.get(control, s.timeoutMs, s.userAgent, s.retryCount);
                if (ctrl.isOk()) {
                    if (s.cacheEnabled) {
                        cache.put(control, ctrl.status, HttpClient.mimeOf(ctrl.contentType),
                                HttpClient.charsetOf(ctrl.contentType), ctrl.body);
                    }
                    indexUrl = extractIndexUrl(ctrl.body, ctrl.contentType);
                }
            }
            if (indexUrl.isEmpty()) indexUrl = prefs.lastIndexUrl();

            // 2. страница 404 — сохраняется для офлайна
            String errorUrl = resolveErrorUrl(s, indexUrl);
            if (!errorUrl.isEmpty()) {
                HttpClient.Resp e = HttpClient.get(errorUrl, s.timeoutMs, s.userAgent, s.retryCount);
                if (e.isOk() && s.cacheEnabled) {
                    cache.put(errorUrl, e.status, HttpClient.mimeOf(e.contentType),
                            HttpClient.charsetOf(e.contentType), e.body);
                    prefs.setLastErrorPageUrl(errorUrl);
                }
            }

            // 3. хэш пароля для раздела настроек
            if (!s.passwordUrl.isEmpty()) {
                HttpClient.Resp p = HttpClient.get(s.passwordUrl, s.timeoutMs, s.userAgent, s.retryCount);
                if (p.isOk() && s.cacheEnabled) {
                    cache.put(s.passwordUrl, p.status, HttpClient.mimeOf(p.contentType),
                            HttpClient.charsetOf(p.contentType), p.body);
                }
            }

            // 4. сама индексная страница
            HttpClient.Resp index = null;
            if (!indexUrl.isEmpty()) {
                index = HttpClient.get(indexUrl, s.timeoutMs, s.userAgent, s.retryCount);
            }

            final RemoteSettings fs = s;
            final String fIndexUrl = indexUrl;
            final String fErrorUrl = errorUrl;
            final HttpClient.Resp fIndex = index;

            ui.post(() -> {
                progress.setVisibility(View.GONE);
                applySettings(fs);
                Log2File.i(TAG, "настройки приняты: control=" + fs.controlUrl
                        + ", индекс=" + fIndexUrl + ", раздел настроек=" + fs.settingsEnabled
                        + ", пароль=" + fs.passwordUrl);
                if (!fIndexUrl.isEmpty()) prefs.setLastIndexUrl(fIndexUrl);
                if (!fErrorUrl.isEmpty()) prefs.setLastErrorPageUrl(fErrorUrl);

                if (fIndex != null && fIndex.isOk()) {
                    Log2File.i(TAG, "индекс загружен: HTTP " + fIndex.status
                            + ", " + fIndex.body.length + " байт");
                    if (fs.cacheEnabled) {
                        cache.put(fIndexUrl, fIndex.status, HttpClient.mimeOf(fIndex.contentType),
                                HttpClient.charsetOf(fIndex.contentType), fIndex.body);
                    }
                    prefs.setLastUpdate(System.currentTimeMillis());
                    setOffline(false);
                    pendingMain = fIndex.body;
                    pendingMainUrl = fIndexUrl;
                    pendingMainContentType = fIndex.contentType;
                    web.loadUrl(fIndexUrl);
                    setStatus("Онлайн · обновлено " + nowTime(), false);
                    return;
                }

                // Сети нет или сервер ответил ошибкой — отдаём кэш/404-заглушку
                CacheStore.Entry cached = cache.get(fIndexUrl);
                if (cached != null && cached.body != null && cached.body.length > 0) {
                    setOffline(true);
                    pendingMain = cached.body;
                    pendingMainUrl = fIndexUrl;
                    pendingMainContentType = cached.mime;
                    web.loadUrl(fIndexUrl);
                    setStatus("Оффлайн · показана сохранённая страница", true);
                    return;
                }
                String reason = (fIndex == null)
                        ? "нет соединения"
                        : (fIndex.networkError ? "нет соединения" : ("HTTP " + fIndex.status));
                Log2File.i(TAG, "индекс недоступен: " + reason);
                showFallback(reason, fIndex == null || fIndex.networkError);
            });
        });
    }

    /** Загружает удалённые настройки; при отсутствии сети — последние принятые. */
    private RemoteSettings loadRemoteSettings() {
        RemoteSettings s = RemoteSettings.defaults();
        s.applyJson(prefs.lastRemoteSettingsJson());

        String url = prefs.settingsUrlOverride();
        if (url.isEmpty()) url = s.settingsUrl;
        if (url != null && !url.isEmpty()) {
            HttpClient.Resp r = HttpClient.get(url, 10000, s.userAgent);
            if (r.status >= 0) {
                // сервер ответил — настройки инициализированы (даже если файла нет)
                remoteSettingsReady = true;
            }
            if (r.isOk()) {
                RemoteSettings fresh = RemoteSettings.defaults();
                fresh.applyJson(r.text());
                fresh.settingsUrl = url;
                prefs.setLastRemoteSettingsJson(fresh.toJson());
                s = fresh;
            }
        }
        // локальные значения (со страницы настроек) имеют приоритет
        s.applyJson(prefs.localSettingsJson());
        return s;
    }

    /**
     * Применяет настройки, присланные со страницы настроек (JS → Android).
     * Приоритет: локальные > удалённые > сохранённые > значения по умолчанию.
     */
    public boolean applyLocalSettings(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            RemoteSettings test = RemoteSettings.fromJson(json.trim());
            if (test == null) return false;
            prefs.setLocalSettingsJson(json.trim());
            if (test.iconVariant != null && !test.iconVariant.trim().isEmpty()) {
                prefs.setIconVariant(test.iconVariant.trim());
            }
            final RemoteSettings s = test;
            ui.post(() -> {
                applySettings(s);
                refresh();
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "applyLocalSettings failed", e);
            return false;
        }
    }

    /** Сбрасывает локальные переопределения настроек. */
    public void clearLocalSettings() {
        prefs.setLocalSettingsJson("");
        ui.post(this::refresh);
    }

    /** Адрес страницы 404: из настроек, иначе — относительно индекса, иначе — control + /errors/404/. */
    private String resolveErrorUrl(RemoteSettings s, String indexUrl) {
        if (s.errorPageUrl != null && !s.errorPageUrl.trim().isEmpty()) {
            return s.errorPageUrl.trim();
        }
        if (indexUrl != null && !indexUrl.trim().isEmpty()) {
            String base = indexUrl.trim();
            if (base.endsWith("/")) return base + "errors/404/";
            int slash = base.lastIndexOf('/');
            if (slash > 0) return base.substring(0, slash + 1) + "errors/404/";
        }
        String control = s.controlUrl;
        String override = prefs.controlUrlOverride();
        if (!override.isEmpty()) control = override;
        if (control == null || control.isEmpty()) return "";
        return control + "/errors/404/";
    }

    /**
     * Из ответа control достаём URL индексной страницы.
     * Логика вынесена в {@link Pointer} (там же и тесты).
     */
    static String extractIndexUrl(byte[] body, String contentType) {
        return Pointer.extractUrl(body, contentType);
    }

    /** Применяет удалённые настройки к интерфейсу. */
    private void applySettings(RemoteSettings s) {
        this.settings = s;

        topBar.setVisibility(s.showHeader ? View.VISIBLE : View.GONE);

        // шестерёнка появляется только после инициализации настроек с сервера
        boolean visible = remoteSettingsReady && (s.settingsEnabled || prefs.forceSettingsVisible());
        btnSettings.setVisibility(visible ? View.VISIBLE : View.GONE);

        String title = prefs.titleOverride();
        if (title.isEmpty()) title = s.title;
        if (!title.isEmpty()) titleView.setText(title);

        boolean light = "light".equalsIgnoreCase(s.theme);
        topBar.setBackgroundColor(light ? 0xFFF2F4F8 : 0xFF171B24);
        titleView.setTextColor(light ? 0xFF14181F : 0xFFE6EAF2);
        statusView.setBackgroundColor(light ? 0xFFF2F4F8 : 0xFF171B24);

        int sb = parseColor(s.statusBarColor, light ? 0xFFF2F4F8 : 0xFF171B24);
        try {
            getWindow().setStatusBarColor(sb);
        } catch (Exception ignored) {
        }
        try {
            int accent = parseColor(s.accentColor, 0xFF4C8DFF);
            btnSettings.setColorFilter(accent);
        } catch (Exception ignored) {
        }

        // --- новые настройки ---
        applyExtraSettings(s);

        // иконка приложения: локальный выбор пользователя важнее удалённой настройки
        String localIcon = prefs.iconVariant();
        String remoteIcon = s.iconVariant == null ? "" : s.iconVariant.trim();
        String wantIcon = !localIcon.isEmpty() ? localIcon : remoteIcon;
        if (!wantIcon.isEmpty() && !wantIcon.equals(IconManager.current(this))) {
            IconManager.apply(this, wantIcon);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Раздел настроек (защищён паролем)                                   */
    /* ------------------------------------------------------------------ */

    /** Открывает настройки: при необходимости сначала спрашивает пароль. */
    private void openSettings() {
        if (passwordRequired()) {
            requestPassword(this::showSettingsDialog);
        } else {
            showSettingsDialog();
        }
    }

    /** Нужен ли пароль: файл password существует и requirePassword = true. */
    private boolean passwordRequired() {
        if (!settings.requirePassword) return false;
        if (settings.passwordUrl == null || settings.passwordUrl.isEmpty()) return false;
        CacheStore.Entry e = cache.get(settings.passwordUrl);
        return e != null && e.body != null && e.body.length > 0;
    }

    /** Диалог ввода пароля. */
    private void requestPassword(final Runnable onOk) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Пароль");

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Пароль")
                .setMessage("Введите пароль для доступа к настройкам")
                .setView(input)
                .setPositiveButton("ОК", null)
                .setNegativeButton("Отмена", null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (verifyPassword(input.getText().toString())) {
                    dlg.dismiss();
                    onOk.run();
                } else {
                    input.setText("");
                    Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dlg.show();
    }

    /** Сверяет введённый пароль с хэшем из файла password. */
    boolean verifyPassword(String input) {
        if (settings.passwordUrl == null || settings.passwordUrl.isEmpty()) return true;
        CacheStore.Entry e = cache.get(settings.passwordUrl);
        if (e == null || e.body == null || e.body.length == 0) return true;
        return Pointer.verify(new String(e.body, StandardCharsets.UTF_8), input);
    }

    private static String digest(String alg, String text) {
        return Pointer.digest(alg, text);
    }

    public void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, dp(8));

        box.addView(label("Адрес control (указатель на индекс):"));
        EditText controlInput = new EditText(this);
        controlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        controlInput.setText(prefs.controlUrlOverride().isEmpty()
                ? settings.controlUrl : prefs.controlUrlOverride());
        controlInput.setSingleLine(false);
        controlInput.setMinLines(2);
        box.addView(controlInput);

        box.addView(label("Адрес файла удалённых настроек (JSON):"));
        EditText settingsInput = new EditText(this);
        settingsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        settingsInput.setText(prefs.settingsUrlOverride().isEmpty()
                ? settings.settingsUrl : prefs.settingsUrlOverride());
        settingsInput.setSingleLine(false);
        settingsInput.setMinLines(2);
        box.addView(settingsInput);

        box.addView(label("Заголовок (пусто = из удалённых настроек):"));
        EditText titleInput = new EditText(this);
        titleInput.setText(prefs.titleOverride());
        titleInput.setSingleLine(true);
        box.addView(titleInput);

        CheckBox alwaysVisible = new CheckBox(this);
        alwaysVisible.setChecked(prefs.forceSettingsVisible());
        alwaysVisible.setText("Всегда показывать кнопку настроек");
        box.addView(alwaysVisible);

        TextView info = new TextView(this);
        info.setTextSize(12);
        info.setPadding(0, dp(8), 0, dp(4));
        box.addView(info);
        updateInfo(info);

        Button reset = new Button(this);
        reset.setText("Сбросить локальные переопределения");
        reset.setOnClickListener(v -> {
            prefs.setControlUrlOverride("");
            prefs.setSettingsUrlOverride("");
            prefs.setTitleOverride("");
            prefs.setForceSettingsVisible(false);
            Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show();
            refresh();
        });
        box.addView(reset);

        box.addView(label("Иконка приложения (в лаунчере):"));
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.VERTICAL);
        String cur = IconManager.current(this);
        TextView curIcon = new TextView(this);
        curIcon.setTextSize(12);
        curIcon.setPadding(0, 0, 0, dp(4));
        curIcon.setText("Сейчас: " + IconManager.title(cur)
                + " (включено вариантов: " + IconManager.countEnabled(this) + ")");
        icons.addView(curIcon);
        for (String id : IconManager.ids()) {
            Button b = new Button(this);
            b.setText(IconManager.title(id) + (id.equals(cur) ? "  ✓" : ""));
            b.setOnClickListener(v -> {
                setIconVariant(id);
            });
            icons.addView(b);
        }
        Button autoBtn = new Button(this);
        autoBtn.setText("Следовать настройкам с сервера"
                + (prefs.iconVariant().isEmpty() ? "  ✓" : ""));
        autoBtn.setOnClickListener(v -> {
            prefs.setIconVariant("");
            IconManager.apply(MainActivity.this, "default");
            Toast.makeText(this, "Иконка — по настройкам сервера", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
        });
        icons.addView(autoBtn);
        Button allBtn = new Button(this);
        allBtn.setText(IconManager.title("all"));
        allBtn.setOnClickListener(v -> {
            IconManager.apply(MainActivity.this, "all");
            Toast.makeText(MainActivity.this, "Все иконки включены", Toast.LENGTH_SHORT).show();
            showSettingsDialog();
        });
        icons.addView(allBtn);
        box.addView(icons);

        box.addView(label("Поведение приложения:"));
        final RemoteSettings eff = settings;
        CheckBox cFull = new CheckBox(this);
        cFull.setText("Полноэкранный режим");
        cFull.setChecked(eff.fullscreen);
        box.addView(cFull);
        CheckBox cKeep = new CheckBox(this);
        cKeep.setText("Не гасить экран");
        cKeep.setChecked(eff.keepScreenOn);
        box.addView(cKeep);
        CheckBox cZoom = new CheckBox(this);
        cZoom.setText("Разрешить масштабирование");
        cZoom.setChecked(eff.allowZoom);
        box.addView(cZoom);
        CheckBox cExt = new CheckBox(this);
        cExt.setText("Внешние ссылки — в браузере");
        cExt.setChecked(eff.openLinksExternally);
        box.addView(cExt);
        CheckBox cBridge = new CheckBox(this);
        cBridge.setText("Разрешить JS-мост (window.Android)");
        cBridge.setChecked(eff.enableBridge);
        box.addView(cBridge);
        final EditText cRefresh = new EditText(this);
        cRefresh.setInputType(InputType.TYPE_CLASS_NUMBER);
        cRefresh.setHint("Автообновление, секунд");
        cRefresh.setText(String.valueOf(eff.refreshIntervalSec));
        box.addView(cRefresh);

        Button clear = new Button(this);
        clear.setText("Очистить кэш");
        clear.setOnClickListener(v -> {
            cache.clear();
            updateInfo(info);
            Toast.makeText(this, "Кэш очищен", Toast.LENGTH_SHORT).show();
        });
        box.addView(clear);

        new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    prefs.setControlUrlOverride(controlInput.getText().toString());
                    prefs.setSettingsUrlOverride(settingsInput.getText().toString());
                    prefs.setTitleOverride(titleInput.getText().toString());
                    prefs.setForceSettingsVisible(alwaysVisible.isChecked());
                    saveLocalExtra(cFull.isChecked(), cKeep.isChecked(), cZoom.isChecked(),
                            cExt.isChecked(), cBridge.isChecked(), parseInt(cRefresh.getText().toString()));
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private void updateInfo(TextView info) {
        info.setText("Индекс: " + (prefs.lastIndexUrl().isEmpty() ? "—" : prefs.lastIndexUrl())
                + "\n404: " + (prefs.lastErrorPageUrl().isEmpty() ? "—" : prefs.lastErrorPageUrl())
                + "\nНастройки: " + (settings.settingsEnabled ? "раздел включён" : "раздел скрыт")
                + (passwordRequired() ? ", защищён паролем" : ", без пароля")
                + "\nКэш: " + humanSize(cache.sizeBytes()) + ", записей: " + cache.entriesCount());
    }

    /* ------------------------------------------------------------------ */
    /* Отображение                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Показывает сохранённую страницу 404 (или встроенную заглушку),
     * если сети нет либо сервер вернул ошибку.
     */
    private void showFallback(String reason, boolean netErr) {
        setOffline(netErr);
        CacheStore.Entry err = cache.get(prefs.lastErrorPageUrl());
        if (err != null && err.body != null && err.body.length > 0) {
            String html = new String(err.body, StandardCharsets.UTF_8);
            web.loadDataWithBaseURL(prefs.lastErrorPageUrl(), html, "text/html", "utf-8", null);
            setStatus((netErr ? "Оффлайн · сохранённая страница 404" : "Сервер: " + reason
                    + " · показана страница 404"), netErr);
        } else {
            String html = readAsset("app/offline.html");
            if (html == null) html = "<html><body>Нет соединения</body></html>";
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            setStatus((netErr ? "Оффлайн · нет сохранённой страницы 404" : "Сервер: " + reason), netErr);
        }
    }

    /** Внедряет bridge.js в текущую страницу. */
    private void injectBridge() {
        String js = readAsset("app/bridge.js");
        if (js != null && !js.isEmpty()) {
            web.evaluateJavascript(js, null);
        }
    }

    private String readAsset(String path) {
        try {
            InputStream in = getAssets().open(path);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void setAppTitle(String title) {
        titleView.setText(title == null || title.trim().isEmpty() ? getString(R.string.app_name) : title);
    }

    private void syncTitle(String pageTitle) {
        // Заголовок из удалённых настроек имеет приоритет над <title> страницы
        String title = prefs.titleOverride();
        if (title.isEmpty()) title = settings.title;
        if (!title.isEmpty()) {
            titleView.setText(title);
            return;
        }
        if (pageTitle != null && !pageTitle.trim().isEmpty()) {
            titleView.setText(pageTitle.trim());
        }
    }

    public void setStatus(String text, boolean offline) {
        statusView.setText(text);
        statusView.setTextColor(offline ? 0xFFFFB74D : 0xFF8A94A6);
    }

    private void setOffline(boolean offline) {
        offlineMode = offline;
        badgeView.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    public void reloadCurrent() {
        web.reload();
    }

    public Prefs getPrefs() {
        return prefs;
    }

    /** Текущие удалённые настройки (для JS-моста). */
    public RemoteSettings getSettings() {
        return settings;
    }

    public long getCacheSizeBytes() {
        return cache.sizeBytes();
    }

    public int getCacheEntriesCount() {
        return cache.entriesCount();
    }

    public String getAppVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    public int getAppVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Уведомления                                                         */
    /* ------------------------------------------------------------------ */

    public void showNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Control",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("События из удалённой страницы");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
            android.app.Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new android.app.Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new android.app.Notification.Builder(this);
            }
            android.app.Notification n = builder
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        } catch (SecurityException se) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "notification failed", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Сеть → автообновление                                                */
    /* ------------------------------------------------------------------ */

    private BroadcastReceiver netReceiver;

    private void registerNetworkReceiver() {
        netReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_CONNECTIVITY_CHANGE.equals(intent.getAction())) return;
                boolean online = Net.isOnline(MainActivity.this);
                if (online && !wasOnline) {
                    refresh();
                }
                wasOnline = online;
            }
        };
        try {
            registerReceiver(netReceiver, new IntentFilter(ACTION_CONNECTIVITY_CHANGE));
        } catch (Exception e) {
            Log.w(TAG, "receiver registration failed", e);
        }
        wasOnline = Net.isOnline(this);
    }

    /* ------------------------------------------------------------------ */
    /* Life cycle                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.lastUpdate() == 0) {
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (netReceiver != null) unregisterReceiver(netReceiver);
        } catch (Exception ignored) {
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Утилиты                                                              */
    /* ------------------------------------------------------------------ */

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

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

    private static String nowTime() {
        return java.text.SimpleDateFormat.getDateTimeInstance(
                java.text.SimpleDateFormat.SHORT, java.text.SimpleDateFormat.SHORT).format(new java.util.Date());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f КБ", bytes / 1024f);
        return String.format(Locale.ROOT, "%.1f МБ", bytes / (1024f * 1024f));
    }
}
