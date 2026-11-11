#!/usr/bin/env bash
# Runs Keycloak from its own distribution (no Docker) as the viewer's sign-in for local development.
#
#   ./keycloak.sh start     download (first time), import the sapiensml realm, start on http://localhost:18210
#   ./keycloak.sh stop | status | logs
#   ./keycloak.sh configure re-apply local.env (client secret, viewer port) to the running Keycloak
#   ./keycloak.sh reset     stop and forget the realm and users (keeps the download)
#
# Admin console: http://localhost:18210/admin (KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD in local.env, admin/admin).
# Test users in realm sapiensml: viewer/viewer (DOCUMENT_VIEWER), vieweradmin/vieweradmin (VIEWER_ADMIN and
# DOCUMENT_VIEWER: the admin page at /admin) and noaccess/noaccess (no role).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK="$HERE/.work"
ENV_FILE="$HERE/local.env"
REALM_TEMPLATE="$HERE/../keycloak/sapiensml-realm.template.json"

mkdir -p "$WORK"
[ -f "$ENV_FILE" ] || cp "$HERE/local.env.example" "$ENV_FILE"
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

VERSION=${KEYCLOAK_VERSION:-26.8.2}
PORT=${KEYCLOAK_PORT:-18210}
ADMIN=${KEYCLOAK_ADMIN:-admin}
ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin}
VIEWER_PORT=${VIEWER_PORT:-18200}
KC_HOME="$WORK/keycloak-$VERSION"
PID_FILE="$WORK/keycloak.pid"
LOG_FILE="$WORK/keycloak.log"
KCADM_CONFIG="$WORK/kcadm.config"

say() { printf '%s\n' "$*"; }
fail() { printf 'keycloak.sh: %s\n' "$*" >&2; exit 1; }

# Keycloak 26 needs Java 21 or newer
use_java() {
    if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    fi
    local java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
    command -v "$java" >/dev/null 2>&1 || fail "Java 21 not found; set JAVA_HOME"
    local major
    major=$("$java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
    [ "${major%%.*}" -ge 21 ] || fail "Keycloak needs Java 21 or newer, found $major ($java); set JAVA_HOME"
    export JAVA_HOME
}

running() {
    [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

# Writes KEY=value into local.env, replacing an existing line
set_env() {
    local key=$1 value=$2 tmp
    tmp=$(mktemp "$WORK/env.XXXXXX")
    awk -v k="$key" -v v="$value" 'BEGIN { done = 0 }
        $0 ~ "^" k "=" { print k "=" v; done = 1; next } { print }
        END { if (!done) print k "=" v }' "$ENV_FILE" > "$tmp"
    cat "$tmp" > "$ENV_FILE"
    rm -f "$tmp"
}

download() {
    [ -x "$KC_HOME/bin/kc.sh" ] && return
    local archive="$WORK/keycloak-$VERSION.tar.gz"
    local url="https://github.com/keycloak/keycloak/releases/download/$VERSION/keycloak-$VERSION.tar.gz"
    say "Downloading Keycloak $VERSION (about 170 MB)..."
    curl -fL --retry 3 --progress-bar -o "$archive" "$url"
    local expected actual
    expected=$(curl -fsSL "$url.sha1" | awk '{print $1}')
    if command -v sha1sum >/dev/null; then
        actual=$(sha1sum "$archive" | awk '{print $1}')
    else
        actual=$(shasum -a 1 "$archive" | awk '{print $1}')
    fi
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || { rm -f "$archive"; fail "checksum mismatch for $archive"; }
    tar -xzf "$archive" -C "$WORK"
    rm -f "$archive"
}

render_realm() {
    mkdir -p "$KC_HOME/data/import"
    sed -e "s|@VIEWER_CLIENT_SECRET@|$VIEWER_OIDC_CLIENT_SECRET|" \
        -e "s|@VIEWER_REDIRECT_URIS@|\"http://localhost:$VIEWER_PORT/login/oauth2/code/viewer\", \"http://localhost:5173/login/oauth2/code/viewer\"|" \
        -e "s|@VIEWER_POST_LOGOUT_URIS@|http://localhost:$VIEWER_PORT/signed-out##http://localhost:5173/signed-out|" \
        "$REALM_TEMPLATE" > "$KC_HOME/data/import/sapiensml-realm.json"
}

kcadm() {
    "$KC_HOME/bin/kcadm.sh" "$@" --config "$KCADM_CONFIG"
}

add_user() {
    local username=$1 first=$2 last=$3 id
    shift 3
    id=$(kcadm get users -r sapiensml -q "username=$username" -q exact=true --fields id --format csv --noquotes)
    if [ -z "$id" ]; then
        kcadm create users -r sapiensml -s "username=$username" -s enabled=true -s "firstName=$first" -s "lastName=$last" \
            -s "email=$username@example.test" -s emailVerified=true >/dev/null
        kcadm set-password -r sapiensml --username "$username" --new-password "$username" >/dev/null
        say "Created test user $username/$username"
    fi
    local role
    for role in "$@"; do
        kcadm add-roles -r sapiensml --uusername "$username" --rolename "$role" >/dev/null
    done
}

add_role() {
    if ! kcadm get "roles/$1" -r sapiensml >/dev/null 2>&1; then
        kcadm create roles -r sapiensml -s "name=$1" -s "description=$2" >/dev/null
        say "Created realm role $1"
    fi
}

start() {
    if running; then
        say "Keycloak is already running: http://localhost:$PORT (pid $(cat "$PID_FILE"))"
        return
    fi
    use_java
    if [ -z "${VIEWER_OIDC_CLIENT_SECRET:-}" ]; then
        VIEWER_OIDC_CLIENT_SECRET=$(openssl rand -hex 24)
        set_env VIEWER_OIDC_CLIENT_SECRET "$VIEWER_OIDC_CLIENT_SECRET"
        say "Generated the viewer's client secret into local.env"
    fi
    download
    render_realm
    say "Starting Keycloak $VERSION on http://localhost:$PORT (log: $LOG_FILE)"
    # Small heap: this is one realm and a handful of users
    KC_BOOTSTRAP_ADMIN_USERNAME="$ADMIN" KC_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
        JAVA_OPTS_KC_HEAP="-Xms128m -Xmx512m" \
        nohup "$KC_HOME/bin/kc.sh" start-dev --http-host=127.0.0.1 --http-port="$PORT" --import-realm \
        > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    local waited=0
    until curl -fs "http://127.0.0.1:$PORT/realms/sapiensml/.well-known/openid-configuration" >/dev/null 2>&1; do
        if ! running; then
            tail -40 "$LOG_FILE" >&2
            fail "Keycloak stopped while starting"
        fi
        [ "$waited" -lt 240 ] || { tail -40 "$LOG_FILE" >&2; fail "Keycloak did not answer within 240 s"; }
        sleep 3
        waited=$((waited + 3))
    done
    rm -f "$KC_HOME/data/import/sapiensml-realm.json"
    configure
    say "Keycloak ready: issuer http://localhost:$PORT/realms/sapiensml, admin console http://localhost:$PORT/admin ($ADMIN)"
}

# The realm is imported only once; keep the client in step with local.env (secret, viewer port) on every start
configure() {
    use_java
    kcadm config credentials --server "http://127.0.0.1:$PORT" --realm master --user "$ADMIN" --password "$ADMIN_PASSWORD" 2>/dev/null
    local client
    client=$(kcadm get clients -r sapiensml -q clientId=sapiensml-document-viewer --fields id --format csv --noquotes)
    kcadm update "clients/$client" -r sapiensml -s "secret=$VIEWER_OIDC_CLIENT_SECRET" \
        -s "redirectUris=[\"http://localhost:$VIEWER_PORT/login/oauth2/code/viewer\",\"http://localhost:5173/login/oauth2/code/viewer\"]" \
        -s "attributes.\"post.logout.redirect.uris\"=http://localhost:$VIEWER_PORT/signed-out##http://localhost:5173/signed-out"
    # Realms imported before the admin page existed lack this role
    add_role VIEWER_ADMIN "May set up the SapiensML Document Viewer's Alfresco connection (admin page)"
    add_user viewer Vera Viewer DOCUMENT_VIEWER
    add_user vieweradmin Ada Admin VIEWER_ADMIN DOCUMENT_VIEWER
    add_user noaccess Nora Access
}

stop() {
    if ! running; then
        say "Keycloak is not running"
        rm -f "$PID_FILE"
        return
    fi
    local pid
    pid=$(cat "$PID_FILE")
    kill "$pid"
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    rm -f "$PID_FILE"
    say "Keycloak stopped"
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    configure) running || fail "Keycloak is not running"; configure; say "Client updated for the viewer on port $VIEWER_PORT" ;;
    status)
        if running; then say "running (pid $(cat "$PID_FILE")): http://localhost:$PORT"; else say "stopped"; fi ;;
    logs) tail -f "$LOG_FILE" ;;
    reset)
        stop
        rm -rf "$KC_HOME/data" "$KCADM_CONFIG"
        say "Realm and users removed; the next start imports them again" ;;
    *)
        sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
        exit 1 ;;
esac
