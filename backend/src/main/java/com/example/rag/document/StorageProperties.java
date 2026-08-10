package com.example.rag.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties("rag.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        DataSize maxFileSize,
        Duration stagingRetention,
        Duration orphanRetention,
        long cleanupDelayMs
) {
}
