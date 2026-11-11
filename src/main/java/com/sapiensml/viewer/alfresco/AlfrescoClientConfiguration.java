package com.sapiensml.viewer.alfresco;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapiensml.viewer.config.ViewerProperties;

/**
 * One pooled HTTP client for Alfresco and its identity provider, with real timeouts, no automatic retries (a
 * retried content request could stream twice) and no cookie store (the PaaS load balancer's cookies stay out).
 * Outbound proxies and trust stores come from the standard JVM properties ({@code -Dhttps.proxyHost=...},
 * {@code -Djavax.net.ssl.trustStore=...}). Clients for a repository are made by {@link AlfrescoClientFactory}.
 */
@Configuration(proxyBeanMethods = false)
public class AlfrescoClientConfiguration {

    @Bean(destroyMethod = "close")
    public CloseableHttpClient alfrescoHttpClient(ViewerProperties properties) {
        ViewerProperties.Alfresco alfresco = properties.alfresco();
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
            .useSystemProperties()
            .setMaxConnTotal(alfresco.maxConnections())
            .setMaxConnPerRoute(alfresco.maxConnections())
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(alfresco.connectTimeout()))
                .setSocketTimeout(Timeout.of(alfresco.readTimeout()))
                .setTimeToLive(TimeValue.ofMinutes(5))
                .build())
            .build();
        return HttpClients.custom()
            .useSystemProperties()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.of(alfresco.readTimeout()))
                .setRedirectsEnabled(false)
                .build())
            .disableAutomaticRetries()
            .disableCookieManagement()
            .evictExpiredConnections()
            .evictIdleConnections(TimeValue.ofSeconds(30))
            .build();
    }

}
