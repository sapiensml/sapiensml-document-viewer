package com.sapiensml.viewer.document;

import java.time.Instant;
import java.util.List;

/**
 * What the browser learns about a document: enough to show it, nothing about where it lives.
 *
 * @param converted the preview is Alfresco's PDF rendition, not the original file
 * @param properties the configured repository properties ({@code viewer.ui.properties}) that have a value
 */
public record DocumentView(String id, String name, String title, String mimeType, long size, Instant modifiedAt,
        Viewer viewer, boolean converted, List<Property> properties, boolean downloadAllowed, boolean printAllowed) {

    /** How the browser shows the preview. */
    public enum Viewer {
        PDF, IMAGE, TEXT,
        /** No preview: the type cannot be shown or converted */
        NONE
    }

    public record Property(String label, String value) {
    }

}
