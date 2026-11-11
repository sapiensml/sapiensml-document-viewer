package com.sapiensml.viewer.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import com.sapiensml.viewer.alfresco.AlfrescoClient;
import com.sapiensml.viewer.alfresco.AlfrescoClientFactory;
import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties;

/**
 * The Alfresco connection in use. Settings saved on the admin page win; otherwise the environment's
 * ({@code VIEWER_ALFRESCO_*}); otherwise the viewer runs without one and asks an administrator to set it up.
 * Saving new settings switches every following request to them at once, without a restart.
 */
@Component
public class AlfrescoConnection {

    public enum Source {
        /** Saved on the admin page */
        ADMIN,
        /** VIEWER_ALFRESCO_* settings */
        ENVIRONMENT,
        /** Not set up */
        NONE
    }

    public record Current(AlfrescoSettings settings, Source source) {

        public boolean configured() {
            return this.source != Source.NONE;
        }

    }

    private record Active(AlfrescoSettings settings, AlfrescoClient client, Source source) {
    }

    private static final Logger log = LoggerFactory.getLogger(AlfrescoConnection.class);

    private final AlfrescoClientFactory factory;

    private final SettingsStore store;

    private final AtomicReference<Active> active = new AtomicReference<>(new Active(null, null, Source.NONE));

    public AlfrescoConnection(AlfrescoClientFactory factory, SettingsStore store, ViewerProperties properties) {
        this.factory = factory;
        this.store = store;
        Optional<AlfrescoSettings> saved = store.load();
        if (saved.isPresent()) {
            use(saved.get(), Source.ADMIN);
        }
        else if (properties.alfresco().baseUrl() != null) {
            try {
                use(fromEnvironment(properties), Source.ENVIRONMENT);
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalStateException("VIEWER_ALFRESCO_* settings are incomplete: " + ex.getMessage(), ex);
            }
        }
        else {
            log.warn("No Alfresco connection yet: an administrator must enter it on the admin page (/admin)");
        }
    }

    /** The client for the current connection. */
    public AlfrescoClient client() {
        Active current = this.active.get();
        if (current.client() == null) {
            throw new NotConfiguredException();
        }
        return current.client();
    }

    public Current current() {
        Active current = this.active.get();
        return new Current(current.settings(), current.source());
    }

    /** Folders whose documents may be opened; empty when there is no connection or no limit. */
    public List<String> allowedFolderIds() {
        AlfrescoSettings settings = this.active.get().settings();
        return settings == null ? List.of() : settings.allowedFolderIds();
    }

    /** Stores settings from the admin page and switches to them. */
    public void save(AlfrescoSettings settings) {
        AlfrescoClient client = this.factory.create(settings);
        this.store.save(settings);
        this.active.set(new Active(settings, client, Source.ADMIN));
        describe(settings, Source.ADMIN);
    }

    private void use(AlfrescoSettings settings, Source source) {
        this.active.set(new Active(settings, this.factory.create(settings), source));
        describe(settings, source);
    }

    private static void describe(AlfrescoSettings settings, Source source) {
        log.info("Alfresco connection ({}): {} as {}", source == Source.ADMIN ? "admin page" : "environment",
                settings.baseUrl(), settings.authType() == ViewerProperties.AuthType.BASIC ? settings.username()
                        : settings.clientId());
        if (settings.allowedFolderIds().isEmpty()) {
            log.warn("No allowed folders: any signed-in viewer can open every document the service account can read");
        }
    }

    private static AlfrescoSettings fromEnvironment(ViewerProperties properties) {
        ViewerProperties.Alfresco alfresco = properties.alfresco();
        return new AlfrescoSettings(alfresco.baseUrl(), alfresco.authType(), alfresco.tokenUri(), alfresco.clientId(),
                alfresco.clientSecret(), alfresco.scope(), alfresco.username(), alfresco.password(),
                List.copyOf(properties.access().allowedAncestorIds()), null, null);
    }

}
