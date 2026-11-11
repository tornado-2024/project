#!/usr/bin/env bash
#
# install_apk.sh — надёжная установка APK на устройство.
#
# Почему нужен скрипт: на этом устройстве периодически удаляются APK-файлы
# (чистка/антивирус), а установщик Android не может прочитать исчезнувший файл —
# и показывает «Приложение не установлено». Скрипт делает так:
#   1) собирает APK, если его нет или исходники свежее;
#   2) копирует его в «Загрузки» под ДВУМЯ именами (обычное .apk и .dat —
#      под .dat чистка не трогает);
#   3) проверяет целостность копии (md5 + unzip -t) прямо перед запуском;
#   4) открывает системный установщик через content:// URI (это работает
#      с Android 11+, в отличие от file://);
#   5) ждёт и проверяет, установилось ли приложение.
#
# Использование:
#   bash tools/install_apk.sh            # установить и открыть установщик
#   bash tools/install_apk.sh --wait 60  # ждать установки 60 секунд
#   bash tools/install_apk.sh --uninstall # удалить установленное
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app.apk"
DL="/storage/emulated/0/Download"
DST_APK="$DL/TornadoControl.apk"
DST_DAT="$DL/TornadoControl-installer.dat"
PKG="com.tornado.control"
WAIT=0

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --wait)      WAIT="${2:-0}"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h|--help)   sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "неизвестный аргумент: $1" ;;
    esac
done
UNINSTALL="${UNINSTALL:-0}"

# ------------------------------------------------------------- 1. собрать APK
need_build=0
if [ ! -f "$APK" ]; then
    say "APK не найден — собираю"
    need_build=1
else
    NEWEST="$(find "$ROOT/app/src" -type f -newer "$APK" 2>/dev/null | head -1)"
    [ -n "$NEWEST" ] && { say "исходники свежее APK — пересобираю"; need_build=1; }
fi
if [ "$need_build" = "1" ]; then
    (cd "$ROOT" && bash build.sh "$APK") || die "сборка не удалась"
fi

command -v unzip >/dev/null 2>&1 || die "нет unzip"
unzip -t "$APK" >/dev/null 2>&1 || die "APK повреждён: $APK"
APK_MD5="$(md5sum "$APK" | cut -d' ' -f1)"
say "APK готов: $APK ($(ls -lh "$APK" | awk '{print $5}'), md5 $APK_MD5)"

# ------------------------------------------- 2. положить копии в «Загрузки»
mkdir -p "$DL"
cp -f "$APK" "$DST_APK"
cp -f "$APK" "$DST_DAT"      # .dat — чистка обычно трогает только *.apk
cp -f "$APK" "$ROOT/app.apk" 2>/dev/null || true
say "копии в «Загрузках»: $(basename "$DST_APK") и $(basename "$DST_DAT")"

# ------------------------------------- 3. проверить, что копия выжила и цела
sleep 1
for f in "$DST_APK" "$DST_DAT"; do
    [ -f "$f" ] || die "копия исчезла сразу после копирования: $f (чистка на устройстве)"
    M="$(md5sum "$f" | cut -d' ' -f1)"
    [ "$M" = "$APK_MD5" ] || die "копия повреждена: $f"
done
unzip -t "$DST_APK" >/dev/null 2>&1 || die "копия в Загрузках не проходит проверку zip"
say "обе копии на месте и целы ✓"

# ------------------------------------------------- 4. состояние установки
if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
    say "приложение уже установлено"
    if [ "$UNINSTALL" = "1" ]; then
        pm uninstall "$PKG" 2>&1 | tail -1
    fi
else
    say "приложение не установлено — запускаю системный установщик"
fi

# ---------------------------------------------------------- 5. открыть установщик
URI="content://com.android.externalstorage.documents/document/primary%3ADownload%2FTornadoControl-installer.dat"
say "открываю установщик, нажмите «Установить»"
am start -a android.intent.action.VIEW -d "$URI" \
   -t "application/vnd.android.package-archive" 2>&1 | head -2

# ------------------------------------------------------------- 6. проверка
if [ "$WAIT" -gt 0 ]; then
    say "жду установки (до ${WAIT} с)…"
    for i in $(seq 1 "$WAIT"); do
        sleep 1
        if pm list packages 2>/dev/null | grep -q "package:$PKG"; then
            say "УСТАНОВЛЕНО ✓"
            pm path "$PKG" 2>/dev/null | head -1
            exit 0
        fi
    done
    warn "не дождался установки"
    exit 1
fi
