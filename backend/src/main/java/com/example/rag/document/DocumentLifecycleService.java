package com.example.rag.document;

import com.example.rag.common.ApiException;
import com.example.rag.governance.GovernanceEventService;
import com.example.rag.persistence.DocumentAclEntryEntity;
import com.example.rag.persistence.DocumentAclEntryRepository;
import com.example.rag.persistence.DocumentEntity;
import com.example.rag.persistence.DocumentFormat;
import com.example.rag.persistence.DocumentRepository;
import com.example.rag.persistence.DocumentRevisionEntity;
import com.example.rag.persistence.DocumentRevisionRepository;
import com.example.rag.persistence.DocumentVisibility;
import com.example.rag.persistence.EvaluationProvenance;
import com.example.rag.persistence.RevisionStatus;
import com.example.rag.persistence.UserEntity;
import com.example.rag.persistence.UserRepository;
import com.example.rag.persistence.UserRole;
import com.example.rag.pipeline.PipelineService;
import com.example.rag.pipeline.ParserEngine;
import com.example.rag.graph.GraphRebuildRequestService;
import com.example.rag.security.PlatformUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class DocumentLifecycleService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern PROVENANCE_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern EVIDENCE_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SPDX_LICENSE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+-]{0,63}");
    private static final Pattern SOURCE_REVISION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+\\-]{0,254}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String EVALUATION_TITLE_PREFIX = "[EVAL][PUBLIC]";

    private final DocumentRepository documents;
    private final DocumentRevisionRepository revisions;
    private final DocumentAclEntryRepository aclEntries;
    private final UserRepository users;
    private final DocumentFileValidator validator;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final DocumentAccessService access;
    private final PipelineService pipeline;
    private final TransactionTemplate transactions;
    private final GraphRebuildRequestService graphRebuilds;
    private final DocumentRuntimePolicyService runtimePolicies;
    private final GovernanceEventService governanceEvents;

    public DocumentLifecycleService(
            DocumentRepository documents,
            DocumentRevisionRepository revisions,
            DocumentAclEntryRepository aclEntries,
            UserRepository users,
            DocumentFileValidator validator,
            ObjectStorageService storage,
            StorageProperties storageProperties,
            DocumentAccessService access,
            PipelineService pipeline,
            TransactionTemplate transactions,
            GraphRebuildRequestService graphRebuilds,
            DocumentRuntimePolicyService runtimePolicies,
            GovernanceEventService governanceEvents
    ) {
        this.documents = documents;
        this.revisions = revisions;
        this.aclEntries = aclEntries;
        this.users = users;
        this.validator = validator;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.access = access;
        this.pipeline = pipeline;
        this.transactions = transactions;
        this.graphRebuilds = graphRebuilds;
        this.runtimePolicies = runtimePolicies;
        this.governanceEvents = governanceEvents;
    }

    public DocumentDetailResponse create(
            PlatformUserPrincipal actor,
            String title,
            DocumentVisibility visibility,
            List<UUID> grantedUserIds,
            MultipartFile file,
            String idempotencyKey,
            EvaluationProvenanceInput provenanceInput
    ) {
        String key = validateIdempotencyKey(idempotencyKey);
        String safeTitle = validateTitle(title);
        DocumentVisibility safeVisibility = validateVisibility(visibility);
        List<UUID> grants = normalizeGrants(grantedUserIds, actor.id());
        EvaluationProvenance provenance = validateProvenance(provenanceInput);
        requireEvaluationScope(safeTitle, safeVisibility, provenance);

        try (var validated = validator.validate(file)) {
            String fingerprint = createFingerprint(
                    actor.id(), safeTitle, safeVisibility, grants, validated, provenance
            );
            UploadReservation reservation = reserveCreate(
                    actor,
                    safeTitle,
                    safeVisibility,
                    grants,
                    validated,
                    key,
                    fingerprint,
                    provenance
            );
            return completeUpload(reservation, validated, actor);
        }
    }

    public DocumentDetailResponse addRevision(
            UUID documentId,
            PlatformUserPrincipal actor,
            MultipartFile file,
            String idempotencyKey,
            EvaluationProvenanceInput provenanceInput,
            String formatChangeConfirmation,
            String formatChangeReason
    ) {
        String key = validateIdempotencyKey(idempotencyKey);
        EvaluationProvenance provenance = validateProvenance(provenanceInput);
        FormatChangeApproval formatChange = new FormatChangeApproval(
                trimToNull(formatChangeConfirmation),
                trimToNull(formatChangeReason)
        );
        try (var validated = validator.validate(file)) {
            List<String> fingerprintValues = new ArrayList<>(List.of(
                    "revision",
                    actor.id().toString(),
                    documentId.toString(),
                    validated.filename(),
                    Long.toString(validated.size()),
                    validated.format().name(),
                    validated.mediaType(),
                    validated.sha256()
            ));
            fingerprintValues.add(formatChange.confirmationOrEmpty());
            fingerprintValues.add(formatChange.reasonOrEmpty());
            appendProvenance(fingerprintValues, provenance);
            String fingerprint = fingerprint(fingerprintValues);
            UploadReservation reservation = reserveRevision(
                    documentId,
                    actor,
                    validated,
                    key,
                    fingerprint,
                    provenance,
                    formatChange
            );
            return completeUpload(reservation, validated, actor);
        }
    }

    public ReparseResponse reparse(
            UUID documentId,
            PlatformUserPrincipal actor,
            ReparseRequest request
    ) {
        if (!"REPARSE".equals(request.confirmation())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CONFIRMATION_INVALID",
                    "确认字段无效"
            );
        }
        String key = validateIdempotencyKey(request.idempotencyKey());
        String reason = request.reason().strip();
        ReparseReservation reservation = reserveReparse(
                documentId,
                request.sourceRevisionId(),
                request.targetParser(),
                reason,
                actor,
                key
        );
        completeStoredObject(
                reservation.upload(),
                target -> storage.copy(
                        reservation.sourceObjectKey(),
                        target
                )
        );
        DocumentRevisionEntity revision = revisions.findById(
                reservation.upload().revisionId()
        ).orElseThrow(DocumentAccessService::notFound);
        return new ReparseResponse(
                documentId,
                request.sourceRevisionId(),
                revision.getId(),
                revision.getRevisionNumber(),
                pipeline.parseJobId(revision.getId()),
                request.targetParser(),
                revision.getStatus()
        );
    }

    @Transactional
    public DocumentDetailResponse updateAcl(
            UUID documentId,
            PlatformUserPrincipal actor,
            DocumentAclUpdateRequest request
    ) {
        DocumentEntity document = documents.findActiveForUpdate(documentId)
                .orElseThrow(DocumentAccessService::notFound);
        if (document.getAclVersion() != request.expectedAclVersion()) {
            throw conflict("ACL_VERSION_CONFLICT", "文档权限已被其他请求更新，请刷新后重试");
        }
        long previousVersion = document.getAclVersion();
        String previousTitle = document.getTitle();
        DocumentVisibility previousVisibility = document.getVisibility();
        long previousGrants = aclEntries.findGrantedUsers(documentId).size();
        String title = validateTitle(request.title());
        document.updateMetadataAndAcl(title, request.visibility());
        replaceAcl(document, request.visibility(), request.safeGrantedUserIds());
        documents.flush();
        graphRebuilds.aclChanged(documentId);
        governanceEvents.append(
                "ACCESS", "DOCUMENT_ACL_CHANGED", actor,
                "DOCUMENT", documentId.toString(), document.getTitle(),
                Map.of(
                        "title", previousTitle,
                        "visibility", previousVisibility,
                        "explicitGrants", previousGrants,
                        "aclVersion", previousVersion
                ),
                Map.of(
                        "title", document.getTitle(),
                        "visibility", document.getVisibility(),
                        "explicitGrants", request.visibility() == DocumentVisibility.ALL_USERS
                                ? 0 : request.safeGrantedUserIds().size(),
                        "aclVersion", document.getAclVersion()
                ),
                request.reason() == null || request.reason().isBlank()
                        ? "管理员更新文档权限范围"
                        : GovernanceEventService.normalizeReason(request.reason())
        );
        return access.getAccessible(documentId, actor);
    }

    @Transactional
    public void delete(UUID documentId) {
        pipeline.cancelForDocument(documentId);
        DocumentEntity document = documents.findActiveForUpdate(documentId)
                .orElseThrow(DocumentAccessService::notFound);
        document.markDeleted();
        documents.flush();
    }

    private UploadReservation reserveCreate(
            PlatformUserPrincipal actor,
            String title,
            DocumentVisibility visibility,
            List<UUID> grants,
            DocumentFileValidator.ValidatedDocument file,
            String key,
            String fingerprint,
            EvaluationProvenance provenance
    ) {
        try {
            return required(transactions.execute(status -> {
                var existing = revisions.findByIdempotencyKeyForUpdate(key).orElse(null);
                if (existing != null) {
                    return reuseExisting(existing, null, actor.id(), fingerprint);
                }
                UserEntity owner = users.findById(actor.id())
                        .filter(UserEntity::isEnabled)
                        .orElseThrow(DocumentAccessService::notFound);
                DocumentEntity document = documents.save(
                        new DocumentEntity(owner, title, visibility)
                );
                DocumentRevisionEntity revision = revisions.save(new DocumentRevisionEntity(
                        document,
                        1,
                        file.sha256(),
                        objectKey(file.extension()),
                        RevisionStatus.STAGED,
                        file.filename(),
                        file.size(),
                        file.mediaType(),
                        file.format(),
                        key,
                        fingerprint,
                        stagingExpiry(),
                        provenance
                ));
                replaceAcl(document, visibility, grants);
                revisions.flush();
                return UploadReservation.upload(document.getId(), revision);
            }));
        } catch (DataIntegrityViolationException exception) {
            return reserveAfterConflict(key, null, actor.id(), fingerprint, exception);
        }
    }

    private UploadReservation reserveRevision(
            UUID documentId,
            PlatformUserPrincipal actor,
            DocumentFileValidator.ValidatedDocument file,
            String key,
            String fingerprint,
            EvaluationProvenance provenance,
            FormatChangeApproval formatChange
    ) {
        try {
            return required(transactions.execute(status -> {
                var existing = revisions.findByIdempotencyKeyForUpdate(key).orElse(null);
                if (existing != null) {
                    return reuseExisting(existing, documentId, null, fingerprint);
                }
                DocumentEntity document = documents.findActiveForUpdate(documentId)
                        .orElseThrow(DocumentAccessService::notFound);
                requireEvaluationScope(
                        document.getTitle(),
                        document.getVisibility(),
                        provenance
                );
                existing = revisions.findByIdempotencyKeyForUpdate(key).orElse(null);
                if (existing != null) {
                    return reuseExisting(existing, documentId, null, fingerprint);
                }
                DocumentRevisionEntity latest = revisions
                        .findFirstByDocumentIdOrderByRevisionNumberDesc(documentId)
                        .orElse(null);
                int nextNumber = latest == null
                        ? 1 : latest.getRevisionNumber() + 1;
                DocumentRevisionEntity formatBaseline = document.getCurrentRevision() == null
                        ? latest : document.getCurrentRevision();
                DocumentFormat previousFormat = formatBaseline == null
                        ? file.format() : formatBaseline.getDocumentFormat();
                boolean changesFormat = previousFormat != file.format();
                if (changesFormat) {
                    requireFormatChangeApproval(formatChange);
                }
                DocumentRevisionEntity revision = revisions.save(new DocumentRevisionEntity(
                        document,
                        nextNumber,
                        file.sha256(),
                        objectKey(file.extension()),
                        RevisionStatus.STAGED,
                        file.filename(),
                        file.size(),
                        file.mediaType(),
                        file.format(),
                        key,
                        fingerprint,
                        stagingExpiry(),
                        provenance
                ));
                if (changesFormat) {
                    revision.configureFormatChange(
                            previousFormat,
                            formatChange.reason(),
                            actor.id()
                    );
                }
                revisions.flush();
                return UploadReservation.upload(documentId, revision);
            }));
        } catch (DataIntegrityViolationException exception) {
            return reserveAfterConflict(key, documentId, null, fingerprint, exception);
        }
    }

    private ReparseReservation reserveReparse(
            UUID documentId,
            UUID sourceRevisionId,
            ParserEngine parser,
            String reason,
            PlatformUserPrincipal actor,
            String key
    ) {
        String fingerprint = fingerprint(List.of(
                "reparse",
                documentId.toString(),
                sourceRevisionId.toString(),
                parser.name(),
                reason,
                actor.id().toString()
        ));
        try {
            return requiredReparse(transactions.execute(status -> {
                var existing = revisions.findByIdempotencyKeyForUpdate(key)
                        .orElse(null);
                if (existing != null) {
                    UploadReservation reused = reuseExisting(
                            existing, documentId, null, fingerprint
                    );
                    DocumentRevisionEntity source =
                            existing.getSourceRevision();
                    if (source == null
                            || !source.getId().equals(sourceRevisionId)) {
                        throw conflict(
                                "IDEMPOTENCY_KEY_REUSED",
                                "幂等键已用于其他重解析请求"
                        );
                    }
                    return new ReparseReservation(
                            reused, source.getSourceObjectKey()
                    );
                }
                DocumentEntity document = documents.findActiveForUpdate(
                        documentId
                ).orElseThrow(DocumentAccessService::notFound);
                DocumentRevisionEntity source = document.getCurrentRevision();
                if (source == null
                        || !source.getId().equals(sourceRevisionId)
                        || source.getStatus() != RevisionStatus.READY) {
                    throw conflict(
                            "REPARSE_SOURCE_NOT_CURRENT",
                            "只能重解析当前已发布的 READY Revision"
                    );
                }
                runtimePolicies.requireFormatEnabled(source.getDocumentFormat());
                if (parser != ParserEngine.AUTO) {
                    runtimePolicies.requireProviderEnabled(
                            source.getDocumentFormat(),
                            com.example.rag.pipeline.ParserProviderKind.valueOf(parser.name())
                    );
                }
                int nextNumber = revisions
                        .findFirstByDocumentIdOrderByRevisionNumberDesc(
                                documentId
                        )
                        .map(previous -> previous.getRevisionNumber() + 1)
                        .orElse(1);
                DocumentRevisionEntity revision =
                        new DocumentRevisionEntity(
                                document,
                                nextNumber,
                                source.getContentHash(),
                                objectKey(extension(
                                        source.getOriginalFilename()
                                )),
                                RevisionStatus.STAGED,
                                source.getOriginalFilename(),
                                source.getFileSizeBytes(),
                                source.getMediaType(),
                                source.getDocumentFormat(),
                                key,
                                fingerprint,
                                stagingExpiry(),
                                source.getEvaluationProvenance()
                        );
                revision.configureReparse(
                        source, parser, reason, actor.id()
                );
                revisions.saveAndFlush(revision);
                return new ReparseReservation(
                        UploadReservation.upload(documentId, revision),
                        source.getSourceObjectKey()
                );
            }));
        } catch (DataIntegrityViolationException exception) {
            ReparseReservation concurrent = transactions.execute(status -> {
                DocumentRevisionEntity revision = revisions
                        .findByIdempotencyKeyForUpdate(key)
                        .orElse(null);
                if (revision == null
                        || revision.getSourceRevision() == null) {
                    return null;
                }
                UploadReservation reused = reuseExisting(
                        revision, documentId, null, fingerprint
                );
                return new ReparseReservation(
                        reused,
                        revision.getSourceRevision().getSourceObjectKey()
                );
            });
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    private UploadReservation reserveAfterConflict(
            String key,
            UUID documentId,
            UUID ownerId,
            String fingerprint,
            DataIntegrityViolationException original
    ) {
        UploadReservation reservation = transactions.execute(status ->
                revisions.findByIdempotencyKeyForUpdate(key)
                        .map(revision -> reuseExisting(
                                revision, documentId, ownerId, fingerprint
                        ))
                        .orElse(null)
        );
        if (reservation == null) {
            throw original;
        }
        return reservation;
    }

    private UploadReservation reuseExisting(
            DocumentRevisionEntity revision,
            UUID expectedDocumentId,
            UUID expectedOwnerId,
            String fingerprint
    ) {
        DocumentEntity document = revision.getDocument();
        if (document.getDeletedAt() != null) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于已删除文档");
        }
        if (expectedDocumentId != null && !document.getId().equals(expectedDocumentId)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他文档");
        }
        if (expectedOwnerId != null && !document.getOwner().getId().equals(expectedOwnerId)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已由其他用户使用");
        }
        assertFingerprint(revision, fingerprint);

        return switch (revision.getStatus()) {
            case STAGED -> UploadReservation.check(document.getId(), revision);
            case UPLOAD_FAILED -> reclaim(revision);
            case DELETED -> throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于已删除版本");
            default -> UploadReservation.complete(document.getId(), revision);
        };
    }

    private DocumentDetailResponse completeUpload(
            UploadReservation reservation,
            DocumentFileValidator.ValidatedDocument file,
            PlatformUserPrincipal actor
    ) {
        completeStoredObject(
                reservation,
                target -> storage.upload(
                        target,
                        file.path(),
                        file.mediaType()
                )
        );
        return access.getAccessible(reservation.documentId(), actor);
    }

    private void completeStoredObject(
            UploadReservation reservation,
            Consumer<String> writer
    ) {
        deleteQuietly(reservation.replacedObjectKey());
        if (reservation.action() == UploadAction.COMPLETE) {
            return;
        }

        UploadReservation current = reservation;
        if (current.action() == UploadAction.CHECK_STAGED) {
            if (storage.exists(current.objectKey())) {
                if (!finalizeUpload(current)) {
                    deleteQuietly(current.objectKey());
                    throw conflict(
                            "UPLOAD_ATTEMPT_SUPERSEDED",
                            "上传任务已失效，请重试"
                    );
                }
                return;
            }
            UploadReservation staged = current;
            current = required(transactions.execute(
                    status -> prepareStagedUpload(staged)
            ));
            deleteQuietly(current.replacedObjectKey());
            if (current.action() == UploadAction.COMPLETE) {
                return;
            }
            if (current.action() == UploadAction.CHECK_STAGED) {
                throw conflict(
                        "UPLOAD_IN_PROGRESS",
                        "相同请求正在上传，请稍后重试"
                );
            }
        }

        try {
            writer.accept(current.objectKey());
        } catch (RuntimeException uploadFailure) {
            boolean abandoned = false;
            try {
                abandoned = markUploadFailed(current);
            } catch (RuntimeException databaseFailure) {
                uploadFailure.addSuppressed(databaseFailure);
            }
            if (abandoned) {
                deleteQuietly(current.objectKey());
            }
            throw uploadFailure;
        }

        try {
            if (!finalizeUpload(current)) {
                deleteQuietly(current.objectKey());
                throw conflict(
                        "UPLOAD_ATTEMPT_SUPERSEDED",
                        "上传任务已失效，请重试"
                );
            }
        } catch (RuntimeException exception) {
            deleteQuietly(current.objectKey());
            throw exception;
        }
    }

    private UploadReservation prepareStagedUpload(UploadReservation attempted) {
        DocumentRevisionEntity revision = revisions.findForUpdate(attempted.revisionId())
                .orElseThrow(DocumentAccessService::notFound);
        assertFingerprint(revision, attempted.fingerprint());
        if (revision.getDocument().getDeletedAt() != null || revision.getStatus() == RevisionStatus.DELETED) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于已删除文档");
        }
        if (revision.getStatus() == RevisionStatus.UPLOAD_FAILED) {
            return reclaim(revision);
        }
        if (revision.getStatus() != RevisionStatus.STAGED) {
            return UploadReservation.complete(revision.getDocument().getId(), revision);
        }
        if (!revision.getSourceObjectKey().equals(attempted.objectKey())) {
            return UploadReservation.check(revision.getDocument().getId(), revision);
        }
        if (revision.getStagingExpiresAt().isAfter(Instant.now())) {
            return UploadReservation.check(revision.getDocument().getId(), revision);
        }
        return reclaim(revision);
    }

    private UploadReservation reclaim(DocumentRevisionEntity revision) {
        String replacedObjectKey = revision.getSourceObjectKey();
        revision.reclaimStaging(
                objectKey(extension(revision.getOriginalFilename())),
                stagingExpiry()
        );
        revisions.flush();
        return UploadReservation.upload(revision.getDocument().getId(), revision, replacedObjectKey);
    }

    private boolean finalizeUpload(UploadReservation attempted) {
        Boolean finalized = transactions.execute(status -> {
            DocumentRevisionEntity revision = revisions.findForUpdate(attempted.revisionId())
                    .orElse(null);
            if (revision == null
                    || !attempted.fingerprint().equals(revision.getRequestFingerprint())
                    || !attempted.objectKey().equals(revision.getSourceObjectKey())
                    || revision.getDocument().getDeletedAt() != null) {
                return false;
            }
            if (revision.getStatus() == RevisionStatus.STAGED) {
                runtimePolicies.requireFormatEnabledForWrite(
                        revision.getDocumentFormat()
                );
                if (revision.getReparseRequestedParser() != null
                        && revision.getReparseRequestedParser() != ParserEngine.AUTO) {
                    runtimePolicies.requireProviderEnabledForWrite(
                            revision.getDocumentFormat(),
                            com.example.rag.pipeline.ParserProviderKind.valueOf(
                                    revision.getReparseRequestedParser().name()
                            )
                    );
                }
                revision.markUploaded();
                revision.getDocument().markContentUpdated();
                if (revision.getReparseRequestedParser() == null) {
                    pipeline.enqueue(revision.getId());
                } else {
                    pipeline.enqueue(
                            revision.getId(),
                            revision.getReparseRequestedParser()
                    );
                }
                revisions.flush();
                return true;
            }
            return revision.getStatus() != RevisionStatus.UPLOAD_FAILED
                    && revision.getStatus() != RevisionStatus.DELETED;
        });
        return Boolean.TRUE.equals(finalized);
    }

    private boolean markUploadFailed(UploadReservation attempted) {
        return Boolean.TRUE.equals(transactions.execute(status -> revisions.findForUpdate(attempted.revisionId())
                .filter(revision -> revision.getStatus() == RevisionStatus.STAGED)
                .filter(revision -> revision.getSourceObjectKey().equals(attempted.objectKey()))
                .filter(revision -> revision.getRequestFingerprint().equals(attempted.fingerprint()))
                .map(revision -> {
                    revision.markUploadFailed();
                    revisions.flush();
                    return true;
                })
                .orElse(false)));
    }

    private void replaceAcl(
            DocumentEntity document,
            DocumentVisibility visibility,
            List<UUID> grantedUserIds
    ) {
        aclEntries.deleteAllByDocumentId(document.getId());
        aclEntries.flush();
        if (visibility == DocumentVisibility.ALL_USERS) {
            return;
        }
        var requested = new LinkedHashSet<>(grantedUserIds == null ? List.<UUID>of() : grantedUserIds);
        requested.remove(document.getOwner().getId());
        List<UserEntity> granted = users.findAllById(requested).stream()
                .filter(user -> user.isEnabled() && user.getRole() == UserRole.USER)
                .toList();
        if (granted.size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACL_USER_INVALID", "授权用户不存在、已停用或不是普通用户");
        }
        aclEntries.saveAll(granted.stream()
                .map(user -> new DocumentAclEntryEntity(document, user))
                .toList());
    }

    private static List<UUID> normalizeGrants(List<UUID> grantedUserIds, UUID ownerId) {
        return (grantedUserIds == null ? List.<UUID>of() : grantedUserIds).stream()
                .filter(id -> id != null && !id.equals(ownerId))
                .distinct()
                .sorted()
                .toList();
    }

    private static EvaluationProvenance validateProvenance(
            EvaluationProvenanceInput input
    ) {
        if (input == null) {
            return null;
        }
        String suiteVersion = trimToNull(input.evaluationSuiteVersion());
        String evidenceKey = trimToNull(input.evaluationEvidenceKey());
        String dataset = trimToNull(input.sourceDataset());
        String title = trimToNull(input.sourceTitle());
        String url = trimToNull(input.sourceUrl());
        String license = trimToNull(input.sourceLicense());
        String revision = trimToNull(input.sourceRevision());
        String contentHash = trimToNull(input.sourceContentHash());
        int present = 0;
        for (String value : List.of(
                suiteVersion == null ? "" : suiteVersion,
                evidenceKey == null ? "" : evidenceKey,
                dataset == null ? "" : dataset,
                title == null ? "" : title,
                url == null ? "" : url,
                license == null ? "" : license,
                revision == null ? "" : revision,
                contentHash == null ? "" : contentHash
        )) {
            if (!value.isEmpty()) {
                present++;
            }
        }
        if (present == 0) {
            return null;
        }
        if (present != 8) {
            throw invalidProvenance(
                    "EVALUATION_PROVENANCE_INCOMPLETE",
                    "评测来源字段必须全部提供或全部省略"
            );
        }
        if (!PROVENANCE_VERSION.matcher(suiteVersion).matches()
                || !EVIDENCE_KEY.matcher(evidenceKey).matches()
                || !PROVENANCE_VERSION.matcher(dataset).matches()
                || title.length() > 500
                || !SPDX_LICENSE.matcher(license).matches()
                || !SOURCE_REVISION.matcher(revision).matches()
                || !SHA256.matcher(contentHash).matches()
                || !validHttpsUrl(url)) {
            throw invalidProvenance(
                    "EVALUATION_PROVENANCE_INVALID",
                    "评测来源版本、Evidence Key、HTTPS URL、SPDX License、Revision 或 Hash 格式无效"
            );
        }
        return new EvaluationProvenance(
                suiteVersion,
                evidenceKey,
                dataset,
                title,
                url,
                license,
                revision,
                contentHash
        );
    }

    private static boolean validHttpsUrl(String value) {
        if (value.length() > 2048) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void requireEvaluationScope(
            String title,
            DocumentVisibility visibility,
            EvaluationProvenance provenance
    ) {
        if (provenance != null
                && (visibility != DocumentVisibility.ALL_USERS
                || !title.startsWith(EVALUATION_TITLE_PREFIX))) {
            throw invalidProvenance(
                    "EVALUATION_PROVENANCE_SCOPE_INVALID",
                    "评测来源只允许用于标题以 [EVAL][PUBLIC] 开头的 ALL_USERS 文档"
            );
        }
    }

    private static void appendProvenance(
            List<String> values,
            EvaluationProvenance provenance
    ) {
        if (provenance == null) {
            return;
        }
        values.add(provenance.getEvaluationSuiteVersion());
        values.add(provenance.getEvaluationEvidenceKey());
        values.add(provenance.getSourceDataset());
        values.add(provenance.getSourceTitle());
        values.add(provenance.getSourceUrl());
        values.add(provenance.getSourceLicense());
        values.add(provenance.getSourceRevision());
        values.add(provenance.getSourceContentHash());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static ApiException invalidProvenance(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static String createFingerprint(
            UUID actorId,
            String title,
            DocumentVisibility visibility,
            List<UUID> grants,
            DocumentFileValidator.ValidatedDocument file,
            EvaluationProvenance provenance
    ) {
        List<String> values = new ArrayList<>(List.of(
                "create",
                actorId.toString(),
                title,
                visibility.name(),
                file.filename(),
                Long.toString(file.size()),
                file.format().name(),
                file.mediaType(),
                file.sha256()
        ));
        grants.forEach(id -> values.add(id.toString()));
        appendProvenance(values, provenance);
        return fingerprint(values);
    }

    private static String fingerprint(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void assertFingerprint(DocumentRevisionEntity revision, String expected) {
        if (!expected.equals(revision.getRequestFingerprint())) {
            throw conflict(
                    "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
                    "幂等键已用于不同的请求内容"
            );
        }
    }

    private Instant stagingExpiry() {
        return Instant.now().plus(storageProperties.stagingRetention());
    }

    private static String validateTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty() || title.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TITLE_INVALID", "标题必须为 1 至 500 个字符");
        }
        return title;
    }

    private static DocumentVisibility validateVisibility(DocumentVisibility visibility) {
        if (visibility == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VISIBILITY_INVALID", "请选择文档可见范围");
        }
        return visibility;
    }

    private static String validateIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "幂等键格式无效");
        }
        return key;
    }

    private static void requireFormatChangeApproval(
            FormatChangeApproval approval
    ) {
        if (!"CHANGE_DOCUMENT_FORMAT".equals(approval.confirmation())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_FORMAT_CHANGE_CONFIRMATION_REQUIRED",
                    "更换文档格式前必须明确确认"
            );
        }
        if (approval.reason() == null
                || approval.reason().length() < 8
                || approval.reason().length() > 500) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_FORMAT_CHANGE_REASON_REQUIRED",
                    "更换文档格式必须提供 8 至 500 个字符的审计理由"
            );
        }
    }

    private static String objectKey(String extension) {
        UUID id = UUID.randomUUID();
        return "objects/" + id.toString().substring(0, 2) + "/"
                + id + extension;
    }

    private static String extension(String filename) {
        String value = filename == null ? "" : filename.trim();
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return ".bin";
        }
        String extension = value.substring(dot).toLowerCase(
                java.util.Locale.ROOT
        );
        return extension.matches("\\.[a-z0-9]{1,10}")
                ? extension
                : ".bin";
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            storage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // Scheduled orphan cleanup remains the durable compensation path.
        }
    }

    private static UploadReservation required(UploadReservation reservation) {
        if (reservation == null) {
            throw new IllegalStateException("Upload reservation returned no result");
        }
        return reservation;
    }

    private static ReparseReservation requiredReparse(
            ReparseReservation reservation
    ) {
        if (reservation == null) {
            throw new IllegalStateException(
                    "Reparse reservation returned no result"
            );
        }
        return reservation;
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private enum UploadAction {
        UPLOAD,
        CHECK_STAGED,
        COMPLETE
    }

    private record UploadReservation(
            UUID documentId,
            UUID revisionId,
            String objectKey,
            String fingerprint,
            String replacedObjectKey,
            UploadAction action
    ) {
        private static UploadReservation upload(UUID documentId, DocumentRevisionEntity revision) {
            return upload(documentId, revision, null);
        }

        private static UploadReservation upload(
                UUID documentId,
                DocumentRevisionEntity revision,
                String replacedObjectKey
        ) {
            return new UploadReservation(
                    documentId,
                    revision.getId(),
                    revision.getSourceObjectKey(),
                    revision.getRequestFingerprint(),
                    replacedObjectKey,
                    UploadAction.UPLOAD
            );
        }

        private static UploadReservation check(UUID documentId, DocumentRevisionEntity revision) {
            return new UploadReservation(
                    documentId,
                    revision.getId(),
                    revision.getSourceObjectKey(),
                    revision.getRequestFingerprint(),
                    null,
                    UploadAction.CHECK_STAGED
            );
        }

        private static UploadReservation complete(UUID documentId, DocumentRevisionEntity revision) {
            return new UploadReservation(
                    documentId,
                    revision.getId(),
                    revision.getSourceObjectKey(),
                    revision.getRequestFingerprint(),
                    null,
                    UploadAction.COMPLETE
            );
        }
    }

    private record ReparseReservation(
            UploadReservation upload,
            String sourceObjectKey
    ) {
    }

    private record FormatChangeApproval(
            String confirmation,
            String reason
    ) {
        private String confirmationOrEmpty() {
            return confirmation == null ? "" : confirmation;
        }

        private String reasonOrEmpty() {
            return reason == null ? "" : reason;
        }
    }
}
