package com.sapiensml.viewer.admin;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.util.StringUtils;

import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;
import com.sapiensml.viewer.document.NodeIds;

/**
 * The Alfresco connection as an administrator enters it. A blank secret or password keeps the stored one, but
 * only for the same account at the same address: changing the token endpoint or client ID needs the secret again.
 */
public record SettingsForm(String baseUrl, AuthType authType, String tokenUri, String clientId, String clientSecret,
        String scope, String username, String password, List<String> allowedFolderIds) {

    static final int MAX_FOLDERS = 50;

    /** The settings to test or save, or {@link InvalidSettingsException} naming each field that is wrong. */
    AlfrescoSettings toSettings(AlfrescoSettings previous, String updatedBy, boolean allowHttp) {
        Map<String, String> errors = new LinkedHashMap<>();
        URI base = address(this.baseUrl, "baseUrl", true, allowHttp, errors);
        if (this.authType == null) {
            errors.put("authType", "Choose how the viewer signs in to Alfresco.");
        }
        URI token = null;
        String id = null;
        String user = null;
        if (this.authType == AuthType.CLIENT_CREDENTIALS) {
            token = address(this.tokenUri, "tokenUri", false, allowHttp, errors);
            id = text(this.clientId);
            if (id == null) {
                errors.put("clientId", "Enter the service account's client ID.");
            }
            else if (id.length() > 200 || id.chars().anyMatch(Character::isWhitespace)) {
                errors.put("clientId", "A client ID has no spaces.");
            }
        }
        else if (this.authType == AuthType.BASIC) {
            user = text(this.username);
            if (user == null) {
                errors.put("username", "Enter the Alfresco user name.");
            }
        }
        String scopes = text(this.scope);
        if (scopes != null && scopes.length() > 500) {
            errors.put("scope", "Too long.");
        }
        List<String> folders = folders(errors);

        AlfrescoSettings candidate = new AlfrescoSettings(base, this.authType, token, id, secret(this.clientSecret), scopes,
                user, secret(this.password), folders, updatedBy, Instant.now());
        String keptSecret = candidate.clientSecret();
        String keptPassword = candidate.password();
        boolean sameAccount = candidate.sameAccountAs(previous);
        if (this.authType == AuthType.CLIENT_CREDENTIALS && keptSecret == null) {
            if (sameAccount && previous.clientSecret() != null) {
                keptSecret = previous.clientSecret();
            }
            else {
                errors.put("clientSecret", "Enter the client secret.");
            }
        }
        if (this.authType == AuthType.BASIC && keptPassword == null) {
            if (sameAccount && previous.password() != null) {
                keptPassword = previous.password();
            }
            else {
                errors.put("password", "Enter the password.");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }
        return new AlfrescoSettings(base, this.authType, token, id,
                this.authType == AuthType.CLIENT_CREDENTIALS ? keptSecret : null,
                this.authType == AuthType.CLIENT_CREDENTIALS ? scopes : null, user,
                this.authType == AuthType.BASIC ? keptPassword : null, folders, updatedBy, Instant.now());
    }

    private List<String> folders(Map<String, String> errors) {
        Set<String> folders = new LinkedHashSet<>();
        if (this.allowedFolderIds != null) {
            for (String value : this.allowedFolderIds) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                NodeIds.normalize(value).ifPresentOrElse(folders::add,
                        () -> errors.putIfAbsent("allowedFolderIds", "Not a node ID: " + abbreviate(value.strip())));
            }
        }
        if (folders.size() > MAX_FOLDERS) {
            errors.put("allowedFolderIds", "At most " + MAX_FOLDERS + " folders.");
        }
        return List.copyOf(folders);
    }

    /**
     * An absolute https address (http only when allowed, for local development), without credentials, query or
     * fragment. For the repository only the origin counts; a trailing /alfresco is dropped.
     */
    private static URI address(String value, String field, boolean originOnly, boolean allowHttp, Map<String, String> errors) {
        String text = text(value);
        if (text == null) {
            errors.put(field, "Enter the address.");
            return null;
        }
        URI uri;
        try {
            uri = new URI(text);
        }
        catch (URISyntaxException ex) {
            errors.put(field, "Not a valid address.");
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || !(scheme.equals("https") || (allowHttp && scheme.equals("http")))) {
            errors.put(field, allowHttp ? "Enter a full http:// or https:// address." : "Enter a full https:// address.");
            return null;
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            errors.put(field, "The address must not contain a user, query or fragment.");
            return null;
        }
        if (!originOnly) {
            return uri;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!(path.isEmpty() || path.equals("/") || path.equals("/alfresco") || path.equals("/alfresco/"))) {
            errors.put(field, "Enter only the address, for example https://alfresco.example.com");
            return null;
        }
        return URI.create(scheme + "://" + uri.getRawAuthority());
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** Secrets are taken as typed; blank means "not entered". */
    private static String secret(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String abbreviate(String value) {
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }

    @Override
    public String toString() {
        return "SettingsForm[baseUrl=" + this.baseUrl + ", authType=" + this.authType + ", clientId=" + this.clientId + "]";
    }

}
