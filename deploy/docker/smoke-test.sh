#!/usr/bin/env bash
# Checks a running quick start (compose.yaml) end to end: an unauthenticated API call is refused, a document link
# sends the browser to Keycloak, and signing in as viewer/viewer gives a session that may view but not administer.
#
#   ./deploy/docker/smoke-test.sh [viewer URL, default http://localhost:18200]
set -euo pipefail

VIEWER=${1:-http://localhost:18200}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
JAR=$WORK/cookies

fail() {
    echo "smoke-test: $*" >&2
    exit 1
}

echo "1. Unauthenticated API call"
code=$(curl -s -o /dev/null -w '%{http_code}' "$VIEWER/api/session")
[ "$code" = 401 ] || fail "GET /api/session without signing in answered $code, expected 401"

echo "2. Document link goes to the identity provider"
location=$(curl -s -o /dev/null -w '%{redirect_url}' -c "$JAR" -b "$JAR" "$VIEWER/documents/00000000-0000-4000-8000-000000000000")
case "$location" in
    */oauth2/authorization/viewer) ;;
    *) fail "the document link redirected to '$location', expected the sign-in" ;;
esac
curl -s -L -c "$JAR" -b "$JAR" -o "$WORK/login.html" "$location"
action=$(grep -o 'id="kc-form-login"[^>]*action="[^"]*"' "$WORK/login.html" | sed -e 's/.*action="//' -e 's/"$//' -e 's/&amp;/\&/g')
[ -n "$action" ] || fail "no Keycloak sign-in form (is Keycloak up and the realm imported?)"

echo "3. Sign in as viewer/viewer"
final=$(curl -s -L -o /dev/null -w '%{http_code} %{url_effective}' -c "$JAR" -b "$JAR" \
    --data-urlencode username=viewer --data-urlencode password=viewer --data credentialId= "$action")
case "$final" in
    "200 $VIEWER"/*) ;;
    *) fail "after signing in the browser ended at '$final', expected a viewer page" ;;
esac

echo "4. Session"
session=$(curl -s -f -b "$JAR" "$VIEWER/api/session") || fail "GET /api/session after signing in failed"
echo "   $session"
grep -q '"username":"viewer"' <<<"$session" || fail "the session is not for viewer"
grep -q '"canView":true' <<<"$session" || fail "viewer has no viewer role"
grep -q '"admin":false' <<<"$session" || fail "viewer must not be an administrator"

echo "Quick start works: sign-in through Keycloak and the viewer's session are fine"
