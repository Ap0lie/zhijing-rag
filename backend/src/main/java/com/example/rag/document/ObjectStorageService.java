package com.example.rag.document;

import com.example.rag.common.ApiException;
import io.minio.CopyObjectArgs;
import io.minio.SourceObject;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ObjectStorageService {

    private final MinioClient client;
    private final StorageProperties properties;

    public ObjectStorageService(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void upload(
            String objectKey,
            Path source,
            String mediaType
    ) {
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .filename(source.toString())
                    .contentType(mediaType)
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public void upload(String objectKey, byte[] content, String mediaType) {
        try (var input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(input, (long) content.length, -1L)
                    .contentType(mediaType)
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public void copy(String sourceObjectKey, String targetObjectKey) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(targetObjectKey)
                    .source(SourceObject.builder()
                            .bucket(properties.bucket())
                            .object(sourceObjectKey)
                            .build())
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public GetObjectResponse open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())
                    || "NoSuchObject".equals(exception.errorResponse().code())) {
                return false;
            }
            throw unavailable(exception);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public List<StoredObject> list() {
        try {
            List<StoredObject> objects = new ArrayList<>();
            var args = ListObjectsArgs.builder().bucket(properties.bucket()).recursive(true).build();
            for (var result : client.listObjects(args)) {
                var item = result.get();
                objects.add(new StoredObject(item.objectName(), item.lastModified().toInstant()));
            }
            return objects;
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    private static ApiException unavailable(Exception exception) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "文档存储暂时不可用",
                exception
        );
    }

    public record StoredObject(String key, Instant lastModified) {
    }
}
