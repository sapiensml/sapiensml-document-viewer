package com.sapiensml.viewer.settings;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sapiensml.viewer.TestProperties;
import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsStoreTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    static final AlfrescoSettings SETTINGS = new AlfrescoSettings(URI.create("https://alfresco.example.com"),
            AuthType.CLIENT_CREDENTIALS, URI.create("https://alfresco.example.com/auth/realms/alfresco/protocol/openid-connect/token"),
            "svc-viewer", "very-secret-value", null, null, null, List.of("f15099e2-b4f5-402a-895c-1bf1f4bfbfa3"), "admin",
            Instant.parse("2026-11-11T20:00:00Z"));

    @TempDir
    Path dir;

    @Test
    void keepsSecretsEncryptedAndReadsThemBack() throws Exception {
        SettingsStore store = new SettingsStore(TestProperties.settings(this.dir, KEY));
        assertThat(store.load()).isEmpty();
        store.save(SETTINGS);

        String file = Files.readString(store.file());
        assertThat(file).contains("alfresco.example.com", "svc-viewer", "\"clientSecret\" : \"v1:")
            .doesNotContain("very-secret-value");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file()))).isEqualTo("rw-------");
        }
        assertThat(Files.list(this.dir).map(path -> path.getFileName().toString())).containsExactly("alfresco-settings.json");

        AlfrescoSettings loaded = new SettingsStore(TestProperties.settings(this.dir, KEY)).load().orElseThrow();
        assertThat(loaded).isEqualTo(SETTINGS);
    }

    @Test
    void anotherKeyCannotReadTheSecrets() {
        new SettingsStore(TestProperties.settings(this.dir, KEY)).save(SETTINGS);
        String other = Base64.getEncoder().encodeToString("x".repeat(32).getBytes());
        assertThat(new SettingsStore(TestProperties.settings(this.dir, other)).load()).isEmpty();
    }

    @Test
    void withoutAConfiguredKeyKeepsOneNextToTheSettings() throws Exception {
        SettingsStore store = new SettingsStore(TestProperties.settings(this.dir, null));
        store.save(SETTINGS);
        Path key = this.dir.resolve(SettingsStore.KEY_FILE);
        assertThat(key).exists();
        assertThat(Base64.getDecoder().decode(Files.readString(key).strip())).hasSize(32);
        assertThat(store.load()).contains(SETTINGS);
        // Without its key file the secrets are lost, never guessed
        Files.delete(key);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void refusesAShortKey() {
        SettingsStore store = new SettingsStore(TestProperties.settings(this.dir, Base64.getEncoder().encodeToString(new byte[16])));
        assertThatThrownBy(() -> store.save(SETTINGS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresADamagedFile() throws Exception {
        Files.writeString(this.dir.resolve(SettingsStore.FILE), "{not json");
        assertThat(new SettingsStore(TestProperties.settings(this.dir, KEY)).load()).isEmpty();
    }

}
