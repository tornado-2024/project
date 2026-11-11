#!/usr/bin/env bash
#
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> apksigner.
# Работает прямо в Termux (aarch64), где Gradle/AGP недоступны.
#
#   ./build.sh            — собрать app.apk
#   ./build.sh out.apk    — собрать под другим именем
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-13/android.jar"
BUILD="$ROOT/build"
OUT="${1:-$ROOT/app.apk}"

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
aapt2 link \
  -o "$BUILD/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  -A "$ROOT/app/src/main/assets" \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  --version-code 1 \
  --version-name "1.0" \
  "$BUILD/res/compiled.zip"

say "javac (исходники)"
find "$ROOT/app/src/main/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac --release 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

say "Подготовка class-файлов (удаление MethodParameters)"
python3 "$ROOT/tools/strip_method_params.py" "$BUILD/classes"

say "d8 (dex)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
D8_JAR="$(find "$SDK/build-tools" -name d8.jar 2>/dev/null | sort | tail -1)"
if [ -n "$D8_JAR" ]; then
  java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api 24 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" @"$BUILD/classes.txt"
else
  d8 --release --min-api 24 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" @"$BUILD/classes.txt"
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
apksigner sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$BUILD/apk/base.apk"

apksigner verify "$OUT"

say "Готово: $OUT"
ls -lh "$OUT" | awk '{print "  размер:", $5}'
