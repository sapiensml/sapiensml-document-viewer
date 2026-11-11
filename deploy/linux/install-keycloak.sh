#!/usr/bin/env bash
# Installs Keycloak (from its distribution, no containers) as the systemd service sapiensml-keycloak, with the sapiensml realm and the
# viewer's client, for a server that has no organisation Keycloak to use. Run as root on the Linux server.
# Java 21, curl, tar, openssl, nginx and (with --local-postgres) PostgreSQL are installed when missing.
# install.sh all runs this script for you.
#
#   sudo ./install-keycloak.sh --hostname https://sso.example.com --viewer-url https://viewer.example.com \
#        --local-postgres --server-name sso.example.com --cert /path/sso.pem --key /path/sso.key
#
# Options:
#   --hostname URL         public address of Keycloak (behind nginx/TLS)                         required
#   --viewer-url URL       public address of the viewer, for its redirect URIs                    required
#   Database, one of:
#   --local-postgres       PostgreSQL on this server (installed if missing), database and user "keycloak"
#   --db-url JDBC --db-username NAME --db-password-file FILE   an existing PostgreSQL
#   --dev-file             Keycloak's own file database (a single test server only, not production)
#   --server-name NAME --cert FILE --key FILE   also put nginx (TLS) in front of Keycloak
#   --viewer-env FILE      write the issuer and the viewer's client secret into the viewer's viewer.env
#   --first-user NAME      create this user in realm sapiensml with both viewer roles and a one-time password
#   --archive FILE         keycloak-<version>.tar.gz already on the server (no download)
#   --java-archive FILE    the Temurin 21 JRE tarball, when the distribution has no Java 21 and there is no internet
#   --version VERSION      default 26.8.2
#   --no-deps              install nothing; fail when something is missing
#
# Prints the viewer's client secret (unless --viewer-env), the temporary admin password and the first user's password.
set -euo pipefail

VERSION=26.8.2
HOSTNAME_URL=""
VIEWER_URL=""
DB_MODE=""
DB_URL=""
DB_USER=""
DB_PASSWORD_FILE=""
ARCHIVE=""
SERVER_NAME=""
CERT=""
KEY=""
VIEWER_ENV=""
FIRST_USER=""
DEPS=true
HTTP_PORT=8180
MANAGEMENT_PORT=9000
SERVICE=sapiensml-keycloak
KC_USER=sapiensml-kc
CONF_DIR=/etc/$SERVICE
ENV_FILE=$CONF_DIR/keycloak.env
UNIT_FILE=/etc/systemd/system/$SERVICE.service
NGINX_FILE=/etc/nginx/conf.d/$SERVICE.conf

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

say() { printf '%s\n' "$*"; }
fail() { printf 'install-keycloak.sh: %s\n' "$*" >&2; exit 1; }

# shellcheck source=lib.sh
. "$HERE/lib.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --hostname) HOSTNAME_URL=${2%/}; shift 2 ;;
        --viewer-url) VIEWER_URL=${2%/}; shift 2 ;;
        --local-postgres) DB_MODE=local; shift ;;
        --db-url) DB_MODE=external; DB_URL=$2; shift 2 ;;
        --db-username) DB_USER=$2; shift 2 ;;
        --db-password-file) DB_PASSWORD_FILE=$2; shift 2 ;;
        --dev-file) DB_MODE=dev-file; shift ;;
        --archive) ARCHIVE=$2; shift 2 ;;
        --java-archive) JAVA_ARCHIVE=$2; shift 2 ;;
        --version) VERSION=$2; shift 2 ;;
        --server-name) SERVER_NAME=$2; shift 2 ;;
        --cert) CERT=$2; shift 2 ;;
        --key) KEY=$2; shift 2 ;;
        --viewer-env) VIEWER_ENV=$2; shift 2 ;;
        --first-user) FIRST_USER=$2; shift 2 ;;
        --no-deps) DEPS=false; shift ;;
        -h | --help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "unknown option $1 (see --help)" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || fail "run as root"
command -v systemctl >/dev/null || fail "systemd is required"
[ -n "$HOSTNAME_URL" ] && [ -n "$VIEWER_URL" ] || fail "--hostname and --viewer-url are required"
if [ ! -f "$ENV_FILE" ]; then
    case "$DB_MODE" in
        local | dev-file) ;;
        external) [ -n "$DB_USER" ] && [ -f "$DB_PASSWORD_FILE" ] || fail "--db-url needs --db-username and --db-password-file" ;;
        *) fail "choose the database: --local-postgres, --db-url ..., or --dev-file (test servers)" ;;
    esac
fi
if [ -n "$SERVER_NAME" ]; then
    [ -f "$CERT" ] && [ -f "$KEY" ] || fail "--server-name needs --cert and --key (existing files)"
fi
if [ -n "$FIRST_USER" ] && ! [[ "$FIRST_USER" =~ ^[A-Za-z0-9._@-]{1,64}$ ]]; then
    fail "--first-user: letters, digits, . _ @ - only"
fi
REALM_TEMPLATE=""
for candidate in "$HERE/sapiensml-realm.template.json" "$HERE/../keycloak/sapiensml-realm.template.json"; do
    [ -f "$candidate" ] && REALM_TEMPLATE=$candidate && break
done
[ -n "$REALM_TEMPLATE" ] || fail "sapiensml-realm.template.json not found next to this script"

# ---- Dependencies -----------------------------------------------------------------------------------------------

if $DEPS; then
    ensure_tools
    ensure_java
else
    JAVA21=$(find_java21) || fail "Java 21 not found (run without --no-deps to install it)"
fi
# kc.sh runs $JAVA_HOME/bin/java, or whatever "java" is on the PATH (often an older one): always set it
KC_JAVA_HOME=$(dirname "$(dirname "$JAVA21")")
if [ "$DB_MODE" = local ]; then
    if $DEPS; then
        ensure_postgres
    else
        postgres_answers || fail "PostgreSQL is not running on this server (run without --no-deps to install it)"
    fi
fi
if [ -n "$SERVER_NAME" ]; then
    if $DEPS; then
        ensure_nginx
        open_web_ports
    else
        command -v nginx >/dev/null || fail "nginx is not installed"
    fi
fi

# ---- Keycloak itself --------------------------------------------------------------------------------------------

KC_HOME=/opt/$SERVICE-$VERSION
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
if [ ! -x "$KC_HOME/bin/kc.sh" ]; then
    if [ -z "$ARCHIVE" ]; then
        url="https://github.com/keycloak/keycloak/releases/download/$VERSION/keycloak-$VERSION.tar.gz"
        say "Downloading Keycloak $VERSION ..."
        curl -fsSL --retry 3 -o "$work/keycloak.tar.gz" "$url" || fail "downloading $url failed (offline? use --archive)"
        expected=$(curl -fsSL "$url.sha1" | awk '{print $1}')
        actual=$(sha1sum "$work/keycloak.tar.gz" | awk '{print $1}')
        [ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "checksum mismatch for the Keycloak download"
        ARCHIVE="$work/keycloak.tar.gz"
    fi
    tar -xzf "$ARCHIVE" -C /opt
    [ -x "$KC_HOME/bin/kc.sh" ] || fail "the archive did not contain keycloak-$VERSION"
fi

if ! id "$KC_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$KC_HOME" --no-create-home --shell "$(command -v nologin || echo /bin/false)" "$KC_USER"
fi
chown -R "$KC_USER:$KC_USER" "$KC_HOME"
ln -sfn "$KC_HOME" "/opt/$SERVICE"
install -d -m 0750 -o root -g "$KC_USER" "$CONF_DIR"

first_install=false
if [ ! -f "$ENV_FILE" ]; then
    first_install=true
    admin_password=$(openssl rand -base64 18 | tr -d '/+=')
    db_password=""
    case "$DB_MODE" in
        local) db_password=$(openssl rand -hex 24) ;;
        external) db_password=$(cat "$DB_PASSWORD_FILE") ;;
    esac
    {
        echo "# Keycloak settings, read by systemd. See https://www.keycloak.org/server/all-config"
        echo "KC_HOSTNAME=$HOSTNAME_URL"
        echo "KC_HTTP_ENABLED=true"
        echo "KC_HTTP_HOST=127.0.0.1"
        echo "KC_HTTP_PORT=$HTTP_PORT"
        echo "KC_PROXY_HEADERS=xforwarded"
        echo "KC_HEALTH_ENABLED=true"
        case "$DB_MODE" in
            dev-file) echo "KC_DB=dev-file" ;;
            local)
                echo "KC_DB=postgres"
                echo "KC_DB_URL=jdbc:postgresql://127.0.0.1:5432/keycloak"
                echo "KC_DB_USERNAME=keycloak"
                echo "KC_DB_PASSWORD=$db_password" ;;
            external)
                echo "KC_DB=postgres"
                echo "KC_DB_URL=$DB_URL"
                echo "KC_DB_USERNAME=$DB_USER"
                echo "KC_DB_PASSWORD=$db_password" ;;
        esac
        echo "# Temporary admin for the first sign-in; create a permanent admin, then delete these two lines"
        echo "KC_BOOTSTRAP_ADMIN_USERNAME=admin"
        echo "KC_BOOTSTRAP_ADMIN_PASSWORD=$admin_password"
        echo "JAVA_OPTS_KC_HEAP=-Xms256m -Xmx1g"
        echo "JAVA_HOME=$KC_JAVA_HOME"
    } > "$ENV_FILE"
    chown root:"$KC_USER" "$ENV_FILE"
    chmod 0640 "$ENV_FILE"
else
    env_file_set "$ENV_FILE" JAVA_HOME "$KC_JAVA_HOME"
fi

if [ "$DB_MODE" = local ] || [ "$(env_file_get "$ENV_FILE" KC_DB_URL)" = "jdbc:postgresql://127.0.0.1:5432/keycloak" ]; then
    postgres_database keycloak keycloak "$(env_file_get "$ENV_FILE" KC_DB_PASSWORD)"
fi

db=$(env_file_get "$ENV_FILE" KC_DB)
say "Building Keycloak for database $db ..."
runuser -u "$KC_USER" -- env JAVA_HOME="$KC_JAVA_HOME" "$KC_HOME/bin/kc.sh" build --db="$db" --health-enabled=true >/dev/null

client_secret=""
if $first_install; then
    client_secret=$(openssl rand -hex 24)
    install -d -m 0750 -o "$KC_USER" -g "$KC_USER" "$KC_HOME/data/import"
    sed -e "s|@VIEWER_CLIENT_SECRET@|$client_secret|" \
        -e "s|@VIEWER_REDIRECT_URIS@|\"$VIEWER_URL/login/oauth2/code/viewer\"|" \
        -e "s|@VIEWER_POST_LOGOUT_URIS@|$VIEWER_URL/signed-out|" \
        "$REALM_TEMPLATE" > "$KC_HOME/data/import/sapiensml-realm.json"
    chown "$KC_USER:$KC_USER" "$KC_HOME/data/import/sapiensml-realm.json"
    chmod 0600 "$KC_HOME/data/import/sapiensml-realm.json"
fi

sed -e "s|@KC_HOME@|$KC_HOME|g" -e "s|@ENV_FILE@|$ENV_FILE|g" -e "s|@KC_USER@|$KC_USER|g" "$HERE/$SERVICE.service" > "$UNIT_FILE"
chmod 0644 "$UNIT_FILE"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1
systemctl restart "$SERVICE"

say "Waiting for Keycloak to be ready ..."
ready=false
for _ in $(seq 1 100); do
    if curl -fs "http://127.0.0.1:$MANAGEMENT_PORT/health/ready" >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 3
done
$ready || { journalctl -u "$SERVICE" -n 40 --no-pager; fail "Keycloak did not become ready"; }
# The realm is in the database now; the file with the client secret is not needed any more
rm -f "$KC_HOME/data/import/sapiensml-realm.json"

# ---- nginx --------------------------------------------------------------------------------------------------------

if [ -n "$SERVER_NAME" ]; then
    cat > "$NGINX_FILE" <<EOF
# nginx in front of Keycloak (written by install-keycloak.sh)
server {
    listen 80;
    listen [::]:80;
    server_name $SERVER_NAME;
    return 301 https://\$host\$request_uri;
}
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $SERVER_NAME;
    ssl_certificate $CERT;
    ssl_certificate_key $KEY;
    ssl_protocols TLSv1.2 TLSv1.3;
    server_tokens off;
    # Keycloak's sign-in responses carry large cookies and headers
    proxy_buffer_size 128k;
    proxy_buffers 4 256k;
    location / {
        proxy_pass http://127.0.0.1:$HTTP_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Port \$server_port;
    }
    # The admin console only from inside the network; adjust to your ranges
    location /admin/ {
        allow 127.0.0.1;
        allow ::1;
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
        proxy_pass http://127.0.0.1:$HTTP_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Port \$server_port;
    }
}
EOF
    chmod 0644 "$NGINX_FILE"
    nginx -t
    systemctl enable nginx >/dev/null 2>&1 || true
    systemctl restart nginx
    allow_nginx_proxy
fi

# ---- Realm users and the viewer's settings ----------------------------------------------------------------------

admin_user=$(env_file_get "$ENV_FILE" KC_BOOTSTRAP_ADMIN_USERNAME)
admin_pass=$(env_file_get "$ENV_FILE" KC_BOOTSTRAP_ADMIN_PASSWORD)
kcadm() {
    JAVA_HOME="$KC_JAVA_HOME" "$KC_HOME/bin/kcadm.sh" "$@" --config "$work/kcadm.config"
}
admin_ok=false
if [ -n "$admin_pass" ] && kcadm config credentials --server "http://127.0.0.1:$HTTP_PORT" --realm master \
    --user "$admin_user" --password "$admin_pass" >/dev/null 2>&1; then
    admin_ok=true
fi

if [ -z "$client_secret" ] && [ -n "$VIEWER_ENV" ] && $admin_ok; then
    # Run again after an interrupted install: read the secret back instead of inventing a new one
    client=$(kcadm get clients -r sapiensml -q clientId=sapiensml-document-viewer --fields id --format csv --noquotes)
    client_secret=$(kcadm get "clients/$client/client-secret" -r sapiensml --fields value --format csv --noquotes)
fi

first_password=""
if [ -n "$FIRST_USER" ]; then
    if ! $admin_ok; then
        say "Not creating $FIRST_USER: the temporary admin in $ENV_FILE is gone. Create the user in the admin console."
    elif [ -z "$(kcadm get users -r sapiensml -q "username=$FIRST_USER" -q exact=true --fields id --format csv --noquotes)" ]; then
        first_password=$(openssl rand -base64 15 | tr -d '/+=')
        kcadm create users -r sapiensml -s "username=$FIRST_USER" -s enabled=true >/dev/null
        kcadm set-password -r sapiensml --username "$FIRST_USER" --new-password "$first_password" --temporary >/dev/null
        kcadm add-roles -r sapiensml --uusername "$FIRST_USER" --rolename VIEWER_ADMIN --rolename DOCUMENT_VIEWER >/dev/null
    fi
fi

if [ -n "$VIEWER_ENV" ]; then
    [ -f "$VIEWER_ENV" ] || fail "$VIEWER_ENV does not exist (run install.sh install first)"
    env_file_set "$VIEWER_ENV" VIEWER_OIDC_ISSUER_URI "$HOSTNAME_URL/realms/sapiensml"
    env_file_set "$VIEWER_ENV" VIEWER_OIDC_CLIENT_ID sapiensml-document-viewer
    if [ -n "$client_secret" ]; then
        env_file_set "$VIEWER_ENV" VIEWER_OIDC_CLIENT_SECRET "$client_secret"
    fi
fi

say ""
say "Keycloak $VERSION is running: $HOSTNAME_URL (issuer $HOSTNAME_URL/realms/sapiensml, database $db)"
if $first_install; then
    say "Temporary Keycloak admin: $admin_user / $admin_pass (admin console $HOSTNAME_URL/admin)"
fi
if [ -n "$VIEWER_ENV" ]; then
    say "Wrote the issuer and the viewer's client secret into $VIEWER_ENV"
elif [ -n "$client_secret" ]; then
    say "Viewer client secret (VIEWER_OIDC_CLIENT_SECRET in /etc/sapiensml-document-viewer/viewer.env): $client_secret"
    say "Issuer (VIEWER_OIDC_ISSUER_URI): $HOSTNAME_URL/realms/sapiensml"
fi
if [ -n "$first_password" ]; then
    say "First user: $FIRST_USER / $first_password (one-time password, changed at the first sign-in; roles VIEWER_ADMIN and DOCUMENT_VIEWER)"
fi
say "Give users the realm role DOCUMENT_VIEWER (Users > user > Role mapping), or connect Okta under Identity providers."
say "Give the person who sets up the Alfresco connection the realm role VIEWER_ADMIN (the viewer's /admin page)."
