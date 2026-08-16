package com.literaryoracle;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic")
public final class SemanticStatusController {
    private final SemanticRetriever semanticRetriever;

    public SemanticStatusController(SemanticRetriever semanticRetriever) {
        this.semanticRetriever = semanticRetriever;
    }

    @GetMapping("/status")
    public SemanticRetriever.SemanticStatus status() {
        return semanticRetriever.status();
    }
}
