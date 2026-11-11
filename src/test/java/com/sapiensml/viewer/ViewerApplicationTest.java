package com.sapiensml.viewer;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application with WireMock as both identity providers: the sign-in Keycloak (OpenID discovery) and the
 * repository (Alfresco and its token endpoint).
 */
@SpringBootTest(properties = {
        "viewer.alfresco.client-id=svc-viewer",
        "viewer.ui.display-properties=cm:title=Title, ins:caseNumber=Case",
        "viewer.ui.source-application=FINEOS",
        "viewer.alfresco.client-secret=s3cret",
        "viewer.access.allowed-ancestor-ids=f15099e2-b4f5-402a-895c-1bf1f4bfbfa3",
        "spring.security.oauth2.client.registration.viewer.client-secret=viewer-secret",
        // ManagementEndpointsTest covers the management port
        "management.server.port=-1" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ViewerApplicationTest {

    static final String PDF = "42099fec-ce6e-4ab6-a537-0369b6b6f7d2";

    static final String WORD = "c1464134-ee8b-44fc-bd4d-eac89eb13b11";

    static final String ZIP = "50c1bf80-f1a0-4dda-bc8f-70e9e321ee35";

    static final String OUTSIDE = "4fb024b1-7d41-41ac-ab1b-c0c037b41a7b";

    static final String FOLDER = "9497a455-efca-4886-b1ac-18cf550fcd99";

    static final String ALLOWED_FOLDER = "f15099e2-b4f5-402a-895c-1bf1f4bfbfa3";

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    static final WireMockServer SERVER = TestServers.SERVER;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        TestServers.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientRegistrationRepository registrations;

    @BeforeEach
    void repository() {
        SERVER.resetRequests();
        SERVER.stubFor(post("/auth/realms/alfresco/protocol/openid-connect/token")
            .willReturn(okJson("{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        node(PDF, "ins:document", true, "application/pdf", 46267, ALLOWED_FOLDER);
        node(WORD, "ins:document", true,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 4000, ALLOWED_FOLDER);
        node(ZIP, "cm:content", true, "application/zip", 4000, ALLOWED_FOLDER);
        node(OUTSIDE, "cm:content", true, "application/pdf", 10, "916d9b20-0393-40e0-8e3c-b157807912c9");
        node(FOLDER, "cm:folder", false, null, 0, ALLOWED_FOLDER);
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + PDF + "/content?attachment=false")
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/pdf;charset=UTF-8")
                .withHeader("Accept-Ranges", "bytes")
                // As Alfresco PaaS does on complete responses
                .withHeader("Content-Range", "bytes 0-12/13")
                .withHeader("Set-Cookie", "AWSALB=sticky; Path=/")
                .withHeader("Server", "Alfresco")
                .withBody("%PDF-1.7 test")));
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + PDF + "/content?attachment=false")
            .withHeader("Range", equalTo("bytes=0-3"))
            .willReturn(aResponse().withStatus(206)
                .withHeader("Content-Range", "bytes 0-3/13")
                .withHeader("Accept-Ranges", "bytes")
                .withBody("%PDF")));
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(API + "/nodes/" + WORD + "/renditions/pdf/content"))
            .willReturn(aResponse().withStatus(404)));
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + WORD + "/renditions")
            .willReturn(okJson("{\"list\":{\"entries\":[{\"entry\":{\"id\":\"pdf\",\"status\":\"NOT_CREATED\"}}]}}")));
        SERVER.stubFor(post(API + "/nodes/" + WORD + "/renditions").willReturn(aResponse().withStatus(202)));
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(API + "/nodes/" + ZIP + "/renditions/pdf/content"))
            .willReturn(aResponse().withStatus(404)));
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + ZIP + "/renditions")
            .willReturn(okJson("{\"list\":{\"entries\":[{\"entry\":{\"id\":\"doclib\",\"status\":\"NOT_CREATED\"}}]}}")));
    }

    static void node(String id, String type, boolean file, String mimeType, long size, String ancestor) {
        String content = mimeType == null ? "" : ",\"content\":{\"mimeType\":\"" + mimeType + "\",\"sizeInBytes\":" + size
                + ",\"encoding\":\"UTF-8\"}";
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(API + "/nodes/" + id + "?include=path")
            .withHeader("Authorization", equalTo("Bearer svc-token"))
            .willReturn(okJson("""
                    {"entry":{"id":"%s","name":"Claim Form 100234.pdf","nodeType":"%s","isFile":%s%s,
                    "modifiedAt":"2026-11-10T21:25:54.758+0000",
                    "properties":{"cm:title":"Claim form","ins:caseNumber":"100234","ins:internalNote":"not for browsers"},
                    "path":{"name":"/Inbound/2026","isComplete":false,"elements":[{"id":"%s","name":"x"}]}}}
                    """.formatted(id, type, file, content, ancestor))));
    }

    static RequestPostProcessor viewer() {
        return oidcLogin().idToken(token -> token.subject("user-1").claim("name", "Vera Viewer"))
            .authorities(new SimpleGrantedAuthority("ROLE_DOCUMENT_VIEWER"));
    }

    static RequestPostProcessor noRole() {
        return oidcLogin().idToken(token -> token.subject("user-2"));
    }

    @Test
    void sendsANewVisitorToTheIdentityProviderAndBackToTheDocument() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mvc.perform(get("/documents/" + PDF).session(session))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/oauth2/authorization/viewer"));
        this.mvc.perform(get("/oauth2/authorization/viewer").session(session))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", allOf(startsWith(SERVER.baseUrl() + "/realms/sapiensml/protocol/openid-connect/auth"),
                    containsString("code_challenge_method=S256"), containsString("client_id=sapiensml-document-viewer"),
                    containsString("redirect_uri=http://localhost/login/oauth2/code/viewer"), not(containsString("kc_idp_hint")))));
        // The document link is remembered for after sign-in
        assertThat(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST").toString()).contains("/documents/" + PDF);
    }

    @Test
    void apiCallsWithoutASessionGet401() throws Exception {
        this.mvc.perform(get("/api/session"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
        this.mvc.perform(get("/api/documents/" + PDF)).andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheAppShellAndAssetsWithSecurityHeaders() throws Exception {
        this.mvc.perform(get("/signed-out"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().string("Content-Security-Policy", allOf(containsString("default-src 'none'"),
                    containsString("frame-ancestors 'none'"), containsString("script-src 'self' 'wasm-unsafe-eval'"))))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        this.mvc.perform(get("/documents/" + PDF).with(viewer())).andExpect(status().isOk());
    }

    @Test
    void acceptsNodeReferenceLinks() throws Exception {
        this.mvc.perform(get("/view").param("nodeId", "workspace://SpacesStore/" + PDF.toUpperCase()).with(viewer()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/documents/" + PDF));
        this.mvc.perform(get("/view").param("nodeId", "../people/-me-").with(viewer()))
            .andExpect(redirectedUrl("/?link=invalid"));
    }

    @Test
    void describesTheSession() throws Exception {
        this.mvc.perform(get("/api/session").with(viewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Vera Viewer"))
            .andExpect(jsonPath("$.canView").value(true))
            .andExpect(jsonPath("$.admin").value(false))
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.allowDownload").value(false));
        this.mvc.perform(get("/api/session").with(noRole())).andExpect(jsonPath("$.canView").value(false));
    }

    @Test
    void refusesUsersWithoutTheViewerRole() throws Exception {
        this.mvc.perform(get("/api/documents/" + PDF).with(noRole()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("You are signed in but not allowed to view documents."));
        SERVER.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(API + "/nodes/" + PDF)));
    }

    @Test
    void describesADocumentWithoutRepositoryDetails(CapturedOutput output) throws Exception {
        this.mvc.perform(get("/api/documents/" + PDF.toUpperCase()).with(viewer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(PDF))
            .andExpect(jsonPath("$.title").value("Claim form"))
            .andExpect(jsonPath("$.viewer").value("PDF"))
            .andExpect(jsonPath("$.converted").value(false))
            .andExpect(jsonPath("$.modifiedAt").value("2026-11-10T21:25:54.758Z"))
            .andExpect(jsonPath("$.properties[0].label").value("Title"))
            .andExpect(jsonPath("$.properties[1].label").value("Case"))
            .andExpect(jsonPath("$.properties[1].value").value("100234"))
            .andExpect(content().string(allOf(not(containsString("not for browsers")), not(containsString("FineosInbound")),
                    not(containsString(ALLOWED_FOLDER)), not(containsString("alfresco")))));
        assertThat(output).contains("VIEW GRANTED node=" + PDF + " user=user-1");
    }

    @Test
    void streamsAPdfWithOnlyTheHeadersItNeeds() throws Exception {
        this.mvc.perform(get("/api/documents/" + PDF + "/preview").with(viewer()))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"))
            .andExpect(content().string("%PDF-1.7 test"))
            .andExpect(header().string("Content-Disposition", "inline; filename=\"Claim Form 100234.pdf\"; filename*=UTF-8''Claim%20Form%20100234.pdf"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().doesNotExist("Server"))
            .andExpect(header().doesNotExist("Content-Range"));
    }

    @Test
    void passesByteRangesThrough() throws Exception {
        this.mvc.perform(get("/api/documents/" + PDF + "/preview").header("Range", "bytes=0-3").with(viewer()))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 0-3/13"))
            .andExpect(content().string("%PDF"));
        // Several ranges are not passed on; the whole file is sent, as HTTP allows
        this.mvc.perform(get("/api/documents/" + PDF + "/preview").header("Range", "bytes=0-1,5-6").with(viewer()))
            .andExpect(status().isOk())
            .andExpect(content().string("%PDF-1.7 test"));
    }

    @Test
    void requestsTheRenditionOfAnOfficeFileAndAsksTheBrowserToWait() throws Exception {
        this.mvc.perform(get("/api/documents/" + WORD).with(viewer()))
            .andExpect(jsonPath("$.viewer").value("PDF"))
            .andExpect(jsonPath("$.converted").value(true));
        this.mvc.perform(get("/api/documents/" + WORD + "/preview").with(viewer()))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Retry-After", "3"))
            .andExpect(jsonPath("$.retryAfterSeconds").value(3));
        SERVER.verify(postRequestedFor(urlEqualTo(API + "/nodes/" + WORD + "/renditions")));
    }

    @Test
    void explainsFilesWithoutAPreview() throws Exception {
        this.mvc.perform(get("/api/documents/" + ZIP).with(viewer())).andExpect(jsonPath("$.viewer").value("NONE"));
        this.mvc.perform(get("/api/documents/" + ZIP + "/preview").with(viewer()))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void answersTheSameForMissingAndOutOfScopeDocuments(CapturedOutput output) throws Exception {
        String missing = "00000000-0000-4000-8000-000000000000";
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(API + "/nodes/" + missing))
            .willReturn(aResponse().withStatus(404)));
        for (String id : new String[] { missing, OUTSIDE, FOLDER }) {
            this.mvc.perform(get("/api/documents/" + id).with(viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The document does not exist or you cannot open it."));
            this.mvc.perform(get("/api/documents/" + id + "/preview").with(viewer())).andExpect(status().isNotFound());
        }
        assertThat(output).contains("VIEW DENIED node=" + OUTSIDE).contains("(outside the allowed folders)")
            .contains("VIEW NOT_FOUND node=" + missing);
    }

    @Test
    void refusesInvalidNodeIdsBeforeCallingAlfresco() throws Exception {
        this.mvc.perform(get("/api/documents/-root-").with(viewer())).andExpect(status().isBadRequest());
        this.mvc.perform(get("/api/documents/-my-/preview").with(viewer())).andExpect(status().isBadRequest());
        SERVER.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(API + "/nodes/-root-")));
    }

    @Test
    void downloadsAreOffByDefault() throws Exception {
        this.mvc.perform(get("/api/documents/" + PDF + "/download").with(viewer()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("Downloading documents is not allowed."));
    }

    @Test
    void reportsAnUnavailableRepositoryWithoutDetail() throws Exception {
        String broken = "a7c9d1e2-0000-4000-8000-000000000009";
        SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(API + "/nodes/" + broken))
            .willReturn(aResponse().withStatus(500).withBody("java.lang.NullPointerException at org.alfresco...")));
        this.mvc.perform(get("/api/documents/" + broken).with(viewer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andExpect(content().string(not(containsString("NullPointer"))));
    }

    @Test
    void keepsTheCorrelationIdOfTheCaller() throws Exception {
        this.mvc.perform(get("/api/session").with(viewer()).header("X-Correlation-Id", "nginx-req-12345"))
            .andExpect(header().string("X-Correlation-Id", "nginx-req-12345"));
        this.mvc.perform(get("/api/session").with(viewer()).header("X-Correlation-Id", "bad id\r\n"))
            .andExpect(header().string("X-Correlation-Id", not("bad id\r\n")));
    }

    @Test
    void signsOutAtTheIdentityProviderToo() throws Exception {
        RequestPostProcessor signedIn = oidcLogin().clientRegistration(this.registrations.findByRegistrationId("viewer"))
            .authorities(new SimpleGrantedAuthority("ROLE_DOCUMENT_VIEWER"));
        this.mvc.perform(MockMvcRequestBuilders.post("/logout").with(signedIn))
            .andExpect(status().isForbidden());
        this.mvc.perform(MockMvcRequestBuilders.post("/logout").with(signedIn).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirectUrl", allOf(startsWith(SERVER.baseUrl() + "/realms/sapiensml/protocol/openid-connect/logout"),
                    containsString("post_logout_redirect_uri=http://localhost/signed-out"))));
    }

    @Test
    void keepsTheServiceAccountOutOfSignIn() {
        ClientRegistration registration = this.registrations.findByRegistrationId("viewer");
        assertThat(registration.getClientId()).isEqualTo("sapiensml-document-viewer");
        assertThat(this.registrations.findByRegistrationId("alfresco-service-account")).isNull();
    }

}
