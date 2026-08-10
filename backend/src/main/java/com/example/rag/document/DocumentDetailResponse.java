package com.example.rag.document;

import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.UserEntity;

import java.util.List;
import java.util.UUID;

public record DocumentDetailResponse(
        DocumentSummaryResponse document,
        UUID currentRevisionId,
        List<GrantedUserResponse> grantedUsers,
        List<DocumentRevisionResponse> revisions
) {
    static DocumentDetailResponse from(
            DocumentEntity document,
            DocumentRevisionEntity latest,
            DocumentRevisionEntity effective,
            List<UserEntity> grantedUsers,
            List<DocumentRevisionEntity> revisions
    ) {
        UUID currentId = document.getCurrentRevision() == null ? null : document.getCurrentRevision().getId();
        UUID effectiveId = effective == null ? null : effective.getId();
        return new DocumentDetailResponse(
                DocumentSummaryResponse.from(document, latest, effective),
                currentId,
                grantedUsers.stream().map(GrantedUserResponse::from).toList(),
                revisions.stream()
                        .map(revision -> DocumentRevisionResponse.from(revision, currentId, effectiveId))
                        .toList()
        );
    }

    public record GrantedUserResponse(UUID id, String username) {
        static GrantedUserResponse from(UserEntity user) {
            return new GrantedUserResponse(user.getId(), user.getUsername());
        }
    }
}
