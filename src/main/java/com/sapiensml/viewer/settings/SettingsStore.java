package com.sapiensml.viewer.settings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties;

/**
 * Keeps the connection entered on the admin page in {@code <viewer.settings.dir>/alfresco-settings.json}, readable
 * by the viewer's own user only, with the client secret and password encrypted. The key comes from
 * {@code VIEWER_SETTINGS_KEY} (install.sh puts one in viewer.env, away from the data); without it a key file is
 * created next to the settings. Writes are atomic, so a crash never leaves half a file.
 */
@Component
public class SettingsStore {

    static final String FILE = "alfresco-settings.json";

    static final String KEY_FILE = "settings.key";

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path dir;

    private final String configuredKey;

    private final JsonMapper json = JsonMapper.builder().build();

    public SettingsStore(ViewerProperties properties) {
        this.dir = properties.settings().dir().toAbsolutePath().normalize();
        this.configuredKey = properties.settings().key();
    }

    Path file() {
        return this.dir.resolve(FILE);
    }

    /** The saved settings; empty when none were saved or they cannot be read (logged). */
    public Optional<AlfrescoSettings> load() {
        Path file = file();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Stored stored = this.json.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            SecretBox box = box(false);
            return Optional.of(new AlfrescoSettings(URI.create(stored.baseUrl()),
                    ViewerProperties.AuthType.valueOf(stored.authType()),
                    stored.tokenUri() == null ? null : URI.create(stored.tokenUri()), stored.clientId(),
                    box.open(stored.clientSecret()), stored.scope(), stored.username(), box.open(stored.password()),
                    stored.allowedFolderIds(), stored.updatedBy(),
                    stored.updatedAt() == null ? null : Instant.parse(stored.updatedAt())));
        }
        catch (IOException | JacksonException | IllegalArgumentException | IllegalStateException ex) {
            log.error("The Alfresco settings in {} cannot be used ({}). Enter them again on the admin page", file,
                    ex.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void save(AlfrescoSettings settings) {
        try {
            createDirectory();
            SecretBox box = box(true);
            Stored stored = new Stored(1, settings.baseUrl().toString(), settings.authType().name(),
                    settings.tokenUri() == null ? null : settings.tokenUri().toString(), settings.clientId(),
                    box.seal(settings.clientSecret()), settings.scope(), settings.username(), box.seal(settings.password()),
                    settings.allowedFolderIds(), settings.updatedBy(),
                    settings.updatedAt() == null ? null : settings.updatedAt().toString());
            writePrivately(file(), this.json.writerWithDefaultPrettyPrinter().writeValueAsString(stored));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Saving the Alfresco settings in " + this.dir + " failed: " + ex.getMessage(), ex);
        }
    }

    private SecretBox box(boolean createKey) throws IOException {
        if (StringUtils.hasText(this.configuredKey)) {
            return new SecretBox(decodeKey(this.configuredKey, "VIEWER_SETTINGS_KEY"));
        }
        Path keyFile = this.dir.resolve(KEY_FILE);
        if (!Files.exists(keyFile)) {
            if (!createKey) {
                throw new IllegalStateException("no settings key: VIEWER_SETTINGS_KEY is empty and " + keyFile + " is missing");
            }
            log.warn("VIEWER_SETTINGS_KEY is not set; keeping the settings key in {}. On servers, set it in viewer.env", keyFile);
            writePrivately(keyFile, Base64.getEncoder().encodeToString(SecretBox.newKey()));
        }
        return new SecretBox(decodeKey(Files.readString(keyFile, StandardCharsets.UTF_8).strip(), keyFile.toString()));
    }

    private static byte[] decodeKey(String value, String source) {
        try {
            return Base64.getDecoder().decode(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException(source + " is not base64", ex);
        }
    }

    private void createDirectory() throws IOException {
        if (Files.isDirectory(this.dir)) {
            return;
        }
        if (POSIX) {
            Files.createDirectories(this.dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        else {
            Files.createDirectories(this.dir);
        }
    }

    private void writePrivately(Path target, String content) throws IOException {
        createDirectory();
        Path temporary = POSIX
                ? Files.createTempFile(this.dir, ".settings", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(this.dir, ".settings", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The file's shape. Secrets are sealed; everything else is plain so operators can read what is configured. */
    record Stored(int version, String baseUrl, String authType, String tokenUri, String clientId, String clientSecret,
            String scope, String username, String password, List<String> allowedFolderIds, String updatedBy,
            String updatedAt) {
    }

}
