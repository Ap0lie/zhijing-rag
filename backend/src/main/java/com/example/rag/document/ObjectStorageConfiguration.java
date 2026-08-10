package com.example.rag.document;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(StorageProperties.class)
public class ObjectStorageConfiguration {

    @Bean
    MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rag.storage",
            name = "initialize",
            havingValue = "true",
            matchIfMissing = true
    )
    ApplicationRunner initializeDocumentBucket(MinioClient client, StorageProperties properties) {
        return arguments -> {
            var exists = BucketExistsArgs.builder().bucket(properties.bucket()).build();
            if (!client.bucketExists(exists)) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                } catch (Exception exception) {
                    if (!client.bucketExists(exists)) {
                        throw exception;
                    }
                }
            }
        };
    }
}
