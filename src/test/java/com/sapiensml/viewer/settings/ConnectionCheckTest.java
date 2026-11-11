package com.sapiensml.viewer.settings;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sapiensml.viewer.TestProperties;
import com.sapiensml.viewer.alfresco.AlfrescoClientConfiguration;
import com.sapiensml.viewer.alfresco.AlfrescoClientFactory;
import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionCheckTest {

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    static final WireMockServer SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    CloseableHttpClient http;

    ConnectionCheck check;

    @BeforeAll
    static void start() {
        SERVER.start();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @BeforeEach
    void setUp() {
        SERVER.resetAll();
        this.http = new AlfrescoClientConfiguration().alfrescoHttpClient(TestProperties.scope(java.util.Set.of(), java.util.Set.of()));
        this.check = new ConnectionCheck(new AlfrescoClientFactory(this.http));
    }

    @AfterEach
    void tearDown() throws IOException {
        this.http.close();
    }

    AlfrescoSettings basic(String... folders) {
        return new AlfrescoSettings(URI.create(SERVER.baseUrl()), AuthType.BASIC, null, null, null, null, "admin", "admin",
                List.of(folders), null, null);
    }

    @Test
    void basicLoginRefused() {
        SERVER.stubFor(get(API + "/people/-me-").willReturn(aResponse().withStatus(401)));
        ConnectionCheck.Result result = this.check.run(basic());
        assertThat(result.ok()).isFalse();
        assertThat(result.steps()).extracting(ConnectionCheck.Step::message)
            .containsExactly("Alfresco refused the user name or password.");
    }

    @Test
    void noRepositoryAtTheAddress() {
        SERVER.stubFor(get(API + "/people/-me-").willReturn(aResponse().withStatus(404)));
        assertThat(this.check.run(basic()).steps().get(0).message()).isEqualTo("There is no Alfresco REST API at this address.");
        SERVER.stubFor(get(API + "/people/-me-").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(this.check.run(basic()).steps().get(0).message()).startsWith("The repository could not be reached.");
    }

    @Test
    void checksEachFolder() {
        String folder = "f15099e2-b4f5-402a-895c-1bf1f4bfbfa3";
        String file = "42099fec-ce6e-4ab6-a537-0369b6b6f7d2";
        String missing = "00000000-0000-4000-8000-000000000000";
        String broken = "00000000-0000-4000-8000-000000000001";
        SERVER.stubFor(get(API + "/people/-me-").willReturn(okJson("{\"entry\":{\"id\":\"admin\"}}")));
        SERVER.stubFor(get(API + "/nodes/" + folder + "?include=path")
            .willReturn(okJson("{\"entry\":{\"id\":\"" + folder + "\",\"name\":\"Inbound\",\"isFolder\":true}}")));
        SERVER.stubFor(get(API + "/nodes/" + file + "?include=path")
            .willReturn(okJson("{\"entry\":{\"id\":\"" + file + "\",\"name\":\"a.pdf\",\"nodeType\":\"cm:content\",\"isFile\":true}}")));
        SERVER.stubFor(get(API + "/nodes/" + missing + "?include=path").willReturn(aResponse().withStatus(404)));
        SERVER.stubFor(get(API + "/nodes/" + broken + "?include=path").willReturn(aResponse().withStatus(500)));

        ConnectionCheck.Result result = this.check.run(basic(folder, file, missing, broken));

        assertThat(result.ok()).isFalse();
        assertThat(result.steps()).extracting(ConnectionCheck.Step::message).containsExactly(
                "Connected to localhost as admin", "Inbound", "This is not a folder (cm:content).",
                "Not found, or the service account cannot read it.", "The repository did not answer.");
        assertThat(this.check.run(basic(folder)).ok()).isTrue();
    }

    @Test
    void unreachableTokenEndpoint() {
        AlfrescoSettings settings = new AlfrescoSettings(URI.create(SERVER.baseUrl()), AuthType.CLIENT_CREDENTIALS,
                URI.create(SERVER.baseUrl() + "/token"), "svc", "secret", null, null, null, List.of(), null, null);
        SERVER.stubFor(post("/token").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(this.check.run(settings).steps().get(0).message()).startsWith("The token endpoint could not be reached.");
    }

}
