# Changelog

All notable changes to the SapiensML Document Viewer. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- **Docker image** `ghcr.io/sapiensml/sapiensml-document-viewer` for linux/amd64 and linux/arm64. It runs as a non-root user, has health checks, and is signed with build provenance. Each release publishes it. See DEPLOY-DOCKER.md.
- **Docker Compose quick start** (`deploy/docker/compose.yaml`): Keycloak with the `sapiensml` realm, the test users and the viewer, with one command. CI builds the image and signs in through it on every change.

## [1.0.0] - 2026-11-11

First public release.

### Added

- **Open by link.** A business application (for example FINEOS) links to `/documents/<node id>`. The viewer fetches the document from Alfresco with a service account and shows it.
- **Sign-in.** OpenID Connect through Keycloak, Okta or another identity provider, with a server-side session. Tokens never reach the browser.
- **Access control:**
  - roles read from a configurable claim (`DOCUMENT_VIEWER`, `VIEWER_ADMIN`);
  - documents limited to allowed folders;
  - a uniform 404 for every refusal;
  - an audit log of every document opened.
- **Alfresco connection.** Alfresco PaaS and the Identity Service through OAuth2 client credentials, or Basic authentication with a repository user.
- **Admin page.** A one-time setup of the Alfresco connection, with a connection test. Credentials are stored encrypted (AES-256-GCM) and applied without a restart.
- **Viewing:**
  - PDF with a text layer, filled form fields, lazy page loading and print;
  - Office and other formats through Alfresco's PDF rendition;
  - images and text.
- **Installers.** Standalone install scripts for Linux (systemd, nginx, PostgreSQL, Keycloak, Java) and Windows (Windows services, TLS from a .pfx), with no Docker. Both are packaged by `deploy/package.sh`.
