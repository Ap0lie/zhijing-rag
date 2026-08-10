package com.example.rag.search;

import com.example.rag.chat.QueryRoutingService;
import com.example.rag.chat.QueryRoutingService.PlannedRequest;
import com.example.rag.search.SearchContracts.SearchPage;
import com.example.rag.search.SearchContracts.SearchRequest;
import com.example.rag.security.PlatformUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/search")
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SearchController {

    private final SearchService search;
    private final Optional<QueryRoutingService> routing;

    SearchController(
            SearchService search,
            Optional<QueryRoutingService> routing
    ) {
        this.search = search;
        this.routing = routing;
    }

    @PostMapping
    SearchPage search(
            @Valid @RequestBody SearchRequest request,
            @AuthenticationPrincipal PlatformUserPrincipal user
    ) {
        if (routing.isEmpty()) {
            return search.search(request, user);
        }
        QueryRoutingService queryRouting = routing.get();
        PlannedRequest planned = queryRouting.planActive(
                request.query(), request.requestedGraphMode()
        );
        if (planned.profile() == null) {
            return search.search(request, user);
        }
        return search.searchPlanned(
                request,
                user,
                null,
                planned.queryPlan(),
                queryRouting.secondRoundPlanner(
                        request.query(),
                        java.util.List.of(),
                        planned.queryPlan(),
                        planned.profile(),
                        planned.policy()
                ),
                planned.routing(),
                planned.policy()
        );
    }
}
