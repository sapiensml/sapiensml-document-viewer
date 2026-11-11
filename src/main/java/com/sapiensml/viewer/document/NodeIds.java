package com.sapiensml.viewer.document;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Node IDs as they arrive in links from business applications. Alfresco node IDs are UUIDs; some applications hold the full node
 * reference ({@code workspace://SpacesStore/<uuid>}). Anything else is refused before any repository call, so a
 * link can never steer the viewer to another Alfresco endpoint.
 */
public final class NodeIds {

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final String NODE_REF_PREFIX = "workspace://SpacesStore/";

    private NodeIds() {
    }

    /** The node ID, lower case, or empty if the value is neither a node ID nor a node reference. */
    public static Optional<String> normalize(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String id = value.strip();
        if (id.regionMatches(true, 0, NODE_REF_PREFIX, 0, NODE_REF_PREFIX.length())) {
            id = id.substring(NODE_REF_PREFIX.length());
        }
        id = id.toLowerCase(Locale.ROOT);
        return UUID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /** The node ID, or {@link DocumentExceptions.InvalidNodeId}. */
    public static String require(String value) {
        return normalize(value).orElseThrow(DocumentExceptions.InvalidNodeId::new);
    }

}
