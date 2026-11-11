package com.sapiensml.viewer.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;

import com.sapiensml.viewer.config.ViewerProperties;

/**
 * Turns the identity provider's roles or groups into the viewer's own role. Which claim holds them and which
 * values count are configuration, because they differ by provider:
 *
 * <ul>
 * <li>Keycloak: realm roles, put into a flat {@code roles} claim by a mapper on the client (see the realm file)</li>
 * <li>Okta: groups, in the {@code groups} claim of the ID token (a Groups claim on the authorization server)</li>
 * </ul>
 */
@Component
public class ClaimRoleMapper implements GrantedAuthoritiesMapper {

    /** The role every document route requires. Never taken from the provider directly. */
    public static final String VIEWER_ROLE = "DOCUMENT_VIEWER";

    /** The role the admin page requires (the Alfresco connection). It does not include viewing. */
    public static final String ADMIN_ROLE = "VIEWER_ADMIN";

    private final List<String> claimPath;

    private final Set<String> viewerRoles;

    private final Set<String> adminRoles;

    public ClaimRoleMapper(ViewerProperties properties) {
        this.claimPath = List.of(properties.security().rolesClaim().split("\\."));
        this.viewerRoles = properties.security().viewerRoles().stream().map(String::strip).collect(Collectors.toSet());
        this.adminRoles = properties.security().adminRoles().stream().map(String::strip).collect(Collectors.toSet());
    }

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new HashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            // OidcUserAuthority holds the ID token and user-info claims together
            if (authority instanceof OAuth2UserAuthority user) {
                Set<String> values = claimValues(user.getAttributes());
                if (values.stream().anyMatch(this.viewerRoles::contains)) {
                    mapped.add(new SimpleGrantedAuthority("ROLE_" + VIEWER_ROLE));
                }
                if (values.stream().anyMatch(this.adminRoles::contains)) {
                    mapped.add(new SimpleGrantedAuthority("ROLE_" + ADMIN_ROLE));
                }
            }
        }
        return mapped;
    }

    /** The claim's values: a list of strings, or a single string. */
    Set<String> claimValues(Map<String, Object> claims) {
        Object value = claims;
        for (String key : this.claimPath) {
            if (!(value instanceof Map<?, ?> map)) {
                return Set.of();
            }
            value = map.get(key);
        }
        if (value instanceof Collection<?> values) {
            return values.stream().filter(item -> item != null).map(Object::toString).collect(Collectors.toSet());
        }
        return value == null ? Set.of() : Set.of(value.toString());
    }

}
