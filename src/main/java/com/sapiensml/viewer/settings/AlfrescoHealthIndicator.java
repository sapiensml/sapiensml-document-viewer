package com.sapiensml.viewer.settings;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.sapiensml.viewer.alfresco.AlfrescoException;

/**
 * Whether Alfresco answers and accepts the service account. Shown under {@code /actuator/health}; it is not part
 * of readiness, so an Alfresco outage (or a viewer not set up yet) does not take the viewer out of the load
 * balancer: users see a clear message, and an administrator can still reach the admin page.
 */
@Component("alfresco")
public class AlfrescoHealthIndicator implements HealthIndicator {

    private final AlfrescoConnection connection;

    public AlfrescoHealthIndicator(AlfrescoConnection connection) {
        this.connection = connection;
    }

    @Override
    public Health health() {
        AlfrescoConnection.Current current = this.connection.current();
        if (!current.configured()) {
            return Health.unknown().withDetail("problem", "NOT_CONFIGURED").build();
        }
        try {
            this.connection.client().ping();
            return Health.up().withDetail("source", current.source().name()).build();
        }
        catch (AlfrescoException ex) {
            // The kind is enough for operators; the message can name repository details
            return Health.down().withDetail("problem", ex.kind().name()).withDetail("status", ex.status())
                .withDetail("source", current.source().name()).build();
        }
    }

}
