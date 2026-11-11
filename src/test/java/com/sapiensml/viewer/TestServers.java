package com.sapiensml.viewer;

import java.io.IOException;
import java.nio.file.Files;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.springframework.test.context.DynamicPropertyRegistry;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

/**
 * One WireMock for the application tests, playing both identity providers and the repository: the sign-in
 * Keycloak (OpenID discovery, read when the application starts) and Alfresco (token endpoint and REST API).
 */
public final class TestServers {

    public static final WireMockServer SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        SERVER.start();
        stubDiscovery();
    }

    private TestServers() {
    }

    /** Again after a test calls resetAll(). */
    public static void stubDiscovery() {
        String issuer = SERVER.baseUrl() + "/realms/sapiensml";
        SERVER.stubFor(get("/realms/sapiensml/.well-known/openid-configuration").willReturn(okJson("""
                {"issuer":"%1$s","authorization_endpoint":"%1$s/protocol/openid-connect/auth",
                "token_endpoint":"%1$s/protocol/openid-connect/token","jwks_uri":"%1$s/protocol/openid-connect/certs",
                "userinfo_endpoint":"%1$s/protocol/openid-connect/userinfo",
                "end_session_endpoint":"%1$s/protocol/openid-connect/logout",
                "response_types_supported":["code"],"subject_types_supported":["public"],
                "id_token_signing_alg_values_supported":["RS256"],"code_challenge_methods_supported":["S256"]}
                """.formatted(issuer))));
    }

    /** The sign-in Keycloak, and the Alfresco connection from the environment. */
    public static void register(DynamicPropertyRegistry registry) {
        registerIssuer(registry);
        registry.add("viewer.alfresco.base-url", SERVER::baseUrl);
        registry.add("viewer.alfresco.token-uri",
                () -> SERVER.baseUrl() + "/auth/realms/alfresco/protocol/openid-connect/token");
    }

    /** Only the sign-in Keycloak, and an empty settings directory: the viewer starts without an Alfresco connection. */
    public static void registerIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.idp.issuer-uri", () -> SERVER.baseUrl() + "/realms/sapiensml");
        registry.add("viewer.settings.dir", TestServers::emptyDirectory);
    }

    private static String emptyDirectory() {
        try {
            return Files.createTempDirectory("viewer-settings").toString();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
