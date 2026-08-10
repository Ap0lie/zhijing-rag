package com.example.rag.search;

import com.example.rag.search.EmbeddingCacheContracts.ClearEmbeddingCacheRequest;
import com.example.rag.search.EmbeddingCacheContracts.ClearEmbeddingCacheResponse;
import com.example.rag.search.EmbeddingCacheContracts.EmbeddingCacheStatsResponse;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/embedding-cache")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class AdminEmbeddingCacheController {

    private final EmbeddingCacheService cache;

    AdminEmbeddingCacheController(EmbeddingCacheService cache) {
        this.cache = cache;
    }

    @GetMapping("/stats")
    EmbeddingCacheStatsResponse stats() {
        return cache.stats();
    }

    @PostMapping("/clear")
    ClearEmbeddingCacheResponse clear(
            @Valid @RequestBody ClearEmbeddingCacheRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return cache.clear(
                request.providerKey(),
                request.model(),
                request.revision(),
                user == null ? null : user.id(),
                request.reason()
        );
    }
}
