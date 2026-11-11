package com.sapiensml.viewer.admin;

import java.time.Instant;
import java.util.List;

import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;
import com.sapiensml.viewer.settings.AlfrescoConnection;

/**
 * The connection as the admin page shows it. Secrets are never sent, only whether one is stored.
 *
 * @param source ADMIN (saved here), ENVIRONMENT (viewer.env) or NONE
 */
public record SettingsView(AlfrescoConnection.Source source, String baseUrl, AuthType authType, String tokenUri,
        String clientId, boolean clientSecretSet, String scope, String username, boolean passwordSet,
        List<String> allowedFolderIds, String updatedBy, Instant updatedAt) {

    static SettingsView of(AlfrescoConnection.Current current) {
        AlfrescoSettings settings = current.settings();
        if (settings == null) {
            return new SettingsView(current.source(), null, AuthType.CLIENT_CREDENTIALS, null, null, false, null, null,
                    false, List.of(), null, null);
        }
        return new SettingsView(current.source(), settings.baseUrl().toString(), settings.authType(),
                settings.tokenUri() == null ? null : settings.tokenUri().toString(), settings.clientId(),
                settings.clientSecret() != null, settings.scope(), settings.username(), settings.password() != null,
                settings.allowedFolderIds(), settings.updatedBy(), settings.updatedAt());
    }

}
