#!/usr/bin/env bash
#
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> apksigner.
# Работает прямо в Termux (aarch64), где Gradle/AGP недоступны.
#
#   ./build.sh            — собрать app.apk (release)
#   ./build.sh --debug    — собрать app-debug.apk (android:debuggable=true)
#   ./build.sh --android10 — собрать app-android10.apk (targetSdk 29, Android 10)
#   ./build.sh out.apk    — собрать под другим именем
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-13/android.jar"
BUILD="$ROOT/build"

MODE="release"
OUT=""
for arg in "$@"; do
    case "$arg" in
        --debug) MODE="debug" ;;
        --android10|--sdk29) MODE="android10" ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) OUT="$arg" ;;
    esac
done
if [ -z "$OUT" ]; then
    case "$MODE" in
        debug)     OUT="$ROOT/app-debug.apk" ;;
        android10) OUT="$ROOT/app-android10.apk" ;;
        *)         OUT="$ROOT/app.apk" ;;
    esac
fi

KS_DIR="$ROOT/keystore"
KS="$KS_DIR/control.jks"
KS_PASS="tornado"
KS_ALIAS="control"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

say "Проверка инструментов"
for tool in aapt2 javac apksigner keytool jar python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "НЕ НАЙДЕН: $tool"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "НЕ НАЙДЕН android.jar: $ANDROID_JAR"; exit 1; }
echo "  android.jar: $ANDROID_JAR"

say "Чистка build/"
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/apk"

say "aapt2 compile (ресурсы)"
aapt2 compile --dir "$ROOT/app/src/main/res" -o "$BUILD/res/compiled.zip"

say "aapt2 link (resources.arsc + AndroidManifest.xml)"
ASSETS_ARG=()
[ -d "$ROOT/app/src/main/assets" ] && ASSETS_ARG=(-A "$ROOT/app/src/main/assets")

MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
VERSION_CODE=1
VERSION_NAME="1.0"
TARGET_SDK=33
MIN_SDK=24

if [ "$MODE" = "debug" ]; then
    say "режим DEBUG: android:debuggable=true, отдельное имя приложения"
    MANIFEST="$BUILD/AndroidManifest.debug.xml"
    python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$MANIFEST" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
s = s.replace('android:label="@string/app_name"', 'android:label="@string/app_name_debug"')
s = s.replace('android:versionCode="1"', 'android:versionCode="2"')
s = s.replace('android:versionName="1.0"', 'android:versionName="1.0-debug"')
s = s.replace('<application', '<application\n        android:debuggable="true"', 1)
open(dst, 'w', encoding='utf-8').write(s)
PY
    VERSION_CODE=2
    VERSION_NAME="1.0-debug"
fi

if [ "$MODE" = "android10" ]; then
    say "режим ANDROID 10: targetSdk 29 (minSdk 24)"
    MANIFEST="$BUILD/AndroidManifest.android10.xml"
    python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$MANIFEST" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
s = s.replace('android:label="@string/app_name"', 'android:label="@string/app_name_android10"')
s = s.replace('android:versionCode="1"', 'android:versionCode="3"')
s = s.replace('android:versionName="1.0"', 'android:versionName="1.0-android10"')
open(dst, 'w', encoding='utf-8').write(s)
PY
    VERSION_CODE=3
    VERSION_NAME="1.0-android10"
    TARGET_SDK=29
fi

aapt2 link \
  -o "$BUILD/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$BUILD/gen" \
  "${ASSETS_ARG[@]}" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD/res/compiled.zip"

say "javac (исходники)"
find "$ROOT/app/src/main/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac --release 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

say "Подготовка class-файлов"
# d8 из build-tools 36+ понимает MethodParameters от javac 21, поэтому вырезать
# атрибут не нужно (а вырезание портило dex и ломало проверку ART при установке).
# Если d8 старый и не справится — вырезаем и повторяем.
NEED_STRIP=0

say "d8 (dex)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
D8_JAR="$(find "$SDK/build-tools" -name d8.jar 2>/dev/null | sort -V | tail -1)"
if [ -n "$D8_JAR" ]; then
  D8_CMD=(java -cp "$D8_JAR" com.android.tools.r8.D8)
else
  D8_CMD=(d8)
fi
echo "  d8: ${D8_JAR:-системный}"
if ! "${D8_CMD[@]}" --release --min-api 24 --lib "$ANDROID_JAR" \
        --output "$BUILD/dex" @"$BUILD/classes.txt" 2>"$BUILD/d8.err"; then
  warn "d8 не справился — вырезаю MethodParameters и повторяю"
  NEED_STRIP=1
fi
if [ "$NEED_STRIP" = "1" ]; then
  python3 "$ROOT/tools/strip_method_params.py" "$BUILD/classes"
  rm -rf "$BUILD/dex" && mkdir -p "$BUILD/dex"
  "${D8_CMD[@]}" --release --min-api 24 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" @"$BUILD/classes.txt" || {
      echo "--- ошибка d8 ---"; head -20 "$BUILD/d8.err"; exit 1; }
else
  echo "  class-файлы оставлены как есть (без модификаций)"
fi

say "Упаковка classes.dex"
jar uf "$BUILD/apk/base.apk" -C "$BUILD/dex" classes.dex

say "Ключ подписи"
if [ ! -f "$KS" ]; then
  mkdir -p "$KS_DIR"
  keytool -genkeypair -v \
    -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Tornado Control, OU=Dev, O=Tornado, C=RU" >/dev/null 2>&1
  echo "  создан $KS"
fi

say "apksigner"
rm -f "$OUT"
APKSIGNER_JAR="$(find "$SDK/build-tools" -name apksigner.jar 2>/dev/null | sort -V | tail -1)"
if [ -n "$APKSIGNER_JAR" ]; then
  SIGN_CMD=(java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool)
else
  SIGN_CMD=(apksigner)
fi
"${SIGN_CMD[@]}" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$BUILD/apk/base.apk"

"${SIGN_CMD[@]}" verify "$OUT"

say "Готово: $OUT"
ls -lh "$OUT" | awk '{print "  размер:", $5}'
