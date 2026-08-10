package com.example.rag.graph;

import com.example.rag.graph.GlobalGraphContracts.ConfigView;
import com.example.rag.graph.GlobalGraphContracts.CreateConfigRequest;
import com.example.rag.graph.GlobalGraphContracts.GenerationView;
import com.example.rag.graph.GlobalGraphContracts.OverviewResponse;
import com.example.rag.graph.GlobalGraphContracts.ReleaseRequest;
import com.example.rag.graph.GlobalGraphContracts.ReportDetail;
import com.example.rag.graph.GlobalGraphContracts.ReportPage;
import com.example.rag.graph.GlobalGraphContracts.StartBuildRequest;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/admin/graph/global")
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "rag.search",
        name = "enabled",
        havingValue = "true"
)
class AdminGlobalGraphController {

    private final GlobalGraphGenerationService generations;
    private final GlobalGraphQueryService queries;

    AdminGlobalGraphController(
            GlobalGraphGenerationService generations,
            GlobalGraphQueryService queries
    ) {
        this.generations = generations;
        this.queries = queries;
    }

    @GetMapping
    OverviewResponse overview(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return generations.overview();
    }

    @PostMapping("/configs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ConfigView createConfig(
            @Valid @RequestBody CreateConfigRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.createConfig(request, user.id());
    }

    @PostMapping("/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GenerationView start(
            @Valid @RequestBody StartBuildRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.start(request, user.id());
    }

    @PostMapping("/publications")
    @Transactional
    GenerationView publish(
            @Valid @RequestBody ReleaseRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.publish(request, user.id());
    }

    @PostMapping("/rollbacks")
    @Transactional
    GenerationView rollback(
            @Valid @RequestBody ReleaseRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.rollback(request, user.id());
    }

    @GetMapping("/reports")
    ReportPage reports(
            @RequestParam(required = false) Long generation,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int size,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.reports(user, generation, page, size);
    }

    @GetMapping("/reports/{reportId}")
    ReportDetail report(
            @PathVariable UUID reportId,
            @RequestParam(required = false) Long generation,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.report(user, generation, reportId);
    }
}
