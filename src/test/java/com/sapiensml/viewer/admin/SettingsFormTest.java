package com.sapiensml.viewer.admin;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsFormTest {

    static final String FOLDER = "f15099e2-b4f5-402a-895c-1bf1f4bfbfa3";

    static SettingsForm clientCredentials(String baseUrl, String secret) {
        return new SettingsForm(baseUrl, AuthType.CLIENT_CREDENTIALS,
                "https://alfresco.example.com/auth/realms/alfresco/protocol/openid-connect/token", " svc ", secret,
                " ", null, null, List.of(FOLDER.toUpperCase(), "", FOLDER));
    }

    @Test
    void normalisesTheRepositoryAddressAndFolders() {
        AlfrescoSettings settings = clientCredentials("https://Alfresco.example.com/alfresco/", "s").toSettings(null, "ada", false);
        assertThat(settings.baseUrl()).isEqualTo(URI.create("https://Alfresco.example.com"));
        assertThat(settings.clientId()).isEqualTo("svc");
        assertThat(settings.scope()).isNull();
        assertThat(settings.allowedFolderIds()).containsExactly(FOLDER);
        assertThat(settings.updatedBy()).isEqualTo("ada");
        assertThat(settings.updatedAt()).isNotNull();
    }

    @Test
    void acceptsOnlyHttpsUnlessAllowed() {
        assertThatThrownBy(() -> clientCredentials("http://localhost:8085", "s").toSettings(null, "ada", false))
            .isInstanceOfSatisfying(InvalidSettingsException.class,
                    ex -> assertThat(ex.fieldErrors()).containsEntry("baseUrl", "Enter a full https:// address."));
        assertThat(clientCredentials("http://localhost:8085", "s").toSettings(null, "ada", true).baseUrl())
            .isEqualTo(URI.create("http://localhost:8085"));
        assertThatThrownBy(() -> clientCredentials("https://tenant/share/page", "s").toSettings(null, "ada", false))
            .isInstanceOfSatisfying(InvalidSettingsException.class, ex -> assertThat(ex.fieldErrors()).containsKey("baseUrl"));
        assertThatThrownBy(() -> clientCredentials("https://tenant?x=1", "s").toSettings(null, "ada", false))
            .isInstanceOfSatisfying(InvalidSettingsException.class, ex -> assertThat(ex.fieldErrors().get("baseUrl"))
                .contains("query"));
        assertThatThrownBy(() -> clientCredentials("not a url", "s").toSettings(null, "ada", false))
            .isInstanceOf(InvalidSettingsException.class);
    }

    @Test
    void basicLoginKeepsThePasswordOnlyForTheSameUserAndAddress() {
        SettingsForm first = new SettingsForm("http://localhost:8085", AuthType.BASIC, null, null, null, null, "admin", "admin",
                List.of());
        AlfrescoSettings saved = first.toSettings(null, "ada", true);
        assertThat(saved.password()).isEqualTo("admin");
        assertThat(saved.clientSecret()).isNull();

        SettingsForm again = new SettingsForm("http://localhost:8085", AuthType.BASIC, null, null, null, null, "admin", "",
                List.of());
        assertThat(again.toSettings(saved, "ada", true).password()).isEqualTo("admin");

        SettingsForm otherUser = new SettingsForm("http://localhost:8085", AuthType.BASIC, null, null, null, null, "bob", null,
                List.of());
        assertThatThrownBy(() -> otherUser.toSettings(saved, "ada", true)).isInstanceOfSatisfying(InvalidSettingsException.class,
                ex -> assertThat(ex.fieldErrors()).containsEntry("password", "Enter the password."));
        SettingsForm noUser = new SettingsForm("http://localhost:8085", AuthType.BASIC, null, null, null, null, " ", "x", List.of());
        assertThatThrownBy(() -> noUser.toSettings(null, "ada", true)).isInstanceOfSatisfying(InvalidSettingsException.class,
                ex -> assertThat(ex.fieldErrors()).containsKey("username"));
    }

    @Test
    void switchingToBasicDropsTheClientSecret() {
        AlfrescoSettings previous = clientCredentials("https://alfresco.example.com", "secret").toSettings(null, "ada", false);
        SettingsForm basic = new SettingsForm("https://alfresco.example.com", AuthType.BASIC, null, null, null, null,
                "admin", "pw", List.of());
        AlfrescoSettings settings = basic.toSettings(previous, "ada", false);
        assertThat(settings.clientSecret()).isNull();
        assertThat(settings.tokenUri()).isNull();
    }

    @Test
    void refusesMissingTypeAndTooManyFolders() {
        SettingsForm noType = new SettingsForm("https://tenant", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> noType.toSettings(null, "ada", false)).isInstanceOfSatisfying(InvalidSettingsException.class,
                ex -> assertThat(ex.fieldErrors()).containsKey("authType"));
        List<String> many = java.util.stream.IntStream.range(0, SettingsForm.MAX_FOLDERS + 1)
            .mapToObj(i -> String.format("00000000-0000-4000-8000-%012d", i)).toList();
        SettingsForm tooMany = new SettingsForm("https://tenant", AuthType.BASIC, null, null, null, null, "a", "pw-xyz-123", many);
        assertThatThrownBy(() -> tooMany.toSettings(null, "ada", false)).isInstanceOfSatisfying(InvalidSettingsException.class,
                ex -> assertThat(ex.fieldErrors()).containsKey("allowedFolderIds"));
        assertThat(tooMany.toString()).doesNotContain("pw-xyz-123");
    }

}
