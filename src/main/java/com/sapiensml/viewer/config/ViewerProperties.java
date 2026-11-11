package com.sapiensml.viewer.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Everything the viewer can be told, under {@code viewer.*}. Sign-in itself is plain Spring Security
 * ({@code spring.security.oauth2.client.*}), so moving from Keycloak to Okta changes only those values and the
 * roles claim below.
 */
@Validated
@ConfigurationProperties("viewer")
public record ViewerProperties(@Valid @NotNull Alfresco alfresco, @Valid @NotNull Security security,
        @Valid @NotNull Access access, @Valid @NotNull Ui ui, @Valid @NotNull Settings settings) {

    /**
     * How the viewer reaches Alfresco. Users never do: every call is made with this one account. The connection
     * entered on the admin page takes precedence; these values are used while none has been saved there.
     */
    public record Alfresco(
            /** The repository's address without /alfresco, for example https://alfresco.example.com. Optional */
            URI baseUrl,
            @NotNull @DefaultValue("CLIENT_CREDENTIALS") AuthType authType,
            /** CLIENT_CREDENTIALS: the identity provider's token endpoint and the service-account client */
            URI tokenUri, String clientId, String clientSecret, String scope,
            /** BASIC: a repository user, for a local Alfresco only */
            String username, String password,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            /** Longest silence while reading a response; large documents stream for longer than this */
            @NotNull @DefaultValue("60s") Duration readTimeout,
            @Min(1) @DefaultValue("100") int maxConnections) {

        @Override
        public String toString() {
            return "Alfresco[baseUrl=" + this.baseUrl + ", authType=" + this.authType + ", clientId=" + this.clientId
                    + ", username=" + this.username + "]";
        }

    }

    public enum AuthType {
        /** OAuth2 client credentials, as on Alfresco PaaS (a Keycloak client of the PaaS realm) */
        CLIENT_CREDENTIALS,
        /** HTTP Basic with a repository user, for local development repositories */
        BASIC
    }

    public record Security(
            /** The ID token or user-info claim holding the user's roles or groups; dots walk into objects */
            @NotBlank @DefaultValue("roles") String rolesClaim,
            /** Claim values that allow viewing documents. Any one is enough */
            @NotEmpty @DefaultValue("DOCUMENT_VIEWER") List<String> viewerRoles,
            /** Claim values that allow the admin page (the Alfresco connection). Any one is enough */
            @NotEmpty @DefaultValue("VIEWER_ADMIN") List<String> adminRoles,
            /** Keycloak only: an identity provider alias (kc_idp_hint), to skip Keycloak's own sign-in page */
            String idpHint,
            /** Origins allowed to show the viewer in a frame. Empty: no framing at all */
            @NotNull @DefaultValue({}) List<String> frameAncestors) {
    }

    public record Access(
            /**
             * Folders whose documents may be opened (node IDs). A document anywhere below one of them qualifies.
             * Empty: every document the service account can read
             */
            @NotNull @DefaultValue({}) Set<String> allowedAncestorIds,
            /** Content types that may be opened, for example ins:document. Empty: any type */
            @NotNull @DefaultValue({}) Set<String> allowedNodeTypes) {
    }

    public record Ui(
            @NotBlank @DefaultValue("SapiensML Document Viewer") String applicationName,
            /** The application whose links open documents (for example FINEOS), named in the page's hints. Optional */
            @DefaultValue("") String sourceApplication,
            @DefaultValue("false") boolean allowDownload,
            @DefaultValue("true") boolean allowPrint,
            /**
             * Repository properties shown next to the document, in this order: {@code prefix:name=Label}, comma
             * separated, for example {@code cm:title=Title,ins:caseNumber=Case}. Nothing else is ever sent
             */
            @DefaultValue("cm:title=Title,cm:description=Description,cm:author=Author") String displayProperties) {

        private static final Pattern ENTRY = Pattern.compile("([A-Za-z][\\w-]*:[\\w.-]+)=(.+)");

        /** @throws IllegalStateException when an entry is not {@code prefix:name=Label} */
        public List<DisplayProperty> displayPropertyList() {
            List<DisplayProperty> list = new ArrayList<>();
            if (this.displayProperties == null || this.displayProperties.isBlank()) {
                return list;
            }
            for (String entry : this.displayProperties.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                Matcher matcher = ENTRY.matcher(entry.strip());
                if (!matcher.matches()) {
                    throw new IllegalStateException("viewer.ui.display-properties (VIEWER_DISPLAY_PROPERTIES): '" + entry.strip()
                            + "' is not prefix:name=Label");
                }
                list.add(new DisplayProperty(matcher.group(1), matcher.group(2).strip()));
            }
            return List.copyOf(list);
        }

    }

    public record DisplayProperty(String name, String label) {
    }

    /** Where the admin page keeps the Alfresco connection. */
    public record Settings(
            /** Directory of alfresco-settings.json; only the viewer's own user may read it */
            @NotNull @DefaultValue("viewer-data") Path dir,
            /**
             * Base64 of 32 random bytes that encrypts the stored secrets (AES-256-GCM). Kept apart from the data,
             * in viewer.env. Empty: a key file is created next to the settings
             */
            String key,
            /** Allow http:// repository addresses on the admin page (local development only) */
            @DefaultValue("false") boolean allowInsecureHttp) {

        @Override
        public String toString() {
            return "Settings[dir=" + this.dir + ", allowInsecureHttp=" + this.allowInsecureHttp + "]";
        }

    }

}
