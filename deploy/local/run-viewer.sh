#!/usr/bin/env bash
# Runs the viewer locally with the settings in local.env (start Keycloak first: ./keycloak.sh start).
#
#   ./run-viewer.sh            run target/sapiensml-document-viewer.jar, building it first if it is missing
#   ./run-viewer.sh --build    rebuild first (mvn package, tests skipped)
#
# First time: open http://localhost:18200/admin as vieweradmin/vieweradmin and enter the Alfresco connection.
# Then open http://localhost:18200/documents/<alfresco node id> and sign in as viewer/viewer.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
ENV_FILE="$HERE/local.env"
JAR="$ROOT/target/sapiensml-document-viewer.jar"

fail() { printf 'run-viewer.sh: %s\n' "$*" >&2; exit 1; }

if [ ! -f "$ENV_FILE" ]; then
    cp "$HERE/local.env.example" "$ENV_FILE"
    fail "created $ENV_FILE: fill in the Alfresco service account, then run ./keycloak.sh start"
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

[ -n "${VIEWER_OIDC_CLIENT_SECRET:-}" ] || fail "VIEWER_OIDC_CLIENT_SECRET is empty: run ./keycloak.sh start first"
# The admin page keeps the Alfresco connection here (and its key, as no VIEWER_SETTINGS_KEY is set locally)
export VIEWER_SETTINGS_DIR=${VIEWER_SETTINGS_DIR:-$HERE/.work/settings}

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
export JAVA_HOME
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [ "${1:-}" = --build ] || [ ! -f "$JAR" ]; then
    (cd "$ROOT" && mvn -q -DskipTests package)
fi

# JAVA_OPTS holds several options: split on purpose
# shellcheck disable=SC2086
exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" --spring.profiles.active=local
