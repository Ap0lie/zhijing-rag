package com.example.rag.graph;

import com.example.rag.common.ApiException;
import com.example.rag.graph.GraphApiContracts.CreateGraphConfigRequest;
import com.example.rag.graph.GraphApiContracts.CreateResolutionRuleRequest;
import com.example.rag.graph.GraphApiContracts.GraphConfigView;
import com.example.rag.graph.GraphApiContracts.GraphExtractionStatus;
import com.example.rag.graph.GraphApiContracts.GraphGenerationView;
import com.example.rag.graph.GraphApiContracts.GraphOverviewResponse;
import com.example.rag.graph.GraphApiContracts.ReleaseGraphGenerationRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewRequest;
import com.example.rag.graph.GraphApiContracts.ResolutionRulePreviewResponse;
import com.example.rag.graph.GraphApiContracts.StartGraphBuildRequest;
import com.example.rag.graph.GraphAssembler.ParentExtraction;
import com.example.rag.graph.GraphBuildContracts.ClaimedGeneration;
import com.example.rag.graph.GraphBuildContracts.ExtractionArtifact;
import com.example.rag.graph.GraphBuildContracts.GraphBuild;
import com.example.rag.graph.GraphBuildContracts.GraphConfig;
import com.example.rag.graph.GraphBuildContracts.ParentSource;
import com.example.rag.graph.GraphBuildContracts.ResolutionRule;
import com.example.rag.graph.GraphBuildContracts.SourceDocument;
import com.example.rag.graph.GraphExtractionProvider.ChildEvidence;
import com.example.rag.graph.GraphExtractionProvider.Descriptor;
import com.example.rag.graph.GraphExtractionProvider.ExtractionInput;
import com.example.rag.graph.GraphExtractionProvider.ExtractionResult;
import com.example.rag.graph.GraphGenerationRepository.ManifestRow;
import com.example.rag.graph.GraphGenerationRepository.SourceSize;
import com.example.rag.projection.GenerationRecoveryProgress;
import com.example.rag.projection.ProjectionClosureStatus;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class GraphGenerationService {

    private final GraphGenerationRepository repository;
    private final GraphExtractionProvider extraction;
    private final GraphAssembler assembler;
    private final ObjectMapper objectMapper;
    private final GraphProperties properties;
    private final GraphResolutionService resolution;
    private final GraphResolutionProposalService proposals;

    GraphGenerationService(
            GraphGenerationRepository repository,
            GraphExtractionProvider extraction,
            GraphAssembler assembler,
            ObjectMapper objectMapper,
            GraphProperties properties,
            GraphResolutionService resolution,
            GraphResolutionProposalService proposals
    ) {
        this.repository = repository;
        this.extraction = extraction;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resolution = resolution;
        this.proposals = proposals;
    }

    GraphOverviewResponse overview() {
        Descriptor descriptor = extraction.descriptor();
        List<GraphConfigView> configs = repository.configs().stream()
                .map(config -> view(config, descriptor))
                .toList();
        List<GraphGenerationView> generations = repository.manifests().stream()
                .map(this::view)
                .toList();
        return new GraphOverviewResponse(
                repository.activeGeneration(),
                new GraphExtractionStatus(
                        descriptor.enabled(),
                        descriptor.model(),
                        descriptor.revision(),
                        descriptor.promptVersion(),
                        descriptor.schemaVersion()
                ),
                configs,
                generations
        );
    }

    GraphConfigView createConfig(
            CreateGraphConfigRequest request,
            UUID actorId
    ) {
        String version = version(request.version());
        GraphConfig config = repository.createConfig(
                version,
                required(request.extractionModel(), "extractionModel"),
                required(request.extractionRevision(), "extractionRevision"),
                required(request.reason(), "reason"),
                actorId
        );
        return view(config, extraction.descriptor());
    }

    GraphConfigView createResolutionRule(
            CreateResolutionRuleRequest request,
            PlatformUserPrincipal actor
    ) {
        GraphConfig config;
        try {
            config = resolution.create(
                    request,
                    actor,
                    version(request.newConfigVersion())
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_RULE_CONFIG_CONFLICT",
                    "新 GraphConfig 或 Rule Set 版本已存在",
                    exception
            );
        }
        return view(config, extraction.descriptor());
    }

    ResolutionRulePreviewResponse previewResolutionRule(
            ResolutionRulePreviewRequest request,
            PlatformUserPrincipal actor
    ) {
        return resolution.preview(request, actor);
    }

    GraphGenerationView start(
            StartGraphBuildRequest request,
            UUID actorId
    ) {
        GraphConfig config = repository.config(
                version(request.graphConfigVersion())
        );
        var manifest = repository.start(
                config.version(),
                required(request.reason(), "reason"),
                actorId
        );
        proposals.markApplied(
                config.version(), manifest.generation(), actorId
        );
        return view(manifest);
    }

    GraphGenerationView publish(
            ReleaseGraphGenerationRequest request,
            UUID actorId
    ) {
        requireConfirmation(request.confirmation(), "PUBLISH");
        return view(repository.release(
                request.graphGeneration(),
                "READY",
                "PUBLISH",
                required(request.reason(), "reason"),
                actorId
        ));
    }

    GraphGenerationView rollback(
            ReleaseGraphGenerationRequest request,
            UUID actorId
    ) {
        requireConfirmation(request.confirmation(), "ROLLBACK");
        return view(repository.release(
                request.graphGeneration(),
                "RETIRED",
                "ROLLBACK",
                required(request.reason(), "reason"),
                actorId
        ));
    }

    Optional<ClaimedGeneration> claim() {
        return repository.claim();
    }

    boolean heartbeat(ClaimedGeneration claim) {
        return repository.heartbeat(claim);
    }

    void build(ClaimedGeneration claim, AtomicBoolean leaseValid) {
        SourceSize sourceSize = repository.sourceSize(claim.generation());
        validateSourceSize(sourceSize);
        List<SourceDocument> documents = repository.sources(
                claim.generation()
        );
        ManifestRow manifest = repository.manifest(claim.generation());
        if (documents.size() != manifest.expectedDocuments()
                || !sourceSetHash(documents).equals(manifest.sourceSetHash())) {
            throw new GraphBuildException(
                    "GRAPH_SOURCE_SET_CHANGED",
                    "Graph Generation 的冻结来源集合不完整或已变化"
            );
        }
        List<ResolutionRule> rules = repository.rules(
                claim.config().resolutionRuleSetVersion()
        );
        Map<UUID, ParentExtraction> parentExtractions =
                new LinkedHashMap<>();
        long cacheHits = 0;
        long modelCalls = 0;
        long processedDocuments = 0;

        for (SourceDocument document : documents) {
            requireLease(leaseValid);
            for (ParentSource parent : document.parents()) {
                String inputHash = inputHash(
                        claim.config(),
                        document,
                        parent
                );
                Optional<ExtractionArtifact> cached = repository.artifact(
                        parent.id(),
                        inputHash
                );
                ExtractionArtifact artifact;
                ExtractionResult result;
                if (cached.isPresent()) {
                    artifact = cached.get();
                    result = verified(artifact);
                    cacheHits++;
                } else {
                    requireRuntime(claim.config());
                    result = extraction.extract(input(document, parent));
                    validate(result);
                    String outputJson = json(result);
                    artifact = repository.saveArtifact(
                            claim.config(),
                            document,
                            parent,
                            inputHash,
                            outputJson,
                            GraphAssembler.sha256(outputJson),
                            list(result.entities()).size(),
                            list(result.relationships()).size()
                    );
                    result = verified(artifact);
                    modelCalls++;
                }
                validate(result);
                parentExtractions.put(
                        parent.id(),
                        new ParentExtraction(
                                artifact.id(),
                                inputHash,
                                result
                        )
                );
            }
            processedDocuments++;
            if (!repository.progress(
                    claim,
                    processedDocuments,
                    cacheHits,
                    modelCalls
            )) {
                throw leaseLost();
            }
        }
        requireLease(leaseValid);
        GraphBuild build = assembler.assemble(
                claim.generation(),
                claim.config(),
                documents,
                parentExtractions,
                rules,
                cacheHits,
                modelCalls
        );
        requireLease(leaseValid);
        repository.persistReady(claim, build);
    }

    void fail(ClaimedGeneration claim, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String code;
        if (cause instanceof GraphExtractionException exception) {
            code = exception.code();
        } else if (cause instanceof CommunityDetectionException exception) {
            code = exception.code();
        } else if (cause instanceof GraphBuildException exception) {
            code = exception.code();
        } else if (cause instanceof ApiException exception) {
            code = exception.getCode();
        } else {
            code = "GRAPH_BUILD_FAILED";
        }
        repository.fail(
                claim,
                code,
                concise(cause.getMessage())
        );
    }

    void cleanupExpired() {
        repository.cleanupExpired();
    }

    private ExtractionInput input(
            SourceDocument document,
            ParentSource parent
    ) {
        return new ExtractionInput(
                document.documentId(),
                document.revisionId(),
                parent.id(),
                document.title(),
                path(parent.headingPath()),
                parent.text(),
                parent.children().stream()
                        .map(child -> new ChildEvidence(
                                child.id(),
                                path(child.headingPath()),
                                child.text()
                        ))
                        .toList()
        );
    }

    private String inputHash(
            GraphConfig config,
            SourceDocument document,
            ParentSource parent
    ) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("model", config.extractionModel());
        contract.put("revision", config.extractionRevision());
        contract.put("promptVersion", config.promptVersion());
        contract.put("schemaVersion", config.schemaVersion());
        contract.put("input", input(document, parent));
        return GraphAssembler.sha256(json(contract));
    }

    private ExtractionResult parse(String value) {
        try {
            return objectMapper.readValue(value, ExtractionResult.class);
        } catch (JsonProcessingException exception) {
            throw new GraphBuildException(
                    "GRAPH_ARTIFACT_INVALID",
                    "缓存的知识图谱抽取 Artifact 无效",
                    exception
            );
        }
    }

    private ExtractionResult verified(ExtractionArtifact artifact) {
        ExtractionResult result = parse(artifact.outputJson());
        validate(result);
        String canonical = json(result);
        if (!GraphAssembler.sha256(canonical).equals(artifact.outputHash())
                || list(result.entities()).size() != artifact.entityCount()
                || list(result.relationships()).size()
                != artifact.relationshipCount()) {
            throw new GraphBuildException(
                    "GRAPH_ARTIFACT_CHECKSUM_MISMATCH",
                    "缓存的知识图谱抽取 Artifact 校验失败"
            );
        }
        return result;
    }

    private void validateSourceSize(SourceSize size) {
        if (size.documents() > properties.getMaxDocuments()
                || size.parents() > properties.getMaxParents()
                || size.characters() > properties.getMaxSourceCharacters()) {
            throw new GraphBuildException(
                    "GRAPH_BUILD_LIMIT_EXCEEDED",
                    "Graph Generation 超过文档、Parent 或文本安全上限"
            );
        }
    }

    private static String sourceSetHash(List<SourceDocument> documents) {
        StringBuilder input = new StringBuilder();
        documents.stream()
                .sorted((left, right) -> left.documentId().compareTo(
                        right.documentId()
                ))
                .forEach(document -> input.append(document.documentId())
                        .append('|')
                        .append(document.revisionId())
                        .append('|')
                        .append(document.aclVersion())
                        .append('\n'));
        return GraphAssembler.sha256(input.toString());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new GraphBuildException(
                    "GRAPH_SERIALIZATION_FAILED",
                    "知识图谱构建数据无法序列化",
                    exception
            );
        }
    }

    private static void validate(ExtractionResult result) {
        if (result == null
                || list(result.entities()).size() > 200
                || list(result.relationships()).size() > 400) {
            throw new GraphBuildException(
                    "GRAPH_EXTRACTION_LIMIT_EXCEEDED",
                    "单个 Parent 的图谱抽取结果超过安全限制"
            );
        }
        for (var entity : list(result.entities())) {
            if (entity == null
                    || list(entity.aliases()).size() > 50
                    || list(entity.mentions()).size() > 100) {
                throw new GraphBuildException(
                        "GRAPH_EXTRACTION_LIMIT_EXCEEDED",
                        "实体别名或 Mention 数量超过安全限制"
                );
            }
        }
    }

    private void requireRuntime(GraphConfig config) {
        Descriptor descriptor = extraction.descriptor();
        if (!compatible(config, descriptor)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GRAPH_EXTRACTION_CONFIGURATION_MISMATCH",
                    "GraphConfig 与当前抽取模型、Revision、Prompt 或 Schema 不匹配"
            );
        }
    }

    private GraphGenerationView view(ManifestRow row) {
        long attempts = row.cacheHits() + row.modelCalls();
        ProjectionClosureStatus closure = repository.closure(row.generation());
        return new GraphGenerationView(
                row.id(),
                row.generation(),
                row.configVersion(),
                row.status(),
                row.expectedDocuments(),
                row.projectedDocuments(),
                row.entities(),
                row.mentions(),
                row.relationships(),
                row.relationshipEvidence(),
                row.communities(),
                row.communityClaims(),
                row.cacheHits(),
                row.modelCalls(),
                attempts == 0 ? 0.0 : (double) row.cacheHits() / attempts,
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

    private static GraphConfigView view(
            GraphConfig config,
            Descriptor descriptor
    ) {
        return new GraphConfigView(
                config.version(),
                config.extractionModel(),
                config.extractionRevision(),
                config.promptVersion(),
                config.schemaVersion(),
                config.normalizationVersion(),
                config.resolutionRuleSetVersion(),
                config.communityAlgorithm(),
                config.communityAlgorithmVersion(),
                config.communitySeed(),
                config.communityResolution(),
                config.reason(),
                compatible(config, descriptor),
                config.createdAt()
        );
    }

    private static boolean compatible(
            GraphConfig config,
            Descriptor descriptor
    ) {
        return descriptor.enabled()
                && config.extractionModel().equals(descriptor.model())
                && config.extractionRevision().equals(descriptor.revision())
                && config.promptVersion().equals(descriptor.promptVersion())
                && config.schemaVersion().equals(descriptor.schemaVersion());
    }

    private static boolean releasable(String status) {
        return "READY".equals(status)
                || "ACTIVE".equals(status)
                || "RETIRED".equals(status);
    }

    private static List<String> path(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static void requireLease(AtomicBoolean leaseValid) {
        if (!leaseValid.get() || Thread.currentThread().isInterrupted()) {
            throw leaseLost();
        }
    }

    private static GraphBuildException leaseLost() {
        return new GraphBuildException(
                "GRAPH_BUILD_LEASE_LOST",
                "Graph Generation 构建租约已失效"
        );
    }

    private static String version(String value) {
        String result = required(value, "version");
        if (!result.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_VERSION_INVALID",
                    "版本只能包含字母、数字、点、下划线和连字符"
            );
        }
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_FIELD_REQUIRED",
                    field + " 不能为空"
            );
        }
        return value.trim();
    }

    private static void requireConfirmation(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "GRAPH_CONFIRMATION_INVALID",
                    "确认字段必须为 " + expected
            );
        }
    }

    private static String concise(String value) {
        String result = value == null || value.isBlank()
                ? "Graph Generation build failed"
                : value.trim();
        return result.substring(0, Math.min(result.length(), 1000));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current instanceof java.util.concurrent.ExecutionException) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }
}

final class GraphBuildException extends RuntimeException {

    private final String code;

    GraphBuildException(String code, String message) {
        super(message);
        this.code = code;
    }

    GraphBuildException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
