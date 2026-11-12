# Deploying the SapiensML Document Viewer on Windows Server

The viewer runs as a Windows service from a single jar. It serves HTTPS itself, so neither IIS nor nginx is needed. The install scripts are PowerShell. They download whatever the server is missing and check each download against a checksum pinned in `lib.ps1`.

Supported: Windows Server 2016, 2019, 2022 and 2025 (x64), with the built-in Windows PowerShell 5.1 or PowerShell 7.

> **Status.** The Windows runtime setup has been tested end to end on macOS with the same files the scripts write: the viewer and Keycloak serving HTTPS from one `.pfx`, settings in `viewer.properties`, the trust store, sign-in with a one-time password, the admin page and sign-out. The PowerShell scripts themselves have not yet run on Windows. Try them on a test server first, and check their syntax there (see [Before you start](#before-you-start)).

## Before you start

1. Copy `sapiensml-document-viewer-<version>-windows.zip` to the server, then in an **elevated** PowerShell (*Run as administrator*):

   ```powershell
   Expand-Archive .\sapiensml-document-viewer-<version>-windows.zip -DestinationPath C:\Temp
   cd C:\Temp\sapiensml-document-viewer-<version>-windows
   Get-ChildItem *.ps1 | Unblock-File               # files from the internet are blocked by default
   Set-ExecutionPolicy -Scope Process Bypass        # this window only
   (Get-FileHash .\sapiensml-document-viewer.jar -Algorithm SHA256).Hash   # compare with SHA256SUMS
   ```

2. Optionally check that the scripts parse on this Windows (prints nothing when all is well):

   ```powershell
   foreach ($f in Get-ChildItem *.ps1) { $e = $null; [void][Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$e); if ($e) { $f.Name; $e } }
   ```

## Quick start: everything on one fresh server

Two host names pointing at the server (one for the viewer, one for Keycloak), then:

```powershell
.\install.ps1 All -ViewerHost viewer.example.com -KeycloakHost sso.example.com `
    -PfxFile C:\certs\sapiensml.pfx -PfxPassword (Read-Host -AsSecureString 'PFX password')
# or, on a test server: -SelfSigned instead of -PfxFile/-PfxPassword
```

The `.pfx` must hold the certificate (with its chain) for both names. Use `-KeycloakPfxFile`/`-KeycloakPfxPassword` if Keycloak has its own certificate. In this order the script:

1. Checks that port 443 is free (IIS's Default Web Site often holds it).
2. Installs Java 21 if none is found.
3. Downloads WinSW.
4. Installs PostgreSQL 17 with a `keycloak` database, listening on localhost only.
5. Installs Keycloak with the `sapiensml` realm on **https://sso.example.com:8443**.
6. Installs the viewer on **https://viewer.example.com** and writes its sign-in settings.
7. Opens the two ports in Windows Firewall.
8. Creates the first user `vieweradmin` (roles `VIEWER_ADMIN` and `DOCUMENT_VIEWER`) and prints a one-time password.
9. Starts both services and waits until the viewer is healthy.

At the first sign-in Keycloak asks `vieweradmin` for a new password and, if missing, an e-mail address and name. Then continue with [Enter the Alfresco connection](#enter-the-alfresco-connection-once).

| Option | |
|---|---|
| `-SelfSigned` | A self-signed certificate for both names (in `LocalMachine\My`, exported to `C:\ProgramData\SapiensML\tls`), trusted by the viewer. Browsers warn: test servers only |
| `-CaCert FILE` | The internal CA that signed the `.pfx` (a `.cer`/`.crt`), so the viewer and Keycloak's admin tool trust the Keycloak site |
| `-ViewerPort`, `-KeycloakPort` | Default 443 and 8443 |
| `-KeycloakDb DevFile` | Keycloak's own file database instead of PostgreSQL (single test server) |
| `-KeycloakDb External -DbUrl JDBC -DbUsername NAME -DbPasswordFile FILE` | An existing PostgreSQL |
| `-PostgresSuperuserPasswordFile FILE` | When PostgreSQL is already installed on the server: its `postgres` password, to create the `keycloak` database |
| `-FirstUser NAME` | Another name for the first user |
| `-AddHosts` | Resolve both names to this server in the hosts file (test servers without DNS records) |
| `-InstallRoot`, `-DataRoot` | Default `C:\SapiensML` (programs; no spaces allowed) and `C:\ProgramData\SapiensML` (settings, data, logs) |
| `-JavaArchive`, `-KeycloakArchive`, `-WinswExe`, `-PostgresInstaller` | Offline servers: the downloads, copied over beforehand. They are checked against the same checksums |
| `-NoDeps` | Download nothing; fail when something is missing |

Running `All` again is safe: settings, secrets, the database and the realm are kept.

### What gets installed when missing

| Need | What | Where |
|---|---|---|
| Java 21 | Eclipse Temurin 21.0.13 JRE (zip). Skipped if a Java 21 is found in `JAVA_HOME`, the PATH or the usual vendor folders | `C:\SapiensML\java` |
| Service wrapper | WinSW 2.12.0 (`WinSW-x64.exe`, self-contained) | `C:\SapiensML\viewer\sapiensml-document-viewer.exe`, `C:\SapiensML\keycloak\sapiensml-keycloak.exe` |
| Keycloak | Keycloak 26.8.2 (zip, SHA-1 checked) | `C:\SapiensML\keycloak\keycloak-26.8.2` |
| PostgreSQL (local Keycloak database) | PostgreSQL 17.11 from EnterpriseDB's installer, run silently without pgAdmin and Stack Builder. Service `postgresql-x64-17`, superuser password in `C:\ProgramData\SapiensML\postgres` (administrators only) | `C:\Program Files\PostgreSQL\17` |
| Firewall | Inbound rules *SapiensML Document Viewer (HTTPS)* and *SapiensML Keycloak (HTTPS)* | |

Nothing already installed is upgraded. The services run as their own virtual accounts (`NT SERVICE\sapiensml-document-viewer`, `NT SERVICE\sapiensml-keycloak`), not LocalSystem. Their settings files are readable only by administrators, SYSTEM and that one service. `.\install.ps1 Deps` only downloads Java 21 and WinSW.

## Step by step (when Keycloak or Okta already exists)

```powershell
.\install.ps1 Install -ViewerHost viewer.example.com -PfxFile C:\certs\viewer.pfx -PfxPassword (Read-Host -AsSecureString)
notepad C:\ProgramData\SapiensML\viewer\viewer.properties     # replace each CHANGE_ME: issuer, client ID and secret
Start-Service sapiensml-document-viewer
.\install.ps1 Status
```

The identity provider's client needs the redirect URI `https://viewer.example.com/login/oauth2/code/viewer` and the post-logout redirect URI `https://viewer.example.com/signed-out`. `viewer.properties` explains each setting. It is a Java properties file: write paths with `/`, and a backslash as `\\`. To run Keycloak on this server later: `.\install-keycloak.ps1 -KeycloakHost ... -ViewerUrl https://viewer.example.com -PfxFile ... -PfxPassword ... -Db Local -ViewerProperties C:\ProgramData\SapiensML\viewer\viewer.properties -FirstUser vieweradmin`.

Behind IIS or a load balancer that terminates TLS, set `VIEWER_ADDRESS=127.0.0.1`, `VIEWER_PORT=8080` and `server.ssl.enabled=false` in `viewer.properties`, and forward `X-Forwarded-For`, `X-Forwarded-Proto` and `Host` to the viewer.

## Enter the Alfresco connection (once)

Open `https://viewer.example.com/admin` as a user with the `VIEWER_ADMIN` role and enter:

| Field | Example: Alfresco PaaS | Example: self-hosted Alfresco |
|---|---|---|
| Repository address | `https://<tenant>.alfrescocloud.com` | `https://alfresco.example.com` |
| Sign-in to Alfresco | Service account (OAuth2 client credentials) | User name and password |
| Token endpoint | *Use the standard path* | - |
| Account | The viewer's client ID and secret in the PaaS realm | A repository user that can read the document folders |
| Allowed folders | The node IDs of the folders whose documents may open | same |

*Save* tests the connection first. The connection is kept in `C:\ProgramData\SapiensML\viewer\data\alfresco-settings.json`, with the secret encrypted by `VIEWER_SETTINGS_KEY` from `viewer.properties`. Keep a copy of that key in the password manager.

## Operating

| | |
|---|---|
| Services | `Get-Service sapiensml-document-viewer, sapiensml-keycloak, postgresql-x64-17` |
| Logs | `C:\ProgramData\SapiensML\viewer\logs` (service output as JSON lines, and the audit trail `audit.json`), `C:\ProgramData\SapiensML\keycloak\logs` |
| Health | `Invoke-WebRequest http://127.0.0.1:8081/actuator/health -UseBasicParsing` (viewer), `http://127.0.0.1:9000/health/ready` (Keycloak); loopback only |
| Upgrade | From the new release's folder: `.\install.ps1 Install`. It keeps the settings, restarts the service and waits for it to be healthy, and goes back to the previous release if it is not. The last five releases are kept |
| Roll back | `.\install.ps1 Rollback` |
| Settings | Edit `viewer.properties`, then `Restart-Service sapiensml-document-viewer`. After changing `JAVA_OPTS`, run `.\install.ps1 Install` again (the options live in the service definition) |
| New certificate | `.\install.ps1 Install -ViewerHost ... -PfxFile new.pfx -PfxPassword ...`; for Keycloak run `install-keycloak.ps1` again with the new `-PfxFile` |
| Backup | `C:\ProgramData\SapiensML` (settings, keys, the saved connection, Keycloak's secrets) and the PostgreSQL `keycloak` database |
| Remove | `.\install.ps1 Uninstall` (keeps settings and logs) or `Uninstall -Purge`. Keycloak, PostgreSQL and Java stay installed |

### Differences from the Linux installation

- No nginx in front: the viewer and Keycloak terminate TLS themselves, and Keycloak uses its own port (8443) because one port cannot be shared by two programs. There is no request rate limit. Use IIS with Application Request Routing, or a load balancer, if one is required.
- Services run under WinSW with virtual accounts instead of systemd units with a system user. Settings are a properties file instead of an environment file.

## Troubleshooting

| Symptom | Look at |
|---|---|
| "running scripts is disabled on this system" | `Set-ExecutionPolicy -Scope Process Bypass` in the same window, and `Unblock-File` the scripts |
| "Port 443 is already used by w3wp/System" | IIS or HTTP.sys holds the port: remove the site's https binding (IIS Manager > Default Web Site > Bindings), or use `-ViewerPort` |
| "cannot resolve sso.example.com" | Create DNS records for both names, or use `-AddHosts` on a test server |
| The viewer service stops right after starting | `C:\ProgramData\SapiensML\viewer\logs\sapiensml-document-viewer.out.log`. `PKIX path building failed` means the Keycloak certificate comes from an internal CA: run again with `-CaCert` |
| "You cannot view documents" after signing in | The user lacks the role, or `VIEWER_ROLES_CLAIM` / `VIEWER_ROLES` do not match what the identity provider sends |
| "The viewer has not been set up yet" | Enter the Alfresco connection at `/admin` |
