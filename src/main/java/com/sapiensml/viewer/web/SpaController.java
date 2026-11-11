package com.sapiensml.viewer.web;

import java.net.URI;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sapiensml.viewer.document.NodeIds;

/**
 * The pages: every one is the React app, which reads the node ID from its own address. Links from business applications take the
 * form {@code /documents/<node id>}; {@code /view?nodeId=<node id or node reference>} is accepted too, for link
 * templates that can only append a parameter.
 */
@Controller
public class SpaController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @GetMapping({ "/", "/documents/{nodeId}", "/admin", "/signed-out" })
    public ResponseEntity<Resource> page() {
        if (!INDEX.exists()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // Asset names change with every build; the page that names them must always be fresh
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML).body(INDEX);
    }

    @GetMapping("/view")
    public ResponseEntity<Void> view(@RequestParam(name = "nodeId", required = false) String nodeId) {
        URI target = NodeIds.normalize(nodeId).map(id -> URI.create("/documents/" + id)).orElse(URI.create("/?link=invalid"));
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

}
