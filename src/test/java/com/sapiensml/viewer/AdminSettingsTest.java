package com.sapiensml.viewer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sapiensml.viewer.settings.SettingsStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one-time setup: the viewer starts with no Alfresco connection at all, an administrator enters it on the admin
 * page, and documents open from then on. Its own WireMock plays the repository and its token endpoint.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.viewer.client-secret=viewer-secret",
        "viewer.settings.allow-insecure-http=true",
        "management.server.port=-1" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminSettingsTest {

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    static final String TOKEN = "/auth/realms/alfresco/protocol/openid-connect/token";

    static final String PDF = "42099fec-ce6e-4ab6-a537-0369b6b6f7d2";

    static final String FOLDER = "f15099e2-b4f5-402a-895c-1bf1f4bfbfa3";

    static final WireMockServer ALFRESCO = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static Path settingsDir;

    static {
        ALFRESCO.start();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.security.oauth2.client.provider.idp.issuer-uri",
                () -> TestServers.SERVER.baseUrl() + "/realms/sapiensml");
        settingsDir = Files.createTempDirectory("viewer-admin-test");
        registry.add("viewer.settings.dir", settingsDir::toString);
        registry.add("viewer.settings.key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stop() {
        ALFRESCO.stop();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void paas() {
        ALFRESCO.resetAll();
        ALFRESCO.stubFor(post(TOKEN).atPriority(5).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}")));
        ALFRESCO.stubFor(post(TOKEN).atPriority(1).withRequestBody(containing("client_secret=right-secret"))
            .willReturn(okJson("{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        ALFRESCO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/people/-me-")
            .withHeader("Authorization", equalTo("Bearer svc-token"))
            .willReturn(okJson("{\"entry\":{\"id\":\"service-account-svc-viewer\"}}")));
        ALFRESCO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + FOLDER + "?include=path")
            .willReturn(okJson("{\"entry\":{\"id\":\"" + FOLDER + "\",\"name\":\"Inbound\","
                    + "\"nodeType\":\"cm:folder\",\"isFile\":false,\"isFolder\":true}}")));
        ALFRESCO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + PDF + "?include=path")
            .willReturn(okJson("{\"entry\":{\"id\":\"" + PDF + "\",\"name\":\"Claim.pdf\",\"nodeType\":\"cm:content\","
                    + "\"isFile\":true,\"isFolder\":false,\"content\":{\"mimeType\":\"application/pdf\",\"sizeInBytes\":10},"
                    + "\"path\":{\"elements\":[{\"id\":\"" + FOLDER + "\"}]}}}")));
    }

    static RequestPostProcessor admin() {
        return oidcLogin().idToken(token -> token.subject("ada").claim("name", "Ada Admin"))
            .authorities(new SimpleGrantedAuthority("ROLE_VIEWER_ADMIN"), new SimpleGrantedAuthority("ROLE_DOCUMENT_VIEWER"));
    }

    static RequestPostProcessor viewer() {
        return oidcLogin().idToken(token -> token.subject("vera"))
            .authorities(new SimpleGrantedAuthority("ROLE_DOCUMENT_VIEWER"));
    }

    String form(String baseUrl, String tokenUri, String clientId, String secret, String... folders) {
        return """
                {"baseUrl":"%s","authType":"CLIENT_CREDENTIALS","tokenUri":"%s","clientId":"%s","clientSecret":%s,
                 "allowedFolderIds":[%s]}""".formatted(baseUrl, tokenUri, clientId, secret == null ? "null" : "\"" + secret + "\"",
                folders.length == 0 ? "" : "\"" + String.join("\",\"", folders) + "\"");
    }

    @Test
    @Order(1)
    void startsWithoutAConnection() throws Exception {
        this.mvc.perform(get("/api/session").with(admin()))
            .andExpect(jsonPath("$.admin").value(true))
            .andExpect(jsonPath("$.configured").value(false));
        this.mvc.perform(get("/api/documents/" + PDF).with(viewer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.notConfigured").value(true));
        this.mvc.perform(get("/api/admin/alfresco").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("NONE"));
    }

    @Test
    @Order(2)
    void onlyAdministratorsReachTheSettings() throws Exception {
        this.mvc.perform(get("/api/admin/alfresco").with(viewer()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("You are signed in but not allowed to change the viewer's settings."));
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), ALFRESCO.baseUrl() + TOKEN, "svc", "right-secret")))
            .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void namesEveryWrongField() throws Exception {
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(form("ftp://tenant", "https://user:pw@idp.test/token", "svc viewer", null, "Sites/claims")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.baseUrl").exists())
            .andExpect(jsonPath("$.fieldErrors.tokenUri").exists())
            .andExpect(jsonPath("$.fieldErrors.clientId").value("A client ID has no spaces."))
            .andExpect(jsonPath("$.fieldErrors.clientSecret").value("Enter the client secret."))
            .andExpect(jsonPath("$.fieldErrors.allowedFolderIds").value("Not a node ID: Sites/claims"));
    }

    @Test
    @Order(4)
    void testsWithoutSaving(CapturedOutput output) throws Exception {
        this.mvc.perform(MockMvcRequestBuilders.post("/api/admin/alfresco/test").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), ALFRESCO.baseUrl() + TOKEN, "svc", "wrong")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.steps[0].message").value("The identity provider refused the client ID or secret."))
            // Nothing the remote side said comes back
            .andExpect(jsonPath("$.steps[0].message", not(containsString("invalid_client"))));
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), ALFRESCO.baseUrl() + TOKEN, "svc", "wrong")))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.saved").value(false));
        assertThat(settingsDir.resolve("alfresco-settings.json")).doesNotExist();
        assertThat(output).contains("SETTINGS REJECTED user=ada");
    }

    @Test
    @Order(5)
    void savesAWorkingConnectionAndDocumentsOpen(CapturedOutput output) throws Exception {
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(form(ALFRESCO.baseUrl() + "/alfresco/", ALFRESCO.baseUrl() + TOKEN, "svc", "right-secret", FOLDER.toUpperCase())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.saved").value(true))
            .andExpect(jsonPath("$.steps[1].message").value("Connected to localhost as service-account-svc-viewer"))
            .andExpect(jsonPath("$.steps[2].message").value("Inbound"))
            .andExpect(jsonPath("$.settings.source").value("ADMIN"))
            .andExpect(jsonPath("$.settings.baseUrl").value(ALFRESCO.baseUrl()))
            .andExpect(jsonPath("$.settings.clientSecretSet").value(true))
            .andExpect(jsonPath("$.settings.clientSecret").doesNotExist())
            .andExpect(jsonPath("$.settings.allowedFolderIds[0]").value(FOLDER))
            .andExpect(jsonPath("$.settings.updatedBy").value("ada"));
        assertThat(Files.readString(settingsDir.resolve("alfresco-settings.json"))).doesNotContain("right-secret");
        assertThat(output).contains("SETTINGS SAVED user=ada").doesNotContain("right-secret");

        this.mvc.perform(get("/api/documents/" + PDF).with(viewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Claim.pdf"));
        this.mvc.perform(get("/api/session").with(viewer())).andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    @Order(6)
    void keepsTheStoredSecretOnlyForTheSameAccount() throws Exception {
        // Same token endpoint and client: the blank secret keeps the stored one
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), ALFRESCO.baseUrl() + TOKEN, "svc", "", FOLDER)))
            .andExpect(status().isOk());
        ALFRESCO.verify(postRequestedFor(urlEqualTo(TOKEN)).withRequestBody(containing("client_secret=right-secret")));
        // Another token endpoint: the stored secret is never sent there
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), "http://elsewhere.test/token", "svc", null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.clientSecret").value("Enter the client secret."));
        this.mvc.perform(MockMvcRequestBuilders.put("/api/admin/alfresco").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(form(ALFRESCO.baseUrl(), ALFRESCO.baseUrl() + TOKEN, "other-client", null)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    void theSavedConnectionSurvivesARestart() {
        SettingsStore store = new SettingsStore(
                TestProperties.settings(settingsDir, Base64.getEncoder().encodeToString(new byte[32])));
        assertThat(store.load()).hasValueSatisfying(settings -> {
            assertThat(settings.clientSecret()).isEqualTo("right-secret");
            assertThat(settings.allowedFolderIds()).containsExactly(FOLDER);
        });
    }

}
