package com.example.rag.graph;

import com.example.rag.graph.GraphApiContracts.CreateGraphConfigRequest;
import com.example.rag.graph.GraphApiContracts.CreateResolutionRuleRequest;
import com.example.rag.graph.GraphApiContracts.GraphCommunityDetail;
import com.example.rag.graph.GraphApiContracts.GraphCommunityPage;
import com.example.rag.graph.GraphApiContracts.GraphConfigView;
import com.example.rag.graph.GraphApiContracts.GraphEntityDetail;
import com.example.rag.graph.GraphApiContracts.GraphEntityPage;
import com.example.rag.graph.GraphApiContracts.GraphGenerationView;
import com.example.rag.graph.GraphApiContracts.GraphOverviewResponse;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateDetail;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidatePage;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateSnapshotView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionCandidateSummary;
import com.example.rag.graph.GraphApiContracts.CreateResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalDetail;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalEventView;
import com.example.rag.graph.GraphApiContracts.GraphResolutionProposalPage;
import com.example.rag.graph.GraphApiContracts.MaterializeResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.ReviseResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.WithdrawResolutionProposalRequest;
import com.example.rag.graph.GraphApiContracts.GraphRelationshipView;
import com.example.rag.graph.GraphApiContracts.GraphRootType;
import com.example.rag.graph.GraphApiContracts.GraphSubgraphView;
import com.example.rag.graph.GraphApiContracts.ReleaseGraphGenerationRequest;
import com.example.rag.graph.GraphApiContracts.RefreshResolutionCandidatesRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewResponse;
import com.example.rag.graph.GraphApiContracts.StartGraphBuildRequest;
import com.example.rag.graph.GraphApiContracts.UpdateResolutionCandidateRequest;
import com.example.rag.graph.GraphRetrievalContracts.ConfigurationResponse;
import com.example.rag.graph.GraphRetrievalContracts.CreateProfileRequest;
import com.example.rag.graph.GraphRetrievalContracts.ProfileView;
import com.example.rag.graph.GraphRetrievalContracts.PublicationView;
import com.example.rag.graph.GraphRetrievalContracts.ReleaseProfileRequest;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/admin/graph")
@ConditionalOnProperty(
        prefix = "rag.graph",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class AdminGraphController {

    private final GraphGenerationService generations;
    private final GraphQueryService queries;
    private final GraphRetrievalConfigurationService retrieval;
    private final GraphRebuildRequestService rebuilds;
    private final GraphVisualizationService visualization;
    private final GraphResolutionCandidateService candidates;
    private final GraphResolutionProposalService proposals;

    AdminGraphController(
            GraphGenerationService generations,
            GraphQueryService queries,
            GraphRetrievalConfigurationService retrieval,
            GraphRebuildRequestService rebuilds,
            GraphVisualizationService visualization,
            GraphResolutionCandidateService candidates,
            GraphResolutionProposalService proposals
    ) {
        this.generations = generations;
        this.queries = queries;
        this.retrieval = retrieval;
        this.rebuilds = rebuilds;
        this.visualization = visualization;
        this.candidates = candidates;
        this.proposals = proposals;
    }

    @GetMapping("/retrieval")
    ConfigurationResponse retrieval(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return retrieval.configuration();
    }

    @PostMapping("/retrieval/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ProfileView createRetrievalProfile(
            @Valid @RequestBody CreateProfileRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return retrieval.create(request, user.id());
    }

    @PostMapping("/retrieval/publications")
    @Transactional
    PublicationView publishRetrievalProfile(
            @Valid @RequestBody ReleaseProfileRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return retrieval.publish(request, user.id(), "PUBLISH");
    }

    @PostMapping("/retrieval/rollbacks")
    @Transactional
    PublicationView rollbackRetrievalProfile(
            @Valid @RequestBody ReleaseProfileRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return retrieval.publish(request, user.id(), "ROLLBACK");
    }

    @GetMapping
    GraphOverviewResponse overview(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return generations.overview();
    }

    @GetMapping("/rebuild-requests")
    List<GraphRebuildRequestService.RebuildRequestView> rebuildRequests(
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return rebuilds.requests();
    }

    @PostMapping("/configs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GraphConfigView createConfig(
            @Valid @RequestBody CreateGraphConfigRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.createConfig(request, user.id());
    }

    @PostMapping("/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphGenerationView start(
            @Valid @RequestBody StartGraphBuildRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.start(request, user.id());
    }

    @PostMapping("/publications")
    @Transactional
    GraphGenerationView publish(
            @Valid @RequestBody ReleaseGraphGenerationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.publish(request, user.id());
    }

    @PostMapping("/rollbacks")
    @Transactional
    GraphGenerationView rollback(
            @Valid @RequestBody ReleaseGraphGenerationRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.rollback(request, user.id());
    }

    @PostMapping("/resolution-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GraphConfigView createRule(
            @Valid @RequestBody CreateResolutionRuleRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.createResolutionRule(request, user);
    }

    @PostMapping("/resolution-rules/previews")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    ResolutionRulePreviewResponse previewRule(
            @Valid @RequestBody ResolutionRulePreviewRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return generations.previewResolutionRule(request, user);
    }

    @PostMapping("/resolution-candidates/refresh")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphResolutionCandidateSnapshotView refreshResolutionCandidates(
            @Valid @RequestBody RefreshResolutionCandidatesRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return candidates.refresh(request, user);
    }

    @GetMapping("/resolution-candidates")
    @Transactional
    GraphResolutionCandidatePage resolutionCandidates(
            @RequestParam @Min(1) long generation,
            @RequestParam(defaultValue = "")
            @Size(max = 32) String candidateType,
            @RequestParam(defaultValue = "")
            @Size(max = 16) String status,
            @RequestParam(defaultValue = "")
            @Size(max = 64) String signal,
            @RequestParam(defaultValue = "")
            @Size(max = 200) String entityQuery,
            @RequestParam(required = false)
            @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(50) int limit,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return candidates.candidates(
                generation, candidateType, status, signal,
                entityQuery, cursor, limit
        );
    }

    @GetMapping("/resolution-candidates/{candidateId}")
    @Transactional
    GraphResolutionCandidateDetail resolutionCandidate(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return candidates.detail(candidateId);
    }

    @PostMapping("/resolution-candidates/{candidateId}/ignore")
    @Transactional
    GraphResolutionCandidateSummary ignoreResolutionCandidate(
            @PathVariable UUID candidateId,
            @Valid @RequestBody UpdateResolutionCandidateRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return candidates.changeState(candidateId, "IGNORE", request, user);
    }

    @PostMapping("/resolution-candidates/{candidateId}/restore")
    @Transactional
    GraphResolutionCandidateSummary restoreResolutionCandidate(
            @PathVariable UUID candidateId,
            @Valid @RequestBody UpdateResolutionCandidateRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return candidates.changeState(candidateId, "RESTORE", request, user);
    }

    @GetMapping("/resolution-proposals")
    @Transactional
    GraphResolutionProposalPage resolutionProposals(
            @RequestParam(defaultValue = "") @Size(max = 16) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return proposals.page(status, page, size, user);
    }

    @GetMapping("/resolution-proposals/{proposalId}")
    @Transactional
    GraphResolutionProposalDetail resolutionProposal(
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return proposals.detail(proposalId, user, true);
    }

    @GetMapping("/resolution-proposals/{proposalId}/events")
    @Transactional(readOnly = true)
    List<GraphResolutionProposalEventView> resolutionProposalEvents(
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdmin(user);
        return proposals.events(proposalId);
    }

    @PostMapping("/resolution-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphResolutionProposalDetail createResolutionProposal(
            @Valid @RequestBody CreateResolutionProposalRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return proposals.create(request, user);
    }

    @PostMapping("/resolution-proposals/{proposalId}/revisions")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphResolutionProposalDetail reviseResolutionProposal(
            @PathVariable UUID proposalId,
            @Valid @RequestBody ReviseResolutionProposalRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return proposals.revise(proposalId, request, user);
    }

    @PostMapping("/resolution-proposals/{proposalId}/withdraw")
    @Transactional
    GraphResolutionProposalDetail withdrawResolutionProposal(
            @PathVariable UUID proposalId,
            @Valid @RequestBody WithdrawResolutionProposalRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return proposals.withdraw(proposalId, request, user);
    }

    @PostMapping("/resolution-proposals/{proposalId}/materialize")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    GraphResolutionProposalDetail materializeResolutionProposal(
            @PathVariable UUID proposalId,
            @Valid @RequestBody MaterializeResolutionProposalRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        queries.requireAdminForUpdate(user);
        return proposals.materialize(proposalId, request, user);
    }

    @GetMapping("/entities")
    GraphEntityPage entities(
            @RequestParam(required = false) Long generation,
            @RequestParam(defaultValue = "")
            @Size(max = 200) String query,
            @RequestParam(defaultValue = "")
            @Size(max = 64) String entityType,
            @RequestParam(required = false)
            @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int size,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.entities(
                user, generation, query, entityType, cursor, page, size
        );
    }

    @GetMapping("/entities/{entityId}")
    GraphEntityDetail entity(
            @PathVariable UUID entityId,
            @RequestParam(required = false) Long generation,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.entity(user, generation, entityId);
    }

    @GetMapping("/communities")
    GraphCommunityPage communities(
            @RequestParam(required = false) Long generation,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int size,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.communities(user, generation, page, size);
    }

    @GetMapping("/communities/{communityId}")
    GraphCommunityDetail community(
            @PathVariable UUID communityId,
            @RequestParam(required = false) Long generation,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return queries.community(
                user,
                generation,
                communityId
        );
    }

    @GetMapping("/subgraph")
    GraphSubgraphView subgraph(
            @RequestParam long generation,
            @RequestParam GraphRootType rootType,
            @RequestParam UUID rootId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(2) int hops,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return visualization.subgraph(
                user,
                generation,
                rootType,
                rootId,
                hops
        );
    }

    @GetMapping("/relationships/{relationshipId}")
    GraphRelationshipView relationship(
            @PathVariable UUID relationshipId,
            @RequestParam long generation,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        return visualization.relationship(
                user,
                generation,
                relationshipId
        );
    }
}
