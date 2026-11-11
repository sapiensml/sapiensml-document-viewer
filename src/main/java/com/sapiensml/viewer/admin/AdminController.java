package com.sapiensml.viewer.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapiensml.viewer.alfresco.AlfrescoSettings;
import com.sapiensml.viewer.audit.AuditLog;
import com.sapiensml.viewer.config.ViewerProperties;
import com.sapiensml.viewer.config.ViewerProperties.AuthType;
import com.sapiensml.viewer.security.ViewerUser;
import com.sapiensml.viewer.settings.AlfrescoConnection;
import com.sapiensml.viewer.settings.ConnectionCheck;
import com.sapiensml.viewer.web.Problems;

/**
 * The admin page's API: the Alfresco connection (address, service account, allowed folders). Only users with the
 * admin role reach it (SecurityConfig), every change needs the CSRF token, and settings are saved only after the
 * connection test passes. Each save and refused save is in the audit trail.
 */
@RestController
@RequestMapping("/api/admin/alfresco")
public class AdminController {

    private final AlfrescoConnection connection;

    private final ConnectionCheck check;

    private final AuditLog audit;

    private final boolean allowHttp;

    public AdminController(AlfrescoConnection connection, ConnectionCheck check, AuditLog audit, ViewerProperties properties) {
        this.connection = connection;
        this.check = check;
        this.audit = audit;
        this.allowHttp = properties.settings().allowInsecureHttp();
    }

    public record SaveResult(boolean saved, SettingsView settings, List<ConnectionCheck.Step> steps) {
    }

    @GetMapping
    public SettingsView settings() {
        return SettingsView.of(this.connection.current());
    }

    /** Tries the settings without saving them. */
    @PostMapping("/test")
    public ConnectionCheck.Result test(@RequestBody SettingsForm form, @AuthenticationPrincipal OAuth2User principal) {
        return this.check.run(toSettings(form, ViewerUser.of(principal)));
    }

    /** Tests the settings and, when the test passes, saves them and switches every following request to them. */
    @PutMapping
    public ResponseEntity<SaveResult> save(@RequestBody SettingsForm form, @AuthenticationPrincipal OAuth2User principal) {
        ViewerUser user = ViewerUser.of(principal);
        AlfrescoSettings settings = toSettings(form, user);
        ConnectionCheck.Result result = this.check.run(settings);
        if (!result.ok()) {
            String failed = result.steps().stream().filter(step -> !step.ok()).map(ConnectionCheck.Step::name)
                .findFirst().orElse("connection test");
            this.audit.settings(user, "REJECTED", "test failed at " + failed + "; " + describe(settings));
            return ResponseEntity.status(422)
                .body(new SaveResult(false, SettingsView.of(this.connection.current()), result.steps()));
        }
        this.connection.save(settings);
        this.audit.settings(user, "SAVED", describe(settings));
        return ResponseEntity.ok(new SaveResult(true, SettingsView.of(this.connection.current()), result.steps()));
    }

    @ExceptionHandler(InvalidSettingsException.class)
    ProblemDetail invalid(InvalidSettingsException ex) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "Some settings are missing or wrong.");
        problem.setProperty("fieldErrors", ex.fieldErrors());
        return problem;
    }

    private AlfrescoSettings toSettings(SettingsForm form, ViewerUser user) {
        return form.toSettings(this.connection.current().settings(), user.username(), this.allowHttp);
    }

    /** What changed, for the audit trail; never a secret. */
    private static String describe(AlfrescoSettings settings) {
        return "baseUrl=" + settings.baseUrl() + ", auth=" + settings.authType() + ", account="
                + (settings.authType() == AuthType.BASIC ? settings.username() : settings.clientId())
                + ", folders=" + settings.allowedFolderIds().size();
    }

}
