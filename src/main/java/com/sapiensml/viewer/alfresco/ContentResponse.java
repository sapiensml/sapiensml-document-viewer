package com.sapiensml.viewer.alfresco;

import java.io.InputStream;

/**
 * A content response from the repository while it is still open: the body is read straight from the connection.
 * Only these headers are ever passed on; cookies, server names and everything else stay behind.
 *
 * @param status 200 or 206
 * @param contentLength -1 when the repository did not say
 */
public record ContentResponse(int status, String contentType, long contentLength, String contentRange,
        String acceptRanges, InputStream body) {
}
