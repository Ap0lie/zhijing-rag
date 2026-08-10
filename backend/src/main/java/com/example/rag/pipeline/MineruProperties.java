package com.example.rag.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("rag.mineru")
public record MineruProperties(
        boolean enabled,
        URI endpoint,
        String version,
        String modelRevision,
        String modelManifestChecksum,
        Duration timeout,
        int maxPages,
        boolean localEndpoint,
        String gpuActiveProfile
) {
    public MineruProperties {
        if (endpoint == null || version == null || version.isBlank()
                || modelRevision == null || modelRevision.isBlank()
                || modelManifestChecksum == null || !modelManifestChecksum.matches("[0-9a-f]{64}")
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || maxPages < 1 || maxPages > 200
                || gpuActiveProfile == null || gpuActiveProfile.isBlank()) {
            throw new IllegalArgumentException("Invalid rag.mineru configuration");
        }
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("rag.mineru.endpoint must use HTTP(S)");
        }
    }

    public boolean gpuAvailable() {
        return !localEndpoint || "mineru".equalsIgnoreCase(gpuActiveProfile);
    }
}
