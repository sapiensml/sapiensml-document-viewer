package com.sapiensml.viewer.audit;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.sapiensml.viewer.security.ViewerUser;

/**
 * Who opened which document, and who was turned away. Written to the {@code AUDIT} logger, which the
 * {@code prod} profile sends to its own JSON file (see logback-spring.xml). Document names and contents are never
 * logged: they can hold personal and health information.
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    public enum Action {
        VIEW, CONTENT, DOWNLOAD
    }

    public enum Outcome {
        GRANTED, DENIED, NOT_FOUND, ERROR
    }

    public void record(Action action, String nodeId, ViewerUser user, Outcome outcome, String detail) {
        String clientIp = clientIp();
        audit.atInfo()
            .setMessage("{} {} node={} user={} ip={}{}")
            .addArgument(action)
            .addArgument(outcome)
            .addArgument(nodeId)
            .addArgument(user.username())
            .addArgument(clientIp)
            .addArgument(detail == null ? "" : " (" + detail + ")")
            .addKeyValue("audit.action", action.name())
            .addKeyValue("audit.outcome", outcome.name())
            .addKeyValue("audit.nodeId", nodeId)
            .addKeyValue("audit.user", user.username())
            .addKeyValue("audit.subject", user.subject())
            .addKeyValue("audit.clientIp", clientIp)
            .addKeyValue("audit.detail", detail)
            .log();
    }

    /**
     * A change to the Alfresco connection on the admin page. Secrets are never part of {@code detail}.
     *
     * @param outcome SAVED or REJECTED
     */
    public void settings(ViewerUser user, String outcome, String detail) {
        String clientIp = clientIp();
        audit.atInfo()
            .setMessage("SETTINGS {} user={} ip={} ({})")
            .addArgument(outcome)
            .addArgument(user.username())
            .addArgument(clientIp)
            .addArgument(detail)
            .addKeyValue("audit.action", "SETTINGS")
            .addKeyValue("audit.outcome", outcome)
            .addKeyValue("audit.user", user.username())
            .addKeyValue("audit.subject", user.subject())
            .addKeyValue("audit.clientIp", clientIp)
            .addKeyValue("audit.detail", detail)
            .log();
    }

    /** The browser's address; behind nginx this comes from X-Forwarded-For (server.forward-headers-strategy). */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return "-";
    }

}
