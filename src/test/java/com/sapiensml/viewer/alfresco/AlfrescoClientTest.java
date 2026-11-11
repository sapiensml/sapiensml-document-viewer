package com.sapiensml.viewer.alfresco;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sapiensml.viewer.TestProperties;
import com.sapiensml.viewer.config.ViewerProperties;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Alfresco client against WireMock playing an Alfresco repository and its Keycloak token endpoint. */
class AlfrescoClientTest {

    static final String NODE = "42099fec-ce6e-4ab6-a537-0369b6b6f7d2";

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    static final String TOKEN = "/auth/realms/alfresco/protocol/openid-connect/token";

    static final WireMockServer SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** A response recorded from Alfresco 26 for a node like this one; names changed, properties trimmed. */
    static final String NODE_JSON = """
            {"entry":{"isFile":true,"createdByUser":{"id":"service-account-document-viewer"},
            "modifiedAt":"2026-11-10T21:25:54.758+0000","nodeType":"ins:document",
            "content":{"mimeType":"application/pdf","mimeTypeName":"Adobe PDF Document","sizeInBytes":46267,"encoding":"UTF-8"},
            "parentId":"9497a455-efca-4886-b1ac-18cf550fcd99","aspectNames":["cm:titled"],"isFolder":false,
            "name":"Claim_Form_100234.pdf","id":"42099fec-ce6e-4ab6-a537-0369b6b6f7d2",
            "properties":{"cm:title":"Claim form - case 100234","ins:caseNumber":"100234","ins:status":"Completed",
            "cm:lastThumbnailModification":["doclib:1794347197561"],"ins:empty":null},
            "path":{"name":"/Inbound/2026/11/10","isComplete":false,"elements":[
            {"id":"f15099e2-b4f5-402a-895c-1bf1f4bfbfa3","name":"Inbound"},
            {"id":"c0f68fa9-3eba-49f3-a8d3-2d49655230d1","name":"2026"},
            {"id":"df8954cb-66c4-4408-b668-d94442a222ea","name":"11"},
            {"id":"9497a455-efca-4886-b1ac-18cf550fcd99","name":"10"}]}}}
            """;

    CloseableHttpClient http;

    AlfrescoClient client;

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
        SERVER.stubFor(post(TOKEN).willReturn(okJson("{\"access_token\":\"t1\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        ViewerProperties properties = TestProperties.scope(java.util.Set.of(), java.util.Set.of());
        this.http = new AlfrescoClientConfiguration().alfrescoHttpClient(properties);
        this.client = new AlfrescoClientFactory(this.http).create(settings(SERVER.baseUrl()));
    }

    static AlfrescoSettings settings(String baseUrl) {
        ViewerProperties.Alfresco alfresco = TestProperties.clientCredentials(baseUrl);
        return new AlfrescoSettings(alfresco.baseUrl(), alfresco.authType(), alfresco.tokenUri(), alfresco.clientId(),
                alfresco.clientSecret(), null, null, null, java.util.List.of(), null, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        this.http.close();
    }

    @Test
    void readsANodeAsTheServiceAccount() {
        SERVER.stubFor(get(API + "/nodes/" + NODE + "?include=path")
            .withHeader("Authorization", equalTo("Bearer t1"))
            .willReturn(okJson(NODE_JSON)));

        AlfrescoNode node = this.client.getNode(NODE);

        assertThat(node.id()).isEqualTo(NODE);
        assertThat(node.file()).isTrue();
        assertThat(node.nodeType()).isEqualTo("ins:document");
        assertThat(node.mimeType()).isEqualTo("application/pdf");
        assertThat(node.sizeInBytes()).isEqualTo(46267);
        assertThat(node.modifiedAt()).isEqualTo(Instant.parse("2026-11-10T21:25:54.758Z"));
        assertThat(node.properties()).containsEntry("ins:caseNumber", "100234").containsKey("ins:empty");
        assertThat(node.ancestorIds()).containsExactly("f15099e2-b4f5-402a-895c-1bf1f4bfbfa3",
                "c0f68fa9-3eba-49f3-a8d3-2d49655230d1", "df8954cb-66c4-4408-b668-d94442a222ea",
                "9497a455-efca-4886-b1ac-18cf550fcd99");
        SERVER.verify(1, postRequestedFor(urlEqualTo(TOKEN))
            .withRequestBody(matching(".*grant_type=client_credentials.*"))
            .withRequestBody(matching(".*client_id=svc-viewer.*"))
            .withRequestBody(matching(".*client_secret=s3cret.*")));
    }

    @Test
    void reusesTheTokenUntilItNearlyExpires() {
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE)).willReturn(okJson(NODE_JSON)));
        SERVER.stubFor(get(API + "/nodes/" + NODE + "/renditions").willReturn(okJson("{\"list\":{\"entries\":[]}}")));
        this.client.getNode(NODE);
        this.client.getNode(NODE);
        this.client.pdfRenditionSupported(NODE);
        SERVER.verify(1, postRequestedFor(urlEqualTo(TOKEN)));
    }

    @Test
    void fetchesANewTokenWhenAlfrescoRefusesTheCurrentOne() {
        SERVER.stubFor(post(TOKEN).inScenario("rotation").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"access_token\":\"old\",\"token_type\":\"Bearer\",\"expires_in\":300}"))
            .willSetStateTo("rotated"));
        SERVER.stubFor(post(TOKEN).inScenario("rotation").whenScenarioStateIs("rotated")
            .willReturn(okJson("{\"access_token\":\"new\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE)).withHeader("Authorization", equalTo("Bearer old"))
            .willReturn(aResponse().withStatus(401)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE)).withHeader("Authorization", equalTo("Bearer new"))
            .willReturn(okJson(NODE_JSON)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE + "/content")).withHeader("Authorization", equalTo("Bearer new"))
            .willReturn(aResponse().withStatus(200).withBody("%PDF")));

        assertThat(this.client.getNode(NODE).id()).isEqualTo(NODE);
        assertThat(read(false, null)).isEqualTo("%PDF");
        SERVER.verify(2, postRequestedFor(urlEqualTo(TOKEN)));
    }

    @Test
    void mapsRepositoryAnswersToKinds() {
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/a")).willReturn(aResponse().withStatus(404)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/b")).willReturn(aResponse().withStatus(403)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/c")).willReturn(aResponse().withStatus(500)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/d")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/e")).willReturn(okJson("not json")));
        assertKind("a", AlfrescoException.Kind.NOT_FOUND);
        assertKind("b", AlfrescoException.Kind.FORBIDDEN);
        assertKind("c", AlfrescoException.Kind.UNAVAILABLE);
        assertKind("d", AlfrescoException.Kind.UNAVAILABLE);
        assertKind("e", AlfrescoException.Kind.UNAVAILABLE);
    }

    @Test
    void reportsARefusedServiceAccount() {
        SERVER.stubFor(post(TOKEN).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}")));
        assertKind(NODE, AlfrescoException.Kind.UNAUTHORIZED);
    }

    @Test
    void streamsContentWithTheBrowsersRange() {
        SERVER.stubFor(get(API + "/nodes/" + NODE + "/content?attachment=false")
            .withHeader("Range", equalTo("bytes=0-3"))
            .willReturn(aResponse().withStatus(206)
                .withHeader("Content-Type", "application/pdf;charset=UTF-8")
                .withHeader("Content-Range", "bytes 0-3/46267")
                .withHeader("Accept-Ranges", "bytes")
                .withHeader("Content-Length", "4")
                .withHeader("Set-Cookie", "AWSALB=x")
                .withBody("%PDF")));

        ContentResponse[] seen = new ContentResponse[1];
        String body = this.client.content(NODE, false, "bytes=0-3", response -> {
            seen[0] = response;
            return new String(response.body().readAllBytes());
        });

        assertThat(body).isEqualTo("%PDF");
        assertThat(seen[0].status()).isEqualTo(206);
        assertThat(seen[0].contentRange()).isEqualTo("bytes 0-3/46267");
        assertThat(seen[0].acceptRanges()).isEqualTo("bytes");
        assertThat(seen[0].contentLength()).isEqualTo(4);
    }

    @Test
    void streamsThePdfRendition() {
        SERVER.stubFor(get(API + "/nodes/" + NODE + "/renditions/pdf/content?attachment=false&placeholder=false")
            .willReturn(aResponse().withStatus(200).withBody("%PDF-rendition")));
        assertThat(read(true, null)).isEqualTo("%PDF-rendition");
        SERVER.verify(getRequestedFor(urlPathEqualTo(API + "/nodes/" + NODE + "/renditions/pdf/content")));
    }

    @Test
    void reportsMissingContentAndBadRanges() {
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE + "/renditions/pdf/content")).willReturn(aResponse().withStatus(404)));
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE + "/content")).willReturn(aResponse().withStatus(416)));
        assertThatThrownBy(() -> read(true, null)).isInstanceOfSatisfying(AlfrescoException.class,
                ex -> assertThat(ex.kind()).isEqualTo(AlfrescoException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> read(false, "bytes=999999-")).isInstanceOfSatisfying(AlfrescoException.class,
                ex -> assertThat(ex.kind()).isEqualTo(AlfrescoException.Kind.RANGE_NOT_SATISFIABLE));
    }

    @Test
    void stopsReadingWhenTheHandlerGivesUp() {
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE + "/content"))
            .willReturn(aResponse().withStatus(200).withBody(new byte[4 * 1024 * 1024])));
        assertThatThrownBy(() -> this.client.content(NODE, false, null, response -> {
            response.body().read(new byte[16]);
            throw new IllegalStateException("browser gone");
        })).hasMessage("browser gone");
        // The pool is still usable afterwards
        SERVER.stubFor(get(urlPathEqualTo(API + "/nodes/" + NODE)).willReturn(okJson(NODE_JSON)));
        assertThat(this.client.getNode(NODE).id()).isEqualTo(NODE);
    }

    @Test
    void checksAndRequestsThePdfRendition() {
        SERVER.stubFor(get(API + "/nodes/" + NODE + "/renditions").willReturn(okJson("""
                {"list":{"pagination":{"count":2},"entries":[
                {"entry":{"id":"doclib","status":"CREATED"}},{"entry":{"id":"pdf","status":"NOT_CREATED"}}]}}""")));
        SERVER.stubFor(get(API + "/nodes/a7c9d1e2-0000-4000-8000-000000000001/renditions").willReturn(okJson("""
                {"list":{"entries":[{"entry":{"id":"doclib","status":"CREATED"}}]}}""")));
        SERVER.stubFor(post(API + "/nodes/" + NODE + "/renditions").willReturn(aResponse().withStatus(202)));
        SERVER.stubFor(post(API + "/nodes/a7c9d1e2-0000-4000-8000-000000000001/renditions").willReturn(aResponse().withStatus(409)));
        SERVER.stubFor(post(API + "/nodes/a7c9d1e2-0000-4000-8000-000000000002/renditions").willReturn(aResponse().withStatus(500)));

        assertThat(this.client.pdfRenditionSupported(NODE)).isTrue();
        assertThat(this.client.pdfRenditionSupported("a7c9d1e2-0000-4000-8000-000000000001")).isFalse();
        this.client.requestPdfRendition(NODE);
        // Already being created
        this.client.requestPdfRendition("a7c9d1e2-0000-4000-8000-000000000001");
        assertThatThrownBy(() -> this.client.requestPdfRendition("a7c9d1e2-0000-4000-8000-000000000002"))
            .isInstanceOf(AlfrescoException.class);
        SERVER.verify(postRequestedFor(urlEqualTo(API + "/nodes/" + NODE + "/renditions"))
            .withRequestBody(equalToJson("{\"id\":\"pdf\"}")));
    }

    @Test
    void pingsWithTheServiceAccount() {
        SERVER.stubFor(get(API + "/people/-me-").willReturn(okJson("{\"entry\":{\"id\":\"svc\"}}")));
        assertThat(this.client.ping()).isEqualTo("svc");
        SERVER.stubFor(get(API + "/people/-me-").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> this.client.ping()).isInstanceOf(AlfrescoException.class);
    }

    @Test
    void refusesIncompleteSettings() {
        AlfrescoClientFactory factory = new AlfrescoClientFactory(this.http);
        AlfrescoSettings noSecret = new AlfrescoSettings(java.net.URI.create(SERVER.baseUrl()),
                ViewerProperties.AuthType.CLIENT_CREDENTIALS, java.net.URI.create(SERVER.baseUrl() + TOKEN), "svc", null,
                null, null, null, null, null, null);
        assertThatThrownBy(() -> factory.create(noSecret)).isInstanceOf(IllegalArgumentException.class);
        AlfrescoSettings basic = new AlfrescoSettings(java.net.URI.create(SERVER.baseUrl()), ViewerProperties.AuthType.BASIC,
                null, null, null, null, "admin", "admin", null, null, null);
        SERVER.stubFor(get(API + "/people/-me-").withHeader("Authorization", equalTo("Basic YWRtaW46YWRtaW4="))
            .willReturn(okJson("{\"entry\":{\"id\":\"admin\"}}")));
        assertThat(factory.create(basic).ping()).isEqualTo("admin");
    }

    @Test
    void parsesBothTimeFormats() {
        assertThat(AlfrescoClient.parseTime("2026-11-10T21:25:54.758+0000")).isEqualTo(Instant.parse("2026-11-10T21:25:54.758Z"));
        assertThat(AlfrescoClient.parseTime("2026-11-10T21:25:54Z")).isEqualTo(Instant.parse("2026-11-10T21:25:54Z"));
        assertThat(AlfrescoClient.parseTime("yesterday")).isNull();
        assertThat(AlfrescoClient.parseTime(null)).isNull();
    }

    @Test
    void basicCredentialsForALocalRepository() {
        AlfrescoCredentials basic = AlfrescoCredentials.Basic.of("admin", "admin");
        assertThat(basic.authorizationHeader()).isEqualTo("Basic YWRtaW46YWRtaW4=");
        assertThat(basic.renewable()).isFalse();
        assertThat(basic.toString()).doesNotContain("YWRt");
    }

    private String read(boolean rendition, String range) {
        return this.client.content(NODE, rendition, range, response -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.body().transferTo(out);
            return out.toString();
        });
    }

    private void assertKind(String nodeId, AlfrescoException.Kind kind) {
        assertThatThrownBy(() -> this.client.getNode(nodeId)).isInstanceOfSatisfying(AlfrescoException.class,
                ex -> assertThat(ex.kind()).isEqualTo(kind));
    }

}
