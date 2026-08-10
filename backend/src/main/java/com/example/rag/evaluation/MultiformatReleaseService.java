package com.example.rag.evaluation;

import com.example.rag.common.ApiException;
import com.example.rag.evaluation.EvaluationContracts.CaseSeed;
import com.example.rag.evaluation.EvaluationContracts.DatasetSeed;
import com.example.rag.evaluation.EvaluationContracts.MultiformatFormatView;
import com.example.rag.evaluation.EvaluationContracts.MultiformatReleaseView;
import com.example.rag.evaluation.EvaluationContracts.SubjectType;
import com.example.rag.evaluation.EvaluationContracts.SubjectView;
import com.example.rag.evaluation.EvaluationContracts.TargetView;
import com.example.rag.security.PlatformUserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class MultiformatReleaseService {

    static final String VERSION = "multiformat-release-v4";
    private static final String SCHEMA_VERSION = "phase18d-multiformat-v4";
    private static final String CASE_TYPE = "MULTIFORMAT_RELEASE";
    private static final List<String> FORMATS = List.of(
            "PDF", "TXT", "MARKDOWN", "HTML",
            "DOCX", "PPTX", "XLSX", "CSV"
    );
    private static final UUID DATASET_ID = stableId(
            "evaluation-dataset:multiformat-release"
    );
    private static final UUID VERSION_ID = stableId(
            "evaluation-dataset-version:" + VERSION
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EvaluationService evaluations;
    private final EvaluationTargetService targets;
    private final MultiformatSecurityProbe securityProbe;

    MultiformatReleaseService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            EvaluationService evaluations,
            EvaluationTargetService targets,
            MultiformatSecurityProbe securityProbe
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evaluations = evaluations;
        this.targets = targets;
        this.securityProbe = securityProbe;
    }

    MultiformatReleaseView view() {
        return versionExists() ? frozenView() : previewView();
    }

    @Transactional
    MultiformatReleaseView freeze(
            String reason,
            PlatformUserPrincipal user
    ) {
        UUID actor = actor(user);
        if (versionExists()) {
            freezeCurrentTarget(reason, user);
            return frozenView();
        }
        List<Candidate> candidates = candidates();
        List<String> missing = candidates.stream()
                .filter(candidate -> !candidate.ready())
                .map(Candidate::documentFormat)
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MULTIFORMAT_RELEASE_PREREQUISITES_MISSING",
                    "缺少可冻结的当前 Revision/Child/SourceLocator："
                            + String.join(", ", missing)
            );
        }

        List<Map<String, Object>> coverage = candidates.stream()
                .map(this::coverageSnapshot)
                .toList();
        Map<String, Object> sourceManifest = new LinkedHashMap<>();
        sourceManifest.put("version", VERSION);
        sourceManifest.put("schemaVersion", SCHEMA_VERSION);
        sourceManifest.put("frozenAt", Instant.now().toString());
        sourceManifest.put("frozenBy", actor.toString());
        sourceManifest.put("reason", reason.strip());
        sourceManifest.put("formats", coverage);
        sourceManifest.put(
                "securitySuiteVersion",
                MultiformatSecurityProbe.SUITE_VERSION
        );
        sourceManifest.put("securitySuiteHash", securityProbe.suiteHash());
        sourceManifest.put("securityProbes", securityProbe.definitions());
        String manifestJson = json(sourceManifest);
        String sourceHash = sha256(
                json(coverage) + ":" + securityProbe.suiteHash()
        );

        DatasetSeed dataset = new DatasetSeed(
                DATASET_ID,
                "multiformat-release",
                "Multi-format Release",
                "Phase 18A 八格式文件、Revision、Child 与 SourceLocator 冻结事实",
                VERSION_ID,
                VERSION,
                SCHEMA_VERSION,
                CASE_TYPE,
                "phase18a:" + sourceHash.substring(0, 24),
                "MIXED",
                sourceHash,
                manifestJson
        );
        List<CaseSeed> mainlineCases = candidates.stream()
                .map(this::caseSeed)
                .toList();
        List<CaseSeed> cases = new ArrayList<>(mainlineCases);
        cases.addAll(securityProbe.definitions().stream()
                .map(this::securityCaseSeed)
                .toList());
        evaluations.seed(dataset, cases);
        for (int index = 0; index < candidates.size(); index++) {
            persistFact(mainlineCases.get(index), candidates.get(index));
        }

        TargetView target = targets.targets().stream()
                .filter(item ->
                        item.subjectType()
                                == SubjectType.MULTIFORMAT_RELEASE
                                && "ACTIVE".equals(item.targetKind())
                )
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Multiformat Evaluation Target is unavailable"
                ));
        SubjectView subject = evaluations.freezeMultiformatSubject(
                VERSION_ID, VERSION, sourceHash, coverage, target, user
        );
        if (!VERSION_ID.equals(subject.datasetVersionId())) {
            throw new IllegalStateException(
                    "Multiformat Subject is not bound to the frozen DatasetVersion"
            );
        }
        return frozenView();
    }

    private MultiformatReleaseView previewView() {
        List<MultiformatFormatView> formats = candidates().stream()
                .map(this::candidateView)
                .toList();
        int ready = (int) formats.stream()
                .filter(item -> "READY".equals(item.mappingStatus()))
                .count();
        return new MultiformatReleaseView(
                "PREVIEW", VERSION, null, null, null, null, null,
                ready, FORMATS.size(), formats
        );
    }

    private MultiformatReleaseView frozenView() {
        List<MultiformatFormatView> formats = storedFacts();
        if (formats.size() != FORMATS.size()) {
            throw new IllegalStateException(
                    "Frozen multiformat DatasetVersion is incomplete"
            );
        }
        SubjectIdentity subject = jdbc.query(
                """
                SELECT id, readiness_status, blocked_reason, snapshot_hash
                FROM evaluation_subjects
                WHERE dataset_version_id = ?
                  AND subject_type = 'MULTIFORMAT_RELEASE'
                ORDER BY
                  CASE readiness_status WHEN 'READY' THEN 0 ELSE 1 END,
                  created_at DESC, id DESC
                LIMIT 1
                """,
                (rs, row) -> new SubjectIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getString("readiness_status"),
                        rs.getString("blocked_reason"),
                        rs.getString("snapshot_hash")
                ),
                VERSION_ID
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Frozen multiformat EvaluationSubject is missing"
                )
        );
        int ready = (int) formats.stream()
                .filter(item -> "READY".equals(item.mappingStatus()))
                .count();
        return new MultiformatReleaseView(
                "FROZEN", VERSION, VERSION_ID, subject.id(),
                subject.readinessStatus(), subject.blockedReason(),
                subject.snapshotHash(), ready, FORMATS.size(), formats
        );
    }

    private SubjectView freezeCurrentTarget(
            String reason,
            PlatformUserPrincipal user
    ) {
        List<MultiformatFormatView> facts = storedFacts();
        if (facts.size() != FORMATS.size()
                || facts.stream().anyMatch(item ->
                !"READY".equals(item.mappingStatus()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MULTIFORMAT_RELEASE_FACTS_STALE",
                    "冻结的多格式事实已失效，不能创建 Phase 18D Subject"
            );
        }
        TargetView target = targets.targets().stream()
                .filter(item -> item.subjectType()
                        == SubjectType.MULTIFORMAT_RELEASE)
                .filter(item -> "ACTIVE".equals(item.targetKind()))
                .filter(item -> "READY".equals(item.readinessStatus()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "MULTIFORMAT_RELEASE_TARGET_NOT_READY",
                        "Phase 18D 真实评测 Target 尚未就绪"
                ));
        String sourceHash = jdbc.queryForObject(
                """
                SELECT source_sha256
                FROM evaluation_dataset_versions
                WHERE id = ? AND version = ?
                """,
                String.class, VERSION_ID, VERSION
        );
        List<Map<String, Object>> coverage = facts.stream()
                .map(this::storedCoverageSnapshot)
                .toList();
        return evaluations.freezeMultiformatSubject(
                VERSION_ID,
                VERSION,
                sourceHash,
                coverage,
                target,
                user
        );
    }

    private Map<String, Object> storedCoverageSnapshot(
            MultiformatFormatView fact
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentFormat", fact.documentFormat());
        snapshot.put("documentId", fact.documentId().toString());
        snapshot.put("revisionId", fact.revisionId().toString());
        snapshot.put("fileSha256", fact.fileSha256());
        snapshot.put("sourceLicense", fact.sourceLicense());
        snapshot.put("parserProvider", fact.expectedParserProvider());
        snapshot.put("parserVersion", fact.expectedParserVersion());
        snapshot.put("chunkerVersion", fact.expectedChunkerVersion());
        snapshot.put("locatorKind", fact.locatorKind());
        snapshot.put("locatorHash", fact.locatorHash());
        return snapshot;
    }

    private List<Candidate> candidates() {
        List<Candidate> rows = jdbc.query(
                """
                WITH ranked AS (
                    SELECT revision.document_format,
                           document.id AS document_id,
                           revision.id AS revision_id,
                           anchor.child_chunk_id,
                           anchor.source_span_id,
                           document.title AS document_title,
                           document.visibility AS document_visibility,
                           document.acl_version,
                           revision.original_filename,
                           revision.content_hash AS file_sha256,
                           COALESCE(revision.source_title, document.title)
                               AS source_title,
                           COALESCE(revision.source_license, 'PROJECT')
                               AS source_license,
                           revision.source_url,
                           COALESCE(
                               revision.source_revision,
                               'revision-' || revision.revision_number
                           ) AS source_revision,
                           COALESCE(
                               revision.parser_provider,
                               CASE revision.document_format
                                 WHEN 'PDF' THEN 'PDFBOX'
                                 WHEN 'TXT' THEN 'TEXT'
                                 WHEN 'MARKDOWN' THEN 'MARKDOWN'
                                 WHEN 'HTML' THEN 'HTML'
                                 WHEN 'DOCX' THEN 'DOCX_POI'
                                 WHEN 'PPTX' THEN 'PPTX_POI'
                                 WHEN 'XLSX' THEN 'XLSX_POI'
                                 WHEN 'CSV' THEN 'CSV_STREAM'
                               END
                           ) AS parser_provider,
                           anchor.parser_version,
                           anchor.chunker_version,
                           anchor.locator_kind,
                           anchor.source_label,
                           anchor.anchor_text,
                           anchor.source_text_hash,
                           anchor.locator::TEXT AS locator,
                           ROW_NUMBER() OVER (
                               PARTITION BY revision.document_format
                               ORDER BY
                                 CASE
                                   WHEN document.title LIKE
                                        '[EVAL][PUBLIC] Phase %' THEN 0
                                   WHEN document.title LIKE 'Phase %' THEN 1
                                   WHEN document.title LIKE
                                        '[EVAL][PUBLIC]%' THEN 2
                                   ELSE 3
                                 END,
                                 document.updated_at DESC,
                                 document.id
                           ) AS position
                    FROM documents document
                    JOIN document_revisions revision
                      ON revision.id = document.current_revision_id
                    JOIN LATERAL (
                        SELECT child.id AS child_chunk_id,
                               span.id AS source_span_id,
                               child.parser_version,
                               child.chunker_version,
                               locator.locator_kind,
                               locator.source_label,
                               child.text AS anchor_text,
                               locator.source_text_hash,
                               jsonb_build_object(
                                   'kind', locator.locator_kind,
                                   'startSourceUnitId',
                                       locator.start_source_unit_id,
                                   'endSourceUnitId',
                                       locator.end_source_unit_id,
                                   'startUnitAddress',
                                       locator.start_unit_address,
                                   'endUnitAddress',
                                       locator.end_unit_address,
                                   'startOffset', locator.start_offset,
                                   'endOffset', locator.end_offset,
                                   'address', locator.address,
                                   'sourceLabel', locator.source_label,
                                   'sourceTextHash',
                                       locator.source_text_hash,
                                   'normalizationVersion',
                                       locator.normalization_version
                               ) AS locator
                        FROM chunks child
                        JOIN source_spans span
                          ON span.chunk_id = child.id
                        JOIN source_locator_projection locator
                          ON locator.source_kind = 'SOURCE_SPAN'
                         AND locator.source_id = span.id
                        WHERE child.revision_id = revision.id
                          AND child.document_id = document.id
                          AND child.chunk_type = 'CHILD'
                        ORDER BY child.chunk_order, span.span_order
                        LIMIT 1
                    ) anchor ON TRUE
                    WHERE document.deleted_at IS NULL
                      AND revision.status = 'READY'
                )
                SELECT *
                FROM ranked
                WHERE position = 1
                ORDER BY CASE document_format
                    WHEN 'PDF' THEN 1
                    WHEN 'TXT' THEN 2
                    WHEN 'MARKDOWN' THEN 3
                    WHEN 'HTML' THEN 4
                    WHEN 'DOCX' THEN 5
                    WHEN 'PPTX' THEN 6
                    WHEN 'XLSX' THEN 7
                    WHEN 'CSV' THEN 8
                    ELSE 99
                END
                """,
                this::candidateRow
        );
        Map<String, Candidate> byFormat = new LinkedHashMap<>();
        rows.forEach(row -> byFormat.put(row.documentFormat(), row));
        List<Candidate> complete = new ArrayList<>();
        for (String format : FORMATS) {
            complete.add(byFormat.getOrDefault(
                    format, Candidate.missing(format)
            ));
        }
        return List.copyOf(complete);
    }

    private List<MultiformatFormatView> storedFacts() {
        return jdbc.query(
                """
                SELECT fact.*, case_row.mapping_status,
                       CASE
                         WHEN document.deleted_at IS NULL
                          AND document.current_revision_id = fact.revision_id
                          AND document.acl_version = fact.acl_version
                          AND document.visibility::TEXT = fact.document_visibility
                          AND revision.status = 'READY'
                          AND revision.content_hash = fact.file_sha256
                          AND revision.parser_provider::TEXT =
                              fact.expected_parser_provider
                          AND child.chunk_type = 'CHILD'
                          AND child.searchable
                          AND child.parser_version = fact.expected_parser_version
                          AND child.chunker_version = fact.expected_chunker_version
                          AND span.source_text_hash = fact.source_text_hash
                          AND locator.locator_kind::TEXT = fact.locator_kind
                          AND locator.source_label = fact.source_label
                          AND jsonb_build_object(
                              'kind', locator.locator_kind,
                              'startSourceUnitId', locator.start_source_unit_id,
                              'endSourceUnitId', locator.end_source_unit_id,
                              'startUnitAddress', locator.start_unit_address,
                              'endUnitAddress', locator.end_unit_address,
                              'startOffset', locator.start_offset,
                              'endOffset', locator.end_offset,
                              'address', locator.address,
                              'sourceLabel', locator.source_label,
                              'sourceTextHash', locator.source_text_hash,
                              'normalizationVersion', locator.normalization_version
                          ) = fact.locator
                         THEN TRUE
                         ELSE FALSE
                       END AS current_mapping
                FROM evaluation_multiformat_case_facts fact
                JOIN evaluation_cases case_row ON case_row.id = fact.case_id
                LEFT JOIN documents document
                  ON document.id = fact.document_id
                LEFT JOIN document_revisions revision
                  ON revision.id = fact.revision_id
                 AND revision.document_id = fact.document_id
                LEFT JOIN chunks child
                  ON child.id = fact.child_chunk_id
                 AND child.document_id = fact.document_id
                 AND child.revision_id = fact.revision_id
                LEFT JOIN source_spans span
                  ON span.id = fact.source_span_id
                 AND span.chunk_id = fact.child_chunk_id
                 AND span.document_id = fact.document_id
                 AND span.revision_id = fact.revision_id
                LEFT JOIN source_locator_projection locator
                  ON locator.source_kind = 'SOURCE_SPAN'
                 AND locator.source_id = fact.source_span_id
                 AND locator.document_id = fact.document_id
                 AND locator.revision_id = fact.revision_id
                WHERE fact.dataset_version_id = ?
                ORDER BY CASE fact.document_format
                    WHEN 'PDF' THEN 1
                    WHEN 'TXT' THEN 2
                    WHEN 'MARKDOWN' THEN 3
                    WHEN 'HTML' THEN 4
                    WHEN 'DOCX' THEN 5
                    WHEN 'PPTX' THEN 6
                    WHEN 'XLSX' THEN 7
                    WHEN 'CSV' THEN 8
                    ELSE 99
                END
                """,
                this::storedFactRow,
                VERSION_ID
        );
    }

    private CaseSeed caseSeed(Candidate candidate) {
        UUID caseId = stableId(
                "evaluation-case:" + VERSION + ":"
                        + candidate.documentFormat().toLowerCase()
        );
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", evaluationQuestion(candidate));
        input.put("documentFormat", candidate.documentFormat());
        input.put("documentId", candidate.documentId().toString());
        input.put("revisionId", candidate.revisionId().toString());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("answerBehavior", "SUPPORTED_WITH_CITATION");
        expected.put("documentId", candidate.documentId().toString());
        expected.put("revisionId", candidate.revisionId().toString());
        expected.put("childChunkId", candidate.childChunkId().toString());
        expected.put("sourceSpanId", candidate.sourceSpanId().toString());
        expected.put("locatorKind", candidate.locatorKind());
        expected.put("locatorHash", candidate.locatorHash());
        expected.put("sourceLabel", candidate.sourceLabel());
        expected.put(
                "securityAssertions",
                securityAssertions(candidate.documentFormat())
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sampleKind", "MAINLINE");
        metadata.put("documentFormat", candidate.documentFormat());
        metadata.put("originalFilename", candidate.originalFilename());
        metadata.put("fileSha256", candidate.fileSha256());
        metadata.put("sourceLicense", candidate.sourceLicense());
        metadata.put("querySource", "ANCHOR_FIRST_LINE");
        return new CaseSeed(
                caseId, VERSION_ID,
                "format:" + candidate.documentFormat().toLowerCase(),
                evaluationLanguage(candidate), CASE_TYPE,
                json(input), json(expected),
                "READY", "[]", json(metadata)
        );
    }

    private CaseSeed securityCaseSeed(
            MultiformatSecurityProbe.ProbeDefinition probe
    ) {
        UUID caseId = stableId(
                "evaluation-case:" + VERSION + ":security:" + probe.key()
        );
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("securityProbe", probe.key());
        input.put("documentFormat", probe.documentFormat());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("suiteVersion", MultiformatSecurityProbe.SUITE_VERSION);
        expected.put("inputSha256", probe.inputSha256());
        expected.put("outcome", "REJECTED_OR_INERT");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sampleKind", "SECURITY");
        metadata.put("documentFormat", probe.documentFormat());
        metadata.put("sourceLicense", "PROJECT");
        return new CaseSeed(
                caseId,
                VERSION_ID,
                "security:" + probe.documentFormat().toLowerCase()
                        + ":" + probe.key(),
                "MIXED",
                "MULTIFORMAT_SECURITY",
                json(input),
                json(expected),
                "READY",
                "[]",
                json(metadata)
        );
    }

    private void persistFact(CaseSeed seed, Candidate candidate) {
        String security = json(securityAssertions(
                candidate.documentFormat()
        ));
        jdbc.update(
                """
                INSERT INTO evaluation_multiformat_case_facts (
                    case_id, dataset_version_id, document_format,
                    document_id, revision_id, child_chunk_id, source_span_id,
                    document_title, document_visibility, acl_version,
                    original_filename, file_sha256,
                    source_title, source_license, source_url, source_revision,
                    expected_parser_provider, expected_parser_version,
                    expected_chunker_version, locator_kind, source_label,
                    source_text_hash, locator, locator_hash,
                    security_assertions
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS JSONB)
                )
                ON CONFLICT (case_id) DO NOTHING
                """,
                seed.id(), VERSION_ID, candidate.documentFormat(),
                candidate.documentId(), candidate.revisionId(),
                candidate.childChunkId(), candidate.sourceSpanId(),
                candidate.documentTitle(), candidate.documentVisibility(),
                candidate.aclVersion(), candidate.originalFilename(),
                candidate.fileSha256(), candidate.sourceTitle(),
                candidate.sourceLicense(), candidate.sourceUrl(),
                candidate.sourceRevision(), candidate.parserProvider(),
                candidate.parserVersion(), candidate.chunkerVersion(),
                candidate.locatorKind(), candidate.sourceLabel(),
                candidate.sourceTextHash(), candidate.locatorJson(),
                candidate.locatorHash(), security
        );
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_multiformat_case_facts
                WHERE case_id = ? AND dataset_version_id = ?
                  AND document_format = ? AND document_id = ?
                  AND revision_id = ? AND child_chunk_id = ?
                  AND source_span_id = ? AND file_sha256 = ?
                  AND locator_hash = ?
                """,
                Integer.class, seed.id(), VERSION_ID,
                candidate.documentFormat(), candidate.documentId(),
                candidate.revisionId(), candidate.childChunkId(),
                candidate.sourceSpanId(), candidate.fileSha256(),
                candidate.locatorHash()
        );
        if (exact == null || exact != 1) {
            throw new IllegalStateException(
                    "Multiformat case fact changed: "
                            + candidate.documentFormat()
            );
        }
    }

    private Map<String, Object> coverageSnapshot(Candidate candidate) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentFormat", candidate.documentFormat());
        snapshot.put("documentId", candidate.documentId().toString());
        snapshot.put("revisionId", candidate.revisionId().toString());
        snapshot.put("fileSha256", candidate.fileSha256());
        snapshot.put("sourceLicense", candidate.sourceLicense());
        snapshot.put("parserProvider", candidate.parserProvider());
        snapshot.put("parserVersion", candidate.parserVersion());
        snapshot.put("chunkerVersion", candidate.chunkerVersion());
        snapshot.put("locatorKind", candidate.locatorKind());
        snapshot.put("locatorHash", candidate.locatorHash());
        return snapshot;
    }

    private MultiformatFormatView candidateView(Candidate candidate) {
        return new MultiformatFormatView(
                candidate.documentFormat(),
                candidate.ready() ? "READY" : "UNMAPPED",
                candidate.ready() ? null
                        : "缺少当前 READY Revision、Child 或 SourceLocator",
                candidate.documentId(), candidate.revisionId(),
                candidate.childChunkId(), candidate.sourceSpanId(),
                candidate.documentTitle(), candidate.documentVisibility(),
                candidate.aclVersion(), candidate.originalFilename(),
                candidate.fileSha256(), candidate.sourceTitle(),
                candidate.sourceLicense(), candidate.sourceRevision(),
                candidate.parserProvider(), candidate.parserVersion(),
                candidate.chunkerVersion(), candidate.locatorKind(),
                candidate.sourceLabel(), candidate.locatorHash(),
                securityAssertions(candidate.documentFormat())
        );
    }

    private MultiformatFormatView storedFactRow(
            ResultSet rs,
            int row
    ) throws SQLException {
        boolean current = rs.getBoolean("current_mapping");
        return new MultiformatFormatView(
                rs.getString("document_format"),
                current ? "READY" : "BLOCKED_PREREQUISITE",
                current ? null : "CURRENT_REVISION_OR_LOCATOR_CHANGED",
                rs.getObject("document_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getObject("child_chunk_id", UUID.class),
                rs.getObject("source_span_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("document_visibility"),
                rs.getLong("acl_version"),
                rs.getString("original_filename"),
                rs.getString("file_sha256"),
                rs.getString("source_title"),
                rs.getString("source_license"),
                rs.getString("source_revision"),
                rs.getString("expected_parser_provider"),
                rs.getString("expected_parser_version"),
                rs.getString("expected_chunker_version"),
                rs.getString("locator_kind"),
                rs.getString("source_label"),
                rs.getString("locator_hash"),
                strings(rs.getString("security_assertions"))
        );
    }

    private Candidate candidateRow(ResultSet rs, int row) throws SQLException {
        String locatorJson = rs.getString("locator");
        return new Candidate(
                rs.getString("document_format"),
                rs.getObject("document_id", UUID.class),
                rs.getObject("revision_id", UUID.class),
                rs.getObject("child_chunk_id", UUID.class),
                rs.getObject("source_span_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("document_visibility"),
                rs.getLong("acl_version"),
                rs.getString("original_filename"),
                rs.getString("file_sha256"),
                rs.getString("source_title"),
                rs.getString("source_license"),
                rs.getString("source_url"),
                rs.getString("source_revision"),
                rs.getString("parser_provider"),
                rs.getString("parser_version"),
                rs.getString("chunker_version"),
                rs.getString("locator_kind"),
                rs.getString("source_label"),
                rs.getString("anchor_text"),
                rs.getString("source_text_hash"),
                locatorJson,
                sha256(locatorJson)
        );
    }

    private boolean versionExists() {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM evaluation_dataset_versions version
                JOIN evaluation_datasets dataset
                  ON dataset.id = version.dataset_id
                WHERE dataset.dataset_key = 'multiformat-release'
                  AND version.version = ?
                """,
                Integer.class, VERSION
        );
        return count != null && count == 1;
    }

    private List<String> securityAssertions(String format) {
        return switch (format) {
            case "PDF" -> List.of(
                    "FORMAT_SIGNATURE", "ENCRYPTED_DOCUMENT",
                    "RESOURCE_LIMIT"
            );
            case "TXT" -> List.of(
                    "BINARY_NUL_REJECTED", "AMBIGUOUS_ENCODING_REJECTED",
                    "RESOURCE_LIMIT"
            );
            case "MARKDOWN" -> List.of(
                    "RAW_HTML_NON_EXECUTABLE", "RESOURCE_LIMIT"
            );
            case "HTML" -> List.of(
                    "XSS_SANITIZED", "REMOTE_RESOURCE_BLOCKED",
                    "JAVASCRIPT_URL_BLOCKED"
            );
            case "DOCX", "PPTX", "XLSX" -> List.of(
                    "OOXML_ZIP_BOMB_REJECTED",
                    "EXTERNAL_RELATIONSHIP_REJECTED",
                    "MACRO_OR_ENCRYPTION_REJECTED"
            );
            case "CSV" -> List.of(
                    "FORMULA_NOT_EXECUTED",
                    "ENCODING_AND_DELIMITER_BOUNDED",
                    "RESOURCE_LIMIT"
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported document format " + format
            );
        };
    }

    private String evaluationQuestion(Candidate candidate) {
        if ("PDF".equals(candidate.documentFormat())) {
            return "How does the platform preserve traceability for grounded answers?";
        }
        if ("PPTX".equals(candidate.documentFormat())) {
            return "BM25 和向量召回合并后执行几次 Rerank？";
        }
        String topic = candidate.anchorText().lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(candidate.documentTitle());
        if (topic.length() > 160) {
            topic = topic.substring(0, 160).strip();
        }
        return "请根据文档概括“" + topic + "”的核心内容。";
    }

    private String evaluationLanguage(Candidate candidate) {
        return "PDF".equals(candidate.documentFormat()) ? "en" : "zh";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode multiformat evaluation data",
                    exception
            );
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(
                    value, new TypeReference<List<String>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to read security assertions",
                    exception
            );
        }
    }

    private static UUID actor(PlatformUserPrincipal user) {
        if (user == null) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"
            );
        }
        return user.id();
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Candidate(
            String documentFormat,
            UUID documentId,
            UUID revisionId,
            UUID childChunkId,
            UUID sourceSpanId,
            String documentTitle,
            String documentVisibility,
            long aclVersion,
            String originalFilename,
            String fileSha256,
            String sourceTitle,
            String sourceLicense,
            String sourceUrl,
            String sourceRevision,
            String parserProvider,
            String parserVersion,
            String chunkerVersion,
            String locatorKind,
            String sourceLabel,
            String anchorText,
            String sourceTextHash,
            String locatorJson,
            String locatorHash
    ) {
        static Candidate missing(String format) {
            return new Candidate(
                    format, null, null, null, null, null, null, 0,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null
                    , null
            );
        }

        boolean ready() {
            return documentId != null
                    && revisionId != null
                    && childChunkId != null
                    && sourceSpanId != null
                    && locatorHash != null;
        }
    }

    private record SubjectIdentity(
            UUID id,
            String readinessStatus,
            String blockedReason,
            String snapshotHash
    ) {
    }
}
