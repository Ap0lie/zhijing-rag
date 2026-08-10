package com.example.rag.search;

import com.example.rag.search.SearchContracts.IndexStatus;
import com.example.rag.search.SearchContracts.SearchDebugResponse;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class AdminSearchController {

    private final SearchService search;
    private final SearchIndexService indexes;

    AdminSearchController(SearchService search, SearchIndexService indexes) {
        this.search = search;
        this.indexes = indexes;
    }

    @PostMapping("/search/debug")
    SearchDebugResponse debug(
            @Valid @RequestBody SearchRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return search.debug(request, user);
    }

    @GetMapping("/indexes")
    IndexStatus status() {
        return indexes.status();
    }

}
