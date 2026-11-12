# Running the SapiensML Document Viewer in Docker

The viewer is published as a Docker image for `linux/amd64` and `linux/arm64`:

```
ghcr.io/sapiensml/sapiensml-document-viewer:<version>     # for example 1.1.0; also 1.1 and latest
```

Docker is optional. The [Linux](DEPLOY.md) and [Windows](DEPLOY-WINDOWS.md) installers set up the same viewer without containers. The image runs the same jar, with the same settings.

## Try it: the quick start

[deploy/docker/compose.yaml](deploy/docker/compose.yaml) runs Keycloak with the `sapiensml` realm, three test users and the viewer, on this machine only:

```bash
docker compose -f deploy/docker/compose.yaml up -d
# or, built from this checkout instead of the published image:
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.build.yaml up -d --build
```

After about a minute, open http://localhost:18200/admin and sign in as `vieweradmin` / `vieweradmin`. Enter the Alfresco connection there (the fields are explained in [DEPLOY.md, step 5](DEPLOY.md#5-enter-the-alfresco-connection-once)). Then open `http://localhost:18200/documents/<node id>`.

| User | Password | Can |
|---|---|---|
| `viewer` | `viewer` | View documents |
| `vieweradmin` | `vieweradmin` | View documents and use the admin page |
| `noaccess` | `noaccess` | Neither (to see the refusal) |

Keycloak's admin console is http://localhost:18210/admin (`admin` / `admin`).

- **An Alfresco on this machine:** use `http://host.docker.internal:<port>` as the repository address, because `localhost` inside the container is the container itself. For plain `http://`, start with `VIEWER_SETTINGS_ALLOW_HTTP=true docker compose ...`.
- **Check it works:** `./deploy/docker/smoke-test.sh` signs in as `viewer` through Keycloak and checks the session. CI runs it on every change.
- **Logs:** `docker compose -f deploy/docker/compose.yaml logs -f viewer`.
- **Stop:** `docker compose -f deploy/docker/compose.yaml down`. Add `--volumes` to also forget the saved Alfresco connection.

The quick start is not for production: Keycloak runs in development mode with the vendor-default admin password, and the client secret is a fixed default. You can override it with `VIEWER_OIDC_CLIENT_SECRET` (letters and digits).

## Production

Run the image behind your TLS reverse proxy or ingress, with your identity provider:

```bash
docker volume create viewer-data
docker run -d --name sapiensml-document-viewer --restart unless-stopped \
    -p 127.0.0.1:8080:8080 \
    --env-file /etc/sapiensml-document-viewer/viewer.env \
    -v viewer-data:/var/lib/sapiensml-document-viewer \
    ghcr.io/sapiensml/sapiensml-document-viewer:1.1.0
```

**Settings.** The settings are the environment variables in the README's [Configuration](README.md#configuration) table, the same `viewer.env` as on Linux ([template](deploy/linux/viewer.env.example)). At least:

| Variable | Value |
|---|---|
| `VIEWER_OIDC_ISSUER_URI` | Your identity provider, for example `https://sso.example.com/realms/sapiensml` |
| `VIEWER_OIDC_CLIENT_ID`, `VIEWER_OIDC_CLIENT_SECRET` | The viewer's confidential client (redirect URI `https://<viewer>/login/oauth2/code/viewer`) |
| `VIEWER_SETTINGS_KEY` | Encrypts the secrets the admin page saves: `head -c 32 /dev/urandom \| base64`. Keep a copy in the password manager. |

Without `VIEWER_SETTINGS_KEY`, the viewer keeps its key in the settings volume, next to the secrets it protects.

**What the image provides:**

| | |
|---|---|
| User | `sapiensml` (uid and gid 10001), not root |
| Ports | `8080` the viewer (`VIEWER_PORT`); `8081` health and info (`VIEWER_MANAGEMENT_PORT`). Don't publish 8081 to users. |
| Volumes | `/var/lib/sapiensml-document-viewer`: the admin page's saved connection (keep it). `/var/log/sapiensml-document-viewer`: the audit file (optional). |
| Logs | JSON lines (Elastic Common Schema) on standard output, including the audit trail |
| Health | `/actuator/health/liveness` and `/actuator/health/readiness` on port 8081; the image's own health check uses liveness |
| Memory | `JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75`; give the container 1 GiB or more |

**Behind a proxy.** The viewer trusts `X-Forwarded-For`, `-Proto` and `-Host` from private network addresses, which is where a proxy container or ingress sits. The proxy must pass them, so that redirect URIs and secure cookies use `https://<viewer>`. Keep `VIEWER_COOKIE_SECURE=true` (the default).

**Bind mounts.** A host directory mounted at `/var/lib/sapiensml-document-viewer` must be writable by uid 10001: `sudo install -d -o 10001 -g 10001 -m 0700 /srv/viewer-data`.

**Read-only root file system.** This works if `/tmp` is writable: `docker run --read-only --tmpfs /tmp ...`, or `readOnlyRootFilesystem: true` with an `emptyDir` at `/tmp` in Kubernetes.

**Kubernetes.** Probe port 8081:

```yaml
livenessProbe:  { httpGet: { path: /actuator/health/liveness, port: 8081 }, initialDelaySeconds: 30 }
readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8081 } }
```

Sessions are held in memory: with more than one replica, use sticky sessions at the ingress. Mount the settings volume (`ReadWriteMany`) on every replica with the same `VIEWER_SETTINGS_KEY`, or give the Alfresco connection through `VIEWER_ALFRESCO_*` variables.

## Verifying the image

Each image is built by this repository's release workflow and signed with build provenance:

```bash
gh attestation verify oci://ghcr.io/sapiensml/sapiensml-document-viewer:1.1.0 --repo sapiensml/sapiensml-document-viewer
```

## Building the image yourself

```bash
docker build -t sapiensml-document-viewer .
```

The [Dockerfile](Dockerfile) builds the UI and the jar from source in its first two stages, so no Java or Node is needed on the machine. Tests run in CI, not in the image build.
