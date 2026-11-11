package com.sapiensml.viewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SapiensML Document Viewer: opens Alfresco documents from links in business applications (FINEOS, Guidewire,
 * Salesforce, a portal, ...). Users sign in with the organisation's OpenID Connect provider
 * (Keycloak, later Okta); the viewer reads the document from Alfresco with its own service account and streams it
 * to the browser. The browser never sees Alfresco, its address or any token.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ViewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ViewerApplication.class, args);
    }

}
