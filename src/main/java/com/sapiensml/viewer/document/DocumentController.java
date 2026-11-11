package com.sapiensml.viewer.document;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sapiensml.viewer.security.ViewerUser;

/**
 * The document API the React viewer calls. Every route needs a signed-in user with the viewer role (see
 * SecurityConfig); the node ID is validated before anything reaches Alfresco.
 */
@RestController
@RequestMapping("/api/documents/{nodeId}")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public DocumentView describe(@PathVariable String nodeId, @AuthenticationPrincipal OAuth2User principal) {
        return this.documents.describe(NodeIds.require(nodeId), ViewerUser.of(principal));
    }

    @GetMapping("/preview")
    public void preview(@PathVariable String nodeId, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @AuthenticationPrincipal OAuth2User principal, HttpServletResponse response) {
        this.documents.preview(NodeIds.require(nodeId), range, ViewerUser.of(principal), response);
    }

    @GetMapping("/download")
    public void download(@PathVariable String nodeId, @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @AuthenticationPrincipal OAuth2User principal, HttpServletResponse response) {
        this.documents.download(NodeIds.require(nodeId), range, ViewerUser.of(principal), response);
    }

}
