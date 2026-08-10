package com.example.rag.chat;

import com.example.rag.chat.QueryIntelligenceContracts.ProfileView;
import com.example.rag.graph.GraphRouteAvailabilityService;
import com.example.rag.graph.GraphRouteAvailabilityService.Availability;
import com.example.rag.search.SearchContracts.GraphMode;
import com.example.rag.search.SearchService.QueryExecutionPolicy;
import com.example.rag.search.SearchService.QueryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRoutingServiceTests {

    private final ChatQueryPlanner planner = mock(ChatQueryPlanner.class);
    private final QueryIntelligenceProfileService profiles =
            mock(QueryIntelligenceProfileService.class);
    private final GraphRouteAvailabilityService availability =
            mock(GraphRouteAvailabilityService.class);
    private QueryRoutingService service;

    @BeforeEach
    void setUp() {
        service = new QueryRoutingService(
                planner, profiles, availability
        );
    }

    @Test
    void explicitModeBypassesRouter() {
        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.LOCAL_GRAPH, null
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.LOCAL_GRAPH);
        assertThat(result.routing().routerCallCount()).isZero();
        verify(planner, never()).initial(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void incompatibleProfileFallsBackToHybridBeforeModelCall() {
        ProfileView profile = mock(ProfileView.class);
        when(profile.enabled()).thenReturn(true);
        when(profiles.matchesRuntime(profile)).thenReturn(false);

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.HYBRID);
        assertThat(result.routing().degraded()).isTrue();
        assertThat(result.routing().degradationCode())
                .isEqualTo("ROUTER_PROFILE_UNAVAILABLE");
        verify(planner, never()).initial(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void unavailableGlobalPrerequisiteFallsBackToHybrid() {
        ProfileView profile = compatibleProfile();
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.AUTO),
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.any(
                        QueryExecutionPolicy.class)
        )).thenReturn(new QueryPlan(
                "question", List.of("question"), 1, false, null,
                GraphMode.GLOBAL_GRAPH, "GLOBAL_SYNTHESIS"
        ));
        when(availability.current()).thenReturn(
                new Availability(true, false)
        );

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.HYBRID);
        assertThat(result.routing().routerCallCount()).isEqualTo(1);
        assertThat(result.routing().degradationCode()).isEqualTo(
                "ROUTER_GLOBAL_PREREQUISITE_UNAVAILABLE"
        );
    }

    @Test
    void availableGlobalRouteIsUsedOnce() {
        ProfileView profile = compatibleProfile();
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.AUTO),
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.any(
                        QueryExecutionPolicy.class)
        )).thenReturn(new QueryPlan(
                "question", List.of("question"), 1, false, null,
                GraphMode.GLOBAL_GRAPH, "GLOBAL_SYNTHESIS"
        ));
        when(availability.current()).thenReturn(
                new Availability(true, true)
        );

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.GLOBAL_GRAPH);
        assertThat(result.routing().routerCallCount()).isEqualTo(1);
        assertThat(result.routing().degraded()).isFalse();
    }

    @Test
    void compatibleProfileFreezesItsExactRuntimeBudget() {
        ProfileView profile = compatibleProfile();
        when(profile.maxSubQueries()).thenReturn(1);
        when(profile.maxRetrievalRounds()).thenReturn(1);
        when(profile.plannerCallLimit()).thenReturn(1);
        when(profile.timeoutMs()).thenReturn(750);
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.HYBRID),
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.any(
                        QueryExecutionPolicy.class)
        )).thenReturn(QueryPlan.single("question"));

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.HYBRID, profile
        );

        assertThat(result.policy().maxSubQueries()).isEqualTo(1);
        assertThat(result.policy().maxRetrievalRounds()).isEqualTo(1);
        assertThat(result.policy().plannerCallLimit()).isEqualTo(1);
        assertThat(result.policy().timeoutMs()).isEqualTo(750);
        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.HYBRID);
        assertThat(result.routing().routerCallCount()).isZero();
    }

    @Test
    void retrievalBudgetStartsAfterInitialPlanning() {
        ProfileView profile = compatibleProfile();
        ArgumentCaptor<QueryExecutionPolicy> plannerPolicy =
                ArgumentCaptor.forClass(QueryExecutionPolicy.class);
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.LOCAL_GRAPH),
                org.mockito.ArgumentMatchers.eq(profile),
                plannerPolicy.capture()
        )).thenAnswer(invocation -> {
            Thread.sleep(25);
            return QueryPlan.single("question");
        });

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.LOCAL_GRAPH, profile
        );

        assertThat(result.policy().deadlineNanos())
                .isGreaterThan(plannerPolicy.getValue().deadlineNanos());
        assertThat(result.policy().remainingNanos())
                .isGreaterThan(TimeUnit.MILLISECONDS.toNanos(2_900));
    }

    @Test
    void autoWithDisabledPlannerFallsBackExplicitlyToHybrid() {
        ProfileView profile = compatibleProfile();
        when(profile.plannerCallLimit()).thenReturn(0);

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.HYBRID);
        assertThat(result.routing().degraded()).isTrue();
        assertThat(result.routing().degradationCode())
                .isEqualTo("ROUTER_PLANNER_DISABLED");
        assertThat(result.queryPlan().plannerCallCount()).isZero();
        assertThat(result.queryPlan().degradationCode())
                .isEqualTo("QUERY_PLANNER_DISABLED");
        verify(planner, never()).initial(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void initialPlannerUsesItsFullProfileTimeoutBeforeRecallStarts() {
        QueryExecutionPolicy normal = QueryExecutionPolicy.start(
                3, 2, 2, 1_000
        );
        long initialTimeout =
                normal.initialPlannerHttpPhaseTimeoutNanos();
        long refinementTimeout = normal.plannerHttpPhaseTimeoutNanos();

        assertThat(initialTimeout).isPositive();
        assertThat(initialTimeout)
                .isGreaterThan(refinementTimeout)
                .isLessThanOrEqualTo(
                        TimeUnit.MILLISECONDS.toNanos(1_000)
                );
        assertThat(refinementTimeout)
                .isLessThanOrEqualTo(
                        TimeUnit.MILLISECONDS.toNanos(250)
                );

        QueryExecutionPolicy shortRequest =
                QueryExecutionPolicy.start(1, 1, 1, 400);
        assertThat(shortRequest.plannerHttpPhaseTimeoutNanos())
                .isZero();
    }

    @Test
    void reservedPlannerBudgetHasStableAutoDegradationCode() {
        ProfileView profile = compatibleProfile();
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.AUTO),
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.any(
                        QueryExecutionPolicy.class)
        )).thenReturn(new QueryPlan(
                "question",
                List.of("question"),
                0,
                true,
                "QUERY_PLANNER_BUDGET_RESERVED",
                GraphMode.HYBRID,
                "SAFE_FALLBACK"
        ));

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(result.routing().selectedMode())
                .isEqualTo(GraphMode.HYBRID);
        assertThat(result.routing().routerCallCount()).isZero();
        assertThat(result.routing().degradationCode())
                .isEqualTo("ROUTER_PLANNER_BUDGET_RESERVED");
    }

    @Test
    void degradedInitialPlanCannotCreateARefinementPlanner() {
        ProfileView profile = compatibleProfile();
        QueryPlan degraded = new QueryPlan(
                "question",
                List.of("question"),
                1,
                true,
                "QUERY_PLANNER_FAILED",
                GraphMode.HYBRID,
                "SAFE_FALLBACK"
        );
        when(planner.initial(
                org.mockito.ArgumentMatchers.eq("question"),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(GraphMode.AUTO),
                org.mockito.ArgumentMatchers.eq(profile),
                org.mockito.ArgumentMatchers.any(
                        QueryExecutionPolicy.class)
        )).thenReturn(degraded);

        QueryRoutingService.PlannedRequest result = service.plan(
                "question", List.of(), GraphMode.AUTO, profile
        );

        assertThat(service.secondRoundPlanner(
                "question",
                List.of(),
                result.queryPlan(),
                result.profile(),
                result.policy()
        )).isNull();
    }

    private ProfileView compatibleProfile() {
        ProfileView profile = mock(ProfileView.class);
        when(profile.enabled()).thenReturn(true);
        when(profiles.matchesRuntime(profile)).thenReturn(true);
        when(profile.maxSubQueries()).thenReturn(3);
        when(profile.maxRetrievalRounds()).thenReturn(2);
        when(profile.plannerCallLimit()).thenReturn(2);
        when(profile.timeoutMs()).thenReturn(3_000);
        return profile;
    }
}
