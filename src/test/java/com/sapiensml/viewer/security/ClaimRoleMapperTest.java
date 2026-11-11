package com.sapiensml.viewer.security;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import com.sapiensml.viewer.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimRoleMapperTest {

    static Collection<? extends GrantedAuthority> map(ClaimRoleMapper mapper, Map<String, Object> claims) {
        OidcIdToken token = OidcIdToken.withTokenValue("token")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claims(all -> all.putAll(claims))
            .build();
        return mapper.mapAuthorities(List.of(new OidcUserAuthority(token), new SimpleGrantedAuthority("SCOPE_openid")));
    }

    static boolean canView(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_DOCUMENT_VIEWER"));
    }

    @Test
    void keycloakRolesClaim() {
        ClaimRoleMapper mapper = new ClaimRoleMapper(TestProperties.roles("roles", List.of("DOCUMENT_VIEWER")));
        assertThat(canView(map(mapper, Map.of("roles", List.of("offline_access", "DOCUMENT_VIEWER"))))).isTrue();
        assertThat(canView(map(mapper, Map.of("roles", List.of("offline_access"))))).isFalse();
        assertThat(canView(map(mapper, Map.of()))).isFalse();
    }

    @Test
    void adminIsItsOwnRole() {
        ClaimRoleMapper mapper = new ClaimRoleMapper(TestProperties.roles("roles", List.of("DOCUMENT_VIEWER")));
        Collection<? extends GrantedAuthority> admin = map(mapper, Map.of("roles", List.of("VIEWER_ADMIN")));
        assertThat(admin).extracting(GrantedAuthority::getAuthority).contains("ROLE_VIEWER_ADMIN");
        assertThat(canView(admin)).isFalse();
        assertThat(map(mapper, Map.of("roles", List.of("DOCUMENT_VIEWER")))).extracting(GrantedAuthority::getAuthority)
            .doesNotContain("ROLE_VIEWER_ADMIN");
    }

    @Test
    void nestedKeycloakRealmAccess() {
        ClaimRoleMapper mapper = new ClaimRoleMapper(TestProperties.roles("realm_access.roles", List.of("DOCUMENT_VIEWER")));
        assertThat(canView(map(mapper, Map.of("realm_access", Map.of("roles", List.of("DOCUMENT_VIEWER")))))).isTrue();
        assertThat(canView(map(mapper, Map.of("realm_access", "flat")))).isFalse();
    }

    @Test
    void oktaGroupsWithTheirOwnNames() {
        ClaimRoleMapper mapper = new ClaimRoleMapper(TestProperties.roles("groups", List.of("Document-Viewers", " Claims-Team ")));
        Collection<? extends GrantedAuthority> authorities = map(mapper, Map.of("groups", List.of("Everyone", "Claims-Team")));
        assertThat(canView(authorities)).isTrue();
        // The provider's own authorities are kept
        assertThat(authorities).extracting(GrantedAuthority::getAuthority).contains("SCOPE_openid");
        assertThat(canView(map(mapper, Map.of("groups", "Document-Viewers")))).isTrue();
    }

}
