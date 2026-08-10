package com.example.rag.search;

import com.example.rag.search.IndexGenerationContracts.IndexGenerationView;
import com.example.rag.search.IndexGenerationContracts.IndexGenerationsResponse;
import com.example.rag.search.IndexGenerationContracts.PublishGenerationRequest;
import com.example.rag.search.IndexGenerationContracts.RollbackGenerationRequest;
import com.example.rag.search.IndexGenerationContracts.StartIndexBuildRequest;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class AdminIndexGenerationController {

    private final IndexGenerationService generations;

    AdminIndexGenerationController(IndexGenerationService generations) {
        this.generations = generations;
    }

    @GetMapping("/index-builds")
    IndexGenerationsResponse generations() {
        return generations.generations();
    }

    @PostMapping("/index-builds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IndexGenerationView start(
            @Valid @RequestBody StartIndexBuildRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return generations.start(request, actorId(user));
    }

    @PostMapping("/retrieval/releases")
    IndexGenerationView publish(
            @Valid @RequestBody PublishGenerationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return generations.publish(request, actorId(user));
    }

    @PostMapping("/retrieval/rollback")
    IndexGenerationView rollback(
            @Valid @RequestBody RollbackGenerationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return generations.rollback(request, actorId(user));
    }

    private static UUID actorId(PlatformUserPrincipal user) {
        return user == null ? null : user.id();
    }
}
