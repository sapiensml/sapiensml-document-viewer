# syntax=docker/dockerfile:1
# SapiensML Document Viewer image, built from source: the React UI with Node, the jar with Maven, then a small
# Java 21 runtime that runs the jar as a non-root user. The build stages run on the build machine's own platform (the
# jar is the same everywhere), so a multi-platform build only repeats the last stage.
#
#   docker build -t sapiensml-document-viewer .
#   docker run -p 8080:8080 --env-file viewer.env -v viewer-data:/var/lib/sapiensml-document-viewer sapiensml-document-viewer
#
# Settings, volumes and health checks: DEPLOY-DOCKER.md. Tests run in CI, not here.

FROM --platform=$BUILDPLATFORM node:22.23.3-alpine3.23@sha256:baf676f7d0e552f3231945c2f979055ca121bce128c152f7a34e6bd1728b1c5a AS ui
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS jar
WORKDIR /build
COPY pom.xml LICENSE NOTICE ./
COPY src ./src
COPY --from=ui /build/frontend/dist ./frontend/dist
# -Dviewer.skipUi=true packages the UI built above instead of running npm again
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests -Dviewer.skipUi=true package

FROM eclipse-temurin:21.0.13_9-jre-alpine-3.23@sha256:e4fc591be0478866dd325d05dd920618c57d891d3e8ccc6925393fb0ec0942a6
LABEL org.opencontainers.image.title="SapiensML Document Viewer" \
      org.opencontainers.image.description="Secure viewer for Alfresco documents, opened by link from business applications, with OpenID Connect sign-in" \
      org.opencontainers.image.vendor="SapiensML Technologies" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.url="https://sapiensml.com" \
      org.opencontainers.image.source="https://github.com/sapiensml/sapiensml-document-viewer"

# Same layout as the Linux install: settings (with the encrypted Alfresco secret) and the audit trail in their own
# directories, owned by the viewer's user only
RUN addgroup -S -g 10001 sapiensml \
 && adduser -S -D -H -u 10001 -G sapiensml -h /opt/sapiensml-document-viewer -s /sbin/nologin sapiensml \
 && mkdir -p /opt/sapiensml-document-viewer /var/lib/sapiensml-document-viewer /var/log/sapiensml-document-viewer \
 && chown sapiensml:sapiensml /var/lib/sapiensml-document-viewer /var/log/sapiensml-document-viewer \
 && chmod 0700 /var/lib/sapiensml-document-viewer
COPY --from=jar /build/target/sapiensml-document-viewer.jar /opt/sapiensml-document-viewer/sapiensml-document-viewer.jar

# prod: JSON logs on the console (the audit trail too, and in its own file). The management port listens on all of
# the container's interfaces so orchestrators can probe it; publish only the viewer's port.
ENV SPRING_PROFILES_ACTIVE=prod \
    VIEWER_SETTINGS_DIR=/var/lib/sapiensml-document-viewer \
    VIEWER_AUDIT_DIR=/var/log/sapiensml-document-viewer \
    VIEWER_MANAGEMENT_ADDRESS=0.0.0.0 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

USER 10001:10001
WORKDIR /opt/sapiensml-document-viewer
EXPOSE 8080 8081
VOLUME ["/var/lib/sapiensml-document-viewer", "/var/log/sapiensml-document-viewer"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${VIEWER_MANAGEMENT_PORT:-8081}/actuator/health/liveness" || exit 1
# A shell so that JAVA_OPTS is honoured; exec keeps Java as PID 1 for clean shutdowns. Arguments go to the viewer.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/sapiensml-document-viewer/sapiensml-document-viewer.jar \"$@\"", "--"]
