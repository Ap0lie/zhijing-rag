package com.example.rag.persistence;

public enum PipelineJobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    QUARANTINED,
    CANCELLED
}
