package com.sapiensml.viewer.alfresco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.ResourceAccessException;

/** The Authorization header the viewer sends to Alfresco. */
public sealed interface AlfrescoCredentials {

    String authorizationHeader();

    /** Forgets a cached token so the next call fetches a new one (after Alfresco refused the current one). */
    void invalidate();

    /** Whether {@link #invalidate()} can produce different credentials, so a refused call is worth one retry. */
    boolean renewable();

    /**
     * Client-credentials tokens. Spring Security caches each token and fetches the next one a minute before it
     * expires (PaaS tokens live five minutes).
     */
    record ClientCredentials(OAuth2AuthorizedClientManager manager, OAuth2AuthorizedClientService clients,
            String registrationId) implements AlfrescoCredentials {

        static final String PRINCIPAL = "sapiensml-document-viewer";

        @Override
        public String authorizationHeader() {
            try {
                OAuth2AuthorizedClient client = this.manager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(this.registrationId)
                    .principal(PRINCIPAL)
                    .build());
                if (client == null) {
                    throw new AlfrescoException(AlfrescoException.Kind.UNAUTHORIZED, 0,
                            "No service-account token for " + this.registrationId, null);
                }
                return "Bearer " + client.getAccessToken().getTokenValue();
            }
            catch (OAuth2AuthorizationException ex) {
                // Spring Security reports an unreachable token endpoint the same way as a refusal, the network
                // error two causes down
                AlfrescoException.Kind kind = networkFailure(ex)
                        ? AlfrescoException.Kind.UNAVAILABLE : AlfrescoException.Kind.UNAUTHORIZED;
                throw new AlfrescoException(kind, 0, "Service-account token not issued: " + ex.getError(), ex);
            }
            catch (RuntimeException ex) {
                if (ex instanceof AlfrescoException) {
                    throw ex;
                }
                throw new AlfrescoException(AlfrescoException.Kind.UNAVAILABLE, 0,
                        "Identity provider unreachable: " + ex.getMessage(), ex);
            }
        }

        private static boolean networkFailure(Throwable ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResourceAccessException || cause instanceof IOException) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void invalidate() {
            this.clients.removeAuthorizedClient(this.registrationId, PRINCIPAL);
        }

        @Override
        public boolean renewable() {
            return true;
        }

        @Override
        public String toString() {
            return "ClientCredentials[" + this.registrationId + "]";
        }

    }

    /** A fixed repository user; only for local development repositories. */
    record Basic(String header) implements AlfrescoCredentials {

        public static Basic of(String username, String password) {
            String pair = username + ":" + password;
            return new Basic("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String authorizationHeader() {
            return this.header;
        }

        @Override
        public void invalidate() {
            // Nothing cached
        }

        @Override
        public boolean renewable() {
            return false;
        }

        @Override
        public String toString() {
            return "Basic[******]";
        }

    }

}
