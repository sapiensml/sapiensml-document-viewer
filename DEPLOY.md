# Deploying the SapiensML Document Viewer on Linux

The viewer runs as a systemd service from a single jar, with nginx in front for TLS. No containers. The install scripts install whatever the server is missing with its own package manager, so a fresh minimal server is enough.

Supported: RHEL, Rocky, Alma and Oracle Linux 8 and 9, Amazon Linux 2023, Fedora (dnf/yum); Ubuntu 22.04/24.04 and Debian 12 (apt); SUSE 15 (zypper); x86_64 or aarch64, with systemd. The scripts have been checked on macOS (shellcheck, settings handling, certificates, Java trust store, Keycloak production mode) but not yet run end to end on each distribution: try them on a test server first.

## Quick start: everything on one fresh server

Two host names pointing at the server (one for the viewer, one for Keycloak), then:

```bash
tar -xzf sapiensml-document-viewer-<version>.tar.gz && cd sapiensml-document-viewer-<version>
sudo ./install.sh all --viewer-host viewer.sapiensml.com --keycloak-host sso.sapiensml.com \
     --cert /path/fullchain.pem --key /path/privkey.pem        # or --self-signed on a test server
```

In this order it installs the missing tools, Java 21 and nginx, opens ports 80 and 443 if firewalld or ufw is on, installs PostgreSQL with a `keycloak` database, installs Keycloak with the `sapiensml` realm, installs the viewer and writes its sign-in settings, puts nginx in front of both, creates the first user `vieweradmin` (roles `VIEWER_ADMIN` and `DOCUMENT_VIEWER`, one-time password printed at the end), starts everything and waits until the viewer is healthy. Then continue with [step 5](#5-enter-the-alfresco-connection-once).

| Option | |
|---|---|
| `--self-signed` | A self-signed certificate for both names, trusted by the viewer (browsers warn: test servers only) |
| `--keycloak-cert FILE --keycloak-key FILE` | Another certificate for the Keycloak site |
| `--ca-cert FILE` | The internal CA that signed `--cert`, so the viewer trusts the Keycloak site |
| `--keycloak-db dev-file` | Keycloak's own file database instead of PostgreSQL (single test server) |
| `--db-url JDBC --db-username NAME --db-password-file FILE` | An existing PostgreSQL instead of a local one |
| `--first-user NAME` | Another name for the first user |
| `--add-hosts` | Resolve both names to this server in `/etc/hosts`, for test servers without DNS records |
| `--java-archive FILE`, `--keycloak-archive FILE` | Offline servers: the Temurin JRE and Keycloak tarballs, copied over beforehand |

Running it again is safe: what exists is kept (settings, secrets, database, realm), what is missing is added.

### What gets installed when missing

| Need | dnf/yum (RHEL family) | apt (Ubuntu, Debian) | zypper (SUSE) |
|---|---|---|---|
| Tools | curl, tar, gzip, openssl, ca-certificates | same | same |
| Java 21 | `java-21-openjdk-headless` (Amazon: `java-21-amazon-corretto-headless`) | `openjdk-21-jre-headless` | `java-21-openjdk-headless` |
| Java 21 when the distribution has none (Debian 12, older releases) | Eclipse Temurin 21.0.13 JRE in `/opt/java`, checksum pinned in `lib.sh` | same | same |
| nginx | `nginx` | `nginx` | `nginx` |
| PostgreSQL 13+ (Keycloak) | `postgresql-server`, after switching RHEL 8's module stream from 10 to 16/15/13 | `postgresql` | `postgresql-server` |
| Keycloak 26.8.2 | from GitHub, SHA-1 checked, in `/opt/sapiensml-keycloak` (service `sapiensml-keycloak`) | same | same |

Nothing already installed is upgraded. When several Java versions are installed, the viewer and Keycloak use the Java 21 explicitly (the systemd unit and `JAVA_HOME` in `keycloak.env`), whatever `java` on the PATH is. `sudo ./install.sh deps [--with-nginx]` installs only the prerequisites; `--no-deps` on `install`, `nginx` and `install-keycloak.sh` installs nothing and fails when something is missing.

The rest of this guide is the same installation step by step, for servers that already have Keycloak or Okta.

## 1. What the server needs

| | |
|---|---|
| Root access, systemd, a supported distribution | Java 21, nginx and the tools are installed by the scripts when missing |
| Internet access to the distribution's repositories and github.com | Or offline: the packages from a local mirror, and `--java-archive` / `--archive` for Temurin and Keycloak |
| A TLS certificate | For `https://<viewer host>` (or `--self-signed` on test servers) |
| Outbound HTTPS | To Alfresco (your repository, or `<tenant>.alfrescocloud.com` for Alfresco PaaS) and to the identity provider. Through a proxy: see `JAVA_OPTS` in `viewer.env` |
| Memory | 1 GB for the viewer (768 MB heap); 1.5 GB more for Keycloak and PostgreSQL on the same server |
| An identity provider client | Keycloak (installed by `all` or step 6) or Okta, with redirect URI `https://<viewer host>/login/oauth2/code/viewer` and post-logout URI `https://<viewer host>/signed-out` |
| An Alfresco service account | A repository user, or a client-credentials client of the Alfresco Identity Service (Alfresco PaaS), with read access to the document folders. It is entered on the admin page after installation |
| An administrator | A user with the `VIEWER_ADMIN` role (Keycloak realm role, or the Okta group in `VIEWER_ADMIN_ROLES`) to enter the Alfresco connection |

## 2. Build the release (on a build machine)

```bash
./deploy/package.sh              # runs all tests; --skip-tests to only build
# → target/sapiensml-document-viewer-<version>.tar.gz
scp target/sapiensml-document-viewer-<version>.tar.gz <server>:/tmp/
```

## 3. Install

```bash
cd /tmp && tar -xzf sapiensml-document-viewer-<version>.tar.gz && cd sapiensml-document-viewer-<version>
sha256sum -c SHA256SUMS
sudo ./install.sh install
```

This installs Java 21 if missing, creates the system user `sapiensml`, installs the jar under `/opt/sapiensml-document-viewer/releases/` (with `current.jar` pointing at it), installs and enables the `sapiensml-document-viewer` systemd service, and writes `/etc/sapiensml-document-viewer/viewer.env` (readable by root and `sapiensml` only) with a freshly generated `VIEWER_SETTINGS_KEY`. Keep a copy of that key in the password manager: it decrypts the Alfresco secret saved in step 5. On the first install it does not start the service yet.

## 4. Configure sign-in and start

Edit `/etc/sapiensml-document-viewer/viewer.env` and replace every `CHANGE_ME`: the identity provider's issuer, and the viewer's client ID and secret there. Each setting is explained in the file. Then:

```bash
sudo systemctl start sapiensml-document-viewer
sudo ./install.sh status                       # service state and health
sudo ./install.sh nginx --server-name viewer.sapiensml.com \
     --cert /etc/pki/tls/certs/viewer.pem --key /etc/pki/tls/private/viewer.key
```

The `nginx` step installs nginx if missing, writes `/etc/nginx/conf.d/sapiensml-document-viewer.conf` (HTTPS, HTTP→HTTPS redirect, rate limit, streaming without buffering), checks it with `nginx -t`, starts nginx, opens ports 80 and 443 when firewalld or ufw is active, and on SELinux systems allows nginx to connect to the viewer. `--self-signed` instead of `--cert`/`--key` makes a test certificate.

## 5. Enter the Alfresco connection (once)

Open `https://viewer.sapiensml.com/admin` and sign in as a user with the `VIEWER_ADMIN` role. Until this is done, users who open a document see "The viewer has not been set up yet".

| Field | Example: Alfresco PaaS | Example: SapiensML test repository |
|---|---|---|
| Repository address | `https://<tenant>.alfrescocloud.com` | `https://alfresco.sapiensml.com` |
| Sign-in to Alfresco | Service account (OAuth2 client credentials) | User name and password |
| Token endpoint | *Use the standard path* | - |
| Account | The viewer's client ID and secret in the PaaS realm | A repository user that can read the document folders |
| Allowed folders | The node IDs of the folders whose documents may open | same |

*Test connection* gets a token, calls Alfresco as the account and opens each folder. *Save* runs the same test and saves only if it passes; the viewer uses the new connection at once, no restart. The settings are in `/var/lib/sapiensml-document-viewer/alfresco-settings.json` (readable by `sapiensml` only, secret encrypted). To rotate the secret later, enter the new one on the same page. The stored secret is never shown, and is kept only while the token endpoint and client ID stay the same.

Test: `https://viewer.sapiensml.com/documents/<node id of a document in an allowed folder>`.

## 6. Keycloak on the same or another server (only if there is none to use)

```bash
sudo ./install-keycloak.sh --hostname https://sso.sapiensml.com --viewer-url https://viewer.sapiensml.com \
     --local-postgres --server-name sso.sapiensml.com --cert /path/sso.pem --key /path/sso.key \
     --viewer-env /etc/sapiensml-document-viewer/viewer.env --first-user vieweradmin
```

It installs what is missing (Java 21, nginx, and PostgreSQL with `--local-postgres`), installs Keycloak 26.8.2 under `/opt/sapiensml-keycloak` as the `sapiensml-keycloak` service (listening on 127.0.0.1:8180, health on 127.0.0.1:9000), imports the `sapiensml` realm with the viewer's client and both roles, and puts nginx in front (the admin console only from private networks). With `--viewer-env` it writes the issuer and client secret into the viewer's settings; otherwise it prints them. It also prints a temporary Keycloak admin password and, with `--first-user`, that user's one-time password. For the database, `--db-url ... --db-username ... --db-password-file ...` uses an existing PostgreSQL, and `--dev-file` keeps Keycloak's data on local disk (a single test server only). Then create users (or connect Okta under *Identity providers*), give them the `DOCUMENT_VIEWER` realm role, and give whoever sets up the Alfresco connection `VIEWER_ADMIN`.

## 7. Upgrade and roll back

```bash
sudo ./install.sh install      # from the new release's directory: keeps viewer.env, restarts, waits for health
sudo ./install.sh rollback     # back to the previous release
```

If the new release does not become healthy within 3 minutes, `install` switches back to the previous release by itself and reports the failure. The last five releases are kept.

## 8. Operating

| | |
|---|---|
| Logs | `journalctl -u sapiensml-document-viewer -f` (JSON lines, Elastic Common Schema) |
| Audit trail | `/var/log/sapiensml-document-viewer/audit.json`, rolled daily and at 100 MB, kept `VIEWER_AUDIT_RETENTION_DAYS` days (400) |
| Health | `curl http://127.0.0.1:8081/actuator/health` (includes whether Alfresco accepts the service account) |
| Alfresco connection | The admin page (`/admin`); saved in `/var/lib/sapiensml-document-viewer`, applied without a restart, kept across upgrades |
| Other settings | Edit `viewer.env`, then `sudo systemctl restart sapiensml-document-viewer` |
| Backup | `/etc/sapiensml-document-viewer/viewer.env` (with `VIEWER_SETTINGS_KEY`) and `/var/lib/sapiensml-document-viewer/alfresco-settings.json` |
| Remove | `sudo ./install.sh uninstall` (keeps settings and logs) or `uninstall --purge`. Keycloak, PostgreSQL, nginx and Java stay installed |

## 9. Troubleshooting

| Symptom | Look at |
|---|---|
| `install.sh all` stops with "cannot resolve sso.sapiensml.com" | Create DNS records for both names, or use `--add-hosts` on a test server |
| Viewer log: `PKIX path building failed` when reaching Keycloak | The Keycloak certificate is signed by an internal CA: run `all` with `--ca-cert`, or add the CA to a trust store as in `JAVA_OPTS` |
| Service does not start, log says the issuer cannot be reached | `VIEWER_OIDC_ISSUER_URI`; the identity provider must answer when the viewer starts (systemd retries every 10 s) |
| Sign-in ends with "Invalid redirect uri" at Keycloak/Okta | The client's redirect URI must be exactly `https://<viewer host>/login/oauth2/code/viewer`; nginx must pass `X-Forwarded-Proto` (the generated site does) |
| Signed in, but "You cannot view documents" | The user lacks the role, or `VIEWER_ROLES_CLAIM` / `VIEWER_ROLES` do not match what the provider sends |
| Every document says "does not exist" | The audit log gives the reason: `outside the allowed folders` (check `VIEWER_ALLOWED_FOLDER_IDS`) or `NOT_FOUND`/`FORBIDDEN` (the service account cannot read it) |
| "The viewer has not been set up yet" | No Alfresco connection saved: an administrator enters it at `/admin` (step 5) |
| Log: "The Alfresco settings ... cannot be used (A stored secret cannot be decrypted)" | `VIEWER_SETTINGS_KEY` changed or was lost. Restore it from the password manager, or enter the connection again at `/admin` |
| "The document store is not available" | `/actuator/health` → `alfresco`: `UNAUTHORIZED` means the service-account secret is wrong or rotated; `UNAVAILABLE` means the PaaS or the outbound proxy cannot be reached |
| Word, Excel or TIFF files keep saying "Preparing" | Alfresco's transform service did not produce the PDF rendition within two minutes |
