package com.sapiensml.viewer.document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sapiensml.viewer.TestProperties;
import com.sapiensml.viewer.alfresco.AlfrescoNode;
import com.sapiensml.viewer.security.ViewerUser;
import com.sapiensml.viewer.settings.AlfrescoConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeAccessPolicyTest {

    static final String INBOUND = "f15099e2-b4f5-402a-895c-1bf1f4bfbfa3";

    static final String TEMPLATES = "916d9b20-0393-40e0-8e3c-b157807912c9";

    static final ViewerUser USER = new ViewerUser("sub", "vera", "Vera");

    static AlfrescoNode node(boolean file, String type, String... ancestors) {
        return new AlfrescoNode("42099fec-ce6e-4ab6-a537-0369b6b6f7d2", "a.pdf", type, file, !file, "application/pdf",
                "UTF-8", 10, Instant.EPOCH, Map.of(), List.of(ancestors));
    }

    static ScopeAccessPolicy policy(Set<String> folders, Set<String> types) {
        AlfrescoConnection connection = mock(AlfrescoConnection.class);
        when(connection.allowedFolderIds()).thenReturn(List.copyOf(folders));
        return new ScopeAccessPolicy(TestProperties.scope(Set.of(), types), connection);
    }

    @Test
    void allowsDocumentsBelowAnAllowedFolder() {
        ScopeAccessPolicy policy = policy(Set.of(INBOUND.toUpperCase()), Set.of());
        assertThat(policy.check(node(true, "ins:document", INBOUND, "c0f68fa9-3eba-49f3-a8d3-2d49655230d1"), USER).allowed())
            .isTrue();
        assertThat(policy.check(node(true, "ins:document", TEMPLATES), USER).reason())
            .isEqualTo("outside the allowed folders");
        assertThat(policy.check(node(true, "cm:content"), USER).allowed()).isFalse();
    }

    @Test
    void refusesFoldersAndOtherTypes() {
        ScopeAccessPolicy policy = policy(Set.of(), Set.of("INS:Document", "cm:content"));
        assertThat(policy.check(node(false, "cm:folder"), USER).reason()).isEqualTo("not a file");
        assertThat(policy.check(node(true, "ins:document"), USER).allowed()).isTrue();
        assertThat(policy.check(node(true, "cm:content"), USER).allowed()).isTrue();
        assertThat(policy.check(node(true, "cm:thumbnail"), USER).allowed()).isFalse();
        assertThat(policy.check(node(true, null), USER).allowed()).isFalse();
    }

    @Test
    void withoutLimitsAllowsAnyFile() {
        ScopeAccessPolicy policy = policy(Set.of(), Set.of());
        assertThat(policy.check(node(true, "cm:content"), USER).allowed()).isTrue();
    }

    @Test
    void refusesAMistypedFolderId() {
        assertThatThrownBy(() -> new ScopeAccessPolicy(TestProperties.scope(Set.of("Sites/claims"), Set.of()),
                mock(AlfrescoConnection.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a node ID");
    }

}
