package com.example.rag.pipeline;

import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/revisions/{revisionId}")
public class DocumentPipelineController {

    private final PipelineService service;

    public DocumentPipelineController(PipelineService service) {
        this.service = service;
    }

    @GetMapping("/pipeline")
    List<PipelineJobResponse> timeline(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.timeline(documentId, revisionId, user);
    }

    @GetMapping("/artifacts")
    RevisionArtifactsResponse artifacts(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.artifacts(documentId, revisionId, user);
    }

    @GetMapping("/structure")
    RevisionStructureResponse structure(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return service.structure(documentId, revisionId, user);
    }

    @GetMapping("/assets/{assetId}/content")
    ResponseEntity<StreamingResponseBody> asset(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @PathVariable UUID assetId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        var asset = service.openAsset(documentId, revisionId, assetId, user);
        StreamingResponseBody body = output -> {
            try (var input = asset.stream()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.mediaType()))
                .contentLength(asset.size())
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(asset.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
