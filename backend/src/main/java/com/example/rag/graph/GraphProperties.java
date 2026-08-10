package com.example.rag.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("rag.graph")
public class GraphProperties {

    private boolean enabled = true;
    private boolean workerEnabled;
    private boolean globalWorkerEnabled;
    private String workerId = "graph-worker";
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration heartbeatInterval = Duration.ofSeconds(30);
    private Duration retention = Duration.ofHours(24);
    private int maxDocuments = 1_000;
    private int maxParents = 5_000;
    private long maxSourceCharacters = 20_000_000;
    private int maxEntities = 50_000;
    private int maxRelationships = 100_000;
    private final Extraction extraction = new Extraction();
    private final Community community = new Community();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public boolean isGlobalWorkerEnabled() {
        return globalWorkerEnabled;
    }

    public void setGlobalWorkerEnabled(boolean globalWorkerEnabled) {
        this.globalWorkerEnabled = globalWorkerEnabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getMaxDocuments() {
        return maxDocuments;
    }

    public void setMaxDocuments(int maxDocuments) {
        this.maxDocuments = maxDocuments;
    }

    public int getMaxParents() {
        return maxParents;
    }

    public void setMaxParents(int maxParents) {
        this.maxParents = maxParents;
    }

    public long getMaxSourceCharacters() {
        return maxSourceCharacters;
    }

    public void setMaxSourceCharacters(long maxSourceCharacters) {
        this.maxSourceCharacters = maxSourceCharacters;
    }

    public int getMaxEntities() {
        return maxEntities;
    }

    public void setMaxEntities(int maxEntities) {
        this.maxEntities = maxEntities;
    }

    public int getMaxRelationships() {
        return maxRelationships;
    }

    public void setMaxRelationships(int maxRelationships) {
        this.maxRelationships = maxRelationships;
    }

    public Extraction getExtraction() {
        return extraction;
    }

    public Community getCommunity() {
        return community;
    }

    public static final class Extraction {

        private boolean enabled;
        private String baseUrl = "http://host.docker.internal:11434/v1";
        private String model = "";
        private String revision = "";
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(45);
        private int maxOutputTokens = 2048;
        private boolean localEndpoint;
        private boolean remoteEvidenceAllowed;

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
    }

    public static final class Community {

        private String baseUrl = "http://worker:8000";
        private Duration timeout = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
