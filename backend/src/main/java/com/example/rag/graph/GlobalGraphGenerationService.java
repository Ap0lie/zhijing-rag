package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GlobalGraphContracts.ConfigView;
import com.example.rag.graph.GlobalGraphContracts.CreateConfigRequest;
import com.example.rag.graph.GlobalGraphContracts.GenerationView;
import com.example.rag.graph.GlobalGraphContracts.GlobalConfig;
import com.example.rag.graph.GlobalGraphContracts.ManifestRow;
import com.example.rag.graph.GlobalGraphContracts.OverviewResponse;
import com.example.rag.graph.GlobalGraphContracts.ReleaseRequest;
import com.example.rag.graph.GlobalGraphContracts.RuntimeStatus;
import com.example.rag.graph.GlobalGraphContracts.StartBuildRequest;
import com.example.rag.graph.GlobalReportProvider.Descriptor;
import com.example.rag.search.GlobalReportIndexService;
import com.example.rag.search.GlobalReportIndexService.ReportDocument;
import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
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
class GlobalGraphGenerationService {

    private final GlobalGraphRepository repository;
    private final GlobalGraphAssembler assembler;
    private final GlobalReportProvider provider;
    private final GlobalReportIndexService indexes;

    GlobalGraphGenerationService(
            GlobalGraphRepository repository,
            GlobalGraphAssembler assembler,
            GlobalReportProvider provider,
            GlobalReportIndexService indexes
    ) {
        this.repository = repository;
        this.assembler = assembler;
        this.provider = provider;
        this.indexes = indexes;
    }

    OverviewResponse overview() {
        Descriptor descriptor = provider.descriptor();
        return new OverviewResponse(
                repository.activeGeneration(),
                new RuntimeStatus(
                        descriptor.enabled(),
                        descriptor.model(),
                        descriptor.revision(),
                        descriptor.promptVersion(),
                        descriptor.schemaVersion()
                ),
                repository.configs().stream()
                        .map(config -> view(config, descriptor))
                        .toList(),
                repository.manifests().stream()
                        .map(this::view)
                        .toList()
        );
    }

    ConfigView createConfig(
            CreateConfigRequest request,
            UUID actorId
    ) {
        requireConfirmation(request.confirmation(), "CREATE");
        String version = version(request.version());
        String reason = required(request.reason(), "reason");
        Descriptor descriptor = provider.descriptor();
        return view(
                repository.createConfig(
                        version,
                        reason,
                        actorId,
                        descriptor
                ),
                descriptor
        );
    }

    GenerationView start(
            StartBuildRequest request,
            UUID actorId
    ) {
        requireConfirmation(request.confirmation(), "BUILD");
        String configVersion = version(request.globalConfigVersion());
        GlobalConfig config = repository.config(configVersion);
        requireCompatible(config);
        return view(repository.start(
                configVersion,
                required(request.reason(), "reason"),
                actorId
        ));
    }

    GenerationView publish(ReleaseRequest request, UUID actorId) {
        requireConfirmation(request.confirmation(), "PUBLISH");
        return view(repository.release(
                request.globalGeneration(),
                actorId,
                required(request.reason(), "reason"),
                "PUBLISH"
        ));
    }

    GenerationView rollback(ReleaseRequest request, UUID actorId) {
        requireConfirmation(request.confirmation(), "ROLLBACK");
        return view(repository.release(
                request.globalGeneration(),
                actorId,
                required(request.reason(), "reason"),
                "ROLLBACK"
        ));
    }

    Optional<GlobalGraphContracts.ClaimedGeneration> claim() {
        return repository.claim();
    }

    boolean heartbeat(GlobalGraphContracts.ClaimedGeneration claim) {
        return repository.heartbeat(claim);
    }

    void build(
            GlobalGraphContracts.ClaimedGeneration claim,
            AtomicBoolean leaseValid
    ) {
        requireCompatible(claim.config());
        requireLease(leaseValid);
        if (!repository.caughtUp(claim.generation())) {
            throw new GlobalReportException(
                    "GLOBAL_SOURCE_STALE",
                    "Global Generation 来源图、Revision 或 ACL 已变化"
            );
        }
        var artifacts = repository.artifacts(claim);
        var evidence = repository.evidence(claim);
        requireLease(leaseValid);
        var build = assembler.assemble(
                claim.generation(),
                claim.config(),
                artifacts,
                evidence
        );
        requireLease(leaseValid);
        GlobalReportIndexService.BuildResult index = indexes.build(
                claim.indexName(),
                claim.config().indexConfigVersion(),
                build.reports().stream()
                        .map(report -> new ReportDocument(
                                report.id(),
                                claim.generation(),
                                report.communityKey(),
                                report.title(),
                                report.summary(),
                                report.searchText(),
                                report.contentHash()
                        ))
                        .toList()
        );
        requireLease(leaseValid);
        repository.persistReady(claim, build, index);
    }

    void fail(
            GlobalGraphContracts.ClaimedGeneration claim,
            Throwable throwable
    ) {
        Throwable cause = unwrap(throwable);
        String code;
        if (cause instanceof GlobalReportException exception) {
            code = exception.code();
        } else if (cause instanceof CommunityDetectionException exception) {
            code = exception.code();
        } else if (cause instanceof ApiException exception) {
            code = exception.getCode();
        } else {
            code = "GLOBAL_BUILD_FAILED";
        }
        try {
            indexes.delete(claim.indexName());
        } catch (RuntimeException ignored) {
            // The physical index is a rebuildable projection.
        }
        repository.fail(claim, code, cause.getMessage());
    }

    void cleanupExpired() {
        for (String index : repository.expiredIndexes()) {
            indexes.delete(index);
            repository.markDeleted(index);
        }
    }

    private GenerationView view(ManifestRow row) {
        ProjectionClosureStatus closure = repository.closure(row.generation());
        return new GenerationView(
                row.id(),
                row.generation(),
                row.configVersion(),
                row.sourceGraphGeneration(),
                row.indexName(),
                row.status(),
                row.expectedSources(),
                row.reports(),
                row.claims(),
                row.evidence(),
                row.indexedReports(),
                row.validVectors(),
                row.modelCalls(),
                releasable(row.status()) && closure.ready(),
                closure,
                GenerationRecoveryProgress.of(
                        row.status(), row.attempt(),
                        row.heartbeatAt(), row.leaseExpiresAt()
                ),
                row.attempt(),
                row.failureCode(),
                row.failureReason(),
                row.buildReason(),
                row.createdAt(),
                row.startedAt(),
                row.completedAt(),
                row.retentionUntil(),
                row.updatedAt()
        );
    }

    private static ConfigView view(
            GlobalConfig config,
            Descriptor descriptor
    ) {
        return new ConfigView(
                config.version(),
                config.reportModel(),
                config.reportRevision(),
                config.promptVersion(),
                config.schemaVersion(),
                config.communityAlgorithm(),
                config.communityAlgorithmVersion(),
                config.communitySeed(),
                config.communityResolution(),
                config.indexConfigVersion(),
                config.bm25TopK(),
                config.vectorTopK(),
                config.rrfRankConstant(),
                config.reportLimit(),
                config.contextTokenBudget(),
                config.mapCallLimit(),
                config.modelCallLimit(),
                config.hardTimeoutMs(),
                config.statementTimeoutMs(),
                config.reason(),
                compatible(config, descriptor),
                config.createdAt()
        );
    }

    private void requireCompatible(GlobalConfig config) {
        if (!compatible(config, provider.descriptor())) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GLOBAL_CONFIG_RUNTIME_MISMATCH",
                    "GlobalGraphConfig 与当前报告模型、Revision、Prompt 或 Schema 不匹配"
            );
        }
    }

    private static boolean compatible(
            GlobalConfig config,
            Descriptor descriptor
    ) {
        return descriptor.enabled()
                && config.reportModel().equals(descriptor.model())
                && config.reportRevision().equals(descriptor.revision())
                && config.promptVersion().equals(
                        descriptor.promptVersion()
                )
                && config.schemaVersion().equals(
                        descriptor.schemaVersion()
                );
    }

    private static boolean releasable(String status) {
        return "READY".equals(status)
                || "ACTIVE".equals(status)
                || "RETIRED".equals(status);
    }

    private static void requireLease(AtomicBoolean leaseValid) {
        if (!leaseValid.get() || Thread.currentThread().isInterrupted()) {
            throw new GlobalReportException(
                    "GLOBAL_BUILD_LEASE_LOST",
                    "Global Generation 构建租约已失效"
            );
        }
    }

    private static String version(String value) {
        String result = required(value, "version");
        if (!result.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GLOBAL_VERSION_INVALID",
                    "版本只能包含字母、数字、点、下划线和连字符"
            );
        }
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GLOBAL_FIELD_REQUIRED",
                    field + " 不能为空"
            );
        }
        return value.trim();
    }

    private static void requireConfirmation(
            String actual,
            String expected
    ) {
        if (!expected.equals(actual)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GLOBAL_CONFIRMATION_INVALID",
                    "确认字段必须为 " + expected
            );
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current instanceof java.util.concurrent.ExecutionException) {
            current = current.getCause();
        }
        return current;
    }
}
