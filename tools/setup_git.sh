#!/usr/bin/env bash
#
# setup_git.sh — автонастройка git и GitHub (Termux / Linux).
#
# Делает всё сразу:
#   * ставит gh, если нет;
#   * пишет ~/.gitconfig (имя, email, ветка main, helper, алиасы);
#   * определяет GitHub-ник из URL репозитория и подставляет noreply-email;
#   * генерирует SSH-ключ (--ssh) или авторизуется по PAT через gh (--login);
#   * добавляет/создаёт remote origin;
#   * делает первый коммит и пушит (--push).
#
# Примеры:
#   bash setup_git.sh --repo https://github.com/tornado-2024/project.git --login --push
#   bash setup_git.sh --repo git@github.com:tornado-2024/project.git --ssh
#   bash setup_git.sh --check
#
set -euo pipefail

# ---------------------------------------------------------------- параметры
NAME=""
EMAIL=""
LOGIN=""
REPO=""
PROTO="https"          # https | ssh
DO_LOGIN=1             # авторизация через gh (PAT)
DO_SSH=0               # сгенерировать SSH-ключ вместо PAT
DO_PUSH=0              # сделать коммит и push
DO_CREATE=0            # создать репозиторий на GitHub, если его нет
VISIBILITY="private"   # private | public
TOKEN_FILE=""          # файл с PAT (для неинтерактивного входа)
BRANCH="main"
CHECK_ONLY=0
COMMIT_MSG="Настройка проекта"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

# ------------------------------------------------------------------ аргументы
while [ $# -gt 0 ]; do
    case "$1" in
        --name)         NAME="${2:-}"; shift 2 ;;
        --email)        EMAIL="${2:-}"; shift 2 ;;
        --login)        LOGIN="${2:-}"; shift 2 ;;
        --repo)         REPO="${2:-}"; shift 2 ;;
        --protocol)     PROTO="${2:-}"; shift 2 ;;
        --token-file)   TOKEN_FILE="${2:-}"; shift 2 ;;
        --visibility)   VISIBILITY="${2:-}"; shift 2 ;;
        --branch)       BRANCH="${2:-}"; shift 2 ;;
        --message)      COMMIT_MSG="${2:-}"; shift 2 ;;
        --push)         DO_PUSH=1; shift ;;
        --create)       DO_CREATE=1; shift ;;
        --public)       VISIBILITY="public"; shift ;;
        --ssh)          DO_SSH=1; DO_LOGIN=0; shift ;;
        --no-login)     DO_LOGIN=0; shift ;;
        --check)        CHECK_ONLY=1; shift ;;
        -h|--help)      usage ;;
        *) die "неизвестный аргумент: $1 (см. --help)" ;;
    esac
done

# ------------------------------------------------------------------- проверки
command -v git >/dev/null 2>&1 || die "git не установлен: pkg install git"
GIT_VERSION="$(git --version | awk '{print $3}')"
say "git $GIT_VERSION"

if [ "$CHECK_ONLY" = "1" ]; then
    echo
    echo "--- текущее состояние ---"
    echo "user.name : $(git config --global user.name 2>/dev/null || echo '— не задан')"
    echo "user.email: $(git config --global user.email 2>/dev/null || echo '— не задан')"
    echo "branch    : $(git config --global init.defaultBranch 2>/dev/null || echo main)"
    echo "helper    : $(git config --global credential.helper 2>/dev/null || echo '— не задан')"
    echo "gitignore : $(git config --global core.excludesfile 2>/dev/null || echo '— не задан')"
    if command -v gh >/dev/null 2>&1; then
        echo "gh        : $(gh --version | head -1)"
        echo "auth      : $(gh auth status 2>&1 | head -1)"
    else
        echo "gh        : не установлен"
    fi
    echo "ssh-ключи : $(ls -1 ~/.ssh/id_* 2>/dev/null | tr '\n' ' ' || echo 'нет')"
    exit 0
fi

# ------------------------------------------------------------------- gh / ssh
if [ "$DO_SSH" = "1" ]; then
    if ! ls ~/.ssh/id_ed25519* >/dev/null 2>&1; then
        say "генерация SSH-ключа (ed25519)"
        command -v ssh-keygen >/dev/null 2>&1 || pkg install -y openssh
        ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -C "$(git config --global user.email 2>/dev/null || echo "termux")" >/dev/null
        echo "  создан ~/.ssh/id_ed25519"
    else
        say "SSH-ключ уже есть"
    fi
    mkdir -p ~/.ssh
    chmod 700 ~/.ssh
    if [ -f ~/.ssh/config ] && ! grep -q "github.com" ~/.ssh/config; then
        cat >> ~/.ssh/config <<'EOF'

Host github.com
    HostName github.com
    User git
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes
EOF
        chmod 600 ~/.ssh/config
        echo "  в ~/.ssh/config добавлен блок для github.com"
    fi
    say "Open the public key on GitHub -> Settings -> SSH and GPG keys:"
    echo
    cat ~/.ssh/id_ed25519.pub
    echo
    echo "  Проверка после добавления: ssh -T git@github.com"
else
    if ! command -v gh >/dev/null 2>&1; then
        say "установка gh (GitHub CLI)"
        if command -v pkg >/dev/null 2>&1; then
            pkg install -y gh >/dev/null 2>&1 || warn "не удалось поставить gh через pkg"
        fi
    fi
    command -v gh >/dev/null 2>&1 || die "не удалось установить gh"
    say "gh $(gh --version | head -1)"

    if [ "$DO_LOGIN" = "1" ]; then
        if gh auth status >/dev/null 2>&1; then
            say "уже авторизованы: $(gh auth status 2>&1 | head -1)"
        elif [ -n "$TOKEN_FILE" ]; then
            say "авторизация по токену из $TOKEN_FILE"
            [ -f "$TOKEN_FILE" ] || die "файл токена не найден: $TOKEN_FILE"
            gh auth login --hostname github.com --git-protocol "$PROTO" --with-token < "$TOKEN_FILE"
        else
            say "авторизация (вставьте токен в терминале)"
            echo "  Токен: https://github.com/settings/tokens → Generate new token (classic) → scope 'repo'"
            gh auth login --hostname github.com --git-protocol "$PROTO"
        fi
        gh auth setup-git >/dev/null 2>&1 || true
        say "авторизация: $(gh auth status 2>&1 | head -1)"
    fi
fi

# ------------------------------------------------------- определение ника/email
detect_login_from_repo() {
    local r="$1"
    case "$r" in
        *github.com[:/]*) echo "$r" | sed -E 's#.*github\.com[:/]+([^/]+)/.*#\1#' ;;
        *) echo "" ;;
    esac
}

if [ -z "$LOGIN" ] && [ -n "$REPO" ]; then
    LOGIN="$(detect_login_from_repo "$REPO")"
    [ -n "$LOGIN" ] && say "GitHub-ник из URL: $LOGIN"
fi

if [ -z "$NAME" ]; then
    NAME="$(git config --global user.name 2>/dev/null || true)"
    [ -z "$NAME" ] && NAME="$LOGIN"
fi

if [ -z "$EMAIL" ] && [ -n "$LOGIN" ]; then
    # GitHub noreply-email: <id>+<login>@users.noreply.github.com
    UID_="$(curl -s -H 'User-Agent: setup_git.sh' "https://api.github.com/users/$LOGIN" \
            | sed -nE 's/.*"id"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' | head -1)"
    if [ -n "$UID_" ]; then
        EMAIL="${UID_}+${LOGIN}@users.noreply.github.com"
        say "noreply-email: $EMAIL"
    fi
fi

if [ -z "$EMAIL" ]; then
    [ -t 0 ] || die "нужен --email (неинтерактивный режим)"
    printf 'Email для коммитов: '
    read -r EMAIL
fi
[ -n "$NAME" ] || die "нужно --name"

# ------------------------------------------------------------------- .gitconfig
say "настройка ~/.gitconfig"
if [ -f ~/.gitconfig ]; then
    cp ~/.gitconfig ~/.gitconfig.bak-$(date +%Y%m%d-%H%M%S)
    echo "  старый файл сохранён как ~/.gitconfig.bak-*"
fi
git config --global user.name "$NAME"
git config --global user.email "$EMAIL"
git config --global init.defaultBranch "$BRANCH"
git config --global push.default simple
git config --global push.followTags true
git config --global pull.rebase false
git config --global fetch.prune true
git config --global core.autocrlf input
git config --global color.ui auto
git config --global core.editor "${EDITOR:-nano}"
git config --global alias.st "status -sb"
git config --global alias.lg "log --oneline --graph --decorate -20"
git config --global alias.co "checkout"
git config --global alias.br "branch"
git config --global safe.directory "*"
if [ "$DO_SSH" = "0" ] && command -v gh >/dev/null 2>&1; then
    git config --global credential.helper "!gh auth git-credential"
fi
echo "  user.name  = $NAME"
echo "  user.email = $EMAIL"

# ------------------------------------------------------------------- репозиторий
if [ -n "$REPO" ]; then
    if git remote get-url origin >/dev/null 2>&1; then
        git remote set-url origin "$REPO"
    else
        git remote add origin "$REPO"
    fi
    say "remote origin -> $REPO"

    # создать репозиторий, если его нет
    if [ "$DO_CREATE" = "1" ] && command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        OWNER="$(detect_login_from_repo "$REPO")"
        NAME_REPO="$(echo "$REPO" | sed -E 's#.*github\.com[:/]+[^/]+/##; s#\.git$##')"
        if gh repo view "$OWNER/$NAME_REPO" >/dev/null 2>&1; then
            say "репозиторий $OWNER/$NAME_REPO уже существует"
        else
            say "создание репозитория $OWNER/$NAME_REPO ($VISIBILITY)"
            gh repo create "$OWNER/$NAME_REPO" --"$VISIBILITY" --source=. --remote=origin >/dev/null
        fi
    fi
fi

# -------------------------------------------------------------------- push
if [ "$DO_PUSH" = "1" ]; then
    say "коммит и push"
    git add -A
    if git diff --cached --quiet; then
        echo "  изменений нет — коммит не создаётся"
    else
        git commit -q -m "$COMMIT_MSG"
    fi
    BRANCH_NOW="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "$BRANCH")"
    git push -u origin "$BRANCH_NOW" && say "запушено в origin/$BRANCH_NOW"
fi

echo
say "готово"
cat <<EOF
  user.name  : $NAME
  user.email : $EMAIL
  branch     : $BRANCH
$( [ -n "$REPO" ] && echo "  origin     : $REPO" )
$( [ "$DO_SSH" = "1" ] && echo "  ssh-ключ   : ~/.ssh/id_ed25519 (публичный — добавьте на GitHub)" )
EOF
