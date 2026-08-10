package com.example.rag.chat;

import com.example.rag.chat.ChatModelProvider.ModelHistoryMessage;
import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.graph.GraphRouteAvailabilityService;
import com.example.rag.graph.GraphRouteAvailabilityService.Availability;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchService.Coverage;
import com.example.rag.search.SearchService.QueryExecutionPolicy;
import com.example.rag.search.SearchService.QueryPlan;
import com.example.rag.search.SearchService.RoutingDecision;
import com.example.rag.search.SearchService.SecondRoundPlanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(
        prefix = "rag.chat",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class QueryRoutingService {

    private final ChatQueryPlanner planner;
    private final QueryIntelligenceProfileService profiles;
    private final GraphRouteAvailabilityService availability;

    QueryRoutingService(
            ChatQueryPlanner planner,
            QueryIntelligenceProfileService profiles,
            GraphRouteAvailabilityService availability
    ) {
        this.planner = planner;
        this.profiles = profiles;
        this.availability = availability;
    }

    public PlannedRequest plan(
            String question,
            List<ModelHistoryMessage> history,
            GraphMode requestedMode,
            ProfileView profile
    ) {
        GraphMode requested = requestedMode == null
                ? GraphMode.HYBRID : requestedMode;
        if (profile == null || !profile.enabled()
                || !profiles.matchesRuntime(profile)) {
            return new PlannedRequest(
                    QueryPlan.single(question),
                    requested == GraphMode.AUTO
                            ? RoutingDecision.fallback(
                            "ROUTER_PROFILE_UNAVAILABLE")
                            : RoutingDecision.explicit(requested),
                    null,
                    null
            );
        }
        QueryExecutionPolicy policy = QueryExecutionPolicy.start(
                profile.maxSubQueries(),
                profile.maxRetrievalRounds(),
                profile.plannerCallLimit(),
                profile.timeoutMs()
        );
        if (profile.plannerCallLimit() == 0) {
            if (requested != GraphMode.AUTO) {
                return new PlannedRequest(
                        QueryPlan.single(question),
                        RoutingDecision.explicit(requested),
                        profile,
                        policy
                );
            }
            return new PlannedRequest(
                    new QueryPlan(
                            question,
                            List.of(question),
                            0,
                            true,
                            "QUERY_PLANNER_DISABLED",
                            GraphMode.HYBRID,
                            "SAFE_FALLBACK"
                    ),
                    RoutingDecision.fallback(
                            "ROUTER_PLANNER_DISABLED"
                    ),
                    profile,
                    policy
            );
        }
        QueryPlan plan = planner.initial(
                question,
                history == null ? List.of() : history,
                requested,
                profile,
                policy
        );
        policy = QueryExecutionPolicy.start(
                profile.maxSubQueries(),
                profile.maxRetrievalRounds(),
                profile.plannerCallLimit(),
                profile.timeoutMs()
        );
        return new PlannedRequest(
                plan, decide(requested, plan), profile, policy
        );
    }

    public PlannedRequest planActive(
            String question,
            GraphMode requestedMode
    ) {
        ProfileView profile = profiles.active();
        return plan(question, List.of(), requestedMode, profile);
    }

    public QueryPlan refine(
            String question,
            List<ModelHistoryMessage> history,
            List<String> attemptedQueries,
            Coverage coverage,
            ProfileView profile,
            QueryExecutionPolicy policy
    ) {
        return planner.refine(
                question, history, attemptedQueries, coverage,
                profile, policy
        );
    }

    public SecondRoundPlanner secondRoundPlanner(
            String question,
            List<ModelHistoryMessage> history,
            QueryPlan initial,
            ProfileView profile,
            QueryExecutionPolicy policy
    ) {
        if (profile == null || policy == null || initial.degraded()
                || profile.maxRetrievalRounds() < 2
                || initial.plannerCallCount()
                >= profile.plannerCallLimit()) {
            return null;
        }
        List<String> attempted = new ArrayList<>(initial.queries());
        return coverage -> {
            QueryPlan refinement = refine(
                    question,
                    history == null ? List.of() : history,
                    List.copyOf(attempted),
                    coverage,
                    profile,
                    policy
            );
            attempted.addAll(refinement.queries());
            return refinement;
        };
    }

    private RoutingDecision decide(
            GraphMode requested,
            QueryPlan plan
    ) {
        if (requested != GraphMode.AUTO) {
            return RoutingDecision.explicit(requested);
        }
        if (plan.degraded()) {
            return new RoutingDecision(
                    GraphMode.HYBRID,
                    Math.min(1, plan.plannerCallCount()),
                    "SAFE_FALLBACK",
                    true,
                    "QUERY_PLANNER_BUDGET_RESERVED".equals(
                            plan.degradationCode())
                            ? "ROUTER_PLANNER_BUDGET_RESERVED"
                            : "ROUTER_FAILED"
            );
        }
        GraphMode selected = plan.routedMode() == null
                ? GraphMode.HYBRID : plan.routedMode();
        Availability current = availability.current();
        if (selected == GraphMode.GLOBAL_GRAPH
                && !current.globalAvailable()) {
            return unavailable(
                    plan, "ROUTER_GLOBAL_PREREQUISITE_UNAVAILABLE"
            );
        }
        if (selected == GraphMode.LOCAL_GRAPH
                && !current.localAvailable()) {
            return unavailable(
                    plan, "ROUTER_LOCAL_PREREQUISITE_UNAVAILABLE"
            );
        }
        return new RoutingDecision(
                selected,
                Math.min(1, plan.plannerCallCount()),
                plan.routeReasonCode() == null
                        ? "UNCLASSIFIED" : plan.routeReasonCode(),
                false,
                null
        );
    }

    private static RoutingDecision unavailable(
            QueryPlan plan,
            String code
    ) {
        return new RoutingDecision(
                GraphMode.HYBRID,
                Math.min(1, plan.plannerCallCount()),
                "SAFE_FALLBACK",
                true,
                code
        );
    }

    public record PlannedRequest(
            QueryPlan queryPlan,
            RoutingDecision routing,
            ProfileView profile,
            QueryExecutionPolicy policy
    ) {
    }
}
