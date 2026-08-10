package com.example.rag.document;

import com.example.rag.document.DocumentRuntimePolicyContracts.ChangePolicyRequest;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/admin/document-formats")
class AdminDocumentFormatController {

    private final DocumentFormatCapabilityService capabilities;
    private final DocumentRuntimePolicyService policies;

    AdminDocumentFormatController(
            DocumentFormatCapabilityService capabilities,
            DocumentRuntimePolicyService policies
    ) {
        this.capabilities = capabilities;
        this.policies = policies;
    }

    @GetMapping
    DocumentFormatsResponse list() {
        return capabilities.capabilities();
    }

    @GetMapping("/{format}/events")
    List<DocumentRuntimePolicyService.PolicyEvent> events(
            @PathVariable DocumentFormat format,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return policies.events(format, limit);
    }

    @PatchMapping("/{format}")
    @Transactional
    DocumentFormatsResponse change(
            @PathVariable DocumentFormat format,
            @Valid @RequestBody ChangePolicyRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal actor
    ) {
        policies.change(
                format,
                request.parserProvider(),
                request.action(),
                request.confirmation(),
                request.reason(),
                actor.id()
        );
        if (request.action() == DocumentRuntimePolicyService.PolicyAction.RESTORE) {
            if (request.parserProvider() == null) {
                capabilities.requireOperational(format);
            } else {
                capabilities.requireProviderOperational(format, request.parserProvider());
            }
        }
        return capabilities.capabilities();
    }
}
