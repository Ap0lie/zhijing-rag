package com.example.rag.document;

import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/documents")
public class AdminDocumentController {

    private final DocumentLifecycleService service;

    public AdminDocumentController(DocumentLifecycleService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentDetailResponse create(
            @AuthenticationPrincipal PlatformUserPrincipal actor,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String title,
            @RequestParam DocumentVisibility visibility,
            @RequestParam(required = false) List<UUID> grantedUserIds,
            @RequestParam(required = false) String evaluationSuiteVersion,
            @RequestParam(required = false) String evaluationEvidenceKey,
            @RequestParam(required = false) String sourceDataset,
            @RequestParam(required = false) String sourceTitle,
            @RequestParam(required = false) String sourceUrl,
            @RequestParam(required = false) String sourceLicense,
            @RequestParam(required = false) String sourceRevision,
            @RequestParam(required = false) String sourceContentHash,
            @RequestParam MultipartFile file
    ) {
        return service.create(
                actor,
                title,
                visibility,
                grantedUserIds == null ? List.of() : grantedUserIds,
                file,
                idempotencyKey,
                provenance(
                        evaluationSuiteVersion,
                        evaluationEvidenceKey,
                        sourceDataset,
                        sourceTitle,
                        sourceUrl,
                        sourceLicense,
                        sourceRevision,
                        sourceContentHash
                )
        );
    }

    @PostMapping(path = "/{documentId}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DocumentDetailResponse addRevision(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal PlatformUserPrincipal actor,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(required = false) String evaluationSuiteVersion,
            @RequestParam(required = false) String evaluationEvidenceKey,
            @RequestParam(required = false) String sourceDataset,
            @RequestParam(required = false) String sourceTitle,
            @RequestParam(required = false) String sourceUrl,
            @RequestParam(required = false) String sourceLicense,
            @RequestParam(required = false) String sourceRevision,
            @RequestParam(required = false) String sourceContentHash,
            @RequestParam(required = false) String formatChangeConfirmation,
            @RequestParam(required = false) String formatChangeReason,
            @RequestParam MultipartFile file
    ) {
        return service.addRevision(
                documentId,
                actor,
                file,
                idempotencyKey,
                provenance(
                        evaluationSuiteVersion,
                        evaluationEvidenceKey,
                        sourceDataset,
                        sourceTitle,
                        sourceUrl,
                        sourceLicense,
                        sourceRevision,
                        sourceContentHash
                ),
                formatChangeConfirmation,
                formatChangeReason
        );
    }

    @PostMapping("/{documentId}/reparse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReparseResponse reparse(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal PlatformUserPrincipal actor,
            @Valid @RequestBody ReparseRequest request
    ) {
        return service.reparse(documentId, actor, request);
    }

    @PatchMapping("/{documentId}/acl")
    DocumentDetailResponse updateAcl(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal PlatformUserPrincipal actor,
            @Valid @RequestBody DocumentAclUpdateRequest request
    ) {
        return service.updateAcl(documentId, actor, request);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID documentId) {
        service.delete(documentId);
    }

    private static EvaluationProvenanceInput provenance(
            String evaluationSuiteVersion,
            String evaluationEvidenceKey,
            String sourceDataset,
            String sourceTitle,
            String sourceUrl,
            String sourceLicense,
            String sourceRevision,
            String sourceContentHash
    ) {
        return new EvaluationProvenanceInput(
                evaluationSuiteVersion,
                evaluationEvidenceKey,
                sourceDataset,
                sourceTitle,
                sourceUrl,
                sourceLicense,
                sourceRevision,
                sourceContentHash
        );
    }

}
