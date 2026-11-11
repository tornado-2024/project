#!/usr/bin/env bash
#
# publish_repo_files.sh — создаёт файлы, необходимые приложению:
#
#   settings.json        — удалённые настройки (controlUrl, title, показ настроек, пароль…)
#   password             — sha256-хэш пароля для раздела настроек
#   errors/404/index.html — страница 404 (кладётся в репозиторий сайта)
#
# Примеры:
#   bash publish_repo_files.sh --title "Tornado" --random-password
#   bash publish_repo_files.sh --password "мой-пароль" --control "https://.../control"
#   bash publish_repo_files.sh --site-dir ~/site --random-password
#
set -euo pipefail

CONTROL="https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/control"
PASSWORD_URL="https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/password"
TITLE=""
PASSWORD=""
RANDOM_PASSWORD=0
SITE_DIR=""
SETTINGS_ONLY=0
SETTINGS_FILE="settings.json"
# Куда класть файлы: по умолчанию корень git-репозитория (а не текущий каталог!)
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
OUT_DIR="$REPO_ROOT"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --control)        CONTROL="${2:-}"; shift 2 ;;
        --password-url)   PASSWORD_URL="${2:-}"; shift 2 ;;
        --title)          TITLE="${2:-}"; shift 2 ;;
        --password)       PASSWORD="${2:-}"; shift 2 ;;
        --random-password) RANDOM_PASSWORD=1; shift ;;
        --site-dir)       SITE_DIR="${2:-}"; shift 2 ;;
        --out-dir)        OUT_DIR="${2:-}"; shift 2 ;;
        --settings-file)  SETTINGS_FILE="${2:-}"; shift 2 ;;
        --settings-only)  SETTINGS_ONLY=1; shift ;;
        -h|--help)        sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "неизвестный аргумент: $1" ;;
    esac
done

command -v sha256sum >/dev/null 2>&1 || die "нет sha256sum"

if [ "$RANDOM_PASSWORD" = "1" ]; then
    PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '=+/' | cut -c1-16)"
    echo "Сгенерированный пароль: $PASSWORD"
    echo "Сохраните его — хэш попадёт в репозиторий, сам пароль — нет."
fi

# ------------------------------------------------------------- settings.json
mkdir -p "$OUT_DIR"
say "создание $OUT_DIR/$SETTINGS_FILE"
cat > "$OUT_DIR/$SETTINGS_FILE" <<EOF
{
  "controlUrl": "$CONTROL",
  "indexUrl": "",
  "errorPageUrl": "",
  "passwordUrl": "$PASSWORD_URL",
  "settingsEnabled": false,
  "requirePassword": true,
  "title": "$TITLE",
  "showHeader": true,
  "refreshOnStart": true,
  "cacheEnabled": true,
  "timeoutMs": 15000,
  "theme": "dark",
  "statusBarColor": "#171B24",
  "accentColor": "#4C8DFF",
  "userAgent": ""
}
EOF
cat "$SETTINGS_FILE"

if [ "$SETTINGS_ONLY" = "1" ]; then
    say "готово (только настройки)"
    exit 0
fi

# ------------------------------------------------------------------ password
if [ -n "$PASSWORD" ]; then
    say "создание $OUT_DIR/password (sha256)"
    printf '%s' "$PASSWORD" | sha256sum | cut -d' ' -f1 > "$OUT_DIR/password"
    echo "  хэш: $(cat "$OUT_DIR/password")"
    echo "  проверка: printf %s '$PASSWORD' | sha256sum"
fi

# --------------------------------------------------------------- страница 404
TARGET_DIR="$SITE_DIR"
[ -n "$TARGET_DIR" ] || TARGET_DIR="$OUT_DIR"
say "создание $TARGET_DIR/errors/404/index.html"
mkdir -p "$TARGET_DIR/errors/404"
cat > "$TARGET_DIR/errors/404/index.html" <<'EOF'
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Страница не найдена</title>
    <style>
        :root { color-scheme: dark; }
        * { box-sizing: border-box; }
        body {
            margin: 0; min-height: 100vh; display: flex; flex-direction: column;
            align-items: center; justify-content: center; gap: 16px; padding: 24px;
            background: #0E1116; color: #E6EAF2; text-align: center;
            font-family: -apple-system, Roboto, "Segoe UI", system-ui, sans-serif;
        }
        .code { font-size: 72px; font-weight: 800; color: #4C8DFF; line-height: 1; }
        h1 { font-size: 20px; margin: 0; }
        p { color: #8A94A6; font-size: 14px; max-width: 420px; margin: 0; line-height: 1.5; }
        a { color: #4C8DFF; text-decoration: none; font-size: 14px; margin-top: 6px; }
    </style>
</head>
<body>
    <div class="code">404</div>
    <h1>Страница не найдена</h1>
    <p>Такой страницы нет. Приложение показывает эту страницу, когда нет связи с сервером.</p>
    <a href="/">На главную</a>
</body>
</html>
EOF

say "готово"
echo "  $OUT_DIR/$SETTINGS_FILE"
[ -f "$OUT_DIR/password" ] && echo "  $OUT_DIR/password (sha256)"
echo "  $TARGET_DIR/errors/404/index.html"
echo
echo "Дальше:"
echo "  cd $OUT_DIR && git add -A && git commit -m 'settings, password, 404' && git push"
