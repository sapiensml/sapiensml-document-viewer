package com.sapiensml.viewer.document;

/** Outcomes the API reports with a status code (see the web package's exception handler). None carries repository detail. */
public final class DocumentExceptions {

    private DocumentExceptions() {
    }

    /** The link does not hold a node ID. */
    public static class InvalidNodeId extends RuntimeException {

        InvalidNodeId() {
            super("Not a node ID");
        }

    }

    /**
     * The document does not exist, the service account cannot read it, or an access policy refused it. Users get
     * the same answer for all three, so the viewer cannot be used to find out which documents exist.
     */
    public static class NotFound extends RuntimeException {

        NotFound(String reason) {
            super(reason);
        }

    }

    /** Alfresco is still creating the PDF rendition; the browser should ask again shortly. */
    public static class PreviewPending extends RuntimeException {

        PreviewPending() {
            super("PDF rendition requested");
        }

    }

    /** The document's type cannot be shown in a browser and Alfresco cannot convert it to PDF. */
    public static class PreviewUnavailable extends RuntimeException {

        PreviewUnavailable(String mimeType) {
            super("No preview for " + mimeType);
        }

    }

    /** Downloads are switched off ({@code viewer.ui.allow-download}). */
    public static class DownloadNotAllowed extends RuntimeException {

        DownloadNotAllowed() {
            super("Downloads are disabled");
        }

    }

    /** The browser asked for bytes beyond the end of the content. */
    public static class RangeNotSatisfiable extends RuntimeException {

        private final long size;

        RangeNotSatisfiable(long size) {
            super("Range outside " + size + " bytes");
            this.size = size;
        }

        public long size() {
            return this.size;
        }

    }

    /** The browser went away while content was streaming (PDF.js cancels requests routinely). Not an error. */
    public static class ClientAborted extends RuntimeException {

        ClientAborted(Throwable cause) {
            super("Client closed the connection", cause);
        }

    }

}
