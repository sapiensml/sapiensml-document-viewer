#!/usr/bin/env bash
# Installs, upgrades and manages the SapiensML Document Viewer as a systemd service on Linux (RHEL, Rocky, Alma, Oracle,
# Amazon Linux, Ubuntu, Debian, SUSE). Run as root from the unpacked release (made by deploy/package.sh). Whatever
# the viewer needs and the server lacks (Java 21, curl, tar, openssl, nginx) is installed with the distribution's
# package manager unless --no-deps is given.
#
#   sudo ./install.sh all --viewer-host viewer.example.com --keycloak-host sso.example.com --self-signed
#                     Everything on one fresh server: dependencies, PostgreSQL, Keycloak, the viewer, nginx for
#                     both, the first admin user. Use --cert/--key instead of --self-signed for real certificates
#                     (more options under "all" below)
#   sudo ./install.sh install       install, or upgrade to the jar next to this script. Keeps the settings; after
#                                   an upgrade waits for the viewer to be healthy and rolls back if not
#   sudo ./install.sh deps [--with-nginx]   only install what is missing
#   sudo ./install.sh nginx --server-name viewer.example.com (--cert FILE --key FILE | --self-signed)
#                                   put nginx (TLS) in front of the viewer
#   sudo ./install.sh rollback      back to the previous release
#   sudo ./install.sh status        service state, health, installed releases
#   sudo ./install.sh uninstall [--purge]   remove the service and releases (--purge: settings, logs, user too)
#
# "all" options:
#   --viewer-host NAME, --keycloak-host NAME   public host names (two names, both pointing at this server)
#   --cert FILE --key FILE | --self-signed     TLS for both sites (--keycloak-cert/--keycloak-key for another pair)
#   --ca-cert FILE         the CA that signed --cert, when it is an internal one, so the viewer trusts Keycloak
#   --keycloak-db local    PostgreSQL on this server, installed if missing (default)
#   --keycloak-db dev-file Keycloak's own file database: a single test server only
#   --db-url JDBC --db-username NAME --db-password-file FILE   an existing PostgreSQL
#   --first-user NAME      create this Keycloak user with both viewer roles (default: vieweradmin)
#   --add-hosts            resolve the two names to this server in /etc/hosts (test servers without DNS)
#   --java-archive FILE, --keycloak-archive FILE   use these downloads instead of fetching them (offline servers)
#
# Layout: /opt/sapiensml-document-viewer/releases/*.jar and current.jar (link), /etc/sapiensml-document-viewer/viewer.env
# (settings and secrets, root:sapiensml 0640), /var/lib/sapiensml-document-viewer (the Alfresco connection saved on
# the admin page, 0700), /var/log/sapiensml-document-viewer (audit trail). Logs: journalctl -u sapiensml-document-viewer
set -euo pipefail

APP=sapiensml-document-viewer
APP_USER=${APP_USER:-sapiensml}
PREFIX=/opt/$APP
CONF_DIR=/etc/$APP
LOG_DIR=/var/log/$APP
ENV_FILE=$CONF_DIR/viewer.env
UNIT_FILE=/etc/systemd/system/$APP.service
NGINX_FILE=/etc/nginx/conf.d/$APP.conf
TRUSTSTORE=$CONF_DIR/truststore.p12
KEEP_RELEASES=5
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-180}

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

say() { printf '%s\n' "$*"; }
fail() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib.sh
. "$HERE/lib.sh"

require_root() {
    [ "$(id -u)" -eq 0 ] || fail "run as root (sudo $0 ...)"
    command -v systemctl >/dev/null || fail "systemd is required"
}

env_value() {
    env_file_get "$ENV_FILE" "$@"
}

# Sets JAVA21: installs Java 21 when allowed and missing
use_java() {
    local install_missing=$1
    JAVA_BIN=$(env_value JAVA_BIN)
    if $install_missing; then
        ensure_java
    else
        JAVA21=$(find_java21) || fail "Java 21 not found. Install it, or set JAVA_BIN in $ENV_FILE, or run without --no-deps"
    fi
}

find_jar() {
    local jar
    for jar in "$HERE/$APP.jar" "$HERE/../../target/$APP.jar"; do
        if [ -f "$jar" ]; then
            readlink -f "$jar"
            return
        fi
    done
    fail "no $APP.jar next to this script (build the release with deploy/package.sh)"
}

jar_version() {
    local version=""
    if [ -f "$HERE/VERSION" ]; then
        version=$(cat "$HERE/VERSION")
    elif command -v unzip >/dev/null; then
        version=$(unzip -p "$1" META-INF/build-info.properties 2>/dev/null | awk -F= '$1 == "build.version" { print $2 }')
    fi
    printf '%s' "${version:-unknown}"
}

http_get() {
    if command -v curl >/dev/null; then
        curl -fsS --max-time 5 "$1"
    else
        wget -q -T 5 -O - "$1"
    fi
}

wait_healthy() {
    local port url waited=0
    port=$(env_value VIEWER_MANAGEMENT_PORT 8081)
    url="http://127.0.0.1:$port/actuator/health/readiness"
    say "Waiting for $url ..."
    until http_get "$url" 2>/dev/null | grep -q '"UP"'; do
        if [ "$waited" -ge "$HEALTH_TIMEOUT" ]; then
            return 1
        fi
        # systemd restarts a crashed JVM; give up once it has stayed down past the first attempt
        if [ "$waited" -gt 20 ] && ! systemctl is-active --quiet "$APP"; then
            return 1
        fi
        sleep 3
        waited=$((waited + 3))
    done
    say "The viewer is up"
}

render() {
    local template=$1 target=$2
    shift 2
    local expression=()
    while [ $# -gt 0 ]; do
        expression+=(-e "s|@$1@|$2|g")
        shift 2
    done
    sed "${expression[@]}" "$template" > "$target"
}

# The key that encrypts the secrets saved on the admin page lives in viewer.env (root), away from the data
ensure_settings_key() {
    [ -n "$(env_value VIEWER_SETTINGS_KEY)" ] && return
    env_file_set "$ENV_FILE" VIEWER_SETTINGS_KEY "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
    say "Generated VIEWER_SETTINGS_KEY in $ENV_FILE (keep a copy in the password manager)"
}

current_release() {
    readlink "$PREFIX/current.jar" 2>/dev/null || true
}

switch_to() {
    ln -sfn "$1" "$PREFIX/current.jar.new"
    mv -Tf "$PREFIX/current.jar.new" "$PREFIX/current.jar"
}

# User, directories, settings file, jar and systemd unit; does not start anything. Sets RELEASE and PREVIOUS
install_files() {
    local jar version
    jar=$(find_jar)
    version=$(jar_version "$jar")
    if ! id "$APP_USER" >/dev/null 2>&1; then
        useradd --system --home-dir "$PREFIX" --no-create-home --shell "$(command -v nologin || echo /bin/false)" "$APP_USER"
        say "Created system user $APP_USER"
    fi
    install -d -m 0755 -o root -g root "$PREFIX" "$PREFIX/releases"
    install -d -m 0750 -o root -g "$APP_USER" "$CONF_DIR"
    install -d -m 0750 -o "$APP_USER" -g "$APP_USER" "$LOG_DIR"
    if [ ! -f "$ENV_FILE" ]; then
        install -m 0640 -o root -g "$APP_USER" "$HERE/viewer.env.example" "$ENV_FILE"
        say "Created $ENV_FILE (readable by root and $APP_USER only; it holds the client secrets)"
    fi
    ensure_settings_key

    RELEASE="$PREFIX/releases/$APP-$version-$(date +%Y%m%d%H%M%S).jar"
    install -m 0644 -o root -g root "$jar" "$RELEASE"
    render "$HERE/$APP.service" "$UNIT_FILE" APP_USER "$APP_USER" PREFIX "$PREFIX" ENV_FILE "$ENV_FILE" \
        JAVA "$JAVA21" LOG_DIR "$LOG_DIR"
    chmod 0644 "$UNIT_FILE"
    systemctl daemon-reload
    systemctl enable "$APP" >/dev/null 2>&1

    PREVIOUS=$(current_release)
    switch_to "$RELEASE"
    if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$RELEASE" ]; then
        printf '%s\n' "$PREVIOUS" > "$PREFIX/previous-release"
    fi
    say "Installed $APP $version as $RELEASE (Java: $JAVA21)"
}

# Restarts on the new release; back to the previous one when it does not become healthy
start_and_verify() {
    systemctl restart "$APP"
    if ! wait_healthy; then
        say "The new release did not become healthy. Last log lines:"
        journalctl -u "$APP" -n 40 --no-pager || true
        if [ -n "$PREVIOUS" ] && [ -f "$PREVIOUS" ] && [ "$PREVIOUS" != "$RELEASE" ]; then
            say "Rolling back to $PREVIOUS"
            switch_to "$PREVIOUS"
            systemctl restart "$APP"
            wait_healthy || true
        fi
        fail "the viewer did not start; see journalctl -u $APP"
    fi
    prune
}

prune() {
    local keep current
    current=$(current_release)
    keep=$(cat "$PREFIX/previous-release" 2>/dev/null || true)
    find "$PREFIX/releases" -maxdepth 1 -name '*.jar' -printf '%T@ %p\n' 2>/dev/null | sort -rn | cut -d' ' -f2- \
        | tail -n +$((KEEP_RELEASES + 1)) | while read -r old; do
            [ "$old" = "$current" ] || [ "$old" = "$keep" ] || rm -f "$old"
        done
}

install_app() {
    require_root
    local deps=true
    while [ $# -gt 0 ]; do
        case "$1" in
            --no-deps) deps=false; shift ;;
            --java-archive) JAVA_ARCHIVE=$2; shift 2 ;;
            *) fail "unknown option $1" ;;
        esac
    done
    if $deps; then
        ensure_tools
    fi
    use_java "$deps"
    install_files
    # Values only: the file's own comments mention CHANGE_ME
    if grep -q '^[^#]*CHANGE_ME' "$ENV_FILE"; then
        say ""
        say "Next: fill in every CHANGE_ME in $ENV_FILE (sign-in settings), then run: sudo systemctl start $APP"
        say "Then sign in at https://<viewer host>/admin as a VIEWER_ADMIN user and enter the Alfresco connection."
        return
    fi
    start_and_verify
}

deps_only() {
    require_root
    local nginx=false
    while [ $# -gt 0 ]; do
        case "$1" in
            --with-nginx) nginx=true; shift ;;
            --java-archive) JAVA_ARCHIVE=$2; shift 2 ;;
            *) fail "unknown option $1" ;;
        esac
    done
    ensure_tools
    use_java true
    if $nginx; then
        ensure_nginx
        open_web_ports
    fi
    say "Everything the viewer needs is installed"
}

write_viewer_site() {
    local server_name=$1 cert=$2 key=$3
    [ -f "$cert" ] && [ -f "$key" ] || fail "certificate or key file not found"
    render "$HERE/nginx-$APP.conf" "$NGINX_FILE" SERVER_NAME "$server_name" CERT "$cert" KEY "$key" \
        PORT "$(env_value VIEWER_PORT 8080)"
    chmod 0644 "$NGINX_FILE"
    nginx -t
    systemctl enable nginx >/dev/null 2>&1 || true
    systemctl restart nginx
    allow_nginx_proxy
    say "nginx serves https://$server_name"
}

nginx_site() {
    require_root
    local server_name="" cert="" key="" self_signed=false deps=true
    while [ $# -gt 0 ]; do
        case "$1" in
            --server-name) server_name=$2; shift 2 ;;
            --cert) cert=$2; shift 2 ;;
            --key) key=$2; shift 2 ;;
            --self-signed) self_signed=true; shift ;;
            --no-deps) deps=false; shift ;;
            *) fail "unknown option $1" ;;
        esac
    done
    [ -n "$server_name" ] || fail "nginx needs --server-name"
    if $deps; then
        ensure_tools
        ensure_nginx
        open_web_ports
    else
        command -v nginx >/dev/null || fail "nginx is not installed"
    fi
    if $self_signed; then
        self_signed_certificate "$server_name"
        cert=$TLS_CERT
        key=$TLS_KEY
    fi
    [ -n "$cert" ] && [ -n "$key" ] || fail "nginx needs --cert and --key, or --self-signed"
    write_viewer_site "$server_name" "$cert" "$key"
    say "Register https://$server_name/login/oauth2/code/viewer as a redirect URI and https://$server_name/signed-out"
    say "as a post-logout redirect URI of the identity provider's client."
}

# Makes the viewer trust one more certificate (self-signed Keycloak site, internal CA), keeping the public CAs
trust_certificate() {
    java_truststore "$JAVA21" "$1" "$TRUSTSTORE"
    chown root:"$APP_USER" "$TRUSTSTORE"
    chmod 0640 "$TRUSTSTORE"
    local options
    options=$(env_value JAVA_OPTS)
    if [[ "$options" != *"javax.net.ssl.trustStore="* ]]; then
        env_file_set "$ENV_FILE" JAVA_OPTS "$options -Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
    fi
    say "The viewer trusts $1 (trust store $TRUSTSTORE)"
}

all_in_one() {
    require_root
    local viewer_host="" keycloak_host="" cert="" key="" kc_cert="" kc_key="" ca_cert="" self_signed=false
    local db=local db_url="" db_user="" db_password_file="" first_user=vieweradmin add_hosts=false kc_archive=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --viewer-host) viewer_host=$2; shift 2 ;;
            --keycloak-host) keycloak_host=$2; shift 2 ;;
            --cert) cert=$2; shift 2 ;;
            --key) key=$2; shift 2 ;;
            --keycloak-cert) kc_cert=$2; shift 2 ;;
            --keycloak-key) kc_key=$2; shift 2 ;;
            --ca-cert) ca_cert=$2; shift 2 ;;
            --self-signed) self_signed=true; shift ;;
            --keycloak-db) db=$2; shift 2 ;;
            --db-url) db=external; db_url=$2; shift 2 ;;
            --db-username) db_user=$2; shift 2 ;;
            --db-password-file) db_password_file=$2; shift 2 ;;
            --first-user) first_user=$2; shift 2 ;;
            --add-hosts) add_hosts=true; shift ;;
            --java-archive) JAVA_ARCHIVE=$2; shift 2 ;;
            --keycloak-archive) kc_archive=$2; shift 2 ;;
            *) fail "unknown option $1 (see the top of $0)" ;;
        esac
    done
    [ -n "$viewer_host" ] && [ -n "$keycloak_host" ] || fail "all needs --viewer-host and --keycloak-host"
    [ "$viewer_host" != "$keycloak_host" ] || fail "the viewer and Keycloak need two different host names"
    if ! $self_signed; then
        [ -n "$cert" ] && [ -n "$key" ] || fail "all needs --cert and --key, or --self-signed"
    fi
    local db_args=()
    case "$db" in
        local) db_args=(--local-postgres) ;;
        dev-file) db_args=(--dev-file) ;;
        external)
            [ -n "$db_user" ] && [ -f "$db_password_file" ] || fail "--db-url needs --db-username and --db-password-file"
            db_args=(--db-url "$db_url" --db-username "$db_user" --db-password-file "$db_password_file") ;;
        *) fail "--keycloak-db is local, dev-file, or use --db-url" ;;
    esac

    say "== Dependencies"
    ensure_tools
    use_java true
    ensure_nginx
    open_web_ports
    if [ "$db" = local ]; then
        ensure_postgres
    fi

    # The viewer calls Keycloak by its public name, so this server must resolve it
    local host
    for host in "$keycloak_host" "$viewer_host"; do
        if ! getent hosts "$host" >/dev/null 2>&1; then
            if $add_hosts; then
                printf '127.0.0.1 %s # %s\n' "$host" "$APP" >> /etc/hosts
                say "Added $host to /etc/hosts"
            elif [ "$host" = "$keycloak_host" ]; then
                fail "this server cannot resolve $host. Create DNS records for both names, or add --add-hosts (test servers)"
            fi
        fi
    done

    if $self_signed; then
        self_signed_certificate "$viewer_host" "$keycloak_host"
        cert=$TLS_CERT
        key=$TLS_KEY
        ca_cert=$TLS_CERT
    fi
    kc_cert=${kc_cert:-$cert}
    kc_key=${kc_key:-$key}

    say "== Viewer files"
    install_files

    say "== Keycloak"
    local kc_args=(--hostname "https://$keycloak_host" --viewer-url "https://$viewer_host" --server-name "$keycloak_host"
        --cert "$kc_cert" --key "$kc_key" --viewer-env "$ENV_FILE" --first-user "$first_user" --no-deps "${db_args[@]}")
    if [ -n "$kc_archive" ]; then
        kc_args+=(--archive "$kc_archive")
    fi
    JAVA_BIN=$JAVA21 "$HERE/install-keycloak.sh" "${kc_args[@]}"

    if [ -n "$ca_cert" ]; then
        trust_certificate "$ca_cert"
    fi

    say "== nginx for the viewer"
    write_viewer_site "$viewer_host" "$cert" "$key"

    say "== Starting the viewer"
    start_and_verify

    say ""
    say "Done. Next:"
    say "  1. Sign in at https://$viewer_host/admin as $first_user (password printed above; change it on first sign-in)"
    say "     and enter the Alfresco connection (see README.md, step 5)."
    say "  2. Create the other users in Keycloak (https://$keycloak_host/admin, realm sapiensml) with the role DOCUMENT_VIEWER,"
    say "     or connect Okta there under Identity providers."
    if $self_signed; then
        say "  The certificate is self-signed: browsers will warn until it is replaced (install.sh nginx --cert/--key)."
    fi
}

rollback() {
    require_root
    local previous current
    previous=$(cat "$PREFIX/previous-release" 2>/dev/null || true)
    [ -n "$previous" ] && [ -f "$previous" ] || fail "no previous release recorded"
    current=$(current_release)
    switch_to "$previous"
    printf '%s\n' "$current" > "$PREFIX/previous-release"
    systemctl restart "$APP"
    wait_healthy || fail "the previous release is not healthy either; see journalctl -u $APP"
    say "Now running $previous"
}

status() {
    systemctl status "$APP" --no-pager --lines 5 || true
    say ""
    say "Current release: $(current_release)"
    say "Previous release: $(cat "$PREFIX/previous-release" 2>/dev/null || echo none)"
    say "Health: $(http_get "http://127.0.0.1:$(env_value VIEWER_MANAGEMENT_PORT 8081)/actuator/health" 2>/dev/null || echo 'not answering')"
}

uninstall() {
    require_root
    systemctl disable --now "$APP" >/dev/null 2>&1 || true
    rm -f "$UNIT_FILE"
    systemctl daemon-reload
    rm -rf "$PREFIX"
    if [ "${1:-}" = --purge ]; then
        rm -rf "$CONF_DIR" "$LOG_DIR" "/var/lib/$APP" "$NGINX_FILE"
        id "$APP_USER" >/dev/null 2>&1 && userdel "$APP_USER"
        say "Removed $APP, its settings, logs and user (Keycloak, PostgreSQL and nginx stay installed)"
    else
        say "Removed $APP; kept $ENV_FILE, /var/lib/$APP and $LOG_DIR (use --purge to remove them)"
    fi
}

command=${1:-}
[ $# -gt 0 ] && shift
case "$command" in
    all) all_in_one "$@" ;;
    install | upgrade) install_app "$@" ;;
    deps) deps_only "$@" ;;
    nginx) nginx_site "$@" ;;
    rollback) rollback ;;
    status) status ;;
    uninstall) uninstall "$@" ;;
    *)
        sed -n '2,34p' "$0" | sed 's/^# \{0,1\}//'
        exit 1 ;;
esac
