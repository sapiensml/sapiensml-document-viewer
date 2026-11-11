package com.sapiensml.viewer.alfresco;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The few Alfresco REST v1 calls the viewer makes, all as the service account: read a node, check and request its
 * PDF rendition, and stream content. Every failure becomes an {@link AlfrescoException}.
 */
public class AlfrescoClient {

    static final String API = "/alfresco/api/-default-/public/alfresco/versions/1";

    public static final String PDF_RENDITION = "pdf";

    private static final Logger log = LoggerFactory.getLogger(AlfrescoClient.class);

    /** Alfresco writes offsets without a colon: 2026-11-10T21:25:54.758+0000 */
    private static final DateTimeFormatter ALFRESCO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final RestClient rest;

    private final CloseableHttpClient http;

    private final URI baseUrl;

    private final AlfrescoCredentials credentials;

    /**
     * @param rest JSON calls; it must already add the credentials (see {@link AlfrescoAuthenticationInterceptor})
     * @param http content streaming, on the same connection pool, so an abandoned download can be cut off
     */
    public AlfrescoClient(RestClient rest, CloseableHttpClient http, URI baseUrl, AlfrescoCredentials credentials) {
        this.rest = rest;
        this.http = http;
        this.baseUrl = baseUrl;
        this.credentials = credentials;
    }

    /** The node with the folders above it that the service account can see. */
    public AlfrescoNode getNode(String nodeId) {
        NodeEntry entry = call("node " + nodeId, () -> this.rest.get()
            .uri(API + "/nodes/{id}?include=path", nodeId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(NodeEntry.class));
        if (entry == null || entry.entry() == null) {
            throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, 200, "Empty node response for " + nodeId, null);
        }
        return entry.entry().toNode();
    }

    /** Whether Alfresco can make a PDF rendition of this node's content type (it lists only those it can). */
    public boolean pdfRenditionSupported(String nodeId) {
        RenditionList list = call("renditions of " + nodeId, () -> this.rest.get()
            .uri(API + "/nodes/{id}/renditions", nodeId)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(RenditionList.class));
        return list != null && list.list() != null && list.list().entries() != null && list.list().entries().stream()
            .anyMatch(item -> item.entry() != null && PDF_RENDITION.equals(item.entry().id()));
    }

    /** Asks Alfresco to create the PDF rendition. Already requested or already there counts as done. */
    public void requestPdfRendition(String nodeId) {
        try {
            call("rendition request for " + nodeId, () -> this.rest.post()
                .uri(API + "/nodes/{id}/renditions", nodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("id", PDF_RENDITION))
                .retrieve()
                .toBodilessEntity());
        }
        catch (AlfrescoException ex) {
            if (ex.status() != 409) {
                throw ex;
            }
        }
    }

    /**
     * Streams the node's content, or its PDF rendition, to {@code handler} while the repository connection is
     * open. A browser that stops reading (PDF.js cancels requests all the time) gets its connection to Alfresco
     * dropped instead of drained, so an abandoned 100 MB file costs nothing.
     *
     * @param range a validated single {@code bytes=} range, or null for the whole content
     */
    public <T> T content(String nodeId, boolean pdfRendition, String range, ContentHandler<T> handler) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(this.baseUrl)
            .path(API + (pdfRendition ? "/nodes/{id}/renditions/" + PDF_RENDITION + "/content" : "/nodes/{id}/content"))
            .queryParam("attachment", "false");
        if (pdfRendition) {
            // A missing rendition is a 404, never a generic placeholder image
            builder.queryParam("placeholder", "false");
        }
        URI uri = builder.buildAndExpand(nodeId).toUri();
        String what = (pdfRendition ? "rendition of " : "content of ") + nodeId;
        for (int attempt = 1;; attempt++) {
            HttpGet request = new HttpGet(uri);
            request.setHeader(HttpHeaders.AUTHORIZATION, this.credentials.authorizationHeader());
            if (range != null) {
                request.setHeader(HttpHeaders.RANGE, range);
            }
            ClassicHttpResponse response = null;
            boolean consumed = false;
            try {
                response = this.http.executeOpen(RoutingSupport.determineHost(request), request, null);
                int status = response.getCode();
                if (status == 401 && attempt == 1 && this.credentials.renewable()) {
                    log.info("Alfresco refused the service-account token for {}; fetching a new one", what);
                    this.credentials.invalidate();
                    continue;
                }
                if (status != 200 && status != 206) {
                    throw failure(status, what, null);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, status, "No body for " + what, null);
                }
                T result = handler.handle(new ContentResponse(status, header(response, HttpHeaders.CONTENT_TYPE),
                        entity.getContentLength(), header(response, HttpHeaders.CONTENT_RANGE),
                        header(response, HttpHeaders.ACCEPT_RANGES), entity.getContent()));
                consumed = true;
                return result;
            }
            catch (IOException | HttpException ex) {
                throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, 0, what + ": " + ex.getMessage(), ex);
            }
            finally {
                if (!consumed) {
                    // Closing an unread response would read it to the end to reuse the connection
                    request.cancel();
                }
                closeQuietly(response);
            }
        }
    }

    /** Obtains the service account's token (or checks that a basic login is configured), without calling Alfresco. */
    public void checkCredentials() {
        this.credentials.authorizationHeader();
    }

    /**
     * Checks that the repository answers and accepts the service account (health check, admin page).
     *
     * @return the Alfresco user the account signs in as
     */
    public String ping() {
        PersonEntry person = call("people/-me-", () -> this.rest.get()
            .uri(API + "/people/-me-")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(PersonEntry.class));
        return person == null || person.entry() == null ? null : person.entry().id();
    }

    @FunctionalInterface
    public interface ContentHandler<T> {

        T handle(ContentResponse response) throws IOException;

    }

    private static <T> T call(String what, Supplier<T> call) {
        try {
            return call.get();
        }
        catch (RestClientResponseException ex) {
            throw failure(ex.getStatusCode().value(), what, ex);
        }
        catch (ResourceAccessException ex) {
            throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, 0, what + ": " + ex.getMessage(), ex);
        }
        catch (RestClientException ex) {
            throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, 0, what + ": unreadable response", ex);
        }
    }

    static AlfrescoException failure(int status, String what, Throwable cause) {
        AlfrescoException.Kind kind = switch (status) {
            // Alfresco answers 400 for a node ID it cannot parse; the viewer only sends UUIDs, so treat it as absent
            case 400, 404 -> AlfrescoException.Kind.NOT_FOUND;
            case 403 -> AlfrescoException.Kind.FORBIDDEN;
            case 401 -> AlfrescoException.Kind.UNAUTHORIZED;
            case 416 -> AlfrescoException.Kind.RANGE_NOT_SATISFIABLE;
            default -> AlfrescoException.Kind.UNAVAILABLE;
        };
        return new AlfrescoException(kind, status, what + ": HTTP " + status, cause);
    }

    private static String header(ClassicHttpResponse response, String name) {
        Header header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    private static void closeQuietly(ClassicHttpResponse response) {
        if (response != null) {
            try {
                response.close();
            }
            catch (IOException ex) {
                log.debug("Closing an Alfresco response failed", ex);
            }
        }
    }

    static Instant parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, ALFRESCO_TIME).toInstant();
        }
        catch (DateTimeParseException ex) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            }
            catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    // Response shapes: only the fields the viewer reads

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NodeEntry(Node entry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Node(String id, String name, String nodeType, @JsonProperty("isFile") Boolean isFile,
            @JsonProperty("isFolder") Boolean isFolder, Content content, String modifiedAt, Map<String, Object> properties,
            Path path) {

        AlfrescoNode toNode() {
            List<String> ancestors = this.path == null || this.path.elements() == null ? List.of()
                    : this.path.elements().stream().map(PathElement::id).toList();
            // Jackson 3 refuses a missing primitive; a missing flag simply means "no"
            return new AlfrescoNode(this.id, this.name, this.nodeType, Boolean.TRUE.equals(this.isFile),
                    Boolean.TRUE.equals(this.isFolder),
                    this.content == null ? null : this.content.mimeType(),
                    this.content == null ? null : this.content.encoding(),
                    this.content == null || this.content.sizeInBytes() == null ? 0 : this.content.sizeInBytes(),
                    parseTime(this.modifiedAt), this.properties, ancestors);
        }

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(String mimeType, String encoding, Long sizeInBytes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Path(List<PathElement> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathElement(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PersonEntry(Person entry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Person(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RenditionList(RenditionPage list) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RenditionPage(List<RenditionItem> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RenditionItem(Rendition entry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rendition(String id, String status) {
    }

}
