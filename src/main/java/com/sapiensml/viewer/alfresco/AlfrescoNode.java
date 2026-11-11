package com.sapiensml.viewer.alfresco;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a repository node the viewer uses.
 *
 * @param ancestorIds the folders above the node that the service account can see, nearest last. Alfresco leaves
 * out the ones it may not read, so on a PaaS this list usually starts below the site
 */
public record AlfrescoNode(String id, String name, String nodeType, boolean file, boolean folder, String mimeType,
        String encoding, long sizeInBytes, Instant modifiedAt, Map<String, Object> properties, List<String> ancestorIds) {

    public AlfrescoNode {
        // Repository properties may hold nulls, which Map.copyOf refuses
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        ancestorIds = ancestorIds == null ? List.of() : List.copyOf(ancestorIds);
    }

}
