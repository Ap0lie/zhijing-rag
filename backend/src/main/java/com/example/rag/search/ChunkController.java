package com.example.rag.search;

import com.example.rag.search.SearchContracts.ChunkContext;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chunks")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class ChunkController {

    private final ChunkContextService chunks;

    ChunkController(ChunkContextService chunks) {
        this.chunks = chunks;
    }

    @GetMapping("/{id}")
    ChunkContext get(
            @PathVariable UUID id,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return chunks.get(id, user);
    }
}
