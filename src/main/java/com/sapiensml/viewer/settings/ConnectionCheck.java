package com.sapiensml.viewer.settings;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sapiensml.viewer.alfresco.AlfrescoClient;
import com.sapiensml.viewer.alfresco.AlfrescoClientFactory;
import com.sapiensml.viewer.alfresco.AlfrescoException;
import com.sapiensml.viewer.alfresco.AlfrescoNode;
import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;

/**
 * Tries settings before they are saved: a token for the service account, a call to the repository as that
 * account, and each allowed folder. It uses a client of its own, so the connection in use is untouched. Answers
 * are fixed sentences: nothing a remote server returns is passed back, so the check cannot be used to read other
 * systems.
 */
@Component
public class ConnectionCheck {

    public record Step(String name, boolean ok, String message) {
    }

    public record Result(boolean ok, List<Step> steps) {
    }

    private final AlfrescoClientFactory factory;

    public ConnectionCheck(AlfrescoClientFactory factory) {
        this.factory = factory;
    }

    public Result run(AlfrescoSettings settings) {
        List<Step> steps = new ArrayList<>();
        AlfrescoClient client = this.factory.create(settings);
        boolean basic = settings.authType() == AuthType.BASIC;
        if (!basic) {
            try {
                client.checkCredentials();
                steps.add(new Step("Service-account token", true, "Issued by " + settings.tokenUri().getHost()));
            }
            catch (AlfrescoException ex) {
                steps.add(new Step("Service-account token", false, ex.kind() == AlfrescoException.Kind.UNAUTHORIZED
                        ? "The identity provider refused the client ID or secret."
                        : "The token endpoint could not be reached. Check the address, the network and any proxy."));
                return new Result(false, steps);
            }
        }
        try {
            String user = client.ping();
            steps.add(new Step("Repository", true, "Connected to " + settings.baseUrl().getHost()
                    + (user == null ? "" : " as " + user)));
        }
        catch (AlfrescoException ex) {
            steps.add(new Step("Repository", false, switch (ex.kind()) {
                case UNAUTHORIZED, FORBIDDEN -> basic ? "Alfresco refused the user name or password."
                        : "Alfresco refused the service account's token.";
                case NOT_FOUND -> "There is no Alfresco REST API at this address.";
                default -> "The repository could not be reached. Check the address, the network and any proxy.";
            }));
            return new Result(false, steps);
        }
        for (String folderId : settings.allowedFolderIds()) {
            steps.add(folder(client, folderId));
        }
        return new Result(steps.stream().allMatch(Step::ok), steps);
    }

    private static Step folder(AlfrescoClient client, String folderId) {
        String name = "Folder " + folderId;
        try {
            AlfrescoNode node = client.getNode(folderId);
            return node.folder() ? new Step(name, true, node.name())
                    : new Step(name, false, "This is not a folder (" + node.nodeType() + ").");
        }
        catch (AlfrescoException ex) {
            return new Step(name, false, ex.kind() == AlfrescoException.Kind.NOT_FOUND
                    || ex.kind() == AlfrescoException.Kind.FORBIDDEN
                    ? "Not found, or the service account cannot read it." : "The repository did not answer.");
        }
    }

}
