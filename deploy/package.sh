#!/usr/bin/env bash
# Builds the releases: target/sapiensml-document-viewer-<version>.tar.gz for Linux servers (jar, install scripts,
# systemd units, settings template, nginx site, Keycloak realm) and target/sapiensml-document-viewer-<version>-windows.zip
# for Windows servers (jar, PowerShell install scripts, settings template, Keycloak realm).
#
#   ./deploy/package.sh                 full build with all tests (mvn verify)
#   ./deploy/package.sh --skip-tests    build only
#
# Linux:   tar -xzf sapiensml-document-viewer-<version>.tar.gz && cd sapiensml-document-viewer-<version> && sudo ./install.sh install
# Windows: Expand-Archive sapiensml-document-viewer-<version>-windows.zip; then .\install.ps1 Install (see its README.md)
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    export JAVA_HOME
fi

if [ "${1:-}" = --skip-tests ]; then
    mvn -q -DskipTests clean package
else
    mvn -q clean verify
fi

VERSION=$(awk -F= '$1 == "build.version" { print $2 }' target/classes/META-INF/build-info.properties)
[ -n "$VERSION" ] || { echo "package.sh: no build version in target/classes/META-INF/build-info.properties" >&2; exit 1; }
NAME=sapiensml-document-viewer-$VERSION
STAGE=target/release/$NAME

rm -rf target/release
mkdir -p "$STAGE"
cp target/sapiensml-document-viewer.jar "$STAGE/"
cp deploy/linux/install.sh deploy/linux/install-keycloak.sh deploy/linux/lib.sh deploy/linux/sapiensml-document-viewer.service \
    deploy/linux/sapiensml-keycloak.service deploy/linux/viewer.env.example deploy/linux/nginx-sapiensml-document-viewer.conf \
    deploy/keycloak/sapiensml-realm.template.json LICENSE NOTICE "$STAGE/"
cp DEPLOY.md "$STAGE/README.md"
chmod 0755 "$STAGE/install.sh" "$STAGE/install-keycloak.sh"
printf '%s\n' "$VERSION" > "$STAGE/VERSION"
if command -v sha256sum >/dev/null; then
    (cd "$STAGE" && sha256sum sapiensml-document-viewer.jar > SHA256SUMS)
else
    (cd "$STAGE" && shasum -a 256 sapiensml-document-viewer.jar > SHA256SUMS)
fi

# Owner and group root on the server, whoever built it
tar --owner=0 --group=0 -czf "target/$NAME.tar.gz" -C target/release "$NAME" 2>/dev/null \
    || tar -czf "target/$NAME.tar.gz" -C target/release "$NAME"
echo "Built target/$NAME.tar.gz"

WIN_STAGE=target/release-windows/$NAME-windows
rm -rf target/release-windows
mkdir -p "$WIN_STAGE"
cp target/sapiensml-document-viewer.jar deploy/windows/install.ps1 deploy/windows/install-keycloak.ps1 deploy/windows/lib.ps1 \
    deploy/windows/viewer.properties.example deploy/keycloak/sapiensml-realm.template.json LICENSE NOTICE "$WIN_STAGE/"
cp DEPLOY-WINDOWS.md "$WIN_STAGE/README.md"
printf '%s\r\n' "$VERSION" > "$WIN_STAGE/VERSION"
cp "$STAGE/SHA256SUMS" "$WIN_STAGE/SHA256SUMS"
rm -f "target/$NAME-windows.zip"
if command -v zip >/dev/null; then
    (cd target/release-windows && zip -qr "../$NAME-windows.zip" "$NAME-windows")
else
    (cd target/release-windows && python3 -m zipfile -c "../$NAME-windows.zip" "$NAME-windows")
fi
echo "Built target/$NAME-windows.zip"
