package com.sapiensml.viewer.admin;

import java.util.Map;

/** Fields of the admin form that are missing or wrong, with a sentence for each. */
class InvalidSettingsException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    InvalidSettingsException(Map<String, String> fieldErrors) {
        super("Invalid settings: " + fieldErrors.keySet());
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    Map<String, String> fieldErrors() {
        return this.fieldErrors;
    }

}
