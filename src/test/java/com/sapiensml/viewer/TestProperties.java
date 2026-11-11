package com.sapiensml.viewer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.sapiensml.viewer.config.ViewerProperties;

/** ViewerProperties for unit tests, with the defaults of application.yml. */
public final class TestProperties {

    private TestProperties() {
    }

    public static ViewerProperties.Alfresco clientCredentials(String baseUrl) {
        return new ViewerProperties.Alfresco(URI.create(baseUrl), ViewerProperties.AuthType.CLIENT_CREDENTIALS,
                URI.create(baseUrl + "/auth/realms/alfresco/protocol/openid-connect/token"), "svc-viewer", "s3cret", null,
                null, null, Duration.ofSeconds(2), Duration.ofSeconds(2), 10);
    }

    public static ViewerProperties of(ViewerProperties.Alfresco alfresco, Set<String> folders, Set<String> types,
            String rolesClaim, List<String> roles) {
        return new ViewerProperties(alfresco,
                new ViewerProperties.Security(rolesClaim, roles, List.of("VIEWER_ADMIN"), null, List.of()),
                new ViewerProperties.Access(folders, types),
                new ViewerProperties.Ui("SapiensML Document Viewer", "", false, true, "cm:title=Title"),
                new ViewerProperties.Settings(Path.of("target", "test-settings"), null, true));
    }

    public static ViewerProperties settings(Path dir, String key) {
        ViewerProperties base = scope(Set.of(), Set.of());
        return new ViewerProperties(base.alfresco(), base.security(), base.access(), base.ui(),
                new ViewerProperties.Settings(dir, key, true));
    }

    public static ViewerProperties scope(Set<String> folders, Set<String> types) {
        return of(clientCredentials("http://alfresco.test"), folders, types, "roles", List.of("DOCUMENT_VIEWER"));
    }

    public static ViewerProperties roles(String claim, List<String> roles) {
        return of(clientCredentials("http://alfresco.test"), Set.of(), Set.of(), claim, roles);
    }

}
