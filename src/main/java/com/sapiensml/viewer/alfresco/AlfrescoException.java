package com.sapiensml.viewer.alfresco;

/**
 * A repository call that did not give the expected answer. The message is for logs only: it may name repository
 * details and is never sent to a browser.
 */
public class AlfrescoException extends RuntimeException {

    public enum Kind {
        /** The node does not exist */
        NOT_FOUND,
        /** The service account may not read the node */
        FORBIDDEN,
        /** The service account's credentials were refused, or no token could be obtained */
        UNAUTHORIZED,
        /** The requested byte range lies outside the content */
        RANGE_NOT_SATISFIABLE,
        /** Alfresco or its identity provider could not be reached, timed out or failed */
        UNAVAILABLE
    }

    private final Kind kind;

    private final int status;

    public AlfrescoException(Kind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return this.kind;
    }

    /** The repository's HTTP status, or 0 when there was no response. */
    public int status() {
        return this.status;
    }

}
