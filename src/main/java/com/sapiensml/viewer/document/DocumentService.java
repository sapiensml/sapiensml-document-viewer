package com.sapiensml.viewer.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.sapiensml.viewer.alfresco.AlfrescoClient;
import com.sapiensml.viewer.alfresco.AlfrescoException;
import com.sapiensml.viewer.alfresco.AlfrescoNode;
import com.sapiensml.viewer.alfresco.ContentResponse;
import com.sapiensml.viewer.audit.AuditLog;
import com.sapiensml.viewer.audit.AuditLog.Action;
import com.sapiensml.viewer.audit.AuditLog.Outcome;
import com.sapiensml.viewer.config.ViewerProperties;
import com.sapiensml.viewer.document.DocumentAccessPolicy.AccessDecision;
import com.sapiensml.viewer.security.ViewerUser;
import com.sapiensml.viewer.settings.AlfrescoConnection;

/**
 * Looks documents up as the service account, applies the access policies, and streams content without buffering
 * it. Browsers can show PDF, common images and plain text themselves; anything else (Word, Excel, TIFF, email, HTML)
 * is shown as Alfresco's PDF rendition, which Alfresco creates on first request.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** SVG is left out on purpose: it can carry scripts. Alfresco renders it to PDF instead */
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    static final Set<String> TEXT_TYPES = Set.of("text/plain", "text/csv");

    /** Larger text files are shown as their PDF rendition, which pages them */
    static final long TEXT_PREVIEW_LIMIT = 5L * 1024 * 1024;

    /** One range only; anything else is ignored and the whole content is sent, as HTTP allows */
    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d{1,19}-\\d{0,19}|-\\d{1,19})");

    private static final String CONTENT_POLICY = "default-src 'none'; sandbox";

    private final AlfrescoConnection connection;

    private final List<DocumentAccessPolicy> policies;

    private final ViewerProperties.Ui ui;

    private final List<ViewerProperties.DisplayProperty> displayProperties;

    private final AuditLog audit;

    public DocumentService(AlfrescoConnection connection, List<DocumentAccessPolicy> policies, ViewerProperties properties,
            AuditLog audit) {
        this.connection = connection;
        this.policies = List.copyOf(policies);
        this.ui = properties.ui();
        // A malformed list is a start-up error
        this.displayProperties = this.ui.displayPropertyList();
        this.audit = audit;
    }

    /** The document's details and how to show it. This is the call that counts as a view in the audit log. */
    public DocumentView describe(String nodeId, ViewerUser user) {
        AlfrescoClient alfresco = this.connection.client();
        AlfrescoNode node = open(alfresco, nodeId, user, Action.VIEW);
        DocumentView.Viewer viewer = nativeViewer(node);
        boolean converted = viewer == null && rendition(alfresco, node);
        if (viewer == null) {
            viewer = converted ? DocumentView.Viewer.PDF : DocumentView.Viewer.NONE;
        }
        this.audit.record(Action.VIEW, node.id(), user, Outcome.GRANTED, null);
        return new DocumentView(node.id(), node.name(), text(node.properties().get("cm:title")), mimeType(node),
                node.sizeInBytes(), node.modifiedAt(), viewer, converted, properties(node), this.ui.allowDownload(),
                this.ui.allowPrint());
    }

    /**
     * Streams what the browser shows: the original when it can display it, otherwise the PDF rendition. When the
     * rendition does not exist yet it is requested and {@link DocumentExceptions.PreviewPending} tells the browser
     * to ask again.
     */
    public void preview(String nodeId, String range, ViewerUser user, HttpServletResponse response) {
        AlfrescoClient alfresco = this.connection.client();
        AlfrescoNode node = open(alfresco, nodeId, user, Action.CONTENT);
        DocumentView.Viewer viewer = nativeViewer(node);
        if (viewer != null) {
            stream(alfresco, node, false, range, contentType(node, viewer), ContentDisposition.inline(), user, response);
            return;
        }
        try {
            stream(alfresco, node, true, range, MediaType.APPLICATION_PDF_VALUE, ContentDisposition.inline(), user,
                    response);
        }
        catch (AlfrescoException ex) {
            if (ex.kind() != AlfrescoException.Kind.NOT_FOUND) {
                throw ex;
            }
            if (!rendition(alfresco, node)) {
                throw new DocumentExceptions.PreviewUnavailable(node.mimeType());
            }
            alfresco.requestPdfRendition(node.id());
            log.debug("Requested the PDF rendition of {}", node.id());
            throw new DocumentExceptions.PreviewPending();
        }
    }

    /** Streams the original file as an attachment, when downloads are allowed. */
    public void download(String nodeId, String range, ViewerUser user, HttpServletResponse response) {
        if (!this.ui.allowDownload()) {
            this.audit.record(Action.DOWNLOAD, nodeId, user, Outcome.DENIED, "downloads disabled");
            throw new DocumentExceptions.DownloadNotAllowed();
        }
        AlfrescoClient alfresco = this.connection.client();
        AlfrescoNode node = open(alfresco, nodeId, user, Action.DOWNLOAD);
        // The browser saves the file; it never interprets it
        stream(alfresco, node, false, range, MediaType.APPLICATION_OCTET_STREAM_VALUE, ContentDisposition.attachment(), user,
                response);
        this.audit.record(Action.DOWNLOAD, node.id(), user, Outcome.GRANTED, null);
    }

    private AlfrescoNode open(AlfrescoClient alfresco, String nodeId, ViewerUser user, Action action) {
        AlfrescoNode node;
        try {
            node = alfresco.getNode(nodeId);
        }
        catch (AlfrescoException ex) {
            if (ex.kind() == AlfrescoException.Kind.NOT_FOUND || ex.kind() == AlfrescoException.Kind.FORBIDDEN) {
                this.audit.record(action, nodeId, user, Outcome.NOT_FOUND, ex.kind().name());
                throw new DocumentExceptions.NotFound(ex.getMessage());
            }
            this.audit.record(action, nodeId, user, Outcome.ERROR, ex.kind().name());
            throw ex;
        }
        for (DocumentAccessPolicy policy : this.policies) {
            AccessDecision decision = policy.check(node, user);
            if (!decision.allowed()) {
                this.audit.record(action, nodeId, user, Outcome.DENIED, decision.reason());
                throw new DocumentExceptions.NotFound(decision.reason());
            }
        }
        return node;
    }

    private void stream(AlfrescoClient alfresco, AlfrescoNode node, boolean pdfRendition, String range,
            String contentType, ContentDisposition.Builder disposition, ViewerUser user, HttpServletResponse response) {
        String validRange = range != null && SINGLE_RANGE.matcher(range.strip()).matches() ? range.strip() : null;
        try {
            alfresco.content(node.id(), pdfRendition, validRange, upstream -> {
                if (validRange == null || validRange.startsWith("bytes=0-")) {
                    // PDF.js reads a document in many ranges; the first request of each opening is enough
                    this.audit.record(Action.CONTENT, node.id(), user, Outcome.GRANTED,
                            pdfRendition ? "pdf rendition" : null);
                }
                writeHeaders(upstream, contentType, disposition.filename(fileName(node, pdfRendition),
                        StandardCharsets.UTF_8).build(), response);
                copy(upstream.body(), response);
                return null;
            });
        }
        catch (AlfrescoException ex) {
            if (ex.kind() == AlfrescoException.Kind.RANGE_NOT_SATISFIABLE) {
                throw new DocumentExceptions.RangeNotSatisfiable(pdfRendition ? -1 : node.sizeInBytes());
            }
            throw ex;
        }
    }

    private static void writeHeaders(ContentResponse upstream, String contentType, ContentDisposition disposition,
            HttpServletResponse response) {
        response.setStatus(upstream.status());
        response.setContentType(contentType);
        if (upstream.contentLength() >= 0) {
            response.setContentLengthLong(upstream.contentLength());
        }
        // Alfresco also sends Content-Range with complete (200) responses; it belongs only to partial ones
        if (upstream.status() == 206 && upstream.contentRange() != null) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, upstream.contentRange());
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes".equalsIgnoreCase(upstream.acceptRanges()) ? "bytes" : "none");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        // Health and claim documents: never kept by the browser or any proxy
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        // Opened directly, the content can run nothing
        response.setHeader("Content-Security-Policy", CONTENT_POLICY);
    }

    /**
     * Copies without buffering the document. A failed read is the repository's problem (reported as such); a
     * failed write means the browser has gone, which ends the stream quietly.
     */
    private static void copy(InputStream body, HttpServletResponse response) throws IOException {
        OutputStream out;
        try {
            out = response.getOutputStream();
        }
        catch (IOException ex) {
            throw new DocumentExceptions.ClientAborted(ex);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = body.read(buffer)) != -1) {
            try {
                out.write(buffer, 0, read);
            }
            catch (IOException ex) {
                throw new DocumentExceptions.ClientAborted(ex);
            }
        }
        try {
            out.flush();
        }
        catch (IOException ex) {
            throw new DocumentExceptions.ClientAborted(ex);
        }
    }

    /** The viewer for content the browser shows as it is, or null when it needs the PDF rendition. */
    private static DocumentView.Viewer nativeViewer(AlfrescoNode node) {
        String mimeType = mimeType(node);
        if (MediaType.APPLICATION_PDF_VALUE.equals(mimeType)) {
            return DocumentView.Viewer.PDF;
        }
        if (IMAGE_TYPES.contains(mimeType)) {
            return DocumentView.Viewer.IMAGE;
        }
        if (TEXT_TYPES.contains(mimeType) && node.sizeInBytes() <= TEXT_PREVIEW_LIMIT) {
            return DocumentView.Viewer.TEXT;
        }
        return null;
    }

    private static boolean rendition(AlfrescoClient alfresco, AlfrescoNode node) {
        return node.sizeInBytes() > 0 && alfresco.pdfRenditionSupported(node.id());
    }

    private static String mimeType(AlfrescoNode node) {
        return node.mimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : node.mimeType().toLowerCase(Locale.ROOT);
    }

    /** The type sent to the browser comes from the allow-lists above, never from the repository's header. */
    private static String contentType(AlfrescoNode node, DocumentView.Viewer viewer) {
        return switch (viewer) {
            case PDF -> MediaType.APPLICATION_PDF_VALUE;
            case IMAGE -> mimeType(node);
            case TEXT -> "text/plain;charset=" + charset(node.encoding()).name();
            case NONE -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private static Charset charset(String encoding) {
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            }
            catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
                log.debug("Unknown encoding {}; using UTF-8", encoding);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String fileName(AlfrescoNode node, boolean pdfRendition) {
        String name = node.name() == null || node.name().isBlank() ? node.id() : node.name();
        if (pdfRendition && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + ".pdf";
        }
        return name;
    }

    private List<DocumentView.Property> properties(AlfrescoNode node) {
        return this.displayProperties.stream()
            .map(property -> new DocumentView.Property(property.label(), text(node.properties().get(property.name()))))
            .filter(property -> property.value() != null && !property.value().isBlank())
            .toList();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.joining(", "));
        }
        return value.toString();
    }

}
