package com.sapiensml.viewer.alfresco;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Builds an {@link AlfrescoClient} for a set of {@link AlfrescoSettings}, on the shared connection pool. Each
 * client has its own token cache, so testing new settings on the admin page never disturbs the client in use.
 */
@Component
public class AlfrescoClientFactory {

    /** The service account's registration. It is kept apart from the sign-in registrations on purpose. */
    static final String REGISTRATION_ID = "alfresco-service-account";

    private final CloseableHttpClient http;

    public AlfrescoClientFactory(CloseableHttpClient alfrescoHttpClient) {
        this.http = alfrescoHttpClient;
    }

    /** @throws IllegalArgumentException when a value the authentication type needs is missing */
    public AlfrescoClient create(AlfrescoSettings settings) {
        require(settings.baseUrl() != null && settings.baseUrl().getHost() != null, "the repository address is missing");
        AlfrescoCredentials credentials = switch (settings.authType()) {
            case CLIENT_CREDENTIALS -> clientCredentials(settings);
            case BASIC -> {
                require(StringUtils.hasText(settings.username()) && StringUtils.hasLength(settings.password()),
                        "BASIC needs a user name and password");
                yield AlfrescoCredentials.Basic.of(settings.username(), settings.password());
            }
        };
        RestClient rest = RestClient.builder()
            .baseUrl(settings.baseUrl().toString())
            .requestFactory(new HttpComponentsClientHttpRequestFactory(this.http))
            .requestInterceptor(new AlfrescoAuthenticationInterceptor(credentials))
            .build();
        return new AlfrescoClient(rest, this.http, settings.baseUrl(), credentials);
    }

    private AlfrescoCredentials clientCredentials(AlfrescoSettings settings) {
        require(settings.tokenUri() != null && StringUtils.hasText(settings.clientId())
                && StringUtils.hasLength(settings.clientSecret()),
                "CLIENT_CREDENTIALS needs a token endpoint, client ID and client secret");
        ClientRegistration.Builder registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            // The PaaS realm accepts both; the form post is the one verified against it
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .clientId(settings.clientId())
            .clientSecret(settings.clientSecret())
            .tokenUri(settings.tokenUri().toString());
        if (StringUtils.hasText(settings.scope())) {
            registration.scope(StringUtils.commaDelimitedListToStringArray(settings.scope()));
        }
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration.build());
        InMemoryOAuth2AuthorizedClientService clients = new InMemoryOAuth2AuthorizedClientService(registrations);

        RestClientClientCredentialsTokenResponseClient tokens = new RestClientClientCredentialsTokenResponseClient();
        tokens.setRestClient(RestClient.builder()
            .requestFactory(new HttpComponentsClientHttpRequestFactory(this.http))
            .configureMessageConverters(converters -> converters
                .addCustomConverter(new FormHttpMessageConverter())
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
        ClientCredentialsOAuth2AuthorizedClientProvider provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokens);
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(provider);
        return new AlfrescoCredentials.ClientCredentials(manager, clients, REGISTRATION_ID);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

}
