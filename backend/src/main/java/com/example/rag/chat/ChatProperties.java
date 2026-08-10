package com.example.rag.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.chat")
public class ChatProperties {

    private boolean enabled = true;
    private Duration sseTimeout = Duration.ofSeconds(30);
    private final Llm llm = new Llm();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public Llm getLlm() {
        return llm;
    }

    public static class Llm {

        private boolean enabled;
        private String baseUrl = "http://host.docker.internal:11434/v1";
        private String model = "";
        private String modelRevision = "runtime";
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(15);
        private int maxOutputTokens = 1_024;
        private int contextWindowTokens = 8_192;
        private boolean localEndpoint;
        private boolean remoteEvidenceAllowed;
        private boolean remoteMemoryAllowed;

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

        public String getModelRevision() {
            return modelRevision;
        }

        public void setModelRevision(String modelRevision) {
            this.modelRevision = modelRevision;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(int contextWindowTokens) {
            if (contextWindowTokens < 1_024) {
                throw new IllegalArgumentException(
                        "LLM context window must be at least 1024 tokens"
                );
            }
            this.contextWindowTokens = contextWindowTokens;
        }

        public boolean isLocalEndpoint() {
            return localEndpoint;
        }

        public void setLocalEndpoint(boolean localEndpoint) {
            this.localEndpoint = localEndpoint;
        }

        public boolean isRemoteEvidenceAllowed() {
            return remoteEvidenceAllowed;
        }

        public void setRemoteEvidenceAllowed(boolean remoteEvidenceAllowed) {
            this.remoteEvidenceAllowed = remoteEvidenceAllowed;
        }

        public boolean isRemoteMemoryAllowed() {
            return remoteMemoryAllowed;
        }

        public void setRemoteMemoryAllowed(boolean remoteMemoryAllowed) {
            this.remoteMemoryAllowed = remoteMemoryAllowed;
        }
    }
}
