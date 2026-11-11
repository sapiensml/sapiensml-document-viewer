package com.sapiensml.viewer.security;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

import com.sapiensml.viewer.config.ViewerProperties;
import com.sapiensml.viewer.web.Problems;

/**
 * Sign-in as a backend for frontend: the viewer runs the OpenID Connect authorization code flow (with PKCE) as a
 * confidential client and keeps the tokens on the server. The browser holds only an HttpOnly session cookie, so no
 * token can leak from the page. Page navigations without a session go to the identity provider and come back to
 * the same document; API calls get 401 and the page reloads itself.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** spring.security.oauth2.client.registration.&lt;id&gt; used for sign-in */
    public static final String REGISTRATION_ID = "viewer";

    static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /**
     * Health and info, on the management port (loopback only by default): open to the install script, nginx and
     * monitoring, with no session. Every other actuator endpoint is refused even if someone exposes it.
     */
    @Bean
    @Order(0)
    SecurityFilterChain management(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                .anyRequest().denyAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain viewer(HttpSecurity http, ClientRegistrationRepository registrations,
            ViewerProperties properties, ClaimRoleMapper roles) throws Exception {
        RequestMatcher api = PATHS.matcher("/api/**");
        // Only page navigations are worth returning to after sign-in, never API or asset requests
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(new OrRequestMatcher(PATHS.matcher(HttpMethod.GET, "/documents/*"),
                PATHS.matcher(HttpMethod.GET, "/view"), PATHS.matcher(HttpMethod.GET, "/admin"),
                PATHS.matcher(HttpMethod.GET, "/")));
        // Back to the link exactly as the business application gave it, without Spring's ?continue marker
        requestCache.setMatchingRequestParameterName(null);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/assets/**", "/pdfjs/**", "/favicon.svg", "/signed-out", "/error").permitAll()
                .requestMatchers("/api/documents/**").hasRole(ClaimRoleMapper.VIEWER_ROLE)
                .requestMatchers("/api/admin/**").hasRole(ClaimRoleMapper.ADMIN_ROLE)
                .anyRequest().authenticated())
            .oauth2Login(login -> login
                .loginPage("/oauth2/authorization/" + REGISTRATION_ID)
                .failureUrl("/signed-out?error=sign-in")
                .authorizationEndpoint(endpoint -> endpoint
                    .authorizationRequestResolver(authorizationRequests(registrations, properties.security().idpHint())))
                .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(roles)))
            .logout(logout -> logout.logoutSuccessHandler(logoutSuccess(registrations)))
            .requestCache(cache -> cache.requestCache(requestCache))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(DelegatingAuthenticationEntryPoint.builder()
                    .addEntryPointFor(problem(HttpStatus.UNAUTHORIZED, "Sign in to continue."), api)
                    .defaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/" + REGISTRATION_ID))
                    .build())
                .defaultAccessDeniedHandlerFor(forbidden(), api))
            // Cookie XSRF-TOKEN, header X-XSRF-TOKEN; only sign-out changes anything
            .csrf(csrf -> csrf.spa())
            .headers(headers -> {
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy(properties.security().frameAncestors())))
                    .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                    .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()"));
                if (!properties.security().frameAncestors().isEmpty()) {
                    // frame-ancestors in the CSP replaces X-Frame-Options, which cannot name several origins
                    headers.frameOptions(frame -> frame.disable());
                }
                else {
                    headers.frameOptions(frame -> frame.deny());
                }
            });
        return http.build();
    }

    /**
     * Pages load scripts, styles, fonts and PDF.js only from the viewer itself. PDF.js decodes some images with
     * WebAssembly, which is all that 'wasm-unsafe-eval' allows (no JavaScript eval).
     */
    static String contentSecurityPolicy(List<String> frameAncestors) {
        String ancestors = frameAncestors.isEmpty() ? "'none'" : String.join(" ", frameAncestors);
        return "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'; "
                + "img-src 'self' data: blob:; font-src 'self' data:; style-src 'self'; manifest-src 'self'; "
                + "base-uri 'none'; form-action 'self'; frame-ancestors " + ancestors;
    }

    /** PKCE on top of the client secret, and Keycloak's kc_idp_hint to go straight to a brokered provider (Okta). */
    private static DefaultOAuth2AuthorizationRequestResolver authorizationRequests(ClientRegistrationRepository registrations,
            String idpHint) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                "/oauth2/authorization");
        Consumer<OAuth2AuthorizationRequest.Builder> customizer = OAuth2AuthorizationRequestCustomizers.withPkce();
        if (StringUtils.hasText(idpHint)) {
            customizer = customizer.andThen(request -> request.additionalParameters(parameters -> parameters.put("kc_idp_hint", idpHint)));
        }
        resolver.setAuthorizationRequestCustomizer(customizer);
        return resolver;
    }

    /**
     * Ends the identity provider's session too (RP-initiated logout), so the next link does not sign the same
     * person in silently. The React app posts to /logout and is told where to go, because a fetch cannot follow
     * a redirect to another site.
     */
    private static OidcClientInitiatedLogoutSuccessHandler logoutSuccess(ClientRegistrationRepository registrations) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/signed-out");
        handler.setDefaultTargetUrl("/signed-out");
        handler.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"redirectUrl\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        });
        return handler;
    }

    private static AuthenticationEntryPoint problem(HttpStatus status, String detail) {
        return (request, response, exception) -> Problems.write(response, status, detail);
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, exception) -> Problems.write(response, HttpStatus.FORBIDDEN,
                exception instanceof CsrfException ? "Reload the page and try again."
                        : request.getRequestURI().startsWith(request.getContextPath() + "/api/admin")
                                ? "You are signed in but not allowed to change the viewer's settings."
                                : "You are signed in but not allowed to view documents.");
    }

}
