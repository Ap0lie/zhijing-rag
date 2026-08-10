package com.example.rag.document;

import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentAccessController {

    private final DocumentAccessService service;

    public DocumentAccessController(DocumentAccessService service) {
        this.service = service;
    }

    @GetMapping
    DocumentPageResponse list(
            @AuthenticationPrincipal PlatformUserPrincipal user,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) DocumentVisibility visibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listAccessible(user, query, visibility, page, size);
    }

    @GetMapping("/{id}")
    DocumentDetailResponse get(
            @PathVariable UUID id,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.getAccessible(id, user);
    }

    @GetMapping("/{documentId}/revisions/{revisionId}/download")
    ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal PlatformUserPrincipal user,
            @RequestParam(defaultValue = "false") boolean inline
    ) {
        var download = service.openDownload(documentId, revisionId, user);
        StreamingResponseBody body = output -> {
            try (var input = download.stream()) {
                input.transferTo(output);
            }
        };
        MediaType mediaType = MediaType.parseMediaType(download.mediaType());
        boolean allowInline = inline && MediaType.APPLICATION_PDF.isCompatibleWith(mediaType);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, (allowInline
                        ? ContentDisposition.inline()
                        : ContentDisposition.attachment())
                        .filename(download.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
