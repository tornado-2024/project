/*
 * bridge.js — обёртка над нативным мостом JavaScript -> Android.
 *
 * Нативные интерфейсы доступны в странице как window.Android и window.Native.
 * Этот файл инжектится в страницу после загрузки и даёт удобный API:
 *
 *   Tornado.vibrate(120);
 *   Tornado.vibratePattern([0, 40, 80, 40]);
 *   Tornado.vibrateEffect('heavy');
 *   Tornado.toast('Привет');
 *   Tornado.deviceInfo().model;
 *
 * Если моста нет (страница открыта в обычном браузере) — используются
 * браузерные фолбэки (navigator.vibrate и т.п.).
 */
(function () {
    'use strict';

    if (window.__tornadoBridge) {
        return;
    }

    var J = window.Android || window.Native || null;

    function hasNative() {
        return !!(J && typeof J.vibrate === 'function');
    }

    function call(fn, args, fallback) {
        try {
            if (!J) {
                return fallback;
            }
            var f = J[fn];
            if (typeof f !== 'function') {
                return fallback;
            }
            return f.apply(J, args || []);
        } catch (e) {
            if (window.console && console.warn) {
                console.warn('[Tornado] вызов ' + fn + ' не удался:', e);
            }
            return fallback;
        }
    }

    function toArray(pattern) {
        if (Array.isArray(pattern)) {
            return pattern;
        }
        if (typeof pattern === 'string') {
            try {
                var parsed = JSON.parse(pattern);
                return Array.isArray(parsed) ? parsed : [];
            } catch (e) {
                return [];
            }
        }
        return [];
    }

    var api = {
        available: hasNative(),
        version: '1.0.0',

        /** Вибрация, мс. */
        vibrate: function (ms) {
            var d = parseInt(ms, 10) || 50;
            if (hasNative()) {
                call('vibrate', [d]);
                return true;
            }
            if (navigator.vibrate) {
                try {
                    return navigator.vibrate(d);
                } catch (e) {
                    return false;
                }
            }
            return false;
        },

        /** Вибрация по паттерну: [0, 40, 80, 40] — мс. */
        vibratePattern: function (pattern, repeat) {
            var arr = toArray(pattern);
            if (!arr.length) {
                return false;
            }
            if (hasNative()) {
                return call('vibratePattern', [JSON.stringify(arr),
                    repeat === undefined ? -1 : repeat]);
            }
            if (navigator.vibrate) {
                try {
                    return navigator.vibrate(arr);
                } catch (e) {
                    return false;
                }
            }
            return false;
        },

        /** Готовые эффекты: click, tick, heavy, notification, success, error. */
        vibrateEffect: function (type) {
            if (hasNative()) {
                return call('vibrateEffect', [String(type || 'click')]);
            }
            return api.vibrate(25);
        },

        /** Всплывающее уведомление. */
        toast: function (message) {
            return call('toast', [String(message == null ? '' : message)]);
        },

        /** Заголовок верхней панели приложения. */
        setTitle: function (title) {
            return call('setTitle', [String(title == null ? '' : title)]);
        },

        /** Цвет статус-бара: '#10131A'. */
        setStatusBarColor: function (hex) {
            return call('setStatusBarColor', [String(hex || '#171B24')]);
        },

        /** Открыть ссылку в системном браузере. */
        openUrl: function (url) {
            return call('openUrl', [String(url || '')]);
        },

        /** Скопировать в буфер обмена. */
        copy: function (text) {
            return call('copy', [String(text == null ? '' : text)]);
        },

        /** Системное окно «Поделиться». */
        share: function (text, title) {
            return call('share', [String(text == null ? '' : text), String(title || '')]);
        },

        /** Системное уведомление. */
        notify: function (title, text) {
            return call('notify', [String(title || ''), String(text || '')]);
        },

        /** Информация об устройстве (объект). */
        deviceInfo: function () {
            try {
                return JSON.parse(call('getDeviceInfo', [], '{}'));
            } catch (e) {
                return {};
            }
        },

        /** Есть ли сеть. */
        isOnline: function () {
            return !!call('isOnline', [], false);
        },

        /** Адрес control-эндпоинта. */
        controlUrl: function () {
            return call('getControlUrl', [], '');
        },

        /** Адрес сохранённой страницы 404. */
        errorPageUrl: function () {
            return call('getErrorPageUrl', [], '');
        },

        /** Удалённые настройки (объект). */
        settings: function () {
            try {
                return JSON.parse(call('getSettings', [], '{}'));
            } catch (e) {
                return {};
            }
        },

        /** URL индексной страницы, полученный из control. */
        indexUrl: function () {
            return call('getIndexUrl', [], '');
        },

        /** Перезагрузить страницу. */
        reload: function () {
            return call('reload', []);
        },

        /** Обновить контент с сервера. */
        refresh: function () {
            return call('refresh', []);
        },

        /** Открыть настройки приложения. */
        showSettings: function () {
            return call('showSettings', []);
        },

        /** Запись в logcat. */
        log: function (message) {
            return call('log', [String(message == null ? '' : message)]);
        },

        /** Закрыть приложение. */
        exit: function () {
            return call('exitApp', []);
        }
    };

    window.Tornado = api;
    window.TC = api;
    window.__tornadoBridge = api;

    if (window.console && console.info) {
        console.info('[Tornado] bridge ' + api.version + (api.available ? ' (native)' : ' (web fallback)'));
    }
})();
