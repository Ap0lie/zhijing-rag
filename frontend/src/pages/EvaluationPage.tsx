import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { useSearchParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import {
  createEvaluationOperationsCache,
  EvaluationOperationsPanel,
} from "./EvaluationOperationsPanel";
import { MultiformatReleasePanel } from "./MultiformatReleasePanel";
import { ReleaseReportPanel } from "./ReleaseReportPanel";
import type {
  AnswerProfile,
  EvaluationBaseline,
  EvaluationCompare,
  EvaluationDataset,
  EvaluationDatasetVersion,
  EvaluationFeedback,
  EvaluationMappingPage,
  MultiformatRelease,
  EvaluationResultPage,
  EvaluationReleaseReport,
  EvaluationRun,
  EvaluationRunEvent,
  EvaluationRunPage,
  EvaluationSubject,
  EvaluationTarget,
  RuntimeAnswerProfile,
} from "../types";

type EvaluationTab =
  | "new"
  | "datasets"
  | "runs"
  | "report"
  | "compare"
  | "baselines"
  | "feedback"
  | "observability"
  | "drills";

const EVALUATION_TABS = new Set<EvaluationTab>([
  "new",
  "datasets",
  "runs",
  "report",
  "compare",
  "baselines",
  "feedback",
  "observability",
  "drills",
]);
const PAGE_CACHE_TTL_MS = 30_000;

type ReportCacheEntry = {
  loadedAt: number;
  results: EvaluationResultPage | null;
  events: EvaluationRunEvent[];
  releaseReport: EvaluationReleaseReport | null;
};

function evaluationTab(value: string | null): EvaluationTab {
  return value && EVALUATION_TABS.has(value as EvaluationTab)
    ? value as EvaluationTab
    : "new";
}

const caseTypeLabel: Record<string, string> = {
  RETRIEVAL: "检索",
  LOCAL_GRAPH: "Local Graph",
  GLOBAL_GRAPH: "Global Graph",
  ANSWER_CITATION: "回答与引用",
  MULTI_TURN: "多轮",
  INTENT: "请求级意图路由",
  PARSER: "Parser",
  MULTIFORMAT_RELEASE: "多格式发布",
};

const statusLabel: Record<string, string> = {
  PENDING: "排队中",
  RUNNING: "运行中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  BLOCKED_PREREQUISITE: "前置条件不足",
  READY: "可运行",
  MAPPED: "已映射",
  UNMAPPED: "未映射",
  NOT_REQUIRED: "无需映射",
  PASSED: "通过",
  APPROVED: "已采纳",
  REJECTED: "已拒绝",
};

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
}

function statusClass(status: string) {
  return status.toLowerCase().replaceAll("_", "-");
}

function idempotencyKey() {
  const id = typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `evaluation:${id}`;
}

function isRealEvaluator(version: string) {
  return version.startsWith("phase11b-real-")
    || version.startsWith("phase12c-real-")
    || version.startsWith("phase18d-real-");
}

export function EvaluationPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = evaluationTab(searchParams.get("tab"));
  const selectedRunId = searchParams.get("run");
  const [datasets, setDatasets] = useState<EvaluationDataset[]>([]);
  const [multiformatRelease, setMultiformatRelease] = useState<MultiformatRelease | null>(null);
  const [subjects, setSubjects] = useState<EvaluationSubject[]>([]);
  const [targets, setTargets] = useState<EvaluationTarget[]>([]);
  const [runs, setRuns] = useState<EvaluationRun[]>([]);
  const [results, setResults] = useState<EvaluationResultPage | null>(null);
  const [events, setEvents] = useState<EvaluationRunEvent[]>([]);
  const [releaseReport, setReleaseReport] = useState<EvaluationReleaseReport | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [mapping, setMapping] = useState<EvaluationMappingPage | null>(null);
  const [mappingVersionId, setMappingVersionId] = useState<string | null>(null);
  const [subjectName, setSubjectName] = useState("当前发布版本");
  const [subjectTargetId, setSubjectTargetId] = useState("");
  const [runSubjectId, setRunSubjectId] = useState("");
  const [runDatasetVersion, setRunDatasetVersion] = useState("");
  const [runActionReason, setRunActionReason] = useState("管理员手动操作");
  const [answerProfiles, setAnswerProfiles] = useState<AnswerProfile[]>([]);
  const [runtimeProfile, setRuntimeProfile] = useState<RuntimeAnswerProfile | null>(null);
  const [baselines, setBaselines] = useState<EvaluationBaseline[]>([]);
  const [feedback, setFeedback] = useState<EvaluationFeedback[]>([]);
  const [compareLeftId, setCompareLeftId] = useState("");
  const [compareRightId, setCompareRightId] = useState("");
  const [compareReason, setCompareReason] = useState("");
  const [comparison, setComparison] = useState<EvaluationCompare | null>(null);
  const [baselineRunId, setBaselineRunId] = useState("");
  const [baselineName, setBaselineName] = useState("当前单轮基线");
  const [baselineReason, setBaselineReason] = useState("");
  const [profileVersion, setProfileVersion] = useState("");
  const [profileReason, setProfileReason] = useState("");
  const overviewLoadedAt = useRef(0);
  const governanceLoadedAt = useRef(0);
  const reportRequestId = useRef(0);
  const reportCache = useRef(new Map<string, ReportCacheEntry>());
  const operationsCache = useRef(createEvaluationOperationsCache());
  const scrollPositions = useRef(new Map<EvaluationTab, number>());
  const pendingScrollRestore = useRef<EvaluationTab | null>(null);

  function updateSearchParams(
    changes: Record<string, string | null>,
    replace = false,
  ) {
    const next = new URLSearchParams(searchParams);
    Object.entries(changes).forEach(([key, value]) => {
      if (value) next.set(key, value);
      else next.delete(key);
    });
    setSearchParams(next, { replace });
  }

  function clearUnsafeDrafts(currentTab: EvaluationTab) {
    if (currentTab === "runs") setRunActionReason("管理员手动操作");
    if (currentTab === "baselines") {
      setProfileReason("");
      setBaselineReason("");
    }
    if (currentTab === "feedback") setReviewReason({});
  }

  function selectTab(nextTab: EvaluationTab) {
    if (nextTab === tab) return;
    clearUnsafeDrafts(tab);
    scrollPositions.current.set(tab, window.scrollY);
    pendingScrollRestore.current = nextTab;
    updateSearchParams({ tab: nextTab });
  }

  useEffect(() => {
    const requested = searchParams.get("tab");
    if (requested && EVALUATION_TABS.has(requested as EvaluationTab)) return;
    const next = new URLSearchParams(searchParams);
    next.set("tab", "new");
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    if (pendingScrollRestore.current !== tab) return;
    pendingScrollRestore.current = null;
    const top = scrollPositions.current.get(tab) ?? 0;
    const frame = window.requestAnimationFrame(() => window.scrollTo({ top }));
    return () => window.cancelAnimationFrame(frame);
  }, [tab]);
  const [reviewReason, setReviewReason] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedRun = useMemo(
    () => runs.find((run) => run.id === selectedRunId) ?? null,
    [selectedRunId, runs],
  );

  const allDatasetVersions = useMemo(
    () => datasets.flatMap((dataset) => dataset.versions.map((version) => ({ dataset, version }))),
    [datasets],
  );
  const wizardVersionId = searchParams.get("datasetVersionId") ?? "";
  const wizardTargetId = searchParams.get("targetId") ?? "";
  const wizardSubjectId = searchParams.get("subjectId") ?? "";
  const wizardStep = searchParams.get("step") ?? "dataset";
  const wizardDataset = allDatasetVersions.find(({ version }) => version.id === wizardVersionId) ?? null;
  const wizardTarget = targets.find((target) => target.id === wizardTargetId) ?? null;
  const wizardSubject = subjects.find((subject) => subject.id === wizardSubjectId)
    ?? subjects.find((subject) => subject.targetId === wizardTargetId && subject.datasetVersionId === wizardVersionId)
    ?? null;
  const wizardTargets = wizardDataset
    ? targets.filter((target) => target.subjectType === wizardDataset.version.caseType)
    : [];

  function setWizard(changes: Record<string, string | null>) {
    updateSearchParams(changes);
  }

  function openReport(runId: string) {
    clearUnsafeDrafts(tab);
    scrollPositions.current.set(tab, window.scrollY);
    pendingScrollRestore.current = "report";
    updateSearchParams({ tab: "report", run: runId });
  }

  const compatibleVersions = useMemo(() => {
    const subject = subjects.find((item) => item.id === runSubjectId);
    if (!subject) return [];
    return datasets.flatMap((dataset) =>
      dataset.versions.filter((version) =>
        version.caseType === subject.subjectType
        && (!subject.datasetVersionId || version.id === subject.datasetVersionId),
      ),
    );
  }, [datasets, runSubjectId, subjects]);

  const subjectTargets = useMemo(
    () => targets.filter((target) => target.subjectType !== "MULTIFORMAT_RELEASE"),
    [targets],
  );

  const terminalRuns = useMemo(
    () => runs.filter((run) =>
      run.status === "SUCCEEDED" || run.status === "BLOCKED_PREREQUISITE"),
    [runs],
  );

  const loadOverview = useCallback(async (quiet = false, force = false) => {
    if (!force && overviewLoadedAt.current > 0
      && Date.now() - overviewLoadedAt.current < PAGE_CACHE_TTL_MS) return;
    if (!quiet) setLoading(true);
    try {
      const [datasetList, targetList, subjectList, runPage, release] = await Promise.all([
        apiRequest<EvaluationDataset[]>("/api/v1/admin/evaluations/datasets"),
        apiRequest<EvaluationTarget[]>("/api/v1/admin/evaluations/targets"),
        apiRequest<EvaluationSubject[]>("/api/v1/admin/evaluations/subjects"),
        apiRequest<EvaluationRunPage>("/api/v1/admin/evaluations/runs?page=0&size=50"),
        apiRequest<MultiformatRelease>("/api/v1/admin/evaluations/multiformat-release"),
      ]);
      setDatasets(datasetList);
      setTargets(targetList);
      setSubjects(subjectList);
      setRuns(runPage.items);
      setMultiformatRelease(release);
      setError(null);
      overviewLoadedAt.current = Date.now();
      setRunSubjectId((current) => current || subjectList[0]?.id || "");
      const firstGenericTarget = targetList.find((target) => target.subjectType !== "MULTIFORMAT_RELEASE");
      setSubjectTargetId((current) => current || firstGenericTarget?.id || "");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "评测中心加载失败");
    } finally {
      if (!quiet) setLoading(false);
    }
  }, []);

  const loadGovernance = useCallback(async (force = false) => {
    if (!force && governanceLoadedAt.current > 0
      && Date.now() - governanceLoadedAt.current < PAGE_CACHE_TTL_MS) return;
    try {
      const [profiles, runtime, baselineList, feedbackList] = await Promise.all([
        apiRequest<AnswerProfile[]>("/api/v1/admin/answer-profiles"),
        apiRequest<RuntimeAnswerProfile>("/api/v1/admin/answer-profiles/runtime"),
        apiRequest<EvaluationBaseline[]>("/api/v1/admin/evaluations/baselines"),
        apiRequest<EvaluationFeedback[]>("/api/v1/admin/evaluations/feedback"),
      ]);
      setAnswerProfiles(profiles);
      setRuntimeProfile(runtime);
      setBaselines(baselineList);
      setFeedback(feedbackList);
      governanceLoadedAt.current = Date.now();
      if (runtime.modelId) {
        const normalized = runtime.modelId.replaceAll(/[^A-Za-z0-9._-]/g, "-").slice(-36);
        setProfileVersion((current) => current || `answer-${normalized || "runtime"}-v1`);
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "评测治理数据加载失败");
    }
  }, []);

  const loadReport = useCallback(async (
    runId: string,
    multiformat: boolean,
    force = false,
  ) => {
    const requestId = ++reportRequestId.current;
    const cached = reportCache.current.get(runId);
    if (!force && cached && Date.now() - cached.loadedAt < PAGE_CACHE_TTL_MS) {
      setResults(cached.results);
      setEvents(cached.events);
      setReleaseReport(cached.releaseReport);
      setReportLoading(false);
      return;
    }
    setReportLoading(true);
    setResults(null);
    setEvents([]);
    setReleaseReport(null);
    const [resultState, eventState, releaseState] = await Promise.allSettled([
      apiRequest<EvaluationResultPage>(
        `/api/v1/admin/evaluations/runs/${runId}/results?page=0&size=100`,
      ),
      apiRequest<EvaluationRunEvent[]>(
        `/api/v1/admin/evaluations/runs/${runId}/events`,
      ),
      multiformat
        ? apiRequest<EvaluationReleaseReport>(`/api/v1/admin/evaluations/runs/${runId}/release-report`)
        : Promise.resolve(null),
    ]);
    if (requestId !== reportRequestId.current) return;
    const nextResults = resultState.status === "fulfilled" ? resultState.value : null;
    const nextEvents = eventState.status === "fulfilled" ? eventState.value : [];
    const nextReleaseReport = releaseState.status === "fulfilled" ? releaseState.value : null;
    setResults(nextResults);
    setEvents(nextEvents);
    setReleaseReport(nextReleaseReport);

    const failed = [resultState, eventState, releaseState]
      .find((state) => state.status === "rejected");
    if (failed?.status === "rejected") {
      const reason = failed.reason;
      setError(reason instanceof ApiError ? reason.message : "评测报告加载失败");
    } else {
      reportCache.current.set(runId, {
        loadedAt: Date.now(),
        results: nextResults,
        events: nextEvents,
        releaseReport: nextReleaseReport,
      });
      setError(null);
    }
    setReportLoading(false);
  }, []);

  const refreshPage = useCallback(async () => {
    setWorking(true);
    try {
      await Promise.all([loadOverview(true, true), loadGovernance(true)]);
      if (tab === "report" && selectedRun) {
        await loadReport(
          selectedRun.id,
          selectedRun.subjectType === "MULTIFORMAT_RELEASE",
          true,
        );
      }
    } finally {
      setWorking(false);
    }
  }, [loadGovernance, loadOverview, loadReport, selectedRun, tab]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    void loadGovernance();
  }, [loadGovernance]);

  useEffect(() => {
    if (loading) return;
    void loadOverview(true);
    if (["baselines", "compare", "feedback"].includes(tab)) {
      void loadGovernance();
    }
  }, [loadGovernance, loadOverview, loading, tab]);

  useEffect(() => {
    if (loading || tab !== "report") return;
    if (selectedRun) return;
    const fallbackRunId = runs[0]?.id ?? null;
    if (selectedRunId === fallbackRunId) return;
    const next = new URLSearchParams(searchParams);
    if (fallbackRunId) next.set("run", fallbackRunId);
    else next.delete("run");
    setSearchParams(next, { replace: true });
  }, [loading, runs, searchParams, selectedRun, selectedRunId, setSearchParams, tab]);

  useEffect(() => {
    if (tab === "report" && selectedRun) {
      void loadReport(
        selectedRun.id,
        selectedRun.subjectType === "MULTIFORMAT_RELEASE",
      );
    }
  }, [loadReport, selectedRun, tab]);

  useEffect(() => {
    const active = runs.some((run) => run.status === "PENDING" || run.status === "RUNNING");
    if (!active || !["new", "runs", "report"].includes(tab)) return;
    const timer = window.setInterval(() => {
      void loadOverview(true, true);
      if (tab === "report" && selectedRun) {
        void loadReport(
          selectedRun.id,
          selectedRun.subjectType === "MULTIFORMAT_RELEASE",
          true,
        );
      }
    }, 1500);
    return () => window.clearInterval(timer);
  }, [loadOverview, loadReport, runs, selectedRun, tab]);

  useEffect(() => {
    const valid = compatibleVersions.some((version) => version.version === runDatasetVersion);
    if (!valid) setRunDatasetVersion(compatibleVersions[0]?.version ?? "");
  }, [compatibleVersions, runDatasetVersion]);

  async function createSubject(event: FormEvent) {
    event.preventDefault();
    setWorking(true);
    try {
      const created = await apiRequest<EvaluationSubject>("/api/v1/admin/evaluations/subjects", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: subjectName.trim(),
          targetId: subjectTargetId,
        }),
      });
      await loadOverview(true, true);
      setRunSubjectId(created.id);
      selectTab("runs");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Subject 创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function freezeWizardSubject() {
    if (!wizardDataset || !wizardTarget) return;
    if (wizardSubject) {
      setWizard({ subjectId: wizardSubject.id, step: "review" });
      return;
    }
    setWorking(true);
    try {
      const created = await apiRequest<EvaluationSubject>("/api/v1/admin/evaluations/subjects", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: `${wizardDataset.version.version} · ${wizardTarget.targetKey}`.slice(0, 120),
          targetId: wizardTarget.id,
        }),
      });
      await loadOverview(true, true);
      setWizard({ subjectId: created.id, step: "review" });
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "冻结评测配置失败");
    } finally {
      setWorking(false);
    }
  }

  async function createWizardRun() {
    if (!wizardDataset || !wizardSubject) return;
    setWorking(true);
    try {
      const created = await apiRequest<EvaluationRun>("/api/v1/admin/evaluations/runs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          evaluationSubjectId: wizardSubject.id,
          datasetVersion: wizardDataset.version.version,
          idempotencyKey: idempotencyKey(),
        }),
      });
      await loadOverview(true, true);
      await loadReport(
        created.id,
        created.subjectType === "MULTIFORMAT_RELEASE",
        true,
      );
      openReport(created.id);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "评测运行创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function freezeMultiformatRelease(reason: string) {
    setWorking(true);
    try {
      const frozen = await apiRequest<MultiformatRelease>("/api/v1/admin/evaluations/multiformat-release", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ confirmation: "FREEZE_MULTIFORMAT_RELEASE", reason }),
      });
      setMultiformatRelease(frozen);
      await loadOverview(true, true);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "多格式发布事实冻结失败");
    } finally {
      setWorking(false);
    }
  }

  async function createRun(event: FormEvent) {
    event.preventDefault();
    if (!runSubjectId || !runDatasetVersion) return;
    setWorking(true);
    try {
      const created = await apiRequest<EvaluationRun>("/api/v1/admin/evaluations/runs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          evaluationSubjectId: runSubjectId,
          datasetVersion: runDatasetVersion,
          idempotencyKey: idempotencyKey(),
        }),
      });
      await loadOverview(true, true);
      await loadReport(
        created.id,
        created.subjectType === "MULTIFORMAT_RELEASE",
        true,
      );
      openReport(created.id);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Run 创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function cancelRun(run: EvaluationRun) {
    setWorking(true);
    try {
      await apiRequest(`/api/v1/admin/evaluations/runs/${run.id}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          confirmation: "CANCEL_EVALUATION_RUN",
          reason: runActionReason.trim(),
        }),
      });
      await loadOverview(true, true);
      await loadReport(
        run.id,
        run.subjectType === "MULTIFORMAT_RELEASE",
        true,
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "取消失败");
    } finally {
      setWorking(false);
    }
  }

  async function retryRun(run: EvaluationRun) {
    setWorking(true);
    try {
      const retried = await apiRequest<EvaluationRun>(
        `/api/v1/admin/evaluations/runs/${run.id}/retry`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            confirmation: "RETRY_EVALUATION_RUN",
            reason: runActionReason.trim(),
            idempotencyKey: idempotencyKey(),
          }),
        },
      );
      await loadOverview(true, true);
      await loadReport(
        retried.id,
        retried.subjectType === "MULTIFORMAT_RELEASE",
        true,
      );
      openReport(retried.id);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "重试失败");
    } finally {
      setWorking(false);
    }
  }

  async function inspectMapping(version: EvaluationDatasetVersion) {
    setMappingVersionId(version.id);
    setMapping(null);
    try {
      setMapping(await apiRequest<EvaluationMappingPage>(
        `/api/v1/admin/evaluations/datasets/versions/${version.id}/mappings?page=0&size=100`,
      ));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "映射状态加载失败");
    }
  }

  async function compareRuns(event: FormEvent) {
    event.preventDefault();
    if (!compareLeftId || !compareRightId) return;
    setWorking(true);
    try {
      const query = new URLSearchParams({
        leftRunId: compareLeftId,
        rightRunId: compareRightId,
      });
      if (compareReason.trim()) query.set("reason", compareReason.trim());
      setComparison(await apiRequest<EvaluationCompare>(
        `/api/v1/admin/evaluations/compare?${query.toString()}`,
      ));
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Run Compare 失败");
    } finally {
      setWorking(false);
    }
  }

  async function createRuntimeProfile(event: FormEvent) {
    event.preventDefault();
    if (!runtimeProfile) return;
    setWorking(true);
    try {
      await apiRequest<AnswerProfile>("/api/v1/admin/answer-profiles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          version: profileVersion.trim(),
          modelProvider: runtimeProfile.modelProvider,
          modelId: runtimeProfile.modelId,
          modelRevision: runtimeProfile.modelRevision,
          endpointIdentity: runtimeProfile.endpointIdentity,
          promptVersion: runtimeProfile.promptVersion,
          orchestrationVersion: runtimeProfile.orchestrationVersion,
          timeoutMs: runtimeProfile.timeoutMs,
          maxOutputTokens: runtimeProfile.maxOutputTokens,
          remoteEvidenceAllowed: runtimeProfile.remoteEvidenceAllowed,
          remoteMemoryAllowed: runtimeProfile.remoteMemoryAllowed,
          reason: profileReason.trim(),
        }),
      });
      await loadGovernance(true);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "AnswerProfile 创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function publishProfile(profile: AnswerProfile, rollback = false) {
    setWorking(true);
    try {
      await apiRequest(
        `/api/v1/admin/answer-profiles/${rollback ? "rollbacks" : "publications"}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            profileVersion: profile.version,
            confirmation: rollback ? "ROLLBACK_ANSWER_PROFILE" : "PUBLISH_ANSWER_PROFILE",
            reason: profileReason.trim() || (rollback ? "回滚 AnswerProfile" : "发布 AnswerProfile"),
          }),
        },
      );
      await loadGovernance(true);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "AnswerProfile 发布失败");
    } finally {
      setWorking(false);
    }
  }

  async function publishBaseline(event: FormEvent) {
    event.preventDefault();
    if (!baselineRunId) return;
    setWorking(true);
    try {
      await apiRequest<EvaluationBaseline>("/api/v1/admin/evaluations/baseline-publications", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          action: "PUBLISH",
          runId: baselineRunId,
          name: baselineName.trim(),
          confirmation: "PUBLISH_BASELINE",
          reason: baselineReason.trim(),
        }),
      });
      await loadGovernance(true);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Baseline 发布失败");
    } finally {
      setWorking(false);
    }
  }

  async function rollbackBaseline(baseline: EvaluationBaseline) {
    setWorking(true);
    try {
      await apiRequest("/api/v1/admin/evaluations/baseline-publications", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          action: "ROLLBACK",
          baselineId: baseline.id,
          confirmation: "PUBLISH_BASELINE",
          reason: baselineReason.trim() || "回滚 Evaluation Baseline",
        }),
      });
      await loadGovernance(true);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Baseline 回滚失败");
    } finally {
      setWorking(false);
    }
  }

  async function review(item: EvaluationFeedback, decision: "APPROVED" | "REJECTED") {
    const reason = reviewReason[item.id]?.trim();
    if (!reason) {
      setError("请先填写审核理由");
      return;
    }
    setWorking(true);
    try {
      await apiRequest(`/api/v1/admin/evaluations/feedback/${item.id}/review`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision, reason }),
      });
      await Promise.all([loadGovernance(true), loadOverview(true, true)]);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "反馈审核失败");
    } finally {
      setWorking(false);
    }
  }

  return (
    <section className="evaluation-page">
      <header className="evaluation-hero">
        <div>
          <h2>评测工作台</h2>
          <p>管理评测数据、运行、报告、基线与低泄漏运维状态。</p>
        </div>
        {tab !== "observability" && tab !== "drills" ? (
          <button className="secondary-button" type="button" onClick={() => void refreshPage()} disabled={working}>
            刷新
          </button>
        ) : null}
      </header>

      {error ? <div className="form-error evaluation-alert" role="alert">{error}</div> : null}

      <nav className="evaluation-tabs" aria-label="评测中心页签">
        {([
          ["new", "新建评测"],
          ["runs", "评测运行"],
          ["report", "报告"],
          ["baselines", "基线"],
        ] as const).map(([value, label]) => (
          <button
            key={value}
            type="button"
            className={tab === value ? "active" : ""}
            onClick={() => selectTab(value)}
          >
            {label}
          </button>
        ))}
      </nav>

      <details className="evaluation-advanced-nav" open={["datasets", "compare", "feedback", "observability", "drills"].includes(tab)}>
        <summary>高级工具</summary>
        <div>
          {([ ["datasets", "数据管理"], ["compare", "运行对比"], ["feedback", "反馈审核"], ["observability", "观测"], ["drills", "故障演练"] ] as const).map(([value, label]) => (
            <button key={value} type="button" className={tab === value ? "active" : ""} onClick={() => selectTab(value)}>{label}</button>
          ))}
        </div>
      </details>

      {loading ? (
        <div className="inline-state evaluation-tab-loading" role="status">
          <span className="spinner" />正在加载评测事实模型
        </div>
      ) : (
        <>
      {tab === "new" ? (
        <section className="evaluation-section evaluation-wizard">
          <ol className="wizard-progress" aria-label="新建评测步骤">
            {["选择数据", "选择目标", "冻结配置", "确认运行"].map((label, index) => {
              const current = ["dataset", "target", "subject", "review"].indexOf(wizardStep);
              return <li key={label} className={index <= Math.max(current, 0) ? "active" : ""}><span>{index + 1}</span>{label}</li>;
            })}
          </ol>

          <section className="evaluation-card wizard-card">
            <header><div><span>步骤 1</span><h3>选择不可变 DatasetVersion</h3></div><small>数据作者工作流保持独立；向导不会改写 Case。</small></header>
            <div className="wizard-option-grid">
              {allDatasetVersions.map(({ dataset, version }) => <button key={version.id} type="button" className={wizardVersionId === version.id ? "selected" : ""} onClick={() => setWizard({ datasetVersionId: version.id, targetId: null, subjectId: null, step: "target" })}><strong>{dataset.title}</strong><span>{version.version} · {caseTypeLabel[version.caseType]}</span><small>{version.caseCount} 个案例 · 可运行 {version.readyCases + version.mappedCases + version.notRequiredCases}</small></button>)}
            </div>
          </section>

          {wizardDataset ? <section className="evaluation-card wizard-card">
            <header><div><span>步骤 2</span><h3>选择 ACTIVE / READY Target</h3></div><small>只显示与 {caseTypeLabel[wizardDataset.version.caseType]} 数据兼容的目标。</small></header>
            {wizardTargets.length ? <div className="wizard-option-grid">{wizardTargets.map((target) => <button key={target.id} type="button" className={wizardTargetId === target.id ? "selected" : ""} onClick={() => setWizard({ targetId: target.id, subjectId: null, step: "subject" })}><strong>{target.targetKey}</strong><span>{target.targetKind} · {statusLabel[target.readinessStatus]}</span><small>{target.blockedReason ?? target.snapshotHash.slice(0, 12)}</small></button>)}</div> : <p className="empty-copy">当前没有兼容 Target。请先在对应检索、图谱或回答模块创建 READY/ACTIVE 候选。</p>}
          </section> : null}

          {wizardTarget ? <section className="evaluation-card wizard-card">
            <header><div><span>步骤 3</span><h3>冻结 EvaluationSubject</h3></div><span className={`evaluation-status ${statusClass(wizardTarget.readinessStatus)}`}>{statusLabel[wizardTarget.readinessStatus]}</span></header>
            <dl className="wizard-facts"><div><dt>Dataset</dt><dd>{wizardDataset?.version.version}</dd></div><div><dt>Target</dt><dd>{wizardTarget.targetKey}</dd></div><div><dt>类型</dt><dd>{caseTypeLabel[wizardTarget.subjectType]}</dd></div><div><dt>Snapshot</dt><dd><code>{wizardTarget.snapshotHash.slice(0, 12)}</code></dd></div></dl>
            {wizardTarget.blockedReason ? <div className="form-error" role="alert">BLOCKED_PREREQUISITE · {wizardTarget.blockedReason}</div> : null}
            <div className="wizard-actions"><button className="secondary-button" type="button" onClick={() => void loadOverview(true, true)}>重新检查前置条件</button><button className="primary-button" type="button" disabled={working} onClick={() => void freezeWizardSubject()}>{wizardSubject ? "使用已冻结配置" : "冻结此配置"}</button></div>
          </section> : null}

          {wizardSubject && wizardStep === "review" ? <section className="evaluation-card wizard-card wizard-confirm">
            <header><div><span>步骤 4</span><h3>确认创建 Run</h3></div><span className={`evaluation-status ${statusClass(wizardSubject.readinessStatus)}`}>{statusLabel[wizardSubject.readinessStatus]}</span></header>
            <p>将使用服务端冻结的 Subject <strong>{wizardSubject.name}</strong> 和 DatasetVersion <strong>{wizardDataset?.version.version}</strong> 排队运行。</p>
            <div className="impact-summary"><strong>此操作会影响</strong><ul><li>创建一条幂等 Evaluation Run 并进入独立评测队列</li></ul><strong>不会影响</strong><ul><li>不会切换线上 Publication、Alias 或自动发布 Baseline</li></ul></div>
            {wizardSubject.blockedReason ? <div className="form-error" role="alert">BLOCKED_PREREQUISITE · {wizardSubject.blockedReason}</div> : null}
            <div className="wizard-actions"><button className="secondary-button" type="button" onClick={() => setWizard({ step: "subject" })}>返回</button><button className="primary-button" type="button" disabled={working} onClick={() => void createWizardRun()}>{working ? "创建中" : wizardSubject.readinessStatus === "READY" ? "确认并开始评测" : "创建阻断记录"}</button></div>
          </section> : null}

          <p className="wizard-footnote">需要编辑数据集？请前往 <button className="text-button" type="button" onClick={() => selectTab("datasets")}>高级工具 · 数据管理</button>。不可变版本不会在向导内被修改。</p>
        </section>
      ) : null}

      {tab === "datasets" ? (
        <section className="evaluation-section">
          <MultiformatReleasePanel
            release={multiformatRelease}
            working={working}
            onFreeze={freezeMultiformatRelease}
          />
          <div className="evaluation-dataset-grid">
            {datasets.map((dataset) => (
              <article className="evaluation-card" key={dataset.id}>
                <header><h3>{dataset.title}</h3><code>{dataset.key}</code></header>
                <p>{dataset.description}</p>
                {dataset.versions.map((version) => (
                  <div className="evaluation-version" key={version.id}>
                    <div>
                      <strong>{version.version}</strong>
                      <span>{caseTypeLabel[version.caseType]} · {version.caseCount} Cases</span>
                    </div>
                    <dl>
                      <div><dt>Mapped</dt><dd>{version.mappedCases}</dd></div>
                      <div><dt>Unmapped</dt><dd>{version.unmappedCases}</dd></div>
                      {version.caseType === "MULTIFORMAT_RELEASE" ? <>
                        <div><dt>Ready</dt><dd>{version.readyCases}</dd></div>
                        <div><dt>Blocked</dt><dd>{version.blockedPrerequisiteCases}</dd></div>
                      </> : null}
                      <div><dt>License</dt><dd>{version.sourceLicense}</dd></div>
                    </dl>
                    <button className="text-button" type="button" onClick={() => void inspectMapping(version)}>
                      查看 Mapping
                    </button>
                  </div>
                ))}
              </article>
            ))}
          </div>

          {mappingVersionId ? (
            <section className="evaluation-mapping">
              <header>
                <div><span>CASE MAPPING</span><h3>当前 Revision 映射</h3></div>
                <button className="text-button" type="button" onClick={() => setMappingVersionId(null)}>关闭</button>
              </header>
              {!mapping ? <div className="inline-state"><span className="spinner" />正在复核 Evidence</div> : (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>Case / 格式</th><th>文件事实</th><th>当前状态</th><th>来源位置</th><th>阻断原因</th></tr></thead>
                    <tbody>{mapping.items.map((item) => (
                      <tr key={item.caseId}>
                        <td><code>{item.caseKey}</code><small>{item.documentFormat ?? item.language}</small></td>
                        <td>{item.originalFilename ?? "—"}<small>{item.sourceLicense ?? "—"} · {item.fileSha256?.slice(0, 12) ?? "—"}</small></td>
                        <td><span className={`evaluation-status ${statusClass(item.effectiveStatus)}`}>{statusLabel[item.effectiveStatus]}</span></td>
                        <td>{item.sourceLabel ?? "—"}<small>{item.locatorKind ?? "—"} · {item.locatorHash?.slice(0, 12) ?? "—"}</small></td>
                        <td>{item.blockedReason ?? (item.missingEvidenceKeys.length ? item.missingEvidenceKeys.join(", ") : "—")}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
            </section>
          ) : null}
        </section>
      ) : null}

      {tab === "runs" ? (
        <section className="evaluation-section">
          <div className="evaluation-form-grid">
            <form className="evaluation-card" onSubmit={createSubject}>
              <header><h3>冻结 EvaluationSubject</h3><span>引用不可变 Target</span></header>
              <label>名称<input value={subjectName} onChange={(event) => setSubjectName(event.target.value)} maxLength={120} required /></label>
              <label>评测 Target<select value={subjectTargetId} onChange={(event) => setSubjectTargetId(event.target.value)} required>
                <option value="">选择 ACTIVE / READY Target</option>
                {subjectTargets.map((target) => (
                  <option value={target.id} key={target.id}>
                    {caseTypeLabel[target.subjectType]} · {target.targetKind} · {statusLabel[target.readinessStatus]}
                  </option>
                ))}
              </select></label>
              <button className="primary-button" disabled={working || !subjectName.trim() || !subjectTargetId}>创建 Subject</button>
            </form>

            <form className="evaluation-card" onSubmit={createRun}>
              <header><h3>创建 Evaluation Run</h3><span>幂等排队，不切线上版本</span></header>
              <label>Subject<select value={runSubjectId} onChange={(event) => setRunSubjectId(event.target.value)} required>
                <option value="">选择 Subject</option>
                {subjects.map((subject) => (
                  <option value={subject.id} key={subject.id}>{subject.name} · {subject.datasetVersion ?? "未绑定数据集"} · {caseTypeLabel[subject.subjectType]} · {statusLabel[subject.readinessStatus]}</option>
                ))}
              </select></label>
              <label>DatasetVersion<select value={runDatasetVersion} onChange={(event) => setRunDatasetVersion(event.target.value)} required>
                <option value="">选择兼容版本</option>
                {compatibleVersions.map((version) => <option value={version.version} key={version.id}>{version.version}</option>)}
              </select></label>
              <button className="primary-button" disabled={working || !runSubjectId || !runDatasetVersion}>排队运行</button>
            </form>
          </div>

          <section className="evaluation-card evaluation-subjects">
            <header><h3>冻结版本</h3><span>{subjects.length} Subjects</span></header>
            {subjects.length === 0 ? <p className="empty-copy">尚未创建 EvaluationSubject。</p> : (
              <div className="evaluation-subject-list">{subjects.map((subject) => (
                <article key={subject.id}>
                  <div><strong>{subject.name}</strong><span>{subject.datasetVersion ?? "未绑定数据集"} · {caseTypeLabel[subject.subjectType]} · {subject.targetKind}</span></div>
                  <span className={`evaluation-status ${statusClass(subject.readinessStatus)}`}>{statusLabel[subject.readinessStatus]}</span>
                  <code>{subject.snapshotHash.slice(0, 12)}</code>
                  <small>{subject.blockedReason ?? `Target ${subject.targetKey ?? "LEGACY"} · Index ${String(subject.snapshot.indexGeneration ?? "—")} · Graph ${String(subject.snapshot.graphGeneration ?? "—")} · Global ${String(subject.snapshot.globalGeneration ?? "—")}`}</small>
                </article>
              ))}</div>
            )}
          </section>

          <section className="evaluation-card">
            <header><h3>Run 队列</h3><span>{runs.length} Runs</span></header>
            <label>操作审计理由
              <input value={runActionReason} onChange={(event) => setRunActionReason(event.target.value)} maxLength={500} required />
            </label>
            {runs.length === 0 ? <p className="empty-copy">尚无 Run。先冻结 Subject，再选择兼容 DatasetVersion。</p> : (
              <div className="table-wrap"><table className="evaluation-run-table">
                <thead><tr><th>Run</th><th>Subject / Dataset</th><th>状态</th><th>进度</th><th>租约</th><th>操作</th></tr></thead>
                <tbody>{runs.map((run) => (
                  <tr key={run.id}>
                    <td><code>{run.id.slice(0, 8)}</code><small>{formatDate(run.createdAt)}</small></td>
                    <td><strong>{run.subjectName}</strong><small>{run.datasetVersion}</small></td>
                    <td><span className={`evaluation-status ${statusClass(run.status)}`}>{statusLabel[run.status]}</span></td>
                    <td>{run.completedCases}/{run.totalCases}<small>成功 {run.succeededCases} · 阻塞 {run.blockedCases}</small></td>
                    <td>{run.leaseOwner ?? "—"}<small>尝试 {run.attempt}</small></td>
                    <td><div className="evaluation-actions">
                      <button className="text-button" type="button" onClick={() => openReport(run.id)}>报告</button>
                      {run.status === "PENDING" || run.status === "RUNNING" ? (
                        <button className="text-button danger-text" type="button" onClick={() => void cancelRun(run)} disabled={working || !runActionReason.trim()}>取消</button>
                      ) : (
                        <button className="text-button" type="button" onClick={() => void retryRun(run)} disabled={working || !runActionReason.trim() || run.status === "BLOCKED_PREREQUISITE"}>重试</button>
                      )}
                    </div></td>
                  </tr>
                ))}</tbody>
              </table></div>
            )}
          </section>
        </section>
      ) : null}

      {tab === "report" ? (
        <section className="evaluation-section">
          <div className="evaluation-report-selector">
            <label>Evaluation Run<select value={selectedRunId ?? ""} onChange={(event) => {
              updateSearchParams({ run: event.target.value || null });
            }}>
              <option value="">选择 Run</option>
              {runs.map((run) => <option value={run.id} key={run.id}>{run.id.slice(0, 8)} · {run.subjectName} · {statusLabel[run.status]}</option>)}
            </select></label>
          </div>
          {reportLoading || (selectedRun && !reportCache.current.has(selectedRun.id)) ? (
            <div className="inline-state evaluation-tab-loading" role="status">
              <span className="spinner" />正在加载评测报告
            </div>
          ) : !selectedRun ? <div className="evaluation-card empty-copy">选择一个 Run 查看报告。</div> : (
            <>
              <section className="evaluation-card evaluation-report-summary">
                <header>
                  <div><span>RUN {selectedRun.id.slice(0, 8)}</span><h3>{selectedRun.subjectName}</h3></div>
                  <span className={`evaluation-status ${statusClass(selectedRun.status)}`}>{statusLabel[selectedRun.status]}</span>
                </header>
                <dl>
                  <div><dt>完成</dt><dd>{selectedRun.completedCases}/{selectedRun.totalCases}</dd></div>
                  <div><dt>成功</dt><dd>{selectedRun.succeededCases}</dd></div>
                  <div><dt>失败</dt><dd>{selectedRun.failedCases}</dd></div>
                  <div><dt>阻塞</dt><dd>{selectedRun.blockedCases}</dd></div>
                  <div><dt>Evaluator</dt><dd>{selectedRun.evaluatorVersion}</dd></div>
                </dl>
                {selectedRun.errorMessage ? <p className="form-error">{selectedRun.errorCode}: {selectedRun.errorMessage}</p> : null}
              </section>

              {releaseReport ? <ReleaseReportPanel report={releaseReport} /> : null}

              <div className="evaluation-report-grid">
                <section className="evaluation-card">
                  <header><h3>Case Results</h3><span>{results?.total ?? 0}</span></header>
                  {!results || results.items.length === 0 ? <p className="empty-copy">尚无 Case Result。</p> : (
                    <div className="evaluation-result-list">{results.items.map((result) => (
                      <article key={result.id}>
                        <header><code>{result.caseKey}</code><span className={`evaluation-status ${statusClass(result.status)}`}>{statusLabel[result.status]}</span></header>
                        <p>{caseTypeLabel[result.caseType]} · {result.language} · {result.durationMs} ms</p>
                        <small>{result.errorMessage ?? `contract_ready = ${String(result.metrics[0]?.value ?? "blocked")}`}</small>
                      </article>
                    ))}</div>
                  )}
                </section>
                <section className="evaluation-card">
                  <header><h3>Run Events</h3><span>{events.length}</span></header>
                  {events.length === 0 ? <p className="empty-copy">尚无事件。</p> : (
                    <ol className="evaluation-event-list">{events.map((event) => (
                      <li key={event.id}><span>{event.sequence}</span><div><strong>{event.eventType}</strong><small>{event.fromStatus ?? "—"} → {event.toStatus} · {formatDate(event.createdAt)}</small></div></li>
                    ))}</ol>
                  )}
                </section>
              </div>
            </>
          )}
        </section>
      ) : null}

      {tab === "compare" ? (
        <section className="evaluation-section">
          <form className="evaluation-card evaluation-compare-form" onSubmit={compareRuns}>
            <header><h3>Run Compare</h3><span>确定性指标与语言/类型切片</span></header>
            <div className="evaluation-form-grid">
              <label>左侧 Run<select value={compareLeftId} onChange={(event) => setCompareLeftId(event.target.value)} required>
                <option value="">选择已结束 Run</option>
                {terminalRuns.map((run) => <option value={run.id} key={run.id}>{run.id.slice(0, 8)} · {run.datasetVersion} · {run.evaluatorVersion}</option>)}
              </select></label>
              <label>右侧 Run<select value={compareRightId} onChange={(event) => setCompareRightId(event.target.value)} required>
                <option value="">选择已结束 Run</option>
                {terminalRuns.map((run) => <option value={run.id} key={run.id}>{run.id.slice(0, 8)} · {run.datasetVersion} · {run.evaluatorVersion}</option>)}
              </select></label>
            </div>
            <label>跨 DatasetVersion 比较说明（同版本可留空）
              <input value={compareReason} onChange={(event) => setCompareReason(event.target.value)} maxLength={500} />
            </label>
            <button className="primary-button" disabled={working || !compareLeftId || !compareRightId}>生成对比</button>
          </form>

          {!comparison ? <div className="evaluation-card empty-copy">选择两个 Run 后生成可复现对比。</div> : (
            <>
              <section className="evaluation-card evaluation-report-summary">
                <header><div><span>RUN COMPARE</span><h3>{comparison.left.id.slice(0, 8)} → {comparison.right.id.slice(0, 8)}</h3></div>
                  <span className={`evaluation-status ${comparison.sameDatasetVersion ? "succeeded" : "pending"}`}>
                    {comparison.sameDatasetVersion ? "同一 DatasetVersion" : "跨版本说明比较"}
                  </span>
                </header>
                <dl>
                  <div><dt>左侧成功</dt><dd>{comparison.left.succeededCases}</dd></div>
                  <div><dt>右侧成功</dt><dd>{comparison.right.succeededCases}</dd></div>
                  <div><dt>指标</dt><dd>{comparison.metrics.length}</dd></div>
                  <div><dt>变化 Case</dt><dd>{comparison.changedCases.length}</dd></div>
                </dl>
              </section>
              <div className="evaluation-report-grid">
                <section className="evaluation-card">
                  <header><h3>Metric Delta</h3><span>{comparison.metrics.length}</span></header>
                  <div className="table-wrap"><table><thead><tr><th>Metric</th><th>Left</th><th>Right</th><th>Δ</th></tr></thead>
                    <tbody>{comparison.metrics.map((metric) => <tr key={metric.metricKey}>
                      <td><code>{metric.metricKey}</code></td>
                      <td>{metric.leftValue ?? "未测量"} <small>n={metric.leftMeasured}</small></td>
                      <td>{metric.rightValue ?? "未测量"} <small>n={metric.rightMeasured}</small></td>
                      <td>{metric.delta == null ? "—" : metric.delta.toFixed(3)}</td>
                    </tr>)}</tbody>
                  </table></div>
                </section>
                <section className="evaluation-card">
                  <header><h3>Slice</h3><span>{comparison.slices.length}</span></header>
                  <div className="evaluation-result-list">{comparison.slices.map((slice) => (
                    <article key={`${slice.dimension}:${slice.value}`}>
                      <header><code>{slice.dimension}:{slice.value}</code></header>
                      <p>Left {slice.leftSucceeded}/{slice.leftFailed}/{slice.leftBlocked}</p>
                      <small>Right {slice.rightSucceeded}/{slice.rightFailed}/{slice.rightBlocked}</small>
                    </article>
                  ))}</div>
                </section>
              </div>
            </>
          )}
        </section>
      ) : null}

      {tab === "baselines" ? (
        <section className="evaluation-section">
          <div className="evaluation-form-grid">
            <form className="evaluation-card" onSubmit={createRuntimeProfile}>
              <header><h3>冻结 AnswerProfile</h3><span>API Key 永不入库</span></header>
              {!runtimeProfile ? <p className="empty-copy">运行时配置加载中。</p> : (
                <>
                  <p>{runtimeProfile.enabled ? "运行时已启用" : "运行时未启用"} · {runtimeProfile.modelId || "未配置模型"} · {runtimeProfile.modelRevision}</p>
                  <label>版本<input value={profileVersion} onChange={(event) => setProfileVersion(event.target.value)} maxLength={64} required /></label>
                  <label>创建/发布理由<input value={profileReason} onChange={(event) => setProfileReason(event.target.value)} maxLength={500} required /></label>
                  <button className="primary-button" disabled={working || !runtimeProfile.modelId || !profileReason.trim()}>从当前运行时创建</button>
                </>
              )}
            </form>

            <form className="evaluation-card" onSubmit={publishBaseline}>
              <header><h3>显式发布 Baseline</h3><span>真实评测执行器完成前保持阻断</span></header>
              <label>Run<select value={baselineRunId} onChange={(event) => setBaselineRunId(event.target.value)} required>
                <option value="">选择真实评测 SUCCEEDED Run</option>
                {runs.filter((run) =>
                  run.status === "SUCCEEDED"
                  && isRealEvaluator(run.evaluatorVersion)
                ).map((run) => (
                  <option value={run.id} key={run.id}>{run.id.slice(0, 8)} · {run.datasetVersion} · {run.evaluatorVersion}</option>
                ))}
              </select></label>
              <label>Baseline 名称<input value={baselineName} onChange={(event) => setBaselineName(event.target.value)} maxLength={160} required /></label>
              <label>审计理由<input value={baselineReason} onChange={(event) => setBaselineReason(event.target.value)} maxLength={500} required /></label>
              <button className="primary-button" disabled={working || !baselineRunId || !baselineReason.trim()}>PUBLISH_BASELINE</button>
            </form>
          </div>

          <section className="evaluation-card">
            <header><h3>AnswerProfile</h3><span>{answerProfiles.length}</span></header>
            {answerProfiles.length === 0 ? <p className="empty-copy">尚无不可变 AnswerProfile。</p> : (
              <div className="evaluation-subject-list">{answerProfiles.map((profile) => (
                <article key={profile.version}>
                  <div><strong>{profile.version}</strong><span>{profile.modelProvider} · {profile.modelId}</span></div>
                  <span className={`evaluation-status ${profile.published ? "succeeded" : "ready"}`}>{profile.published ? "ACTIVE" : "不可变"}</span>
                  <code>{profile.modelRevision}</code>
                  <small>{profile.endpointIdentity} · {profile.maxOutputTokens} tokens</small>
                  {!profile.published ? <div className="evaluation-actions">
                    <button className="text-button" type="button" disabled={working || !profileReason.trim()} onClick={() => void publishProfile(profile)}>发布</button>
                    <button className="text-button" type="button" disabled={working || !profileReason.trim()} onClick={() => void publishProfile(profile, true)}>回滚到此版本</button>
                  </div> : null}
                </article>
              ))}</div>
            )}
          </section>

          <section className="evaluation-card">
            <header><h3>Baselines</h3><span>{baselines.length}</span></header>
            {baselines.length === 0 ? <p className="empty-copy">尚无已发布 Baseline。质量未测量不会被伪造成零分。</p> : (
              <div className="evaluation-subject-list">{baselines.map((baseline) => (
                <article key={baseline.id}>
                  <div><strong>{baseline.name}</strong><span>{baseline.baselineKey}</span></div>
                  <span className={`evaluation-status ${baseline.gateStatus === "PASSED" ? "succeeded" : "blocked"}`}>
                    {baseline.published
                      ? baseline.gateStatus === "PASSED" ? "ACTIVE" : "ACTIVE · 已阻断"
                      : statusLabel[baseline.gateStatus]}
                  </span>
                  <code>{baseline.runId.slice(0, 8)}</code>
                  <small>硬门禁 {String(baseline.gateSummary.passed)} · Judge {String(baseline.judgeAdvisory.status)}</small>
                  {!baseline.published && baseline.gateStatus === "PASSED" ? <button className="text-button" type="button" disabled={working || !baselineReason.trim()} onClick={() => void rollbackBaseline(baseline)}>回滚到此 Baseline</button> : null}
                </article>
              ))}</div>
            )}
          </section>
        </section>
      ) : null}

      {tab === "feedback" ? (
        <section className="evaluation-section">
          <section className="evaluation-card">
            <header><h3>共享反馈审核</h3><span>{feedback.length}</span></header>
            <p>这里只展示用户明确同意分享的脱敏版本；审核者无法通过此页面读取原始问答正文。</p>
            {feedback.length === 0 ? <p className="empty-copy">暂无待审核或已审核的共享反馈。</p> : (
              <div className="evaluation-feedback-list">{feedback.map((item) => (
                <article key={item.id}>
                  <header>
                    <div><strong>{item.rating}/5</strong><code>{item.chatRunId.slice(0, 8)}</code></div>
                    <span className={`evaluation-status ${statusClass(item.reviewStatus)}`}>{statusLabel[item.reviewStatus] ?? item.reviewStatus}</span>
                  </header>
                  <p><strong>问题：</strong>{String(item.redactedSample.question ?? "—")}</p>
                  <p><strong>回答：</strong>{String(item.redactedSample.answer ?? "—")}</p>
                  <small>{item.comment || "未填写评论"} · {formatDate(item.createdAt)}</small>
                  {item.reviewStatus === "PENDING" ? (
                    <div className="evaluation-review">
                      <input aria-label={`审核理由 ${item.id}`} placeholder="审核理由" value={reviewReason[item.id] ?? ""} onChange={(event) => setReviewReason((current) => ({ ...current, [item.id]: event.target.value }))} maxLength={500} />
                      <button className="text-button" type="button" disabled={working} onClick={() => void review(item, "APPROVED")}>采纳并创建新 DatasetVersion</button>
                      <button className="text-button danger-text" type="button" disabled={working} onClick={() => void review(item, "REJECTED")}>拒绝</button>
                    </div>
                  ) : <small>{item.reviewReason} {item.createdDatasetVersionId ? `· DatasetVersion ${item.createdDatasetVersionId.slice(0, 8)}` : ""}</small>}
                </article>
              ))}</div>
            )}
          </section>
        </section>
      ) : null}

      {tab === "observability" || tab === "drills" ? (
        <EvaluationOperationsPanel view={tab} cache={operationsCache.current} />
      ) : null}
        </>
      )}
    </section>
  );
}
