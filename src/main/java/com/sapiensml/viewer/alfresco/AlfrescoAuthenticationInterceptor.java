package com.sapiensml.viewer.alfresco;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Adds the service account's credentials to every JSON call. When Alfresco refuses a token before it expires
 * (rotated secret, revoked session), the token is dropped and the call is made once more with a new one.
 */
public class AlfrescoAuthenticationInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoAuthenticationInterceptor.class);

    private final AlfrescoCredentials credentials;

    public AlfrescoAuthenticationInterceptor(AlfrescoCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, this.credentials.authorizationHeader());
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() != 401 || !this.credentials.renewable()) {
            return response;
        }
        response.close();
        log.info("Alfresco refused the service-account token for {} {}; fetching a new one", request.getMethod(),
                request.getURI().getPath());
        this.credentials.invalidate();
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, this.credentials.authorizationHeader());
        return execution.execute(request, body);
    }

}
