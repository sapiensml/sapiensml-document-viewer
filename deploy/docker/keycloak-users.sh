#!/usr/bin/env bash
# Creates the quick start's test users in the sapiensml realm. compose.yaml runs it inside the Keycloak image once
# Keycloak is ready. Same users and roles as deploy/local/keycloak.sh (the roles come with the realm); safe to rerun.
set -euo pipefail

CONFIG=/tmp/kcadm.config

kcadm() {
    /opt/keycloak/bin/kcadm.sh "$@" --config "$CONFIG"
}

add_user() {
    local username=$1 first=$2 last=$3 id role
    shift 3
    id=$(kcadm get users -r sapiensml -q "username=$username" -q exact=true --fields id --format csv --noquotes)
    if [ -z "$id" ]; then
        kcadm create users -r sapiensml -s "username=$username" -s enabled=true -s "firstName=$first" -s "lastName=$last" \
            -s "email=$username@example.test" -s emailVerified=true >/dev/null
        kcadm set-password -r sapiensml --username "$username" --new-password "$username" >/dev/null
        echo "Created test user $username/$username"
    fi
    for role in "$@"; do
        kcadm add-roles -r sapiensml --uusername "$username" --rolename "$role" >/dev/null
    done
}

kcadm config credentials --server "$KEYCLOAK_URL" --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"
add_user viewer Vera Viewer DOCUMENT_VIEWER
add_user vieweradmin Ada Admin VIEWER_ADMIN DOCUMENT_VIEWER
add_user noaccess Nora Access
echo "Test users ready: viewer/viewer, vieweradmin/vieweradmin, noaccess/noaccess"
