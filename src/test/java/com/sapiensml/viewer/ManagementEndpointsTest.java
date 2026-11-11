package com.sapiensml.viewer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health and info as the install script and nginx reach them: on the separate management port, without a
 * session, while every other actuator endpoint stays closed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "viewer.alfresco.client-id=svc-viewer",
        "viewer.alfresco.client-secret=s3cret",
        "spring.security.oauth2.client.registration.viewer.client-secret=viewer-secret",
        "management.server.port=0",
        // Exposed by mistake: must still be refused
        "management.endpoints.web.exposure.include=health,info,env" })
class ManagementEndpointsTest {

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    static {
        TestServers.SERVER.stubFor(post("/auth/realms/alfresco/protocol/openid-connect/token")
            .willReturn(okJson("{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        TestServers.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/people/-me-")
            .willReturn(okJson("{\"entry\":{\"id\":\"service-account-svc-viewer\"}}")));
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        TestServers.register(registry);
    }

    @LocalManagementPort
    int managementPort;

    @LocalServerPort
    int serverPort;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(int port, String path) throws Exception {
        return this.http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndInfoNeedNoSignIn() throws Exception {
        HttpResponse<String> readiness = get(this.managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"UP\"");
        assertThat(readiness.headers().firstValue("Set-Cookie")).isEmpty();

        HttpResponse<String> health = get(this.managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"alfresco\":{\"details\":{\"source\":\"ENVIRONMENT\"},\"status\":\"UP\"");

        assertThat(get(this.managementPort, "/actuator/info").statusCode()).isEqualTo(200);
    }

    @Test
    void otherEndpointsStayClosed() throws Exception {
        assertThat(get(this.managementPort, "/actuator/env").statusCode()).isEqualTo(403);
    }

    @Test
    void theApplicationPortHasNoActuator() throws Exception {
        // Sent to sign-in like any unknown page, never answered
        assertThat(get(this.serverPort, "/actuator/health").statusCode()).isEqualTo(302);
    }

}
