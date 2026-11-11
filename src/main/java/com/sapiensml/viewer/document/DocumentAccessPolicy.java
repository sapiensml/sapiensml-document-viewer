package com.sapiensml.viewer.document;

import com.sapiensml.viewer.alfresco.AlfrescoNode;
import com.sapiensml.viewer.security.ViewerUser;

/**
 * Decides whether a signed-in user may open a document the service account can read. Every policy bean must
 * allow it. {@link ScopeAccessPolicy} limits documents by folder and type; an entitlement check in the business application, such as FINEOS (may this
 * user see this case or party?) belongs here as another bean, using the node's ins: properties.
 */
public interface DocumentAccessPolicy {

    AccessDecision check(AlfrescoNode node, ViewerUser user);

    /** @param reason why access was refused, for the audit log only */
    record AccessDecision(boolean allowed, String reason) {

        private static final AccessDecision ALLOW = new AccessDecision(true, null);

        public static AccessDecision allow() {
            return ALLOW;
        }

        public static AccessDecision deny(String reason) {
            return new AccessDecision(false, reason);
        }

    }

}
