package com.sapiensml.viewer.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** RFC 9457 problem details with the request's correlation ID, so a user can quote it to support. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    /** For the security filters, which answer before Spring MVC is involved. Values are fixed text. */
    public static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\""
                + (correlationId == null ? "" : ",\"correlationId\":\"" + correlationId + "\"") + "}");
    }

}
