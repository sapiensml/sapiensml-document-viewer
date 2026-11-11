# shellcheck shell=bash
# Shared by install.sh and install-keycloak.sh (sourced, never run on its own; the caller defines say and fail).
# Installs what is missing with the distribution's own package manager: dnf/yum (RHEL, Rocky, Alma, Oracle,
# Amazon Linux, Fedora), apt (Ubuntu, Debian) or zypper (SUSE). Java 21 comes from the distribution when it has
# it, otherwise from Eclipse Temurin (checksum pinned below). Nothing already installed is upgraded or removed.

# Eclipse Temurin JRE used when the distribution has no Java 21 package. SHA-256 from the Adoptium release.
TEMURIN_RELEASE=21.0.13+9
TEMURIN_SHA256_X64=a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd
TEMURIN_SHA256_AARCH64=b1734db7ade49c3f6acc6ae06233b8c2dd90e3e2f00f67000ae1c972aaa64d8c
TEMURIN_HOME=/opt/java/temurin-21-jre

PKG=""
APT_UPDATED=false
JAVA21=""

# ---- Packages ---------------------------------------------------------------------------------------------------

detect_platform() {
    [ -n "$PKG" ] && return
    if command -v dnf >/dev/null 2>&1; then
        PKG=dnf
    elif command -v yum >/dev/null 2>&1; then
        PKG=yum
    elif command -v apt-get >/dev/null 2>&1; then
        PKG=apt
    elif command -v zypper >/dev/null 2>&1; then
        PKG=zypper
    else
        fail "no supported package manager (dnf, yum, apt or zypper); install Java 21, curl, tar and openssl by hand and use --no-deps"
    fi
}

apt_update() {
    if ! $APT_UPDATED; then
        apt-get update -q >/dev/null
        APT_UPDATED=true
    fi
}

pkg_install() {
    detect_platform
    say "Installing $* ..."
    case "$PKG" in
        dnf | yum) "$PKG" -y -q install "$@" ;;
        apt)
            apt_update
            DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends "$@" ;;
        zypper) zypper --non-interactive --quiet install --no-recommends "$@" ;;
    esac
}

# Whether the configured repositories offer a package (installed or not)
pkg_available() {
    detect_platform
    case "$PKG" in
        dnf | yum) "$PKG" -q list --available "$1" >/dev/null 2>&1 || "$PKG" -q list --installed "$1" >/dev/null 2>&1 ;;
        apt)
            apt_update
            local candidate
            candidate=$(apt-cache policy "$1" 2>/dev/null | awk '/Candidate:/ { print $2 }')
            [ -n "$candidate" ] && [ "$candidate" != "(none)" ] ;;
        zypper) zypper --non-interactive --quiet search --match-exact "$1" >/dev/null 2>&1 ;;
    esac
}

# curl, tar, gzip, openssl and the CA bundle, which every step below needs
ensure_tools() {
    local missing=()
    command -v curl >/dev/null 2>&1 || missing+=(curl)
    command -v tar >/dev/null 2>&1 || missing+=(tar)
    command -v gzip >/dev/null 2>&1 || missing+=(gzip)
    command -v openssl >/dev/null 2>&1 || missing+=(openssl)
    if [ ! -f /etc/ssl/certs/ca-certificates.crt ] && [ ! -f /etc/pki/tls/certs/ca-bundle.crt ] \
        && [ ! -f /etc/ssl/ca-bundle.pem ]; then
        missing+=(ca-certificates)
    fi
    if [ ${#missing[@]} -gt 0 ]; then
        pkg_install "${missing[@]}"
    fi
}

# ---- Java 21 ----------------------------------------------------------------------------------------------------

java_major() {
    "$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { print $2 }' | cut -d. -f1
}

# The first Java 21 or newer: JAVA_BIN, the PATH, the distribution's JVM directory, then the Temurin fallback.
# Several Javas may be installed (RHEL keeps an older one as "java"), so the scripts always use this exact path.
find_java21() {
    local candidate major
    for candidate in "${JAVA_BIN:-}" "$(command -v java 2>/dev/null || true)" /usr/lib/jvm/*/bin/java "$TEMURIN_HOME/bin/java"; do
        [ -n "$candidate" ] && [ -x "$candidate" ] || continue
        major=$(java_major "$candidate")
        if [ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null; then
            readlink -f "$candidate"
            return 0
        fi
    done
    return 1
}

# Sets JAVA21. JAVA_ARCHIVE may name a Temurin tarball already on the server (no download)
ensure_java() {
    if JAVA21=$(find_java21); then
        say "Java 21: $JAVA21"
        return
    fi
    detect_platform
    local package="" candidate
    case "$PKG" in
        dnf | yum)
            for candidate in java-21-openjdk-headless java-21-amazon-corretto-headless; do
                if pkg_available "$candidate"; then
                    package=$candidate
                    break
                fi
            done ;;
        apt) pkg_available openjdk-21-jre-headless && package="openjdk-21-jre-headless" ;;
        zypper) pkg_available java-21-openjdk-headless && package="java-21-openjdk-headless" ;;
    esac
    if [ -n "$package" ] && pkg_install "$package" && JAVA21=$(find_java21); then
        say "Java 21: $JAVA21"
        return
    fi
    say "The distribution has no Java 21 package; installing Eclipse Temurin $TEMURIN_RELEASE in $TEMURIN_HOME"
    install_temurin
    JAVA21=$(find_java21) || fail "Java 21 could not be installed"
    say "Java 21: $JAVA21"
}

install_temurin() {
    local arch expected
    case "$(uname -m)" in
        x86_64 | amd64) arch=x64; expected=$TEMURIN_SHA256_X64 ;;
        aarch64 | arm64) arch=aarch64; expected=$TEMURIN_SHA256_AARCH64 ;;
        *) fail "no Temurin build for $(uname -m); install Java 21 by hand and set JAVA_BIN" ;;
    esac
    local file="OpenJDK21U-jre_${arch}_linux_hotspot_${TEMURIN_RELEASE/+/_}.tar.gz"
    local url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${TEMURIN_RELEASE/+/%2B}/$file"
    local work
    work=$(mktemp -d)
    if [ -n "${JAVA_ARCHIVE:-}" ]; then
        cp "$JAVA_ARCHIVE" "$work/$file"
    else
        curl -fsSL --retry 3 -o "$work/$file" "$url" || { rm -rf "$work"; fail "downloading $url failed (offline? use --java-archive)"; }
    fi
    if [ "$(sha256sum "$work/$file" | awk '{ print $1 }')" != "$expected" ]; then
        rm -rf "$work"
        fail "checksum mismatch for $file (expected Temurin $TEMURIN_RELEASE for $arch)"
    fi
    mkdir -p "$work/x" /opt/java
    tar -xzf "$work/$file" -C "$work/x"
    local top
    top=$(find "$work/x" -mindepth 1 -maxdepth 1 -type d | head -1)
    [ -x "$top/bin/java" ] || { rm -rf "$work"; fail "unexpected layout in $file"; }
    rm -rf "/opt/java/$(basename "$top")"
    mv "$top" /opt/java/
    chown -R root:root "/opt/java/$(basename "$top")"
    ln -sfn "/opt/java/$(basename "$top")" "$TEMURIN_HOME"
    rm -rf "$work"
}

# ---- nginx and the firewall ---------------------------------------------------------------------------------------

ensure_nginx() {
    if ! command -v nginx >/dev/null 2>&1; then
        pkg_install nginx
    fi
    [ -d /etc/nginx/conf.d ] || fail "/etc/nginx/conf.d does not exist; this nginx is laid out differently"
}

# Opens HTTP and HTTPS in firewalld or ufw when one of them is active; never switches a firewall on
open_web_ports() {
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --quiet --permanent --add-service=http --add-service=https
        firewall-cmd --quiet --reload
        say "firewalld: http and https open"
    elif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
        ufw allow 80/tcp >/dev/null
        ufw allow 443/tcp >/dev/null
        say "ufw: ports 80 and 443 open"
    fi
}

# SELinux: nginx may connect to the applications on loopback
allow_nginx_proxy() {
    if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" = Enforcing ]; then
        setsebool -P httpd_can_network_connect 1 || say "Run: setsebool -P httpd_can_network_connect 1"
    fi
}

# ---- TLS for test servers ---------------------------------------------------------------------------------------

# Sets TLS_CERT and TLS_KEY: a self-signed certificate for the given host names (browsers will warn; test only).
# It goes where the distribution keeps certificates, so SELinux labels it for nginx.
self_signed_certificate() {
    local dir_cert=/etc/ssl/certs dir_key=/etc/ssl/private
    if [ -d /etc/pki/tls/certs ]; then
        dir_cert=/etc/pki/tls/certs
        dir_key=/etc/pki/tls/private
    fi
    install -d -m 0755 "$dir_cert"
    install -d -m 0700 "$dir_key"
    TLS_CERT=$dir_cert/sapiensml-self-signed.crt
    TLS_KEY=$dir_key/sapiensml-self-signed.key
    if [ -f "$TLS_CERT" ] && [ -f "$TLS_KEY" ]; then
        say "Using the existing self-signed certificate $TLS_CERT"
        return
    fi
    local san="" host config
    for host in "$@"; do
        san="${san:+$san,}DNS:$host"
    done
    # A config file rather than -addext, which OpenSSL before 1.1.1 does not know
    config=$(mktemp)
    printf '%s\n' "[req]" "distinguished_name = dn" "x509_extensions = v3" "prompt = no" "[dn]" "CN = $1" "[v3]" \
        "subjectAltName = $san" "basicConstraints = critical,CA:FALSE" "keyUsage = critical,digitalSignature,keyEncipherment" \
        "extendedKeyUsage = serverAuth" > "$config"
    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 825 -config "$config" \
        -keyout "$TLS_KEY" -out "$TLS_CERT" 2>/dev/null || { rm -f "$config"; fail "creating the self-signed certificate failed"; }
    rm -f "$config"
    chmod 0600 "$TLS_KEY"
    chmod 0644 "$TLS_CERT"
    command -v restorecon >/dev/null 2>&1 && restorecon "$TLS_CERT" "$TLS_KEY" 2>/dev/null
    say "Created a self-signed certificate for $* ($TLS_CERT); browsers will warn about it"
}

# A trust store with Java's own CAs plus one more certificate (a self-signed one, or an internal CA), so the
# viewer trusts the Keycloak site and still trusts the public CAs of the repository. Its contents are public; the
# password only protects its integrity.
java_truststore() {
    local java=$1 certificate=$2 target=$3
    local home keytool cacerts
    home=$(dirname "$(dirname "$java")")
    keytool=$home/bin/keytool
    cacerts=$home/lib/security/cacerts
    [ -x "$keytool" ] && [ -f "$cacerts" ] || fail "keytool or cacerts not found under $home"
    rm -f "$target"
    "$keytool" -importkeystore -noprompt -srckeystore "$cacerts" -srcstorepass changeit \
        -destkeystore "$target" -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1
    "$keytool" -importcert -noprompt -alias sapiensml-extra-trust -file "$certificate" -keystore "$target" \
        -storepass changeit >/dev/null
}

# ---- PostgreSQL for Keycloak ------------------------------------------------------------------------------------

psql_admin() {
    (cd / && runuser -u postgres -- psql -v ON_ERROR_STOP=1 -tAq "$@")
}

postgres_answers() {
    id postgres >/dev/null 2>&1 && psql_admin -c 'SELECT 1' >/dev/null 2>&1
}

# A PostgreSQL server on this host, version 13 or newer (the oldest Keycloak 26 supports)
ensure_postgres() {
    if ! postgres_answers; then
        detect_platform
        case "$PKG" in
            dnf | yum)
                # RHEL 8 defaults to PostgreSQL 10: switch to the newest module stream offered
                local stream streams
                streams=$("$PKG" -q module list postgresql 2>/dev/null | awk '$1 == "postgresql" { print $2 }' || true)
                for stream in 16 15 13; do
                    if printf '%s\n' "$streams" | grep -qx "$stream"; then
                        "$PKG" -y -q module reset postgresql >/dev/null
                        "$PKG" -y -q module enable "postgresql:$stream" >/dev/null
                        break
                    fi
                done
                local server=postgresql-server candidate
                if ! pkg_available postgresql-server; then
                    for candidate in postgresql17-server postgresql16-server postgresql15-server; do
                        if pkg_available "$candidate"; then
                            server=$candidate
                            break
                        fi
                    done
                fi
                pkg_install "$server"
                if [ ! -f /var/lib/pgsql/data/PG_VERSION ] && command -v postgresql-setup >/dev/null 2>&1; then
                    postgresql-setup --initdb >/dev/null
                fi ;;
            apt) pkg_install postgresql ;;
            zypper) pkg_install postgresql-server postgresql ;;
        esac
        systemctl enable --now postgresql >/dev/null 2>&1 || systemctl start postgresql
        local waited=0
        until postgres_answers; do
            [ "$waited" -lt 60 ] || fail "PostgreSQL did not start; see journalctl -u postgresql"
            sleep 2
            waited=$((waited + 2))
        done
    fi
    local version
    version=$(psql_admin -c 'SHOW server_version_num')
    [ "${version:-0}" -ge 130000 ] || fail "PostgreSQL $(psql_admin -c 'SHOW server_version') is older than Keycloak supports (13 or newer)"
    say "PostgreSQL $(psql_admin -c 'SHOW server_version') is running"
}

# Database and login role (password login from loopback only); names are fixed words, the password is hex
postgres_database() {
    local name=$1 user=$2 password=$3
    if [ -z "$(psql_admin -c "SELECT 1 FROM pg_roles WHERE rolname = '$user'")" ]; then
        psql_admin -c "CREATE ROLE $user LOGIN PASSWORD '$password'"
    else
        psql_admin -c "ALTER ROLE $user WITH LOGIN PASSWORD '$password'"
    fi
    if [ -z "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$name'")" ]; then
        psql_admin -c "CREATE DATABASE $name OWNER $user ENCODING 'UTF8' TEMPLATE template0"
    fi
    # Ahead of the distribution's defaults (RHEL uses ident for loopback, which a service account cannot pass).
    # md5 also accepts SCRAM-stored passwords
    local hba
    hba=$(psql_admin -c 'SHOW hba_file')
    if ! grep -q "^host $name $user 127.0.0.1/32 " "$hba"; then
        sed -i "1i host $name $user 127.0.0.1/32 md5\nhost $name $user ::1/128 md5" "$hba"
        psql_admin -c 'SELECT pg_reload_conf()' >/dev/null
    fi
}

# ---- Settings files ---------------------------------------------------------------------------------------------

# One value of a KEY=value file (systemd EnvironmentFile); the file is never sourced
env_file_get() {
    local file=$1 key=$2 default=${3:-}
    local value
    value=$(awk -F= -v k="$key" '$1 == k { sub(/^[^=]*=/, ""); v = $0 } END { print v }' "$file" 2>/dev/null)
    printf '%s' "${value:-$default}"
}

# Sets KEY=value in place (adds it when missing), keeping the file's owner and mode; the value is taken verbatim
env_file_set() {
    local file=$1 key=$2 value=$3 tmp
    tmp=$(mktemp "$(dirname "$file")/.env.XXXXXX")
    KEY="$key" VALUE="$value" awk 'BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"]; done = 0 }
        index($0, k "=") == 1 { print k "=" v; done = 1; next } { print }
        END { if (!done) print k "=" v }' "$file" > "$tmp"
    chown --reference="$file" "$tmp"
    chmod --reference="$file" "$tmp"
    mv -f "$tmp" "$file"
}
