package com.sapiensml.viewer.alfresco;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.sapiensml.viewer.config.ViewerProperties.AuthType;

/**
 * Everything needed to reach one Alfresco repository: its address, the service account's credentials and the
 * folders whose documents may be opened. Entered on the admin page, or taken from the environment.
 *
 * @param baseUrl the repository's address without /alfresco
 * @param allowedFolderIds node IDs of folders; documents below them may be opened. Empty: any document the account can read
 * @param updatedBy who saved these settings on the admin page, null when they come from the environment
 */
public record AlfrescoSettings(URI baseUrl, AuthType authType, URI tokenUri, String clientId, String clientSecret,
        String scope, String username, String password, List<String> allowedFolderIds, String updatedBy,
        Instant updatedAt) {

    public AlfrescoSettings {
        allowedFolderIds = allowedFolderIds == null ? List.of() : List.copyOf(allowedFolderIds);
    }

    /**
     * Whether a secret stored for {@code previous} may be kept for these settings: the secret would go to the
     * same place for the same account. Otherwise it must be entered again, so a changed address can never receive
     * the stored secret.
     */
    public boolean sameAccountAs(AlfrescoSettings previous) {
        if (previous == null || previous.authType() != this.authType) {
            return false;
        }
        return switch (this.authType) {
            case CLIENT_CREDENTIALS -> Objects.equals(previous.tokenUri(), this.tokenUri)
                    && Objects.equals(previous.clientId(), this.clientId);
            case BASIC -> Objects.equals(previous.baseUrl(), this.baseUrl) && Objects.equals(previous.username(), this.username);
        };
    }

    @Override
    public String toString() {
        return "AlfrescoSettings[baseUrl=" + this.baseUrl + ", authType=" + this.authType + ", tokenUri=" + this.tokenUri
                + ", clientId=" + this.clientId + ", username=" + this.username + ", allowedFolderIds="
                + this.allowedFolderIds + "]";
    }

}
