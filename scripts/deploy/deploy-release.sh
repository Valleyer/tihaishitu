#!/usr/bin/env bash
# Wanjing Academy - live Linux release script (CentOS/systemd/Docker layout, 2026-10-08).
# Run: bash /usr/local/deploy/deploy-release.sh
# This script NEVER edits the DB manually and NEVER automatically rolls back migrations.
set -Eeuo pipefail
umask 077

UPLOAD=/usr/local/deploy
APP_ROOT=/usr/local/wanjingqiuzhi
JAR_LIVE="$APP_ROOT/backend/tihaishitu-backend.jar"
BACKUP_ROOT="$APP_ROOT/backup"
RELEASE_ROOT="$APP_ROOT/releases"
WEB_ROOT=/usr/local/docker/nginx/html
SERVICE=wanjingqiuzhi
HEALTH_URL=http://172.17.0.1:12345/actuator/health
SITE_URL=http://127.0.0.1/
LOCK=/run/lock/wanjingqiuzhi-release.lock
RELEASE_MARKER="$APP_ROOT/current-release.sha"
ENV_FILE=/etc/wanjingqiuzhi/backend.env
PHASE=preflight
NEW_SERVICE_STARTED=0

fail() {
  printf '\n[ERROR] %s\n' "$*" >&2
  if [[ "$PHASE" == backend_start && "$NEW_SERVICE_STARTED" == 1 ]]; then
    printf '[INFO] Stopping failed new backend to prevent auto-restart loops.\n' >&2
    systemctl stop "$SERVICE" >/dev/null 2>&1 || true
  fi
  printf '[INFO] Release stopped. Keep backup/logs, do not blindly repair DB or retry.\n' >&2
  exit 1
}
on_error() {
  local code=$?
  trap - ERR
  printf '\n[FAILED] Release stopped in phase "%s" (line %s, exit %s).\n' "$PHASE" "$1" "$code" >&2
  if [[ "$PHASE" == backend_start && "$NEW_SERVICE_STARTED" == 1 ]]; then
    printf '[INFO] Stopping failed new service to prevent restart loops.\n' >&2
    systemctl stop "$SERVICE" >/dev/null 2>&1 || true
  fi
  printf '[INFO] Do not blindly re-run, repair Flyway, restore the old JAR, or overwrite the DB.\n' >&2
  printf '[INFO] Inspect: systemctl status %s -l; journalctl -u %s -n 150 --no-pager\n' "$SERVICE" "$SERVICE" >&2
  printf '[INFO] Keep backup and staged release files; consult AI with redacted logs.\n' >&2
  exit "$code"
}
trap 'on_error "$LINENO"' ERR

read_env_value() {
  local key="$1"
  local value
  value="$(awk -v key="$key" '
    /^[[:space:]]*#/ { next }
    {
      line=$0
      sub(/\r$/, "", line)
      if (line ~ "^[[:space:]]*" key "[[:space:]]*=") {
        sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", line)
        print line
        exit
      }
    }
  ' "$ENV_FILE")"
  if [[ ${#value} -ge 2 ]]; then
    local first="${value:0:1}"
    local last="${value: -1}"
    if [[ ( "$first" == '"' && "$last" == '"' ) || ( "$first" == "'" && "$last" == "'" ) ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s' "$value"
}

path_is_same_or_below() {
  local path="$1"
  local parent="$2"
  [[ "$path" == "$parent" || "$path" == "$parent/"* ]]
}

[[ "${EUID:-$(id -u)}" -eq 0 ]] || fail 'Run with root/sudo.'
[[ -t 0 ]] || fail 'Interactive terminal required to confirm DB backup.'
for cmd in flock systemctl docker curl sha256sum unzip tar grep awk tr df install mv cp cmp date seq sleep runuser readlink; do
  command -v "$cmd" >/dev/null 2>&1 || fail "Missing required command: $cmd"
done

exec 9>"$LOCK"
flock -n 9 || fail 'Another deployment is running (lock held).'

[[ -f "$UPLOAD/tihaishitu-backend.jar" && ! -L "$UPLOAD/tihaishitu-backend.jar" ]] || fail 'Missing uploaded JAR.'
[[ -f "$UPLOAD/frontend-dist.zip" && ! -L "$UPLOAD/frontend-dist.zip" ]] || fail 'Missing uploaded frontend ZIP.'
[[ -f "$UPLOAD/release.sha256" && ! -L "$UPLOAD/release.sha256" ]] || fail 'Missing release.sha256.'
[[ -f "$UPLOAD/release.info" && ! -L "$UPLOAD/release.info" ]] || fail 'Missing release.info.'

cd "$UPLOAD"
# build-release.bat writes text files with Windows CRLF. Normalize carriage returns in-memory
# so Linux sha256sum does not treat "\r" as part of the listed filenames.
tr -d '\r' < release.sha256 | sha256sum -c - || fail 'SHA-256 mismatch: re-upload the complete release.'
# No shell sourcing/eval of uploaded metadata; only a 40-character Git SHA is accepted.
SHA="$(awk -F= '$1 == "COMMIT_SHA" { print $2 }' release.info | tr -d '\r')"
[[ "$SHA" =~ ^[0-9a-fA-F]{40}$ ]] || fail 'Invalid/duplicate COMMIT_SHA in release.info.'
[[ "$(tr -d '\r' < release.info | grep -c '^BRANCH=main$' || true)" == 1 ]] || fail 'Release was not built from main.'
SHORT_SHA="${SHA:0:7}"
if [[ -f "$RELEASE_MARKER" && "$(cat "$RELEASE_MARKER")" == "$SHA" ]]; then
  fail "This exact Git SHA is already marked deployed: $SHORT_SHA"
fi

[[ -s "$JAR_LIVE" && -f "$JAR_LIVE" ]] || fail "Live JAR not found: $JAR_LIVE"
[[ -f "$WEB_ROOT/index.html" ]] || fail "Live frontend root not found: $WEB_ROOT"
[[ -d "$BACKUP_ROOT" || -d "$APP_ROOT" ]] || fail 'App root not found.'
systemctl is-active --quiet "$SERVICE" || fail "Existing $SERVICE must be healthy before starting an automated update."
BEFORE_HEALTH="$(curl -fsS --max-time 8 "$HEALTH_URL")" || fail 'Old backend health request failed.'
printf '%s' "$BEFORE_HEALTH" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' || fail 'Old backend is not UP.'
[[ "$(docker inspect -f '{{.State.Running}}' nginx)" == true ]] || fail 'Nginx Docker container not running.'
[[ "$(docker inspect -f '{{.State.Running}}' mysql57)" == true ]] || fail 'MySQL Docker container not running.'
AVAILABLE_MB="$(df -Pm "$APP_ROOT" | awk 'NR==2 {print $4}')"
[[ "$AVAILABLE_MB" =~ ^[0-9]+$ ]] || fail 'Unable to read free disk capacity.'
(( AVAILABLE_MB >= 600 )) || fail "Not enough free space ($AVAILABLE_MB MB; require >=600 MB)."

[[ -f "$ENV_FILE" ]] || fail "Production environment file missing: $ENV_FILE"
IMAGE_ENV_COUNT="$(awk '
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*QUESTION_IMAGE_DIR[[:space:]]*=/ { count++ }
  END { print count + 0 }
' "$ENV_FILE")"
[[ "$IMAGE_ENV_COUNT" == 1 ]] || fail "Expected exactly one QUESTION_IMAGE_DIR entry in $ENV_FILE; found $IMAGE_ENV_COUNT."
QUESTION_IMAGE_DIR_CONFIGURED="$(read_env_value QUESTION_IMAGE_DIR)"
[[ -n "$QUESTION_IMAGE_DIR_CONFIGURED" ]] || fail "QUESTION_IMAGE_DIR is empty in $ENV_FILE. Configure an explicit persistent absolute path before deployment."
[[ "$QUESTION_IMAGE_DIR_CONFIGURED" == /* ]] || fail "QUESTION_IMAGE_DIR must be an absolute path: $QUESTION_IMAGE_DIR_CONFIGURED"
[[ -d "$QUESTION_IMAGE_DIR_CONFIGURED" ]] || fail "QUESTION_IMAGE_DIR does not exist: $QUESTION_IMAGE_DIR_CONFIGURED. Create it deliberately with the systemd service user before deployment."
QUESTION_IMAGE_DIR="$(readlink -f -- "$QUESTION_IMAGE_DIR_CONFIGURED")"
[[ -n "$QUESTION_IMAGE_DIR" && -d "$QUESTION_IMAGE_DIR" ]] || fail "Unable to resolve QUESTION_IMAGE_DIR: $QUESTION_IMAGE_DIR_CONFIGURED"
[[ "$QUESTION_IMAGE_DIR" != "/" ]] || fail 'QUESTION_IMAGE_DIR must not be /.'
for protected in "$WEB_ROOT" "$UPLOAD" "$RELEASE_ROOT" "$BACKUP_ROOT"; do
  if path_is_same_or_below "$QUESTION_IMAGE_DIR" "$protected"; then
    fail "Unsafe QUESTION_IMAGE_DIR ($QUESTION_IMAGE_DIR): it must not be the same as or below $protected"
  fi
done

SERVICE_USER="$(systemctl show "$SERVICE" -p User | awk -F= '$1 == "User" { print $2; exit }')"
[[ -n "$SERVICE_USER" ]] || SERVICE_USER=root
runuser -u "$SERVICE_USER" -- test -r "$QUESTION_IMAGE_DIR"   || fail "QUESTION_IMAGE_DIR is not readable by systemd service user $SERVICE_USER: $QUESTION_IMAGE_DIR"
runuser -u "$SERVICE_USER" -- test -w "$QUESTION_IMAGE_DIR"   || fail "QUESTION_IMAGE_DIR is not writable by systemd service user $SERVICE_USER: $QUESTION_IMAGE_DIR"
runuser -u "$SERVICE_USER" -- test -x "$QUESTION_IMAGE_DIR"   || fail "QUESTION_IMAGE_DIR is not traversable by systemd service user $SERVICE_USER: $QUESTION_IMAGE_DIR"

printf '\nRelease: %s\n' "$SHA"
printf 'Backend: %s\nFrontend: %s\n' "$JAR_LIVE" "$WEB_ROOT"
printf '\nBEFORE CONTINUING:\n'
printf '1. Have you manually exported the entire LIVE tihaishitu DB\n'
printf '   (schema + data + flyway_schema_history) and verified the backup?\n'
printf '2. QUESTION_IMAGE_DIR is configured and writable. After YES this script will\n'
printf '   snapshot the complete immutable image directory before stopping the old backend.\n'
printf '3. Do you accept the maintenance window and non-auto-reversible DB migration?\n'
read -r -p 'Type YES to proceed: ' CONFIRM
[[ "$CONFIRM" == YES ]] || fail 'User did not confirm a production DB backup.'

PHASE=staging
STAMP="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="$RELEASE_ROOT/release-$SHORT_SHA-$STAMP"
BACKUP_DIR="$BACKUP_ROOT/pre-release-$SHORT_SHA-$STAMP"
mkdir -p "$RELEASE_DIR/frontend" "$BACKUP_DIR"
install -m 0644 "$UPLOAD/tihaishitu-backend.jar" "$RELEASE_DIR/tihaishitu-backend.jar"
install -m 0644 "$UPLOAD/frontend-dist.zip" "$RELEASE_DIR/frontend-dist.zip"
install -m 0644 "$UPLOAD/release.info" "$RELEASE_DIR/release.info"
FRONTEND_ZIP_TEST_RC=0
unzip -tq "$RELEASE_DIR/frontend-dist.zip" >/dev/null 2>&1 || FRONTEND_ZIP_TEST_RC=$?
if (( FRONTEND_ZIP_TEST_RC > 1 )); then
  fail "Frontend ZIP integrity test failed (unzip exit $FRONTEND_ZIP_TEST_RC)."
fi
if (( FRONTEND_ZIP_TEST_RC == 1 )); then
  printf '[WARN] Frontend ZIP integrity test returned warnings; continuing to real extraction and structure validation.\n'
fi

unzip -tq "$RELEASE_DIR/tihaishitu-backend.jar" >/dev/null || fail 'Backend JAR is not a valid ZIP/JAR archive.'

FRONTEND_UNZIP_RC=0
unzip -oq "$RELEASE_DIR/frontend-dist.zip" -d "$RELEASE_DIR/frontend" >/dev/null 2>&1 || FRONTEND_UNZIP_RC=$?
if (( FRONTEND_UNZIP_RC > 1 )); then
  fail "Frontend ZIP extraction failed (unzip exit $FRONTEND_UNZIP_RC)."
fi
if (( FRONTEND_UNZIP_RC == 1 )); then
  printf '[WARN] Frontend ZIP extraction returned warnings (common for Windows path separators); validating extracted files.\n'
fi
[[ -s "$RELEASE_DIR/frontend/index.html" && -d "$RELEASE_DIR/frontend/assets" ]] || fail 'ZIP content lacks index.html/assets. Stop before changing live system.'
# Staging uses umask 077 for private backups; explicitly make PUBLIC static assets readable.
chmod -R u+rwX,go+rX "$RELEASE_DIR/frontend"
[[ -s "$RELEASE_DIR/frontend/favicon.ico" ]] || fail 'ZIP is missing favicon.ico.'

PHASE=backup
printf '\n[INFO] Backing up old backend, frontend and systemd/nginx config...\n'
/bin/cp -L "$JAR_LIVE" "$BACKUP_DIR/tihaishitu-backend.jar"
tar -czf "$BACKUP_DIR/frontend-old.tar.gz" -C "$WEB_ROOT" .
tar -czf "$BACKUP_DIR/nginx-conf-old.tar.gz" -C /usr/local/docker/nginx conf
/bin/cp -a /etc/systemd/system/wanjingqiuzhi.service "$BACKUP_DIR/wanjingqiuzhi.service"
if [[ -f "$ENV_FILE" ]]; then
  /bin/cp -p "$ENV_FILE" "$BACKUP_DIR/backend.env"
  chmod 0600 "$BACKUP_DIR/backend.env"
fi

printf '[INFO] Snapshotting immutable question images before stopping the backend...\n'
tar -czf "$BACKUP_DIR/question-images-old.tar.gz" -C "$QUESTION_IMAGE_DIR" .
printf 'QUESTION_IMAGE_DIR=%s\n' "$QUESTION_IMAGE_DIR_CONFIGURED" > "$BACKUP_DIR/question-images.info"
printf 'RESOLVED_QUESTION_IMAGE_DIR=%s\n' "$QUESTION_IMAGE_DIR" >> "$BACKUP_DIR/question-images.info"
[[ -s "$BACKUP_DIR/question-images-old.tar.gz" && -s "$BACKUP_DIR/question-images.info" ]]   || fail 'Question image backup is incomplete.'
tar -tzf "$BACKUP_DIR/question-images-old.tar.gz" >/dev/null   || fail 'Question image backup archive is unreadable.'

[[ -s "$BACKUP_DIR/tihaishitu-backend.jar" && -s "$BACKUP_DIR/frontend-old.tar.gz" ]] || fail 'Program backup is incomplete.'
tar -tzf "$BACKUP_DIR/frontend-old.tar.gz" | grep '^\./index.html$' >/dev/null || fail 'Frontend backup is missing index.html.'
printf '[INFO] Backup saved: %s\n' "$BACKUP_DIR"

PHASE=backend_start
printf '\n[INFO] Stopping old backend; starting new one (Flyway may migrate DB)...\n'
systemctl stop "$SERVICE"
install -m 0644 "$RELEASE_DIR/tihaishitu-backend.jar" "$JAR_LIVE.new"
mv -f "$JAR_LIVE.new" "$JAR_LIVE"
NEW_SERVICE_STARTED=1
systemctl start "$SERVICE"

HEALTHY=0
for i in $(seq 1 90); do
  if systemctl is-active --quiet "$SERVICE"; then
    HEALTH_JSON="$(curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null || true)"
    if printf '%s' "$HEALTH_JSON" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      HEALTHY=1
      break
    fi
  fi
  sleep 2
done
[[ "$HEALTHY" == 1 ]] || fail 'New backend failed health check after up to 180 seconds. Frontend was NOT published.'
printf '[INFO] New backend healthy.\n'

PHASE=frontend_publish
printf '[INFO] Publishing frontend assets first, index.html LAST...\n'
# Public files must be readable by the Nginx worker, unlike private backups.
umask 022
# Do not delete old hashed assets: open tabs may still need them.
# tar excludes index.html so an old index remains until every new resource is copied.
tar -C "$RELEASE_DIR/frontend" --exclude='./index.html' -cf - . | tar -C "$WEB_ROOT" -xf -
/bin/cp -f "$RELEASE_DIR/frontend/index.html" "$WEB_ROOT/.index.html.$SHORT_SHA.new"
chmod 0644 "$WEB_ROOT/.index.html.$SHORT_SHA.new"
mv -f "$WEB_ROOT/.index.html.$SHORT_SHA.new" "$WEB_ROOT/index.html"
umask 077
cmp -s "$RELEASE_DIR/frontend/index.html" "$WEB_ROOT/index.html" || fail 'Published index differs from new index.'
curl -fsS --max-time 8 "$SITE_URL" | grep -q '<title>' || fail 'Nginx is not serving a valid index with a title.'
curl -fsSI --max-time 8 "${SITE_URL}favicon.ico" >/dev/null || fail 'Nginx favicon endpoint failed.'
FINAL_HEALTH="$(curl -fsS --max-time 8 "$HEALTH_URL")"
printf '%s' "$FINAL_HEALTH" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' || fail 'Backend failed after frontend publication.'

PHASE=finalizing
printf '%s\n' "$SHA" > "$RELEASE_MARKER"
chmod 0644 "$RELEASE_MARKER"
printf '\n[SUCCESS] Wanjing Academy release deployed.\n'
printf 'Commit: %s\nStaging: %s\nBackup: %s\n' "$SHA" "$RELEASE_DIR" "$BACKUP_DIR"
printf 'Question images: %s\n' "$QUESTION_IMAGE_DIR"
printf 'Image backup: %s\n' "$BACKUP_DIR/question-images-old.tar.gz"
printf 'Backend health: %s\n' "$FINAL_HEALTH"
printf 'DB migration verification: inspect flyway_schema_history and compare business counts if this release contains migrations.\n'
printf 'Now verify login, study, questions, World and management features in browser.\n'
