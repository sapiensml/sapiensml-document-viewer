package com.sapiensml.viewer.security;

import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The signed-in user as the viewer records them.
 *
 * @param subject the identity provider's stable user ID ({@code sub})
 * @param username the sign-in name ({@code spring.security.oauth2.client.provider.idp.user-name-attribute})
 */
public record ViewerUser(String subject, String username, String displayName) {

    public static ViewerUser of(OAuth2User user) {
        String username = user.getName();
        Object subject = user.getAttribute("sub");
        Object name = user.getAttribute("name");
        return new ViewerUser(subject == null ? username : subject.toString(), username,
                name == null || name.toString().isBlank() ? username : name.toString());
    }

}
