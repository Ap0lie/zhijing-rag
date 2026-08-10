package com.example.rag.search;

import com.example.rag.search.EmbeddingArtifactRepository.ArtifactModelSummary;
import com.example.rag.search.EmbeddingArtifactRepository.ArtifactWrite;
import com.example.rag.search.EmbeddingArtifactRepository.ClearResult;
import com.example.rag.search.EmbeddingArtifactRepository.StoredArtifact;
import com.example.rag.search.EmbeddingCacheContracts.ArtifactCacheStats;
import com.example.rag.search.EmbeddingCacheContracts.ClearEmbeddingCacheResponse;
import com.example.rag.search.EmbeddingCacheContracts.EmbeddingCacheStatsResponse;
import com.example.rag.search.EmbeddingCacheContracts.ModelCacheStats;
import com.example.rag.search.EmbeddingCacheContracts.QueryCacheStats;
import com.example.rag.search.ModelCircuitBreakers.ModelType;
import com.example.rag.search.RetrievalConfigurationContracts.IndexConfigView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

@Service
@ConditionalOnProperty(prefix = "rag.search", name = "enabled", havingValue = "true")
class EmbeddingCacheService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheService.class);
    private static final String CHILD_INDEX = "CHILD_INDEX";
    private static final String GLOBAL_REPORT_INDEX =
            "GLOBAL_REPORT_INDEX";
    private static final String QUERY_RETRIEVAL = "QUERY_RETRIEVAL";

    private final EmbeddingProvider provider;
    private final EmbeddingArtifactRepository artifacts;
    private final ModelCircuitBreakers circuits;
    private final SearchProperties properties;
    private final Cache<QueryCacheKey, byte[]> queryCache;
    private final ExecutorService indexEmbeddingExecutor;
    private final ConcurrentHashMap<QueryCacheKey, CompletableFuture<List<Double>>> inFlight =
            new ConcurrentHashMap<>();
    private final LongAdder queryCoalesced = new LongAdder();
    private final LongAdder queryModelCalls = new LongAdder();
    private final LongAdder querySavedModelCalls = new LongAdder();
    private final LongAdder artifactHits = new LongAdder();
    private final LongAdder artifactMisses = new LongAdder();
    private final LongAdder artifactEvictions = new LongAdder();
    private final LongAdder artifactCorruptions = new LongAdder();
    private final LongAdder artifactModelCalls = new LongAdder();
    private final LongAdder artifactSavedModelCalls = new LongAdder();

    EmbeddingCacheService(
            EmbeddingProvider provider,
            EmbeddingArtifactRepository artifacts,
            ModelCircuitBreakers circuits,
            SearchProperties properties
    ) {
        this.provider = provider;
        this.artifacts = artifacts;
        this.circuits = circuits;
        this.properties = properties;
        queryCache = Caffeine.newBuilder()
                .maximumSize(properties.getEmbeddingQueryCacheMaxEntries())
                .expireAfterWrite(properties.getEmbeddingQueryCacheTtl())
                .recordStats()
                .build();
        indexEmbeddingExecutor = Executors.newFixedThreadPool(
                properties.getEmbeddingIndexConcurrency(),
                Thread.ofVirtual().name("embedding-index-", 0).factory()
        );
    }

    List<Double> embedQuery(IndexConfigView config, String input) {
        return embedQueries(config, List.of(input)).getFirst();
    }

    List<List<Double>> embedQueries(
            IndexConfigView config,
            List<String> inputs
    ) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        EmbeddingNamespace namespace = namespace(config);
        requireCompatible(namespace);
        List<List<Double>> result = new ArrayList<>(inputs.size());
        List<QueryCacheKey> keys = new ArrayList<>(inputs.size());
        Map<QueryCacheKey, CompletableFuture<List<Double>>> waiting =
                new LinkedHashMap<>();
        Map<QueryCacheKey, CompletableFuture<List<Double>>> owned =
                new LinkedHashMap<>();
        Map<QueryCacheKey, String> inputByKey = new LinkedHashMap<>();
        for (String input : inputs) {
            QueryCacheKey key = new QueryCacheKey(
                    namespace,
                    QUERY_RETRIEVAL,
                    sha256(input)
            );
            keys.add(key);
            inputByKey.putIfAbsent(key, input);
            byte[] cached = queryCache.getIfPresent(key);
            if (cached != null) {
                querySavedModelCalls.increment();
                result.add(decode(
                        cached, sha256(cached), namespace.dimensions()
                ));
                continue;
            }
            CompletableFuture<List<Double>> created =
                    new CompletableFuture<>();
            CompletableFuture<List<Double>> current =
                    inFlight.putIfAbsent(key, created);
            if (current == null) {
                owned.put(key, created);
                result.add(null);
            } else {
                waiting.put(key, current);
                queryCoalesced.increment();
                querySavedModelCalls.increment();
                result.add(null);
            }
        }

        if (!owned.isEmpty()) {
            List<QueryCacheKey> missingKeys =
                    List.copyOf(owned.keySet());
            try {
                queryModelCalls.increment();
                List<List<Double>> generated = circuits.call(
                        ModelType.EMBEDDING,
                        () -> provider.embed(
                                missingKeys.stream()
                                        .map(inputByKey::get)
                                        .toList()
                        )
                );
                if (generated.size() != missingKeys.size()) {
                    throw new ModelResponseException(
                            "Embedding response count does not match query misses"
                    );
                }
                for (int index = 0;
                     index < missingKeys.size();
                     index++) {
                    QueryCacheKey key = missingKeys.get(index);
                    EncodedVector encoded = encode(
                            generated.get(index),
                            namespace.dimensions()
                    );
                    queryCache.put(key, encoded.bytes());
                    owned.get(key).complete(encoded.canonical());
                }
            } catch (RuntimeException exception) {
                owned.values().forEach(future ->
                        future.completeExceptionally(exception));
                throw exception;
            } finally {
                owned.forEach((key, future) ->
                        inFlight.remove(key, future));
            }
        }

        for (int index = 0; index < result.size(); index++) {
            if (result.get(index) != null) {
                continue;
            }
            QueryCacheKey key = keys.get(index);
            CompletableFuture<List<Double>> future = owned.get(key);
            if (future == null) {
                future = waiting.get(key);
            }
            result.set(index, completed(future));
        }
        return List.copyOf(result);
    }

    List<List<Double>> embedChildren(
            IndexConfigView config,
            List<ChildEmbeddingInput> inputs
    ) {
        return embedArtifacts(config, CHILD_INDEX, inputs);
    }

    List<List<Double>> embedGlobalReports(
            IndexConfigView config,
            List<ChildEmbeddingInput> inputs
    ) {
        return embedArtifacts(config, GLOBAL_REPORT_INDEX, inputs);
    }

    private List<List<Double>> embedArtifacts(
            IndexConfigView config,
            String purpose,
            List<ChildEmbeddingInput> inputs
    ) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        EmbeddingNamespace namespace = namespace(config);
        requireCompatible(namespace);
        List<PreparedChild> prepared = new ArrayList<>(inputs.size());
        Set<String> uniqueHashes = new LinkedHashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            ChildEmbeddingInput input = inputs.get(index);
            String inputHash = sha256(input.text());
            prepared.add(new PreparedChild(index, input.contentHash(), input.text(), inputHash));
            uniqueHashes.add(inputHash);
        }

        Map<String, StoredArtifact> stored;
        try {
            stored = artifacts.find(
                    namespace,
                    purpose,
                    List.copyOf(uniqueHashes)
            );
        } catch (DataAccessException exception) {
            log.warn("Embedding artifact read failed; falling back to the model", exception);
            stored = Map.of();
        }

        List<List<Double>> result = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            result.add(null);
        }
        Map<String, PreparedChild> missing = new LinkedHashMap<>();
        Set<UUID> touched = new LinkedHashSet<>();
        Set<UUID> corrupt = new LinkedHashSet<>();
        for (PreparedChild input : prepared) {
            StoredArtifact artifact = stored.get(input.inputHash());
            if (artifact == null) {
                artifactMisses.increment();
                missing.putIfAbsent(input.inputHash(), input);
                continue;
            }
            try {
                if (artifact.byteSize() != namespace.dimensions() * Float.BYTES) {
                    throw new IllegalArgumentException("Embedding artifact byte size is invalid");
                }
                List<Double> vector = decode(
                        artifact.vectorBytes(),
                        artifact.vectorChecksum(),
                        namespace.dimensions()
                );
                result.set(input.index(), vector);
                artifactHits.increment();
                touched.add(artifact.id());
            } catch (IllegalArgumentException exception) {
                artifactCorruptions.increment();
                artifactMisses.increment();
                corrupt.add(artifact.id());
                missing.putIfAbsent(input.inputHash(), input);
            }
        }
        removeCorrupt(corrupt);
        touch(touched);

        int providerBatchSize = properties.getEmbeddingBatchSize();
        long uncachedCalls =
                (uniqueHashes.size() + providerBatchSize - 1L) / providerBatchSize;
        long requiredCalls =
                (missing.size() + providerBatchSize - 1L) / providerBatchSize;
        artifactSavedModelCalls.add(uncachedCalls - requiredCalls);
        if (missing.isEmpty()) {
            return List.copyOf(result);
        }

        List<PreparedChild> modelInputs = List.copyOf(missing.values());
        Map<String, List<Double>> generatedByHash = new LinkedHashMap<>();
        List<ArtifactWrite> writes = new ArrayList<>(modelInputs.size());
        List<List<PreparedChild>> batches = new ArrayList<>();
        List<CompletableFuture<List<List<Double>>>> futures = new ArrayList<>();
        for (int start = 0; start < modelInputs.size(); start += providerBatchSize) {
            List<PreparedChild> batch = List.copyOf(modelInputs.subList(
                    start,
                    Math.min(start + providerBatchSize, modelInputs.size())
            ));
            batches.add(batch);
            futures.add(CompletableFuture.supplyAsync(() -> {
                artifactModelCalls.increment();
                return provider.embed(
                        batch.stream().map(PreparedChild::text).toList()
                );
            }, indexEmbeddingExecutor));
        }
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<PreparedChild> batch = batches.get(batchIndex);
            List<List<Double>> generated = completed(futures.get(batchIndex));
            if (generated.size() != batch.size()) {
                throw new ModelResponseException(
                        "Embedding response count does not match cache misses"
                );
            }
            for (int index = 0; index < batch.size(); index++) {
                PreparedChild input = batch.get(index);
                EncodedVector encoded = encode(
                        generated.get(index),
                        namespace.dimensions()
                );
                generatedByHash.put(
                        input.inputHash(),
                        encoded.canonical()
                );
                writes.add(new ArtifactWrite(
                        input.contentHash(),
                        input.inputHash(),
                        encoded.bytes(),
                        encoded.checksum()
                ));
            }
        }
        try {
            artifacts.save(namespace, purpose, writes);
        } catch (DataAccessException exception) {
            log.warn("Embedding artifact write failed; continuing with model output", exception);
        }
        for (PreparedChild input : prepared) {
            if (result.get(input.index()) == null) {
                result.set(input.index(), generatedByHash.get(input.inputHash()));
            }
        }
        if (result.stream().anyMatch(item -> item == null)) {
            throw new IllegalStateException("Embedding cache failed to preserve input order");
        }
        return List.copyOf(result);
    }

    EmbeddingCacheStatsResponse stats() {
        CacheStats cacheStats = queryCache.stats();
        EmbeddingArtifactRepository.ArtifactSummary artifactSummary = artifacts.summary();
        Map<ModelKey, MutableModelStats> models = new LinkedHashMap<>();
        for (QueryCacheKey key : queryCache.asMap().keySet()) {
            models.computeIfAbsent(ModelKey.from(key.namespace()), ignored -> new MutableModelStats())
                    .queryEntries++;
        }
        for (ArtifactModelSummary summary : artifacts.modelSummaries()) {
            MutableModelStats model = models.computeIfAbsent(
                    new ModelKey(
                            summary.providerKey(),
                            summary.model(),
                            summary.revision(),
                            summary.dimensions()
                    ),
                    ignored -> new MutableModelStats()
            );
            model.artifactEntries += summary.entries();
            model.artifactBytes += summary.bytes();
        }
        List<ModelCacheStats> modelStats = models.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ModelCacheStats(
                        entry.getKey().providerKey(),
                        entry.getKey().model(),
                        entry.getKey().revision(),
                        entry.getKey().dimensions(),
                        entry.getValue().queryEntries,
                        entry.getValue().artifactEntries,
                        entry.getValue().artifactBytes
                ))
                .toList();
        return new EmbeddingCacheStatsResponse(
                new QueryCacheStats(
                        queryCache.estimatedSize(),
                        properties.getEmbeddingQueryCacheMaxEntries(),
                        cacheStats.hitCount(),
                        cacheStats.missCount(),
                        cacheStats.evictionCount(),
                        queryCoalesced.sum(),
                        queryModelCalls.sum(),
                        querySavedModelCalls.sum()
                ),
                new ArtifactCacheStats(
                        artifactSummary.entries(),
                        artifactSummary.bytes(),
                        properties.getEmbeddingArtifactMaxBytes(),
                        artifactHits.sum(),
                        artifactMisses.sum(),
                        artifactEvictions.sum(),
                        artifactCorruptions.sum(),
                        artifactModelCalls.sum(),
                        artifactSavedModelCalls.sum()
                ),
                modelStats,
                Instant.now()
        );
    }

    ClearEmbeddingCacheResponse clear(
            String providerKey,
            String model,
            String revision,
            UUID actorId,
            String reason
    ) {
        String normalizedProvider = providerKey.trim();
        String normalizedModel = model.trim();
        String normalizedRevision = revision.trim();
        ClearResult cleared = artifacts.clear(
                normalizedProvider,
                normalizedModel,
                normalizedRevision,
                actorId,
                reason.trim()
        );
        List<QueryCacheKey> matching = queryCache.asMap().keySet().stream()
                .filter(key -> key.namespace().providerKey().equals(normalizedProvider))
                .filter(key -> key.namespace().model().equals(normalizedModel))
                .filter(key -> key.namespace().revision().equals(normalizedRevision))
                .toList();
        queryCache.invalidateAll(matching);
        inFlight.forEach((key, future) -> {
            if (key.namespace().providerKey().equals(normalizedProvider)
                    && key.namespace().model().equals(normalizedModel)
                    && key.namespace().revision().equals(normalizedRevision)) {
                future.whenComplete((ignored, failure) -> queryCache.invalidate(key));
            }
        });
        return new ClearEmbeddingCacheResponse(
                cleared.deletedArtifacts(),
                matching.size(),
                cleared.freedBytes()
        );
    }

    void cleanup() {
        Instant now = Instant.now();
        var expired = artifacts.evictExpiredAndUnreferenced(
                now.minus(properties.getEmbeddingArtifactRetention()),
                now.minusSeconds(60)
        );
        artifactEvictions.add(expired.entries());
        long bytes = artifacts.summary().bytes();
        if (bytes > properties.getEmbeddingArtifactMaxBytes()) {
            var capacity = artifacts.evictOldest(
                    bytes - properties.getEmbeddingArtifactMaxBytes()
            );
            artifactEvictions.add(capacity.entries());
        }
    }

    void clearQueryCache() {
        queryCache.invalidateAll();
        inFlight.clear();
    }

    @PreDestroy
    void closeIndexEmbeddingExecutor() {
        indexEmbeddingExecutor.close();
    }

    private void removeCorrupt(Set<UUID> ids) {
        for (UUID id : ids) {
            try {
                artifacts.delete(id);
            } catch (DataAccessException exception) {
                log.warn("Failed to delete corrupt embedding artifact {}", id, exception);
            }
        }
    }

    private void touch(Set<UUID> ids) {
        try {
            artifacts.touch(
                    List.copyOf(ids),
                    Instant.now().minus(properties.getEmbeddingArtifactTouchInterval())
            );
        } catch (DataAccessException exception) {
            log.warn("Failed to update embedding artifact usage", exception);
        }
    }

    private EmbeddingNamespace namespace(IndexConfigView config) {
        if (config == null
                || !config.vectorEnabled()
                || ModelProviders.blank(config.embeddingProviderKey())
                || ModelProviders.blank(config.embeddingInputFormatVersion())
                || ModelProviders.blank(config.embeddingNormalizationVersion())) {
            throw new IllegalStateException("Vector IndexConfig is incomplete");
        }
        return new EmbeddingNamespace(
                config.embeddingProviderKey(),
                config.embeddingModel(),
                config.embeddingRevision(),
                config.vectorDimensions(),
                config.embeddingInputFormatVersion(),
                config.embeddingNormalizationVersion()
        );
    }

    private void requireCompatible(EmbeddingNamespace namespace) {
        ModelDescriptor model = provider.descriptor();
        if (!"openai-compatible".equals(namespace.providerKey())
                || !"raw-text-v1".equals(namespace.inputFormatVersion())
                || !"none-v1".equals(namespace.normalizationVersion())
                || !model.enabled()
                || !namespace.model().equals(model.model())
                || !namespace.revision().equals(model.revision())
                || !Integer.valueOf(namespace.dimensions()).equals(model.dimensions())) {
            throw new IllegalStateException(
                    "Embedding service does not match the selected IndexConfig"
            );
        }
    }

    private static <T> T completed(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    static EncodedVector encode(List<Double> vector, int dimensions) {
        if (vector == null || vector.size() != dimensions) {
            throw new IllegalArgumentException("Embedding vector dimensions are invalid");
        }
        ByteBuffer buffer = ByteBuffer.allocate(dimensions * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        float[] canonical = new float[dimensions];
        boolean nonZero = false;
        for (int index = 0; index < vector.size(); index++) {
            Double value = vector.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Embedding vector contains a non-finite number");
            }
            float number = value.floatValue();
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("Embedding vector cannot be stored as FP32");
            }
            nonZero |= number != 0.0f;
            canonical[index] = number;
            buffer.putFloat(number);
        }
        if (!nonZero) {
            throw new IllegalArgumentException("Embedding vector must not be all zero");
        }
        byte[] bytes = buffer.array();
        return new EncodedVector(
                bytes,
                sha256(bytes),
                new FloatVectorList(canonical)
        );
    }

    static List<Double> decode(byte[] bytes, String checksum, int dimensions) {
        if (bytes == null
                || bytes.length != dimensions * Float.BYTES
                || checksum == null
                || !checksum.equals(sha256(bytes))) {
            throw new IllegalArgumentException("Embedding artifact checksum or size is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[dimensions];
        boolean nonZero = false;
        int index = 0;
        while (buffer.hasRemaining()) {
            float value = buffer.getFloat();
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding artifact contains a non-finite number");
            }
            nonZero |= value != 0.0f;
            result[index++] = value;
        }
        if (!nonZero) {
            throw new IllegalArgumentException("Embedding artifact must not be all zero");
        }
        return new FloatVectorList(result);
    }

    static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Embedding input is required");
        }
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record EncodedVector(
            byte[] bytes,
            String checksum,
            List<Double> canonical
    ) {
    }

    private static final class FloatVectorList
            extends AbstractList<Double> implements RandomAccess {

        private final float[] values;

        private FloatVectorList(float[] values) {
            this.values = values;
        }

        @Override
        public Double get(int index) {
            return (double) values[index];
        }

        @Override
        public int size() {
            return values.length;
        }
    }

    private record QueryCacheKey(
            EmbeddingNamespace namespace,
            String purpose,
            String inputHash
    ) {
    }

    private record PreparedChild(
            int index,
            String contentHash,
            String text,
            String inputHash
    ) {
    }

    private record ModelKey(
            String providerKey,
            String model,
            String revision,
            int dimensions
    ) implements Comparable<ModelKey> {

        static ModelKey from(EmbeddingNamespace namespace) {
            return new ModelKey(
                    namespace.providerKey(),
                    namespace.model(),
                    namespace.revision(),
                    namespace.dimensions()
            );
        }

        @Override
        public int compareTo(ModelKey other) {
            return Comparator.comparing(ModelKey::providerKey)
                    .thenComparing(ModelKey::model)
                    .thenComparing(ModelKey::revision)
                    .thenComparingInt(ModelKey::dimensions)
                    .compare(this, other);
        }
    }

    private static final class MutableModelStats {

        private long queryEntries;
        private long artifactEntries;
        private long artifactBytes;
    }
}
