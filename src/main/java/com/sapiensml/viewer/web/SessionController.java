package com.sapiensml.viewer.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapiensml.viewer.config.ViewerProperties;
import com.sapiensml.viewer.security.ClaimRoleMapper;
import com.sapiensml.viewer.security.ViewerUser;
import com.sapiensml.viewer.settings.AlfrescoConnection;

/** Who is signed in and what the viewer offers them, for the React app's header and toolbar. */
@RestController
public class SessionController {

    private final ViewerProperties.Ui ui;

    private final AlfrescoConnection connection;

    public SessionController(ViewerProperties properties, AlfrescoConnection connection) {
        this.ui = properties.ui();
        this.connection = connection;
    }

    /**
     * @param admin may use the admin page
     * @param configured an Alfresco connection is set up (until then documents cannot open)
     */
    public record Session(String username, String displayName, boolean canView, boolean admin, boolean configured,
            String applicationName, String sourceApplication, boolean allowDownload, boolean allowPrint) {
    }

    @GetMapping("/api/session")
    public Session session(@AuthenticationPrincipal OAuth2User principal, Authentication authentication) {
        ViewerUser user = ViewerUser.of(principal);
        return new Session(user.username(), user.displayName(), has(authentication, ClaimRoleMapper.VIEWER_ROLE),
                has(authentication, ClaimRoleMapper.ADMIN_ROLE), this.connection.current().configured(),
                this.ui.applicationName(), this.ui.sourceApplication(), this.ui.allowDownload(), this.ui.allowPrint());
    }

    private static boolean has(Authentication authentication, String role) {
        return authentication.getAuthorities().stream().anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

}
