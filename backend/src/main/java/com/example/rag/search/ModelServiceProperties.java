package com.example.rag.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.models")
public class ModelServiceProperties {

    private final Endpoint embedding = new Endpoint(
            "http://embedding-model:8000",
            "Qwen/Qwen3-Embedding-0.6B",
            "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3",
            1024
    );
    private final Endpoint rerank = new Endpoint(
            "http://reranker-model:8000",
            "Qwen/Qwen3-Reranker-0.6B",
            "e61197ed45024b0ed8a2d74b80b4d909f1255473",
            null
    );

    public Endpoint getEmbedding() {
        return embedding;
    }

    public Endpoint getRerank() {
        return rerank;
    }

    public static final class Endpoint {

        private boolean enabled;
        private String baseUrl;
        private String model;
        private String revision;
        private Integer dimensions;
        private Duration timeout = Duration.ofSeconds(2);

        Endpoint(String baseUrl, String model, String revision, Integer dimensions) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.revision = revision;
            this.dimensions = dimensions;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }

        public Integer getDimensions() {
            return dimensions;
        }

        public void setDimensions(Integer dimensions) {
            this.dimensions = dimensions;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
