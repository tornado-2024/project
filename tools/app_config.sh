#!/usr/bin/env bash
#
# app_config.sh — настройка приложения: имя, иконка, основной URL, ключ подписи,
# package, версия. После правок собирает APK.
#
# Интерактивный режим (меню):
#   bash tools/app_config.sh
#
# Неинтерактивный:
#   bash tools/app_config.sh --name "Моё приложение" --build
#   bash tools/app_config.sh --icon ~/Pictures/logo.png --build
#   bash tools/app_config.sh --icon-make "#4C8DFF" --build
#   bash tools/app_config.sh --control https://example.com/control --build
#   bash tools/app_config.sh --index https://example.com/ --title "Пример" --build
#   bash tools/app_config.sh --new-key --key-pass secret --build
#   bash tools/app_config.sh --package com.my.app --build
#   bash tools/app_config.sh --show
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_DIR="app/src/main/java/com/tornado/control"
STRINGS="app/src/main/res/values/strings.xml"
MANIFEST="app/src/main/AndroidManifest.xml"
GRADLE="app/build.gradle"
SETTINGS_JSON="settings.json"
REMOTE="app/src/main/java/com/tornado/control/RemoteSettings.java"
BUILD_SH="build.sh"
KS_DIR="keystore"
BUILD="$ROOT/build"
KS="$KS_DIR/control.jks"

APP_NAME=""
APP_ICON=""
APP_ICON_MAKE=""
CONTROL_URL=""
INDEX_URL=""
SETTINGS_URL=""
ERROR_URL=""
PASSWORD_URL=""
TITLE=""
NEW_KEY=0
KEY_PASS=""
PACKAGE=""
VERSION_NAME=""
VERSION_CODE=""
DO_BUILD=0
BUILD_ALL=0
SHOW=0

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }
ok()   { printf '\033[1;32m[+]\033[0m %s\n' "$*"; }

ARG_COUNT=$#
while [ $# -gt 0 ]; do
    case "$1" in
        --name)        APP_NAME="${2:-}"; shift 2 ;;
        --icon)        APP_ICON="${2:-}"; shift 2 ;;
        --icon-make)   APP_ICON_MAKE="${2:-}"; shift 2 ;;
        --control)     CONTROL_URL="${2:-}"; shift 2 ;;
        --index)       INDEX_URL="${2:-}"; shift 2 ;;
        --settings-url) SETTINGS_URL="${2:-}"; shift 2 ;;
        --error-url)   ERROR_URL="${2:-}"; shift 2 ;;
        --password-url) PASSWORD_URL="${2:-}"; shift 2 ;;
        --title)       TITLE="${2:-}"; shift 2 ;;
        --new-key)     NEW_KEY=1; shift ;;
        --key-pass)    KEY_PASS="${2:-}"; shift 2 ;;
        --package)     PACKAGE="${2:-}"; shift 2 ;;
        --version)     VERSION_NAME="${2:-}"; shift 2 ;;
        --version-code) VERSION_CODE="${2:-}"; shift 2 ;;
        --build)       DO_BUILD=1; shift ;;
        --build-all)   DO_BUILD=1; BUILD_ALL=1; shift ;;
        --show)        SHOW=1; shift ;;
        -h|--help)     sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "неизвестный аргумент: $1 (см. --help)" ;;
    esac
done

py() { python3 - "$@"; }

# ------------------------------------------------------------------ показать
show_config() {
    say "Текущая конфигурация"
    py "$STRINGS" "$MANIFEST" "$REMOTE" "$SETTINGS_JSON" "$KS" "$BUILD_SH" <<'PY2'
import json, os, re, subprocess, sys
strings, manifest, remote, sj, ks, bsh = sys.argv[1:7]

def read(p):
    try:
        return open(p, encoding='utf-8').read()
    except Exception:
        return ''

st = read(strings)
m = re.search(r'<string name="app_name">([^<]*)<', st)
print('  имя (label)      : %s' % (m.group(1) if m else '?'))

mf = read(manifest)
m = re.search(r'android:icon="([^"]*)"', mf)
print('  иконка           : %s' % (m.group(1) if m else '?'))
m = re.search(r'package="([^"]*)"', mf)
print('  package          : %s' % (m.group(1) if m else '?'))
m = re.search(r'android:versionName="([^"]*)"', mf)
print('  версия           : %s' % (m.group(1) if m else '?'))
m = re.search(r'android:versionCode="([^"]*)"', mf)
print('  versionCode      : %s' % (m.group(1) if m else '?'))

rm = read(remote)
for const in ('DEFAULT_CONTROL_URL', 'DEFAULT_SETTINGS_URL', 'DEFAULT_PASSWORD_URL'):
    m = re.search(const + r'\s*=\s*"([^"]*)"', rm)
    if m:
        label = {'DEFAULT_CONTROL_URL': 'control (код)    ',
                  'DEFAULT_SETTINGS_URL': 'settings (код)  ',
                  'DEFAULT_PASSWORD_URL': 'password (код)  '}[const]
        print('  %s: %s' % (label, m.group(1)))

print('  settings.json    : %s' % sj)
if os.path.exists(sj):
    try:
        d = json.load(open(sj, encoding='utf-8'))
        for k in ('controlUrl', 'settingsUrl', 'indexUrl', 'errorPageUrl',
                  'passwordUrl', 'title', 'settingsEnabled', 'requirePassword', 'theme'):
            if k in d:
                print('    %-14s = %s' % (k, d[k]))
    except Exception as e:
        print('    (не читается: %s)' % e)

print('  ключ подписи     : %s' % ks)
if os.path.exists(ks):
    pw = re.search(r'KS_PASS="([^"]*)"', read(bsh))
    pw = pw.group(1) if pw else ''
    try:
        out = subprocess.run(['keytool', '-list', '-v', '-keystore', ks,
                              '-storepass', pw], capture_output=True, text=True,
                             timeout=30).stdout
        m = re.search(r'SHA256: ([0-9A-F:]+)', out)
        print('                     SHA-256: %s' % (m.group(1) if m else '?'))
    except Exception:
        pass
PY2
}

if [ "$SHOW" = "1" ]; then
    show_config
    exit 0
fi

# --------------------------------------------------------------------- имя
if [ -n "$APP_NAME" ]; then
    say "Меняю имя приложения на «$APP_NAME»"
    py "$STRINGS" "$APP_NAME" <<'PY'
import re, sys
path, name = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
for key in ('app_name', 'app_name_debug', 'app_name_android10'):
    if key == 'app_name_debug':
        s = re.sub(r'(<string name="%s">)([^<]*)(</string>)' % key,
                   lambda m: m.group(1) + name + ' (debug)' + m.group(3), s)
    elif key == 'app_name_android10':
        s = re.sub(r'(<string name="%s">)([^<]*)(</string>)' % key,
                   lambda m: m.group(1) + name + ' (Android 10)' + m.group(3), s)
    else:
        s = re.sub(r'(<string name="%s">)([^<]*)(</string>)' % key,
                   lambda m: m.group(1) + name + m.group(3), s)
open(path, 'w', encoding='utf-8').write(s)
PY
    ok "имя обновлено (label в лаунчере)"
    warn "текст ВНУТРИ приложения (верхняя панель) задаётся отдельно: settings.json → title"
fi

# ------------------------------------------------------------------- иконка
set_icon() {
    local png="$1"
    mkdir -p app/src/main/res/mipmap-xxhdpi app/src/main/res/mipmap-hdpi app/src/main/res/mipmap-mdpi
    cp "$png" app/src/main/res/mipmap-xxhdpi/ic_launcher.png
    cp "$png" app/src/main/res/mipmap-hdpi/ic_launcher.png
    cp "$png" app/src/main/res/mipmap-mdpi/ic_launcher.png
    py "$MANIFEST" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace('android:icon="@drawable/ic_launcher"', 'android:icon="@mipmap/ic_launcher"')
s = s.replace('android:roundIcon="@drawable/ic_launcher"', 'android:roundIcon="@mipmap/ic_launcher"')
open(p, 'w', encoding='utf-8').write(s)
PY
    ok "иконка заменена на $png (mipmap-xxhdpi/hdpi/mdpi)"
}

if [ -n "$APP_ICON" ]; then
    [ -f "$APP_ICON" ] || die "файл иконки не найден: $APP_ICON"
    say "Заменяю иконку на $APP_ICON"
    case "$APP_ICON" in
        *.xml) cp "$APP_ICON" app/src/main/res/drawable/ic_launcher.xml
               py "$MANIFEST" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_launcher"')
s = s.replace('android:roundIcon="@mipmap/ic_launcher"', 'android:roundIcon="@drawable/ic_launcher"')
open(p, 'w', encoding='utf-8').write(s)
PY
               ok "иконка-вектор заменена" ;;
        *)   set_icon "$APP_ICON" ;;
    esac
fi

if [ -n "$APP_ICON_MAKE" ]; then
    say "Генерирую иконку цветом $APP_ICON_MAKE"
    TMP="$BUILD/icon.png"; mkdir -p build
    python3 tools/make_icon.py "$TMP" 512 >/dev/null 2>&1 || die "не удалось сгенерировать иконку"
    set_icon "$TMP"
fi

# --------------------------------------------------------------------- URL
urls_changed=0
if [ -n "$CONTROL_URL" ] || [ -n "$INDEX_URL" ] || [ -n "$SETTINGS_URL" ] \
   || [ -n "$ERROR_URL" ] || [ -n "$PASSWORD_URL" ] || [ -n "$TITLE" ]; then
    say "Обновляю адреса"
    py "$SETTINGS_JSON" "$REMOTE" "$CONTROL_URL" "$INDEX_URL" "$SETTINGS_URL" \
             "$ERROR_URL" "$PASSWORD_URL" "$TITLE" <<'PY'
import json, os, re, sys
sj, rem, ctl, idx, stu, err, pwd, title = sys.argv[1:9]

def set_if(v, cond):
    return v if cond else None

# settings.json в репозитории
if os.path.exists(sj):
    try:
        d = json.load(open(sj, encoding='utf-8'))
    except Exception:
        d = {}
    for key, val in (('controlUrl', ctl), ('indexUrl', idx), ('settingsUrl', stu),
                     ('errorPageUrl', err), ('passwordUrl', pwd), ('title', title)):
        if val:
            d[key] = val
    with open(sj, 'w', encoding='utf-8') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')

# значения по умолчанию в коде
s = open(rem, encoding='utf-8').read()
def repl_const(text, const, value):
    if not value:
        return text
    return re.sub(r'(public static final String %s\s*=\s*)"[^"]*"' % const,
                  lambda m: m.group(1) + '"%s"' % value, text, count=1)
s = repl_const(s, 'DEFAULT_CONTROL_URL', ctl)
s = repl_const(s, 'DEFAULT_SETTINGS_URL', stu)
s = repl_const(s, 'DEFAULT_PASSWORD_URL', pwd)
open(rem, 'w', encoding='utf-8').write(s)
PY
    ok "адреса обновлены в settings.json и в коде (значения по умолчанию)"
    urls_changed=1
fi

# --------------------------------------------------------------- package
if [ -n "$PACKAGE" ]; then
    OLD_PKG=$(grep -o 'package="[^"]*"' "$MANIFEST" | head -1 | cut -d'"' -f2)
    [ "$OLD_PKG" != "$PACKAGE" ] || die "package не изменился"
    say "Меняю package: $OLD_PKG → $PACKAGE"
    mkdir -p "app/src/main/java/$(dirname "$PACKAGE")"
    mv "$JAVA_DIR" "app/src/main/java/$PACKAGE"
    py "$OLD_PKG" "$PACKAGE" "$MANIFEST" "$GRADLE" <<'PY'
import os, re, sys
old, new, mf, gr = sys.argv[1:5]
s = open(mf, encoding='utf-8').read()
s = s.replace('package="%s"' % old, 'package="%s"' % new)
open(mf, 'w', encoding='utf-8').write(s)
g = open(gr, encoding='utf-8').read()
g = g.replace("namespace '%s'" % old, "namespace '%s'" % new)
g = g.replace('applicationId "%s"' % old, 'applicationId "%s"' % new)
open(gr, 'w', encoding='utf-8').write(g)
root = os.path.dirname(os.path.dirname(mf))
for dirpath, _dirs, files in os.walk(root):
    for f in files:
        if f.endswith('.java'):
            p = os.path.join(dirpath, f)
            t = open(p, encoding='utf-8').read()
            if 'package %s;' % old in t:
                t = t.replace('package %s;' % old, 'package %s;' % new)
                open(p, 'w', encoding='utf-8').write(t)
PY
    ok "package изменён (не забудьте удалить старую версию приложения с телефона)"
fi

# ----------------------------------------------------------------- версия
if [ -n "$VERSION_NAME" ] || [ -n "$VERSION_CODE" ]; then
    say "Обновляю версию"
    py "$MANIFEST" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
import re, sys
p, vn, vc = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if vn:
    s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % vn, s)
if vc:
    s = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%s"' % vc, s)
open(p, 'w', encoding='utf-8').write(s)
PY
    ok "версия обновлена"
fi

# ------------------------------------------------------------------- ключ
if [ "$NEW_KEY" = "1" ]; then
    say "Создаю новый ключ подписи"
    if [ -z "$KEY_PASS" ]; then
        KEY_PASS="tornado"
        warn "пароль ключа не задан — использую 'tornado'"
    fi
    if [ -f "$KS" ]; then
        mv "$KS" "$KS.bak-$(date +%Y%m%d-%H%M%S)"
        ok "старый ключ сохранён рядом"
    fi
    mkdir -p "$KS_DIR"
    keytool -genkeypair -v -keystore "$KS" -alias control \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=Tornado Control, OU=Dev, O=Tornado, C=RU" >/dev/null 2>&1
    py "$BUILD_SH" "$KEY_PASS" <<'PY'
import re, sys
p, pw = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8').read()
s = re.sub(r'KS_PASS="[^"]*"', 'KS_PASS="%s"' % pw, s)
open(p, 'w', encoding='utf-8').write(s)
PY
    ok "новый ключ создан, пароль прописан в build.sh"
    warn "старое приложение с прежним ключом нужно УДАЛИТЬ, иначе будет конфликт подписи"
fi

# ------------------------------------------------------------------ сборка
if [ "$DO_BUILD" = "1" ]; then
    say "Сборка"
    if [ "$BUILD_ALL" = "1" ]; then
        bash "$BUILD_SH" && bash "$BUILD_SH" --debug && bash "$BUILD_SH" --android10
    else
        bash "$BUILD_SH"
    fi
    cp -f app.apk "Download/TornadoControl.apk" 2>/dev/null || true
    ok "APK готов: $ROOT/app.apk"
fi

# ------------------------------------------------------------- интерактив
if [ "$ARG_COUNT" -eq 0 ]; then
    echo
    say "Меню настройки"
    echo "  1) имя приложения (label в лаунчере)"
    echo "  2) иконка: путь к PNG или вектору"
    echo "  3) сгенерировать иконку (--icon-make '#4C8DFF')"
    echo "  4) основной URL (control / index)"
    echo "  5) заголовок верхней панели (title)"
    echo "  6) новый ключ подписи"
    echo "  7) package / версия"
    echo "  8) показать текущую конфигурацию"
    echo "  9) пересобрать APK"
    echo "  0) выход"
    read -r -p "Выбор: " ch
    case "$ch" in
        1) read -r -p "Имя приложения: " v; bash "$0" --name "$v" ;;
        2) read -r -p "Путь к иконке: " v; bash "$0" --icon "$v" ;;
        3) read -r -p "Цвет (#4C8DFF): " v; bash "$0" --icon-make "$v" ;;
        4) read -r -p "control URL: " c; read -r -p "index URL (пусто — не менять): " i
           bash "$0" --control "$c" ${i:+--index "$i"} ;;
        5) read -r -p "Заголовок верхней панели: " v; bash "$0" --title "$v" ;;
        6) read -r -p "Пароль нового ключа: " v; bash "$0" --new-key --key-pass "$v" ;;
        7) read -r -p "package (пусто — не менять): " p; read -r -p "версия: " vn
           bash "$0" ${p:+--package "$p"} ${vn:+--version "$vn"} ;;
        8) bash "$0" --show ;;
        9) bash "$0" --build ;;
        0) exit 0 ;;
        *) echo "неверный выбор" ;;
    esac
fi
