export type UserRole = "ADMIN" | "USER";
export type DocumentVisibility = "ALL_USERS" | "RESTRICTED";
export type DocumentFormat =
  | "PDF"
  | "TXT"
  | "MARKDOWN"
  | "HTML"
  | "DOCX"
  | "PPTX"
  | "XLSX"
  | "CSV";

export type SourceLocatorKind =
  | "PAGE"
  | "LINE_RANGE"
  | "HEADING_BLOCK"
  | "DOM_PATH"
  | "PARAGRAPH"
  | "TABLE_CELL"
  | "SLIDE_SHAPE"
  | "CELL_RANGE";

export interface SourceLocator {
  kind: SourceLocatorKind;
  startUnit: string | null;
  endUnit: string | null;
  startOffset: number;
  endOffset: number;
  address: string | null;
  sourceTextHash: string | null;
  normalizationVersion: string;
  startPage?: number | null;
  endPage?: number | null;
  sourceLabel?: string | null;
}

export interface SourceLocationFields {
  documentFormat?: DocumentFormat | null;
  sourceLocator?: SourceLocator | null;
  sourceLabel?: string | null;
  startPage?: number | null;
  endPage?: number | null;
}

export type ParserProvider =
  | "PDFBOX"
  | "MINERU"
  | "TEXT"
  | "MARKDOWN"
  | "HTML"
  | "DOCX_POI"
  | "PPTX_POI"
  | "XLSX_POI"
  | "CSV_STREAM";

export interface ParserProviderCapability {
  provider: ParserProvider;
  available: boolean;
  reasonCode: string | null;
  runtimeStatus?: DocumentRuntimeStatus;
  policyStatus?: DocumentPolicyStatus;
  policyVersion?: number;
  runningJobs?: number;
}

export type DocumentRuntimeStatus = "AVAILABLE" | "UNAVAILABLE" | "DISABLED" | "HEARTBEAT_STALE";
export type DocumentPolicyStatus = "ENABLED" | "DISABLED";

export interface DocumentFormatCapability {
  format: DocumentFormat;
  enabled: boolean;
  runtimeStatus?: DocumentRuntimeStatus;
  policyStatus?: DocumentPolicyStatus;
  policyVersion?: number;
  runningJobs?: number;
  displayName: string;
  extensions: string[];
  mediaTypes: string[];
  maxFileSizeBytes: number;
  locatorKinds: SourceLocatorKind[];
  parserProviders: ParserProviderCapability[];
  parserOverrideAllowed: boolean;
}

export interface DocumentFormatsResponse {
  schemaVersion: string;
  formats: DocumentFormatCapability[];
}

export interface DocumentRuntimePolicyEvent {
  id: number;
  policyKey: string;
  scope: "FORMAT" | "PARSER";
  documentFormat: DocumentFormat;
  parserProvider: ParserProvider | null;
  action: "DISABLE" | "RESTORE";
  previousStatus: DocumentPolicyStatus;
  newStatus: DocumentPolicyStatus;
  policyVersion: number;
  reason: string;
  actorUsername: string;
  createdAt: string;
}

export interface CurrentUser {
  id: string;
  username: string;
  role: UserRole;
}

export type EvaluationCaseType =
  | "RETRIEVAL"
  | "LOCAL_GRAPH"
  | "GLOBAL_GRAPH"
  | "ANSWER_CITATION"
  | "MULTI_TURN"
  | "INTENT"
  | "PARSER"
  | "MULTIFORMAT_RELEASE";

export interface EvaluationDatasetVersion {
  id: string;
  version: string;
  schemaVersion: string;
  caseType: EvaluationCaseType;
  sourceRevision: string;
  sourceLicense: string;
  sourceSha256: string;
  caseCount: number;
  mappedCases: number;
  unmappedCases: number;
  notRequiredCases: number;
  readyCases: number;
  blockedPrerequisiteCases: number;
  createdAt: string;
}

export interface EvaluationDataset {
  id: string;
  key: string;
  title: string;
  description: string;
  versions: EvaluationDatasetVersion[];
}

export interface EvaluationCaseMapping {
  caseId: string;
  caseKey: string;
  language: string;
  storedStatus: "MAPPED" | "UNMAPPED" | "NOT_REQUIRED" | "READY" | "BLOCKED_PREREQUISITE";
  effectiveStatus: "MAPPED" | "UNMAPPED" | "NOT_REQUIRED" | "READY" | "BLOCKED_PREREQUISITE";
  missingEvidenceKeys: string[];
  documentFormat: string | null;
  originalFilename: string | null;
  fileSha256: string | null;
  sourceLicense: string | null;
  documentId: string | null;
  revisionId: string | null;
  childChunkId: string | null;
  sourceSpanId: string | null;
  locatorKind: string | null;
  sourceLabel: string | null;
  locatorHash: string | null;
  blockedReason: string | null;
}

export interface EvaluationMappingPage {
  datasetVersionId: string;
  page: number;
  size: number;
  total: number;
  items: EvaluationCaseMapping[];
}

export type EvaluationSubjectType =
  | "RETRIEVAL"
  | "LOCAL_GRAPH"
  | "GLOBAL_GRAPH"
  | "ANSWER_CITATION"
  | "MULTI_TURN"
  | "INTENT"
  | "PARSER"
  | "MULTIFORMAT_RELEASE";

export interface EvaluationSubject {
  id: string;
  name: string;
  subjectType: EvaluationSubjectType;
  targetId: string | null;
  targetKey: string | null;
  targetKind: "ACTIVE" | "READY" | "LEGACY";
  datasetVersionId: string | null;
  datasetVersion: string | null;
  snapshot: Record<string, unknown>;
  snapshotHash: string;
  readinessStatus: "READY" | "BLOCKED_PREREQUISITE";
  blockedReason: string | null;
  createdAt: string;
}

export interface MultiformatReleaseFormat {
  documentFormat: string;
  mappingStatus: "READY" | "UNMAPPED" | "BLOCKED_PREREQUISITE";
  blockedReason: string | null;
  documentId: string | null;
  revisionId: string | null;
  childChunkId: string | null;
  sourceSpanId: string | null;
  documentTitle: string | null;
  documentVisibility: "ALL_USERS" | "RESTRICTED" | null;
  aclVersion: number;
  originalFilename: string | null;
  fileSha256: string | null;
  sourceTitle: string | null;
  sourceLicense: string | null;
  sourceRevision: string | null;
  expectedParserProvider: string | null;
  expectedParserVersion: string | null;
  expectedChunkerVersion: string | null;
  locatorKind: string | null;
  sourceLabel: string | null;
  locatorHash: string | null;
  securityAssertions: string[];
}

export interface MultiformatRelease {
  state: "PREVIEW" | "FROZEN";
  version: string;
  datasetVersionId: string | null;
  subjectId: string | null;
  subjectReadinessStatus: "READY" | "BLOCKED_PREREQUISITE" | null;
  subjectBlockedReason: string | null;
  subjectSnapshotHash: string | null;
  readyFormats: number;
  totalFormats: number;
  formats: MultiformatReleaseFormat[];
}

export interface EvaluationTarget {
  id: string;
  targetKey: string;
  subjectType: EvaluationSubjectType;
  targetKind: "ACTIVE" | "READY";
  snapshot: Record<string, unknown>;
  snapshotHash: string;
  readinessStatus: "READY" | "BLOCKED_PREREQUISITE";
  blockedReason: string | null;
  createdAt: string;
}

export type EvaluationRunStatus =
  | "PENDING"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "BLOCKED_PREREQUISITE";

export interface EvaluationRun {
  id: string;
  evaluationSubjectId: string;
  subjectName: string;
  subjectType: EvaluationSubjectType;
  datasetVersionId: string;
  datasetKey: string;
  datasetVersion: string;
  originalRunId: string | null;
  status: EvaluationRunStatus;
  evaluatorVersion: string;
  totalCases: number;
  completedCases: number;
  succeededCases: number;
  failedCases: number;
  blockedCases: number;
  cancelRequested: boolean;
  attempt: number;
  leaseOwner: string | null;
  leaseExpiresAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface EvaluationRunPage {
  page: number;
  size: number;
  total: number;
  items: EvaluationRun[];
}

export interface EvaluationMetric {
  key: string;
  status: "MEASURED" | "NOT_MEASURED" | "BLOCKED_PREREQUISITE";
  value: number | null;
  details: Record<string, unknown>;
}

export interface EvaluationResult {
  id: string;
  caseId: string;
  caseKey: string;
  language: string;
  caseType: EvaluationCaseType;
  status: "SUCCEEDED" | "FAILED" | "BLOCKED_PREREQUISITE";
  output: Record<string, unknown>;
  errorCode: string | null;
  errorMessage: string | null;
  durationMs: number;
  createdAt: string;
  metrics: EvaluationMetric[];
}

export interface EvaluationResultPage {
  runId: string;
  page: number;
  size: number;
  total: number;
  items: EvaluationResult[];
}

export interface EvaluationPerformanceStats {
  samples: number;
  p50Ms: number | null;
  p95Ms: number | null;
  maxMs: number | null;
  errorRate: number;
}

export interface EvaluationFormatReleaseResult {
  documentFormat: DocumentFormat;
  caseId: string;
  caseKey: string;
  status: "SUCCEEDED" | "FAILED" | "BLOCKED_PREREQUISITE";
  documentId: string | null;
  revisionId: string | null;
  locatorKind: SourceLocatorKind | null;
  sourceLabel: string | null;
  hardGatePassed: boolean;
  citationResolved: boolean;
  degraded: boolean;
  degradationCode: string | null;
  errorCode: string | null;
  durationMs: number;
}

export interface EvaluationReleaseReport {
  runId: string;
  runStatus: EvaluationRunStatus;
  evaluatorVersion: string;
  datasetVersion: string;
  subjectId: string;
  subjectSnapshotHash: string;
  frozenSubject: Record<string, unknown>;
  totalCases: number;
  succeededCases: number;
  failedCases: number;
  blockedCases: number;
  locatorResolutionRate: number | null;
  citationResolutionRate: number | null;
  hardGateFailures: number;
  degradationCount: number;
  executionBaseline: {
    queryProfileVersion: string;
    plannerCallCount: number;
    retrievalCallCount: number;
    rerankCallCount: number;
    queryDegradedCount: number;
    memoryContractVersion: string;
    memoryInjectedCount: number;
    memoryUsedCount: number;
    memoryTokenCount: number;
  };
  performance: Record<string, EvaluationPerformanceStats>;
  formats: EvaluationFormatReleaseResult[];
  blockers: string[];
  unmeasuredItems: string[];
  recommendation: "READY_FOR_BASELINE" | "BLOCKED";
}

export interface EvaluationRunEvent {
  id: number;
  sequence: number;
  eventType: string;
  fromStatus: EvaluationRunStatus | null;
  toStatus: EvaluationRunStatus;
  details: Record<string, unknown>;
  createdAt: string;
}

export interface RuntimeAnswerProfile {
  enabled: boolean;
  modelProvider: string;
  modelId: string;
  modelRevision: string;
  endpointIdentity: string;
  promptVersion: string;
  orchestrationVersion: string;
  timeoutMs: number;
  maxOutputTokens: number;
  remoteEvidenceAllowed: boolean;
  remoteMemoryAllowed: boolean;
}

export interface AnswerProfile {
  version: string;
  modelProvider: string;
  modelId: string;
  modelRevision: string;
  endpointIdentity: string;
  promptVersion: string;
  orchestrationVersion: string;
  timeoutMs: number;
  maxOutputTokens: number;
  remoteEvidenceAllowed: boolean;
  remoteMemoryAllowed: boolean;
  reason: string;
  published: boolean;
  createdAt: string;
}

export interface EvaluationMetricDelta {
  metricKey: string;
  leftValue: number | null;
  rightValue: number | null;
  delta: number | null;
  leftMeasured: number;
  rightMeasured: number;
}

export interface EvaluationSliceDelta {
  dimension: string;
  value: string;
  leftSucceeded: number;
  leftFailed: number;
  leftBlocked: number;
  rightSucceeded: number;
  rightFailed: number;
  rightBlocked: number;
}

export interface EvaluationCaseDelta {
  caseKey: string;
  language: string;
  caseType: EvaluationCaseType;
  leftStatus: string;
  rightStatus: string;
}

export interface EvaluationCompare {
  left: EvaluationRun;
  right: EvaluationRun;
  sameDatasetVersion: boolean;
  comparisonReason: string;
  metrics: EvaluationMetricDelta[];
  slices: EvaluationSliceDelta[];
  changedCases: EvaluationCaseDelta[];
}

export interface EvaluationBaseline {
  id: string;
  name: string;
  baselineKey: string;
  datasetVersionId: string;
  evaluationSubjectId: string;
  runId: string;
  gateStatus: "PASSED" | "BLOCKED";
  gateSummary: Record<string, unknown>;
  metricSummary: Record<string, unknown>;
  judgeAdvisory: Record<string, unknown>;
  published: boolean;
  createdAt: string;
}

export interface EvaluationFeedback {
  id: string;
  chatRunId: string;
  rating: number;
  comment: string;
  consentToShare: boolean;
  redactedSample: Record<string, unknown>;
  reviewStatus: "PENDING" | "APPROVED" | "REJECTED";
  reviewReason: string | null;
  createdDatasetVersionId: string | null;
  createdAt: string;
}

export interface EvaluationWorkloadPermit {
  onlineChatActive: boolean;
  activeChatRuns: number;
  evaluationMayClaim: boolean;
  pauseReason: string | null;
}

export interface EvaluationObservability {
  enabled: boolean;
  capturedAt: string;
  windowHours: number;
  captureContent: boolean;
  highCardinalityLabels: boolean;
  retentionDays: number;
  workloadPermit: EvaluationWorkloadPermit;
  queues: Record<string, number>;
  rates: Record<string, number>;
  latencyP50Ms: Record<string, number>;
  latencyP95Ms: Record<string, number>;
  embeddingCache: Record<string, number>;
  graph: Record<string, number>;
}

export interface EvaluationGate {
  baselineId: string;
  baselineName: string;
  baselineKey: string;
  runId: string;
  runStatus: string;
  gateStatus: string;
  published: boolean;
  blockers: string[];
  metricSummary: Record<string, unknown>;
  createdAt: string;
}

export type EvaluationDrillType =
  | "MODEL_TIMEOUT"
  | "OPENSEARCH_UNAVAILABLE"
  | "GRAPH_STALE"
  | "CANARY_LEAK_SCAN";

export type EvaluationDrillExecutionMode =
  | "SIMULATION_ONLY"
  | "REAL_VERIFY";

export interface EvaluationDrill {
  id: string;
  originalDrillId: string | null;
  drillType: EvaluationDrillType;
  executionMode: EvaluationDrillExecutionMode;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  attempt: number;
  maxAttempts: number;
  cancelRequested: boolean;
  leaseOwner: string | null;
  leaseExpiresAt: string | null;
  resultSummary: Record<string, unknown>;
  errorCode: string | null;
  errorMessage: string | null;
  reason: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface EvaluationDrillEvent {
  id: number;
  sequence: number;
  eventType: string;
  fromStatus: string | null;
  toStatus: string;
  details: Record<string, unknown>;
  createdAt: string;
}

export interface ManagedUser extends CurrentUser {
  enabled: boolean;
  createdAt: string;
  securityVersion?: number;
  accessSummary?: UserAccessSummary;
}

export interface UserAccessSummary {
  platformAccess: boolean;
  publicDocuments: number;
  ownedDocuments: number;
  explicitGrants: number;
  totalDocuments: number;
}

export interface UserAccessView {
  user: ManagedUser;
  access: UserAccessSummary;
}

export interface UserDocumentGrant {
  documentId: string;
  title: string;
  visibility: DocumentVisibility;
  ownerUserId: string;
  ownerUsername: string;
  aclVersion: number;
  accessSource: "PUBLIC" | "OWNER" | "EXPLICIT" | "NONE";
  granted: boolean;
  editable: boolean;
}

export interface UserDocumentGrantPage {
  userId: string;
  page: number;
  size: number;
  total: number;
  items: UserDocumentGrant[];
}

export interface OperationImpact {
  operation: string;
  objectType: string;
  objectId: string | null;
  confirmation: string;
  factVersion: number;
  immediateEffects: string[];
  asynchronousEffects: string[];
  notAffected: string[];
  blockers: string[];
  affectedCounts: Record<string, number>;
  rollback: string;
}

export interface AdminAuditEvent {
  sourceEvent: string;
  module: string;
  action: string;
  actorId: string | null;
  actorSnapshot: string;
  objectType: string;
  objectId: string;
  objectLabel: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
  reason: string;
  occurredAt: string;
}

export interface AdminAuditPage {
  items: AdminAuditEvent[];
  nextCursor: string | null;
}

export interface EvaluationProvenance {
  suiteVersion: string;
  evidenceKey: string;
  sourceDataset: string;
  sourceTitle: string;
  sourceUrl: string;
  sourceLicense: string;
  sourceRevision: string;
  sourceContentHash: string;
}

export interface DocumentSummary {
  id: string;
  title: string;
  visibility: DocumentVisibility;
  ownerUsername: string;
  aclVersion: number;
  effectiveRevisionId: string | null;
  latestRevisionNumber: number | null;
  latestRevisionStatus: RevisionStatus | null;
  createdAt: string;
  updatedAt: string;
  documentFormat?: DocumentFormat | null;
  mediaType?: string | null;
  effectiveEvaluationProvenance?: EvaluationProvenance | null;
  latestEvaluationProvenance?: EvaluationProvenance | null;
}

export type RevisionStatus =
  | "STAGED"
  | "UPLOADED"
  | "UPLOAD_FAILED"
  | "PROCESSING"
  | "READY"
  | "FAILED"
  | "QUARANTINED"
  | "DELETED";

export interface DocumentPage {
  items: DocumentSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DocumentRevision {
  id: string;
  revisionNumber: number;
  status: RevisionStatus;
  originalFilename: string;
  fileSizeBytes: number;
  contentHash: string;
  documentFormat?: DocumentFormat | null;
  mediaType?: string | null;
  createdAt: string;
  current: boolean;
  effective: boolean;
  evaluationProvenance?: EvaluationProvenance | null;
  sourceRevisionId?: string | null;
  reparseReason?: string | null;
  reparseRequestedParser?: ParserEngine | null;
  formatChangeFrom?: DocumentFormat | null;
  formatChangeReason?: string | null;
}

export interface DocumentDetail {
  document: DocumentSummary;
  currentRevisionId: string | null;
  grantedUsers: Pick<CurrentUser, "id" | "username">[];
  revisions: DocumentRevision[];
}

export type PipelineStage = "INGEST" | "PARSE" | "CHUNK" | "EMBED" | "INDEX";
export type PipelineStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "QUARANTINED" | "CANCELLED";
export type ParserEngine = "AUTO" | "PDFBOX" | "MINERU";

export interface PipelineJob {
  id: string;
  documentId: string;
  revisionId: string;
  revisionNumber: number;
  documentTitle: string;
  documentFormat?: DocumentFormat | null;
  stage: PipelineStage;
  status: PipelineStatus;
  attempt: number;
  maxAttempts: number;
  leaseOwner: string | null;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  quarantineReason: string | null;
  parserRequestedEngine?: ParserEngine | null;
  parserSelectedEngine?: Exclude<ParserEngine, "AUTO"> | null;
  parserProvider?: ParserProvider | null;
  parserDecisionCode?: string | null;
  parserEngineVersion?: string | null;
  parserPageCount?: number | null;
  parserSourceUnitCount?: number | null;
  parserScannedCandidate?: boolean | null;
  parserOcrRequired?: boolean | null;
  parserMulticolumnCandidate?: boolean | null;
  parserTableCandidate?: boolean | null;
  parserImageCandidate?: boolean | null;
  parserModelRevision?: string | null;
  parserModelManifestChecksum?: string | null;
  parserDecidedAt?: string | null;
  parserOverrideReason?: string | null;
  createdAt: string;
  updatedAt: string;
  retryable: boolean;
  cancelable?: boolean;
}

export interface ReparseResponse {
  documentId: string;
  sourceRevisionId: string;
  revisionId: string;
  revisionNumber: number;
  pipelineJobId: string;
  targetParser: ParserEngine;
  status: RevisionStatus;
}

export interface PipelineJobPage {
  items: PipelineJob[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PipelineStageFact {
  stage: PipelineStage;
  status: PipelineStatus | "NOT_AVAILABLE";
  source: "JOB" | "REVISION" | "CHUNK_FACT" | "INDEX_PROJECTION" | "DERIVED";
  updatedAt: string | null;
}

export interface PipelineJobAttempt {
  id: string;
  stage: PipelineStage;
  status: PipelineStatus;
  attempt: number;
  maxAttempts: number;
  parserProvider: ParserProvider | null;
  parserDecisionCode: string | null;
  leaseOwner: string | null;
  leaseExpiresAt: string | null;
  heartbeatAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  quarantineReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  createdAt: string;
  updatedAt: string;
  automaticRetryExhausted: boolean;
  manualActionCode: "MANUAL_REQUEUE" | "SWITCH_PARSER" | "CHECK_PROVIDER" | "CREATE_REPARSE_REVISION" | null;
}

export interface PipelineProjectionState {
  kind: "INDEX" | "GRAPH" | "GLOBAL";
  generation: number | null;
  status: string;
  reasonCode: string | null;
}

export interface PipelineRevisionSummary {
  documentId: string;
  revisionId: string;
  revisionNumber: number;
  documentTitle: string;
  documentFormat: DocumentFormat;
  revisionStatus: string;
  currentRevision: boolean;
  aggregateStatus: PipelineStatus;
  currentStage: PipelineStage;
  updatedAt: string;
  nextActionCode: string;
  nextActionLabel: string;
  automaticRetryExhausted: boolean;
  isolationCode: string | null;
  isolationReason: string | null;
  parserProvider: ParserProvider | null;
  stages: PipelineStageFact[];
  jobs: PipelineJobAttempt[];
  downstream: {
    index: PipelineProjectionState;
    graph: PipelineProjectionState;
    global: PipelineProjectionState;
  };
}

export interface PipelineRevisionPage {
  items: PipelineRevisionSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  counts: {
    attention: number;
    failed: number;
    quarantined: number;
    running: number;
    completed: number;
  };
}

export interface PipelineRecoveryResponse {
  job: PipelineJob;
  revision: PipelineRevisionSummary;
  impact: OperationImpact;
  replayed: boolean;
}

export interface ContentBlock extends SourceLocationFields {
  id: string;
  type: string;
  order: number;
  text: string;
  headingPath: string[];
  startPage: number | null;
  endPage: number | null;
  startOffset: number;
  endOffset: number;
  charCount: number;
  tokenCount: number;
}

export interface ParsedChunk extends SourceLocationFields {
  id: string;
  type: "PARENT" | "CHILD";
  parentChunkId: string | null;
  order: number;
  text: string;
  headingPath: string[];
  startPage: number | null;
  endPage: number | null;
  charCount: number;
  tokenCount: number;
  searchable: boolean;
}

export interface RevisionArtifacts {
  revisionId: string;
  parserVersion: string;
  chunkerVersion: string;
  tokenCounterVersion: string;
  markdown: string;
  contentBlocks: ContentBlock[];
  chunks: ParsedChunk[];
}

export interface StructureBoundingBox {
  sourceUnitId?: string | null;
  sourceUnitOrder?: number;
  sourceUnitKind?: string;
  pageNumber: number | null;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface StructureCell {
  id: string;
  rowIndex: number;
  columnIndex: number;
  rowSpan: number;
  columnSpan: number;
  header: boolean;
  text: string;
  sourceTextHash: string;
  cellReference?: string | null;
  cellType?: "BLANK" | "TEXT" | "NUMBER" | "DATE" | "BOOLEAN" | "ERROR" | "FORMULA";
  rawValue?: string | null;
  displayValue?: string;
  formulaText?: string | null;
  numberFormat?: string | null;
}

export interface StructureTable extends SourceLocationFields {
  id: string;
  order: number;
  contentBlockId: string;
  previewAssetId: string | null;
  pageNumber: number | null;
  boundingBox: StructureBoundingBox;
  caption: string | null;
  sourceTextHash: string;
  cells: StructureCell[];
}

export interface StructureImage extends SourceLocationFields {
  id: string;
  order: number;
  type: "FIGURE" | "TABLE_PREVIEW";
  contentBlockId: string | null;
  pageNumber: number | null;
  boundingBox: StructureBoundingBox;
  filename: string;
  mediaType: string;
  byteSize: number;
  contentHash: string;
  caption: string | null;
  contentUrl: string;
}

export interface StructureSourceSpan extends SourceLocationFields {
  id: string;
  chunkId: string;
  chunkType: "PARENT" | "CHILD";
  chunkOrder: number;
  order: number;
  startPage: number | null;
  endPage: number | null;
  pageStartOffset: number;
  pageEndOffset: number;
  chunkStartOffset: number;
  chunkEndOffset: number;
  sourceTextHash: string;
  boundingBoxes: StructureBoundingBox[];
}

export interface RevisionStructure {
  revisionId: string;
  documentFormat?: DocumentFormat | null;
  resultPackage: {
    parserVersion: string;
    parserRevision: string | null;
    inputHash: string | null;
    outputHash: string | null;
    schemaVersion: string;
    offsetEncoding: "UTF16_CODE_UNIT";
    pageCount: number | null;
    sourceUnitCount?: number;
    documentFormat?: DocumentFormat;
    parserProvider?: ParserProvider;
    textEncoding?: string | null;
    sanitization?: string | null;
    parseDecision?: string | null;
    delimiter?: string | null;
  };
  tables: StructureTable[];
  images: StructureImage[];
  sourceSpans: StructureSourceSpan[];
  truncated: boolean;
}

export interface SearchRequest {
  query: string;
  visibility?: DocumentVisibility;
  documentId?: string;
  page: number;
  size: number;
  graphModeRequested?: GraphMode;
}

export interface SearchHit extends SourceLocationFields {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  headingPath: string[];
  startPage: number | null;
  endPage: number | null;
  snippet: string;
  evidence?: SearchEvidence | null;
}

export interface SearchEvidence {
  rank: number;
  retrievalScore: number;
  rerankScore: number | null;
  childText: string;
  childTokenCount: number;
  parent: ParentEvidence | null;
  graphPaths: GraphPathEvidence[];
  globalClaims?: GlobalClaimEvidence[];
}

export interface GraphPathEvidence extends SourceLocationFields {
  depth: number;
  relationshipId: string;
  relationshipType: string;
  supportingChunkId: string;
  sourceSpanId: string;
  documentId: string;
  documentTitle: string;
  startPage: number | null;
  endPage: number | null;
  evidenceText: string;
  contributedTokens: number;
}

export interface GlobalClaimEvidence extends SourceLocationFields {
  reportId: string;
  reportTitle: string;
  communityKey: number;
  claimId: string;
  claimText: string;
  supportingChunkId: string;
  sourceSpanId: string;
  documentId: string;
  documentTitle: string;
  startPage: number | null;
  endPage: number | null;
  evidenceText: string;
  contributedTokens: number;
}

export interface GlobalExecution {
  configVersion: string;
  globalGeneration: number | null;
  reportCount: number;
  reportLimit: number;
  modelCallLimit: number;
  hardTimeoutMs: number;
  shadow: boolean;
}

export interface QuerySlot {
  round: number;
  slot: number;
  query: string;
  status: "PENDING" | "SUCCESS" | "DEGRADED" | "FAILED" | "SKIPPED";
  candidateCount: number;
  degradationCode: string | null;
}

export interface QueryExecution {
  standaloneQuery: string;
  slots: QuerySlot[];
  plannerCallCount: number;
  retrievalCallCount: number;
  rerankCallCount: number;
  coverageSufficient: boolean;
  degraded: boolean;
  degradationCode: string | null;
  retrievedCandidateCount?: number;
  authorizedCandidateCount?: number;
  rerankedCandidateCount?: number;
  evidenceCandidateCount?: number;
}

export interface RouteExecution {
  requestedMode: GraphMode;
  selectedMode: Exclude<GraphMode, "AUTO">;
  routerCallCount: number;
  reasonCode: string;
  degraded: boolean;
  degradationCode: string | null;
}

export interface ParentEvidence extends SourceLocationFields {
  chunkId: string;
  text: string;
  headingPath: string[];
  startPage: number | null;
  endPage: number | null;
  tokenCount: number;
  contributedTokens: number;
  truncated: boolean;
}

export interface SearchPage {
  items: SearchHit[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  tookMs: number;
  profileVersion: string;
  indexGeneration: number;
  modeRequested: RetrievalMode;
  modeUsed: RetrievalMode;
  degraded: boolean;
  degradationCode: string | null;
  totalRelation: "EXACT" | "CAPPED";
  graphProfileVersion: string | null;
  graphGeneration: number | null;
  graphModeRequested: GraphMode;
  graphModeUsed: Exclude<GraphMode, "AUTO">;
  graphDegraded: boolean;
  graphDegradationCode: string | null;
  globalExecution?: GlobalExecution | null;
  routeExecution?: RouteExecution;
  queryExecution?: QueryExecution | null;
}

export interface SourceSpan extends SourceLocationFields {
  order: number;
  startPage: number | null;
  endPage: number | null;
  startOffset: number;
  endOffset: number;
  chunkStartOffset: number;
  chunkEndOffset: number;
  sourceTextHash: string;
}

export interface ChunkView extends SourceLocationFields {
  id: string;
  type: "PARENT" | "CHILD";
  order: number;
  text: string;
  headingPath: string[];
  startPage: number | null;
  endPage: number | null;
  tokenCount: number;
}

export interface ChunkContext {
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  documentFormat?: DocumentFormat | null;
  child: ChunkView;
  parent: ChunkView | null;
  sourceSpans: SourceSpan[];
}

export interface Bm25DebugCandidate {
  rank: number;
  score: number;
  bm25Rank: number | null;
  vectorRank: number | null;
  rrfScore: number | null;
  graphRank?: number | null;
  graphPaths?: GraphPathEvidence[];
  globalRank?: number | null;
  globalClaims?: GlobalClaimEvidence[];
  rerankRank?: number | null;
  rerankScore?: number | null;
  evidenceRank?: number | null;
  matchedFields: string[];
  accepted: boolean;
  rejectionReason: string | null;
  result: SearchHit | null;
}

export interface RetrievalDebugStage {
  name:
    | "BM25"
    | "VECTOR"
    | "RRF"
    | "ACL_REVISION"
    | "GRAPH_SEED"
    | "GRAPH_TRAVERSE"
    | "GRAPH_FUSION"
    | "GLOBAL_REPORT_RETRIEVAL"
    | "GLOBAL_EVIDENCE"
    | "GLOBAL_MAP"
    | "GLOBAL_REDUCE"
    | "RERANK"
    | "EVIDENCE"
    | "PARENT"
    | "ACL_FINAL";
  status: "SUCCESS" | "DEGRADED" | "SKIPPED";
  inputCount: number;
  outputCount: number;
  tookMs: number;
  code: string | null;
}

export interface ContextBudget {
  limitTokens: number;
  childTokens: number;
  parentTokens: number;
  totalTokens: number;
  parentCount: number;
  graphTokens: number;
  graphPathCount: number;
  globalTokens?: number;
  globalClaimCount?: number;
  trimReasons: string[];
}

export interface Bm25DebugResponse {
  query: string;
  retrievalProfile: string;
  indexName: string;
  indexGeneration: number;
  modeRequested: RetrievalMode;
  modeUsed: RetrievalMode;
  degraded: boolean;
  degradationCode: string | null;
  graphProfileVersion: string | null;
  graphGeneration: number | null;
  graphModeRequested: GraphMode;
  graphModeUsed: Exclude<GraphMode, "AUTO">;
  graphDegraded: boolean;
  graphDegradationCode: string | null;
  tookMs: number;
  stages?: RetrievalDebugStage[];
  contextBudget?: ContextBudget;
  globalExecution?: GlobalExecution | null;
  candidates: Bm25DebugCandidate[];
  result: SearchPage;
}

export interface SearchIndexStatus {
  indexName: string;
  indexGeneration: number;
  documentCount: number;
  chunkCount: number;
  status: string;
  updatedAt: string | null;
  rebuilding: boolean;
}

export type RetrievalMode = "BM25" | "HYBRID";
export type GraphMode = "AUTO" | "HYBRID" | "LOCAL_GRAPH" | "GLOBAL_GRAPH";
export type AnswerStrategy = "STANDARD" | "DEEP_GLOBAL";
export type ModelServiceType = "EMBEDDING" | "RERANK";
export type ModelServiceStatus = "DISABLED" | "UP" | "DOWN";

export interface RetrievalProfile {
  version: string;
  mode: RetrievalMode;
  defaultPageSize: number;
  maxPageSize: number;
  bm25TopK: number;
  vectorTopK: number;
  rrfRankConstant: number;
  rerankTopK: number;
  evidenceTopK: number;
  parentTokenBudget: number;
  createdAt: string;
}

export type CreateRetrievalProfileRequest = Omit<RetrievalProfile, "createdAt">;

export interface IndexConfig {
  version: string;
  schemaVersion: string;
  analyzer: string;
  embeddingProviderKey: string;
  embeddingModel: string | null;
  embeddingRevision: string | null;
  vectorDimensions: number | null;
  embeddingInputFormatVersion: string;
  embeddingNormalizationVersion: string;
  distance: string | null;
  hnswM: number | null;
  hnswEfConstruction: number | null;
  createdAt: string;
}

export interface RetrievalPublication {
  profileVersion: string;
  publicationEventId: string;
  publishedAt: string;
}

export interface ActiveIndexManifest {
  indexGeneration: number;
  indexName: string;
  indexConfigVersion: string;
  status: string;
}

export interface GoldenBaselineSlice {
  name: string;
  caseCount: number;
  candidateHitAt50: number | null;
}

export interface GoldenBaseline {
  datasetVersion: string;
  caseCount: number;
  status: "NOT_RUN" | "PASSED" | "FAILED";
  generatedAt: string | null;
  reportAvailable: boolean;
  slices: GoldenBaselineSlice[];
}

export interface RetrievalConfiguration {
  currentPublication: RetrievalPublication | null;
  activeManifest: ActiveIndexManifest | null;
  indexConfigs: IndexConfig[];
  profiles: RetrievalProfile[];
  goldenBaseline: GoldenBaseline;
}

export interface ModelServiceHealth {
  type: ModelServiceType;
  status: ModelServiceStatus;
  model: string;
  revision: string;
  dimensions: number | null;
  latencyMs: number | null;
  checkedAt: string;
  errorCode: string | null;
}

export interface ModelServicesHealth {
  services: ModelServiceHealth[];
}

export interface EmbeddingCacheQueryStatistics {
  entries: number;
  maxEntries: number;
  hits: number;
  misses: number;
  evictions: number;
  coalesced: number;
  modelCalls: number;
  savedModelCalls: number;
}

export interface EmbeddingCacheArtifactStatistics {
  entries: number;
  bytes: number;
  maxBytes: number;
  hits: number;
  misses: number;
  evictions: number;
  corruptions: number;
  modelCalls: number;
  savedModelCalls: number;
}

export interface EmbeddingCacheModelStatistics {
  providerKey: string;
  model: string;
  revision: string;
  dimensions: number;
  queryEntries: number;
  artifactEntries: number;
  artifactBytes: number;
}

export interface EmbeddingCacheStatistics {
  query: EmbeddingCacheQueryStatistics;
  artifacts: EmbeddingCacheArtifactStatistics;
  models: EmbeddingCacheModelStatistics[];
  checkedAt: string;
}

export interface ClearEmbeddingCacheRequest {
  providerKey: string;
  model: string;
  revision: string;
  confirmation: "CLEAR";
  reason: string;
}

export interface ClearEmbeddingCacheResult {
  deletedArtifacts: number;
  invalidatedQueryEntries: number;
  freedBytes: number;
}

export type IndexGenerationStatus =
  | "BUILDING"
  | "READY"
  | "ACTIVE"
  | "RETIRED"
  | "FAILED"
  | "DELETED";

export interface ProjectionFormatCoverage {
  documentFormat: DocumentFormat;
  expectedDocumentCount: number;
  projectedDocumentCount: number;
  locatorReadyDocumentCount: number;
  staleDocumentCount: number;
}

export interface ProjectionClosureStatus {
  sourceLocatorCompatible: boolean;
  caughtUp: boolean;
  expectedDocumentCount: number;
  projectedDocumentCount: number;
  locatorReadyDocumentCount: number;
  staleDocumentCount: number;
  missingLocatorDocumentCount: number;
  orphanedProjectionCount: number;
  allUsersSourceDocumentCount: number;
  restrictedSourceDocumentCount: number;
  invalidEvidenceCount: number;
  formats: ProjectionFormatCoverage[];
  blockers: string[];
}

export interface GenerationRecoveryProgress {
  state: "RUNNING" | "AWAITING_TAKEOVER" | "COMPLETE" | "FAILED";
  attempt: number;
  heartbeatAt: string | null;
  leaseExpiresAt: string | null;
}

export interface IndexGeneration {
  id: string;
  indexGeneration: number;
  indexName: string;
  indexConfigVersion: string;
  status: IndexGenerationStatus;
  expectedDocumentCount: number;
  expectedChunkCount: number;
  indexedChunkCount: number;
  validVectorCount: number;
  vectorCoverage: number;
  readyCheckPassed: boolean;
  closure?: ProjectionClosureStatus;
  recovery?: GenerationRecoveryProgress;
  buildAttempt: number;
  failureCode: string | null;
  failureReason: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  retentionUntil: string | null;
  updatedAt: string;
}

export interface IndexGenerationsResponse {
  activeGeneration: number | null;
  generations: IndexGeneration[];
}

export type ChatSessionStatus = "ACTIVE" | "ARCHIVED";
export type ChatMessageRole = "USER" | "ASSISTANT" | "SYSTEM";
export type ChatMessageStatus = "PENDING" | "STREAMING" | "COMPLETED" | "FAILED" | "CANCELLED";
export type ChatRunStatus = "RUNNING" | "COMPLETED" | "REFUSED" | "FAILED" | "CANCELLED";
export type PersistedChatRunStatus = "PENDING" | ChatRunStatus;
export type MemorySuggestionStatus =
  | "PENDING"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED";

export interface ChatSessionSummary {
  id: string;
  title: string;
  status: ChatSessionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ChatSessionsResponse {
  items: ChatSessionSummary[];
}

export interface ChatCitationSummary extends SourceLocationFields {
  id: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  chunkId: string;
  startPage: number | null;
  endPage: number | null;
  label: string;
}

export interface ChatMessage {
  id: string;
  role: ChatMessageRole;
  status: ChatMessageStatus;
  content: string;
  language: string;
  runId?: string | null;
  hidden: boolean;
  createdAt: string;
  citations?: ChatCitationSummary[];
  memorySuggestionStatus?: MemorySuggestionStatus | null;
  memorySuggestionCount?: number;
  memorySuggestionErrorCode?: string | null;
}

export interface ChatRunSummary {
  id: string;
  status: PersistedChatRunStatus;
  errorCode: string | null;
  queryProfileVersion?: string | null;
  historyMessageIds?: string[];
  historyCounterVersion?: string | null;
  historyTokenCount?: number;
  historyTrimReasons?: string[];
  contextCompressionPolicyVersion?: string | null;
  historySummaryId?: string | null;
  historySummaryTokenCount?: number;
  historySummarySourceCount?: number;
  contextCompressionStatus?: ChatContextStatus | null;
  contextCompressionReasonCode?: string | null;
  memoryUsedCount?: number;
  memoryTokenCount?: number;
  memoryDegradationCode?: string | null;
  standaloneQuery?: string | null;
  querySlots?: QuerySlot[];
  plannerCallCount?: number;
  retrievalCallCount?: number;
  rerankCallCount?: number;
  coverageSufficient?: boolean;
  queryDegraded?: boolean;
  queryDegradationCode?: string | null;
  retrievedCandidateCount?: number;
  authorizedCandidateCount?: number;
  rerankedCandidateCount?: number;
  evidenceCandidateCount?: number;
  validatedEvidenceCount?: number;
  routeSelectedMode?: Exclude<GraphMode, "AUTO"> | null;
  routerCallCount?: number;
  routeReasonCode?: string | null;
  routeDegraded?: boolean;
  routeDegradationCode?: string | null;
  graphProfileVersion: string | null;
  graphGeneration: number | null;
  graphModeRequested: GraphMode | null;
  graphModeUsed: Exclude<GraphMode, "AUTO"> | null;
  graphDegraded: boolean;
  graphDegradationCode: string | null;
  globalConfigVersion?: string | null;
  globalGeneration?: number | null;
  answerStrategyRequested?: AnswerStrategy | null;
  answerStrategyUsed?: AnswerStrategy | null;
  mapCallCount?: number;
  reduceCallCount?: number;
  createdAt: string;
  completedAt: string | null;
}

export interface ChatSessionDetail extends ChatSessionSummary {
  messages: ChatMessage[];
  runs: ChatRunSummary[];
}

export interface ChatMemorySuggestionStatusResponse {
  items: Array<{
    messageId: string;
    status: MemorySuggestionStatus;
    suggestionCount: number;
    errorCode: string | null;
  }>;
  pending: boolean;
}

export type ChatContextStatus =
  | "NOT_NEEDED"
  | "PENDING"
  | "USED"
  | "FALLBACK"
  | "STALE"
  | "REMOTE_BLOCKED"
  | "FAILED";

export interface ChatContextStatusResponse {
  status: ChatContextStatus;
  policyVersion: string;
  coveredMessageCount: number;
  tailMessageCount: number;
  summaryTokenCount: number;
  finalHistoryTokenCount: number;
  estimatedSavedTokens: number;
  compressionRatio: number;
  updatedAt: string | null;
  reasonCode: string | null;
}

export interface ChatCitationDetail extends ChatCitationSummary {
  childText: string;
  headingPath: string[];
  sourceSpan: SourceSpan & { id: string };
  parentText?: string | null;
}

export interface ChatAnswerDeltaEvent {
  runId: string;
  messageId?: string;
  text: string;
}

export interface ChatCitationEvent {
  runId: string;
  citation: ChatCitationSummary;
}

export type ChatMemoryUsageStatus =
  | "USED"
  | "INJECTED"
  | "DOCUMENT_EVIDENCE"
  | "TRIMMED"
  | "REMOTE_BLOCKED";

export interface ChatRunMemoryUsage {
  runId: string;
  memoryId: string;
  memoryType: MemoryType;
  usageStatus: ChatMemoryUsageStatus;
  relevanceScore: number;
  tokenCount: number;
  tokenLimit?: number;
  tokenCounterVersion?: string;
  tokenCountExact?: boolean;
  sourceTypes: MemorySourceType[];
  available: boolean;
  memoryKey: string | null;
  content: string | null;
  trimReason: string | null;
  createdAt: string;
}

export interface ChatMemoryUsedEvent {
  runId: string;
  memories: Array<{
    memoryId: string;
    memoryType: MemoryType;
    usageStatus: "USED" | "DOCUMENT_EVIDENCE";
  }>;
}

export interface ChatCompletedEvent {
  runId: string;
  status: "COMPLETED" | "REFUSED";
  messageId: string;
  refusalCode?: string | null;
  queryProfileVersion: string | null;
  historyMessageCount: number;
  historyTokenCount: number;
  historyTrimReasons: string[];
  standaloneQuery: string;
  querySlots: QuerySlot[];
  plannerCallCount: number;
  retrievalCallCount: number;
  rerankCallCount: number;
  coverageSufficient: boolean;
  queryDegraded: boolean;
  queryDegradationCode: string | null;
  retrievedCandidateCount: number;
  authorizedCandidateCount: number;
  rerankedCandidateCount: number;
  evidenceCandidateCount: number;
  validatedEvidenceCount: number;
  routeSelectedMode: Exclude<GraphMode, "AUTO">;
  routerCallCount: number;
  routeReasonCode: string;
  routeDegraded: boolean;
  routeDegradationCode: string | null;
  graphProfileVersion: string | null;
  graphGeneration: number | null;
  graphModeRequested: GraphMode;
  graphModeUsed: Exclude<GraphMode, "AUTO">;
  graphDegraded: boolean;
  graphDegradationCode: string | null;
  globalConfigVersion?: string | null;
  globalGeneration?: number | null;
  answerStrategyRequested?: AnswerStrategy | null;
  answerStrategyUsed?: AnswerStrategy | null;
  mapCallCount?: number;
  reduceCallCount?: number;
}

export interface ChatFailedEvent {
  runId: string;
  status: "FAILED" | "CANCELLED";
  code: string;
  message: string;
}

export interface QueryIntelligenceProfile {
  version: string;
  enabled: boolean;
  plannerProvider: string;
  plannerModel: string;
  plannerRevision: string;
  promptVersion: string;
  schemaVersion: string;
  tokenCounterType: "CONSERVATIVE_UTF8" | "MODEL_TOKENIZER";
  tokenCounterVersion: string;
  modelContextTokens: number;
  historyMessageLimit: number;
  historyTokenBudget: number;
  historyContextPercent: number;
  maxSubQueries: number;
  maxRetrievalRounds: number;
  plannerCallLimit: number;
  timeoutMs: number;
  fallbackMode: "ORIGINAL_QUERY";
  reason: string;
  published: boolean;
  createdAt: string;
}

export interface QueryIntelligenceRuntime {
  llmEnabled: boolean;
  plannerProvider: string;
  plannerModel: string;
  plannerRevision: string;
  promptVersion: string;
  schemaVersion: string;
  supportedCounterType: "CONSERVATIVE_UTF8" | "MODEL_TOKENIZER";
  supportedCounterVersion: string;
}

export interface QueryIntelligencePublicationEvent {
  eventId: number;
  profileVersion: string;
  previousProfileVersion: string | null;
  intentRunId: string | null;
  multiTurnRunId: string | null;
  action: string;
  reason: string;
  createdAt: string;
}

export type GraphGenerationStatus =
  | "BUILDING"
  | "READY"
  | "ACTIVE"
  | "RETIRED"
  | "FAILED"
  | "DELETED";

export interface GraphExtractionStatus {
  enabled: boolean;
  model: string;
  revision: string;
  promptVersion: string;
  schemaVersion: string;
}

export interface GraphConfig {
  version: string;
  extractionModel: string;
  extractionRevision: string;
  promptVersion: string;
  schemaVersion: string;
  normalizationVersion: string;
  resolutionRuleSetVersion: string;
  communityAlgorithm: string;
  communityAlgorithmVersion: string;
  communitySeed: number;
  communityResolution: number;
  reason: string;
  runtimeCompatible: boolean;
  createdAt: string;
}

export interface GraphGeneration {
  id: string;
  graphGeneration: number;
  graphConfigVersion: string;
  status: GraphGenerationStatus;
  expectedDocumentCount: number;
  projectedDocumentCount: number;
  entityCount: number;
  mentionCount: number;
  relationshipCount: number;
  relationshipEvidenceCount: number;
  communityCount: number;
  communityClaimCount: number;
  cacheHitCount: number;
  modelCallCount: number;
  cacheHitRate: number;
  caughtUp: boolean;
  closure?: ProjectionClosureStatus;
  recovery?: GenerationRecoveryProgress;
  buildAttempt: number;
  failureCode: string | null;
  failureReason: string | null;
  buildReason: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  retentionUntil: string | null;
  updatedAt: string;
}

export interface GraphRebuildRequest {
  id: string;
  documentId: string;
  documentTitle: string;
  targetRevisionId: string;
  targetRevisionNumber: number;
  targetAclVersion: number;
  reason: "REVISION_PUBLISHED" | "ACL_CHANGED";
  state:
    | "REQUESTED"
    | "GRAPH_BUILDING"
    | "GRAPH_READY"
    | "GLOBAL_BUILDING"
    | "FULFILLED"
    | "SUPERSEDED";
  sourceGraphGeneration: number | null;
  sourceGlobalGeneration: number | null;
  globalRebuildRequired: boolean;
  candidateGraphGeneration: number | null;
  candidateGlobalGeneration: number | null;
  requestedAt: string;
  graphReadyAt: string | null;
  globalReadyAt: string | null;
  completedAt: string | null;
}

export interface GraphOverview {
  activeGeneration: number | null;
  extraction: GraphExtractionStatus;
  configs: GraphConfig[];
  generations: GraphGeneration[];
}

export interface GraphRetrievalProfile {
  version: string;
  seedLimit: number;
  maxHops: number;
  entityLimit: number;
  edgeLimit: number;
  graphChildLimit: number;
  graphWeight: number;
  graphContextTokenBudget: number;
  graphContextPercent: number;
  statementTimeoutMs: number;
  reason: string;
  createdAt: string;
}

export interface GraphRetrievalPublication {
  profileVersion: string;
  publicationEventId: number;
  publishedAt: string;
}

export interface GraphRetrievalConfiguration {
  currentPublication: GraphRetrievalPublication;
  activeGraphGeneration: number | null;
  profiles: GraphRetrievalProfile[];
}

export interface GraphEntitySummary {
  id: string;
  canonicalName: string;
  entityType: string;
  description: string | null;
  mentionCount: number;
  relationshipCount: number;
  communityKey: number | null;
  aliases?: string[];
  matchSource?: "CANONICAL_NAME" | "ALIAS" | null;
  matchedAlias?: string | null;
}

export interface GraphEntityPage {
  graphGeneration: number;
  page: number;
  size: number;
  total: number;
  nextCursor?: string | null;
  items: GraphEntitySummary[];
}

export interface GraphResolutionImpact {
  mentionCount: number;
  sourceSpanCount: number;
  relationshipCount: number;
  relationshipEvidenceCount: number;
  communityCount: number;
  documentCount: number;
  queryImpactState: "NOT_AVAILABLE";
  queryImpactReason: string;
}

export interface GraphResolutionNotice {
  code: string;
  message: string;
}

export interface GraphResolutionPreview {
  previewToken: string | null;
  expiresAt: string | null;
  graphGeneration: number;
  graphStatus: "ACTIVE" | "READY";
  baseConfigVersion: string;
  sourceSetHash: string;
  action: "MERGE" | "SPLIT";
  entities: Array<{
    id: string;
    canonicalName: string;
    entityType: string;
    aliases: string[];
    mentionCount: number;
    relationshipCount: number;
    relationshipEvidenceCount: number;
  }>;
  impact: GraphResolutionImpact;
  blockers: GraphResolutionNotice[];
  warnings: GraphResolutionNotice[];
}

export type GraphResolutionEntity = GraphResolutionPreview["entities"][number];

export type GraphResolutionCandidateType =
  | "SUSPECTED_DUPLICATE"
  | "SUSPECTED_MERGE";

export type GraphResolutionCandidateStatus = "ACTIVE" | "IGNORED" | "STALE";

export interface GraphResolutionCandidateSnapshot {
  id: string;
  graphGeneration: number;
  graphConfigVersion: string;
  sourceSetHash: string;
  algorithmVersion: string;
  inputHash: string;
  status: "READY" | "STALE" | "FAILED";
  duplicateCandidateCount: number;
  splitCandidateCount: number;
  createdAt: string;
  staleAt: string | null;
  staleReason: string | null;
}

export interface GraphResolutionCandidateSignal {
  code: string;
  strength: "HARD" | "SUPPORTING" | "WEAK";
  explanation: string;
  numericValue: number | null;
}

export interface GraphResolutionCandidateSummary {
  id: string;
  candidateType: GraphResolutionCandidateType;
  suggestedAction: "MERGE" | "SPLIT";
  status: GraphResolutionCandidateStatus;
  version: number;
  entities: GraphResolutionPreview["entities"];
  suggestedTargetName: string | null;
  suggestedTargetType: string | null;
  suggestedAliases: string[];
  signals: GraphResolutionCandidateSignal[];
  evidenceCount: number;
  sourceDocumentCount: number;
  stableRank: number;
  createdAt: string;
  updatedAt: string;
}

export interface GraphResolutionCandidatePage {
  snapshot: GraphResolutionCandidateSnapshot | null;
  nextCursor: string | null;
  items: GraphResolutionCandidateSummary[];
}

export interface GraphResolutionCandidateEvidence extends SourceLocationFields {
  anchorType: string;
  anchorId: string;
  entityId: string;
  entityName: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  childChunkId: string;
  sourceSpanId: string;
  excerpt: string;
}

export interface GraphResolutionCandidateNeighbor {
  entityId: string;
  entityName: string;
  neighborId: string;
  neighborName: string;
  neighborType: string;
  shared: boolean;
  evidenceCount: number;
}

export interface GraphResolutionCandidateEvent {
  id: number;
  eventType: string;
  previousStatus: GraphResolutionCandidateStatus | null;
  nextStatus: GraphResolutionCandidateStatus;
  version: number;
  reason: string;
  createdAt: string;
}

export interface GraphResolutionCandidateDetail {
  candidate: GraphResolutionCandidateSummary;
  evidence: GraphResolutionCandidateEvidence[];
  neighbors: GraphResolutionCandidateNeighbor[];
  events: GraphResolutionCandidateEvent[];
}

export type GraphResolutionProposalStatus =
  | "DRAFT"
  | "READY"
  | "CONFLICTED"
  | "STALE"
  | "WITHDRAWN"
  | "MATERIALIZED"
  | "APPLIED";

export interface GraphResolutionProposalConflict {
  conflictingProposalId: string;
  code: string;
  message: string;
}

export interface GraphResolutionProposalSummary {
  id: string;
  candidateId: string | null;
  status: GraphResolutionProposalStatus;
  version: number;
  currentRevision: number;
  baseGraphGeneration: number;
  baseGraphConfigVersion: string;
  materializedConfigVersion: string | null;
  appliedGraphGeneration: number | null;
  action: "MERGE" | "SPLIT";
  entities: GraphResolutionEntity[];
  matchAliases: string[];
  targetCanonicalName: string;
  targetEntityType: string;
  impact: GraphResolutionImpact;
  blockers: GraphResolutionNotice[];
  warnings: GraphResolutionNotice[];
  conflicts: GraphResolutionProposalConflict[];
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  nextStep: string;
}

export interface GraphResolutionProposalPage {
  page: number;
  size: number;
  total: number;
  items: GraphResolutionProposalSummary[];
}

export interface GraphResolutionProposalRevision {
  id: string;
  revision: number;
  supersedesRevision: number | null;
  action: "MERGE" | "SPLIT";
  entities: GraphResolutionEntity[];
  matchAliases: string[];
  targetCanonicalName: string;
  targetEntityType: string;
  impact: GraphResolutionImpact;
  blockers: GraphResolutionNotice[];
  warnings: GraphResolutionNotice[];
  reason: string;
  createdBy: string;
  createdAt: string;
}

export interface GraphResolutionProposalEvent {
  id: number;
  eventType: string;
  revision: number;
  previousStatus: GraphResolutionProposalStatus | null;
  nextStatus: GraphResolutionProposalStatus;
  proposalVersion: number;
  reason: string;
  createdAt: string;
}

export interface GraphResolutionProposalDetail {
  proposal: GraphResolutionProposalSummary;
  revisions: GraphResolutionProposalRevision[];
  events: GraphResolutionProposalEvent[];
}

export interface GraphMention extends SourceLocationFields {
  id: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  childChunkId: string;
  sourceSpanId: string;
  surfaceText: string;
  startPage: number | null;
  endPage: number | null;
}

export interface GraphRelationshipEvidence extends SourceLocationFields {
  id: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  childChunkId: string;
  sourceSpanId: string;
  evidenceText: string;
  startPage: number | null;
  endPage: number | null;
}

export interface GraphRelationship {
  id: string;
  sourceEntityId: string;
  sourceName: string;
  targetEntityId: string;
  targetName: string;
  relationshipType: string;
  description: string | null;
  evidence: GraphRelationshipEvidence[];
}

export type GraphRootType = "ENTITY" | "COMMUNITY";

export interface GraphSubgraphNode {
  id: string;
  name: string;
  entityType: string;
  communityKey: number | null;
  depth: number;
  mentionCount: number;
  relationshipCount: number;
  root: boolean;
}

export interface GraphSubgraphEdge {
  id: string;
  sourceEntityId: string;
  targetEntityId: string;
  relationshipType: string;
  description: string | null;
  evidenceCount: number;
}

export interface GraphSubgraph {
  generation: number;
  rootType: GraphRootType;
  rootId: string;
  rootLabel: string;
  hops: number;
  truncated: boolean;
  nodes: GraphSubgraphNode[];
  edges: GraphSubgraphEdge[];
}

export interface GraphEntityDetail {
  entity: GraphEntitySummary;
  aliases: string[];
  mentions: GraphMention[];
  relationships: GraphRelationship[];
}

export interface GraphCommunitySummary {
  id: string;
  communityKey: number;
  title: string;
  summary: string;
  entityCount: number;
  claimCount: number;
}

export interface GraphCommunityPage {
  graphGeneration: number;
  page: number;
  size: number;
  total: number;
  items: GraphCommunitySummary[];
}

export interface GraphCommunityClaim {
  id: string;
  claimText: string;
  relationshipId: string;
  evidence: GraphRelationshipEvidence;
}

export interface GraphCommunityDetail {
  community: GraphCommunitySummary;
  entities: GraphEntitySummary[];
  claims: GraphCommunityClaim[];
}

export interface GlobalGraphConfig {
  version: string;
  reportModel: string;
  reportRevision: string;
  promptVersion: string;
  schemaVersion: string;
  communityAlgorithm: string;
  communityAlgorithmVersion: string;
  communitySeed: number;
  communityResolution: number;
  indexConfigVersion: string;
  bm25TopK: number;
  vectorTopK: number;
  rrfRankConstant: number;
  reportLimit: number;
  contextTokenBudget: number;
  mapCallLimit: number;
  modelCallLimit: number;
  hardTimeoutMs: number;
  statementTimeoutMs: number;
  reason: string;
  runtimeCompatible: boolean;
  createdAt: string;
}

export interface GlobalGraphGeneration {
  id: string;
  globalGeneration: number;
  globalConfigVersion: string;
  sourceGraphGeneration: number;
  indexName: string;
  status: GraphGenerationStatus;
  expectedSourceCount: number;
  reportCount: number;
  claimCount: number;
  evidenceCount: number;
  indexedReportCount: number;
  validVectorCount: number;
  modelCallCount: number;
  caughtUp: boolean;
  closure?: ProjectionClosureStatus;
  recovery?: GenerationRecoveryProgress;
  buildAttempt: number;
  failureCode: string | null;
  failureReason: string | null;
  buildReason: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  retentionUntil: string | null;
  updatedAt: string;
}

export interface GlobalGraphRuntime {
  enabled: boolean;
  model: string;
  revision: string;
  promptVersion: string;
  schemaVersion: string;
}

export interface GlobalGraphOverview {
  activeGeneration: number | null;
  runtime: GlobalGraphRuntime;
  configs: GlobalGraphConfig[];
  generations: GlobalGraphGeneration[];
}

export interface GlobalCommunityReportSummary {
  id: string;
  globalGeneration: number;
  communityKey: number;
  title: string;
  summary: string;
  tokenCount: number;
  claimCount: number;
  evidenceCount: number;
}

export interface GlobalCommunityReportPage {
  globalGeneration: number;
  page: number;
  size: number;
  total: number;
  items: GlobalCommunityReportSummary[];
}

export interface GlobalCommunityReportDetail {
  report: GlobalCommunityReportSummary;
  claims: GlobalReportClaim[];
}

export interface GlobalReportClaim {
  id: string;
  order: number;
  claimText: string;
  evidence: GlobalReportEvidence[];
}

export interface GlobalReportEvidence extends SourceLocationFields {
  id: string;
  documentId: string;
  documentTitle: string;
  revisionId: string;
  revisionNumber: number;
  childChunkId: string;
  sourceSpanId: string;
  evidenceText: string;
  startPage: number | null;
  endPage: number | null;
}

export type MemoryType =
  | "USER_PREFERENCE"
  | "USER_FACT"
  | "SESSION_SUMMARY"
  | "DOCUMENT_FACT";

export type MemoryStatus =
  | "CANDIDATE"
  | "ACTIVE"
  | "REJECTED"
  | "REVOKED"
  | "EXPIRED"
  | "FORGOTTEN";

export type MemorySourceType =
  | "CHAT_SESSION"
  | "CHAT_MESSAGE"
  | "DOCUMENT_SPAN";

export interface MemorySettings {
  enabled: boolean;
  suggestionEnabled: boolean;
  version: number;
  updatedAt: string;
}

export interface MemoryItem {
  id: string;
  memoryType: MemoryType;
  memoryKey: string;
  content: string | null;
  status: MemoryStatus;
  versionNumber: number;
  origin: "USER" | "SUGGESTION";
  supersedesMemoryId: string | null;
  sourceCount: number;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MemorySourceInput {
  sourceType: MemorySourceType;
  chatSessionId: string | null;
  chatMessageId: string | null;
  documentId: string | null;
  revisionId: string | null;
  childChunkId: string | null;
  sourceSpanId: string | null;
}

export interface MemorySource extends MemorySourceInput, SourceLocationFields {
  id: string;
  sourceDeletedAt: string | null;
  createdAt: string;
}

export interface MemoryEvent {
  id: number;
  eventType: string;
  relatedMemoryId: string | null;
  reason: string | null;
  createdAt: string;
}

export interface MemoryProfileEntry {
  memoryId: string;
  key: string;
  value: string;
  versionNumber: number;
}

export interface MemoryProfile {
  memoryEnabled: boolean;
  preferences: MemoryProfileEntry[];
}

export type AdminValueState =
  | "VALUE"
  | "NOT_APPLICABLE"
  | "NOT_AVAILABLE"
  | "ERROR"
  | "BLOCKED"
  | "STALE";

export interface AdminOverviewLink {
  title: string;
  href: string;
}

export interface AdminOverviewDomain {
  key: string;
  title: string;
  description: string;
  href: string;
  links: AdminOverviewLink[];
}

export interface AdminAttentionItem {
  code: string;
  title: string;
  description: string;
  count: number | null;
  severity: "INFO" | "WARNING" | "ERROR";
  valueState: AdminValueState;
  reasonCode: string | null;
  updatedAt: string | null;
  href: string;
}

export interface AdminOverview {
  schemaVersion: string;
  capturedAt: string;
  domains: AdminOverviewDomain[];
  attentionItems: AdminAttentionItem[];
}
