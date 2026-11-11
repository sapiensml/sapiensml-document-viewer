package com.sapiensml.viewer.web;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sapiensml.viewer.alfresco.AlfrescoException;
import com.sapiensml.viewer.document.DocumentExceptions;
import com.sapiensml.viewer.settings.NotConfiguredException;

/**
 * Maps outcomes to status codes with short, fixed messages. Repository messages, node paths and stack traces stay
 * in the server log.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final int RETRY_AFTER_SECONDS = 3;

    @ExceptionHandler(DocumentExceptions.InvalidNodeId.class)
    ProblemDetail invalidNodeId() {
        return Problems.of(HttpStatus.BAD_REQUEST, "This link does not point to a document.");
    }

    @ExceptionHandler(DocumentExceptions.NotFound.class)
    ProblemDetail notFound(DocumentExceptions.NotFound ex) {
        log.debug("Document not found or refused: {}", ex.getMessage());
        return Problems.of(HttpStatus.NOT_FOUND, "The document does not exist or you cannot open it.");
    }

    @ExceptionHandler(DocumentExceptions.PreviewPending.class)
    ResponseEntity<ProblemDetail> previewPending() {
        ProblemDetail problem = Problems.of(HttpStatus.ACCEPTED, "The document is being prepared for viewing.");
        problem.setProperty("retryAfterSeconds", RETRY_AFTER_SECONDS);
        return ResponseEntity.accepted().header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS)).body(problem);
    }

    @ExceptionHandler(DocumentExceptions.PreviewUnavailable.class)
    ProblemDetail previewUnavailable(DocumentExceptions.PreviewUnavailable ex) {
        log.debug("{}", ex.getMessage());
        return Problems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This type of file cannot be shown in the browser.");
    }

    @ExceptionHandler(DocumentExceptions.DownloadNotAllowed.class)
    ProblemDetail downloadNotAllowed() {
        return Problems.of(HttpStatus.FORBIDDEN, "Downloading documents is not allowed.");
    }

    @ExceptionHandler(DocumentExceptions.RangeNotSatisfiable.class)
    ResponseEntity<ProblemDetail> rangeNotSatisfiable(DocumentExceptions.RangeNotSatisfiable ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        if (ex.size() >= 0) {
            response.header(HttpHeaders.CONTENT_RANGE, "bytes */" + ex.size());
        }
        return response.body(Problems.of(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "The requested range is not available."));
    }

    @ExceptionHandler(DocumentExceptions.ClientAborted.class)
    void clientAborted(DocumentExceptions.ClientAborted ex) {
        // PDF.js cancels range requests as the user scrolls; the browser is no longer listening
        log.trace("Client went away during a transfer", ex);
    }

    @ExceptionHandler(NotConfiguredException.class)
    ProblemDetail notConfigured() {
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE,
                "The viewer has not been set up yet. An administrator must enter the Alfresco connection.");
        problem.setProperty("notConfigured", true);
        return problem;
    }

    @ExceptionHandler(AlfrescoException.class)
    ProblemDetail alfresco(AlfrescoException ex, HttpServletResponse response) {
        switch (ex.kind()) {
            case NOT_FOUND, FORBIDDEN -> {
                log.debug("Repository refused a follow-up call: {}", ex.getMessage());
                return committed(response) ? null
                        : Problems.of(HttpStatus.NOT_FOUND, "The document does not exist or you cannot open it.");
            }
            case UNAUTHORIZED -> log.error("Alfresco refused the service account ({}). Check viewer.alfresco.client-id "
                    + "and client-secret, and that the account may read the documents", ex.getMessage(), ex);
            default -> log.warn("Alfresco unavailable: {}", ex.getMessage(), ex);
        }
        return committed(response) ? null
                : Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "The document store is not available. Try again in a few minutes.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex, HttpServletResponse response) {
        log.error("Unexpected failure", ex);
        return committed(response) ? null : Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong.");
    }

    /** Once content has started to flow nothing can be added; the browser sees a cut-off transfer. */
    private static boolean committed(HttpServletResponse response) {
        return response.isCommitted();
    }

}
