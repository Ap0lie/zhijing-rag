package com.example.rag.document;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-formats")
public class DocumentFormatController {

    private final DocumentFormatCapabilityService service;

    public DocumentFormatController(DocumentFormatCapabilityService service) {
        this.service = service;
    }

    @GetMapping
    DocumentFormatsResponse list() {
        return service.capabilities();
    }
}
