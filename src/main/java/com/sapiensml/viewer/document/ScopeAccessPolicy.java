package com.sapiensml.viewer.document;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.sapiensml.viewer.alfresco.AlfrescoNode;
import com.sapiensml.viewer.config.ViewerProperties;
import com.sapiensml.viewer.security.ViewerUser;
import com.sapiensml.viewer.settings.AlfrescoConnection;

/**
 * Keeps the viewer to the documents it is meant for: files only, of the configured types, below the configured
 * folders. The service account can usually read more than users should open (templates, other
 * environments), and a node ID in a link is easy to change.
 *
 * <p>Folders are matched by node ID, not by path: on the PaaS the service account cannot see the folders above
 * its own, so Alfresco returns a shortened path ({@code /Inbound/2026/11/10}) but still lists the
 * IDs of the folders it can see.
 *
 * <p>The folders belong to the Alfresco connection (admin page or {@code VIEWER_ALLOWED_FOLDER_IDS}); the types come
 * from {@code VIEWER_ALLOWED_NODE_TYPES}.
 */
@Component
public class ScopeAccessPolicy implements DocumentAccessPolicy {

    private final AlfrescoConnection connection;

    private final Set<String> allowedNodeTypes;

    public ScopeAccessPolicy(ViewerProperties properties, AlfrescoConnection connection) {
        this.connection = connection;
        // A mistyped ID in the environment is a start-up error, not a silently empty scope
        properties.access().allowedAncestorIds().forEach(id -> NodeIds.normalize(id).orElseThrow(
                () -> new IllegalStateException("viewer.access.allowed-ancestor-ids: not a node ID: " + id)));
        this.allowedNodeTypes = properties.access().allowedNodeTypes().stream()
            .map(type -> type.strip().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public AccessDecision check(AlfrescoNode node, ViewerUser user) {
        if (!node.file()) {
            return AccessDecision.deny("not a file");
        }
        if (!this.allowedNodeTypes.isEmpty()
                && (node.nodeType() == null || !this.allowedNodeTypes.contains(node.nodeType().toLowerCase(Locale.ROOT)))) {
            return AccessDecision.deny("type " + node.nodeType() + " not allowed");
        }
        Set<String> folders = this.connection.allowedFolderIds().stream()
            .map(id -> id.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (!folders.isEmpty()
                && node.ancestorIds().stream().map(id -> id.toLowerCase(Locale.ROOT)).noneMatch(folders::contains)) {
            return AccessDecision.deny("outside the allowed folders");
        }
        return AccessDecision.allow();
    }

}
