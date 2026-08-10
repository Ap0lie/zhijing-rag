package com.example.rag.persistence;

public enum RevisionStatus {
    STAGED,
    UPLOAD_FAILED,
    UPLOADED,
    PROCESSING,
    READY,
    FAILED,
    QUARANTINED,
    DELETED
}
