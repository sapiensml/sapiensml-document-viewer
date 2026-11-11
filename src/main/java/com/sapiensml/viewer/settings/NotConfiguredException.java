package com.sapiensml.viewer.settings;

/** No Alfresco connection yet: neither saved on the admin page nor given in the environment. */
public class NotConfiguredException extends RuntimeException {

    public NotConfiguredException() {
        super("The Alfresco connection has not been set up");
    }

}
