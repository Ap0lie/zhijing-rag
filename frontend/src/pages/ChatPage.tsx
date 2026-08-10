import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type RefObject,
} from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest, apiStreamRequest } from "../api";
import { useAuth } from "../auth";
import { CloseIcon, DocumentIcon } from "../components/Icons";
import { SourceLocation } from "../components/SourceLocation";
import { consumeEventStream, type ServerSentEvent } from "../sse";
import type {
  AnswerStrategy,
  ChatAnswerDeltaEvent,
  ChatCitationDetail,
  ChatCitationEvent,
  ChatCitationSummary,
  ChatCompletedEvent,
  ChatFailedEvent,
  ChatMemoryUsedEvent,
  ChatMemorySuggestionStatusResponse,
  ChatMessage,
  ChatRunMemoryUsage,
  ChatRunSummary,
  ChatRunStatus,
  PersistedChatRunStatus,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatSessionsResponse,
  GraphMode,
  MemoryItem,
  MemoryType,
  QuerySlot,
} from "../types";

type LoadState = "loading" | "ready" | "error";

interface LiveRun {
  runId: string | null;
  question: string | null;
  answer: string;
  citations: ChatCitationSummary[];
  status: ChatRunStatus;
  code: string | null;
  message: string | null;
  graphProfileVersion: string | null;
  graphGeneration: number | null;
  graphModeRequested: GraphMode;
  graphModeUsed: Exclude<GraphMode, "AUTO"> | null;
  graphDegraded: boolean;
  graphDegradationCode: string | null;
  globalConfigVersion: string | null;
  globalGeneration: number | null;
  answerStrategyRequested: AnswerStrategy;
  answerStrategyUsed: AnswerStrategy | null;
  mapCallCount: number;
  reduceCallCount: number;
  queryProfileVersion: string | null;
  historyMessageCount: number;
  historyTokenCount: number;
  historyTrimReasons: string[];
  standaloneQuery: string | null;
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
  routeSelectedMode: Exclude<GraphMode, "AUTO"> | null;
  routerCallCount: number;
  routeReasonCode: string | null;
  routeDegraded: boolean;
  routeDegradationCode: string | null;
  memoryUsedCount: number;
}

interface RunModeSnapshot {
  graphModeRequested: GraphMode;
  answerStrategyRequested: AnswerStrategy;
}

const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function graphModeLabel(value: GraphMode) {
  switch (value) {
    case "AUTO":
      return "智能选择";
    case "HYBRID":
      return "标准检索";
    case "LOCAL_GRAPH":
      return "关系检索";
    case "GLOBAL_GRAPH":
      return "全局分析";
  }
}

function graphModeRunLabel(
  requested?: GraphMode | null,
  used?: Exclude<GraphMode, "AUTO"> | null,
) {
  return `${graphModeLabel(requested ?? "HYBRID")} → ${used ? graphModeLabel(used) : "未确定"}`;
}

function globalExecutionMessage(
  requested: AnswerStrategy | null | undefined,
  used: AnswerStrategy | null | undefined,
  mapCalls: number | null | undefined,
  reduceCalls: number | null | undefined,
) {
  if (used === "DEEP_GLOBAL" || requested === "DEEP_GLOBAL") {
    return `本次深度全局分析完成 ${mapCalls ?? 0} 次资料归纳和 ${reduceCalls ?? 0} 次综合生成。`;
  }
  return "本次先使用已发布的公共报告定位主题，再与当前权限范围内的文档候选融合并复核；最终回答仅引用下方可访问原文。";
}

function useDialogFocus(
  dialogRef: RefObject<HTMLElement | null>,
  onClose: () => void,
) {
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    const focusableSelector = [
      "button:not([disabled])",
      "a[href]",
      "input:not([disabled])",
      "select:not([disabled])",
      "textarea:not([disabled])",
      "[tabindex]:not([tabindex='-1'])",
    ].join(", ");
    const focusableElements = () =>
      Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector));

    document.body.style.overflow = "hidden";
    focusableElements()[0]?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusableElements();
      if (elements.length === 0) {
        event.preventDefault();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    dialog.addEventListener("keydown", handleKeyDown);
    return () => {
      dialog.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [dialogRef, onClose]);
}

function requestKey(prefix: string) {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}:${suffix}`;
}

function uniqueCitations(
  citations: ChatCitationSummary[],
  next: ChatCitationSummary,
): ChatCitationSummary[] {
  return citations.some((citation) => citation.id === next.id) ? citations : [...citations, next];
}

function parseEvent<T>(event: ServerSentEvent, expectedType: string): T {
  if (event.type !== expectedType) {
    throw new Error(`Unexpected event ${event.type}`);
  }
  return JSON.parse(event.data) as T;
}

function statusLabel(status: ChatRunStatus) {
  switch (status) {
    case "RUNNING":
      return "正在生成";
    case "COMPLETED":
      return "回答完成";
    case "REFUSED":
      return "已安全拒答";
    case "CANCELLED":
      return "已取消";
    case "FAILED":
      return "生成失败";
  }
}

function refusalMessage(code?: string | null, validatedEvidenceCount?: number | null) {
  switch (code) {
    case "MODEL_REFUSED":
      return "已找到可追溯的候选来源，但模型未能形成有引用支撑的可靠答案。";
    case "UNSUPPORTED_ANSWER":
      return "生成内容未通过引用校验，系统已拒绝输出缺少证据支持的答案。";
    case "INSUFFICIENT_EVIDENCE":
      return "当前授权范围内没有足够的可引用证据，系统没有生成推测性答案。";
    default:
      return validatedEvidenceCount && validatedEvidenceCount > 0
        ? "候选来源已通过权限与来源复核，但本次未形成可靠的可引用答案。"
        : "当前授权范围内没有足够的可引用证据，系统没有生成推测性答案。";
  }
}

function messageStatusLabel(message: ChatMessage) {
  if (message.status === "FAILED") return "生成失败";
  if (message.status === "CANCELLED") return "已取消";
  if (message.status === "PENDING" || message.status === "STREAMING") return "生成中";
  return null;
}

function historyTrimLabel(reason: string) {
  switch (reason) {
    case "QUERY_PROFILE_DISABLED":
      return "历史功能已关闭";
    case "HISTORY_MESSAGE_LIMIT":
      return "达到消息上限";
    case "HISTORY_TOKEN_BUDGET":
      return "达到 Token 预算";
    case "ASSISTANT_CITATION_REVOKED":
      return "排除已撤权回答";
    case "INCOMPLETE_RUN":
      return "排除未完成回答";
    case "HISTORY_ORPHAN_ASSISTANT":
      return "排除无对应问题的回答";
    default:
      return reason;
  }
}

const DEGRADATION_MESSAGES: Record<string, string> = {
  QUERY_PLANNER_FAILED: "问题拆解暂时未完成，已按原问题继续检索。",
  QUERY_PLANNER_BUDGET_RESERVED: "本次未调用问题拆解，已按原问题继续检索。",
  QUERY_PLANNER_REFINE_FAILED: "补充检索规划未完成，已使用已有检索结果。",
  QUERY_ROUTER_RESPONSE_INVALID: "智能选择结果无法识别，本次使用标准检索。",
  ROUTER_FAILED: "智能选择暂时不可用，本次使用标准检索。",
  ROUTER_TIMEOUT: "智能选择响应超时，本次使用标准检索。",
  ROUTER_PROFILE_UNAVAILABLE: "智能选择尚未就绪，本次使用标准检索。",
  SAFE_FALLBACK: "增强能力暂时不可用，本次已使用安全的基础检索。",
  GRAPH_NO_ACTIVE_GENERATION: "关系图谱尚未就绪，本次使用标准检索。",
  GRAPH_PROJECTION_STALE: "关系图谱正在更新，本次使用标准检索。",
  GLOBAL_AUTHORIZATION_RECHECK_UNAVAILABLE: "全局分析的权限复核暂时不可用，本次未使用全局结果。",
  GLOBAL_DISABLED: "全局分析尚未启用，本次已使用其他可用检索方式。",
  RERANK_DISABLED: "精排服务尚未启用，本次按基础相关性顺序选取证据。",
  RERANK_TIMEOUT: "精排响应超时，本次按基础相关性顺序选取证据。",
  RERANK_UNAVAILABLE: "精排服务暂时不可用，本次按基础相关性顺序选取证据。",
};

function technicalCodes(value?: string | null) {
  return (value ?? "")
    .split(/[+,|]/)
    .map((code) => code.trim())
    .filter(Boolean);
}

function degradationMessage(value?: string | null) {
  const codes = technicalCodes(value);
  const message = codes.map((code) => DEGRADATION_MESSAGES[code]).find(Boolean);
  return message ?? "部分增强能力暂时不可用，系统已使用可用的安全路径继续。";
}

function normalizedQuery(value?: string | null) {
  return (value ?? "").trim().replace(/\s+/g, " ").toLocaleLowerCase();
}

function automaticSessionTitle(question: string) {
  const normalized = question.trim().replace(/\s+/g, " ");
  const characters = Array.from(normalized);
  return characters.length <= 28
    ? normalized
    : `${characters.slice(0, 28).join("").trimEnd()}…`;
}

function appearsToContainMultipleItems(value?: string | null) {
  const query = value ?? "";
  const identifiers = new Set(
    query.match(/[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+/g) ?? [],
  );
  if (identifiers.size >= 2) return true;
  const separators = query.match(/[、,，;；/]/g)?.length ?? 0;
  const enumerators = query.match(/(?:^|\s)(?:\d+[.)、]|[一二三四五六七八九十]+[、.])/g)?.length ?? 0;
  return separators >= 2 || enumerators >= 2;
}

function decompositionIncomplete({
  standaloneQuery,
  slots,
  degraded,
  degradationCode,
}: {
  standaloneQuery?: string | null;
  slots?: QuerySlot[];
  degraded?: boolean;
  degradationCode?: string | null;
}) {
  if (!degraded || !appearsToContainMultipleItems(standaloneQuery)) return false;
  const plannerFailed = technicalCodes(degradationCode).some((code) =>
    code.startsWith("QUERY_PLANNER"));
  const effective = (slots ?? []).filter((slot) => slot.status !== "SKIPPED");
  return plannerFailed
    && effective.length <= 1
    && normalizedQuery(effective[0]?.query) === normalizedQuery(standaloneQuery);
}

function RetrievalTrace({
  standaloneQuery,
  slots,
  plannerCalls,
  retrievalCalls,
  rerankCalls,
  coverageSufficient,
  degraded,
  degradationCode,
  routeRequestedMode,
  routeSelectedMode,
  routerCalls,
  routeReasonCode,
  routeDegraded,
  routeDegradationCode,
  retrievedCandidateCount,
  authorizedCandidateCount,
  rerankedCandidateCount,
  evidenceCandidateCount,
  validatedEvidenceCount,
  runStatus,
  refusalCode,
}: {
  standaloneQuery?: string | null;
  slots?: QuerySlot[];
  plannerCalls?: number;
  retrievalCalls?: number;
  rerankCalls?: number;
  coverageSufficient?: boolean;
  degraded?: boolean;
  degradationCode?: string | null;
  routeRequestedMode?: GraphMode | null;
  routeSelectedMode?: Exclude<GraphMode, "AUTO"> | null;
  routerCalls?: number;
  routeReasonCode?: string | null;
  routeDegraded?: boolean;
  routeDegradationCode?: string | null;
  retrievedCandidateCount?: number | null;
  authorizedCandidateCount?: number | null;
  rerankedCandidateCount?: number | null;
  evidenceCandidateCount?: number | null;
  validatedEvidenceCount?: number | null;
  runStatus?: ChatRunStatus | PersistedChatRunStatus;
  refusalCode?: string | null;
}) {
  if (!standaloneQuery || !slots || slots.length === 0) return null;
  const anyDegraded = Boolean(degraded || routeDegraded);
  const distinctQueries = slots
    .filter((slot) => slot.status !== "SKIPPED")
    .map((slot) => slot.query.trim())
    .filter((query, index, values) =>
      normalizedQuery(query) !== normalizedQuery(standaloneQuery)
      && values.findIndex((value) => normalizedQuery(value) === normalizedQuery(query)) === index);
  const hasCandidateFlow = [
    retrievedCandidateCount,
    authorizedCandidateCount,
    rerankedCandidateCount,
    evidenceCandidateCount,
    validatedEvidenceCount,
  ].every((count) => typeof count === "number");
  const refused = runStatus === "REFUSED";
  return (
    <details className="chat-retrieval-trace">
      <summary>
        检索过程 · {anyDegraded ? "已采用简化策略" : refused ? "未形成可引用答案" : coverageSufficient ? "召回候选足够" : "召回候选不足"}
      </summary>
      <div className="chat-retrieval-readable">
        {degraded ? <p className="chat-run-notice warning">{degradationMessage(degradationCode)}</p> : null}
        {routeDegraded ? <p className="chat-run-notice warning">{degradationMessage(routeDegradationCode)}</p> : null}
        {!anyDegraded && refused ? (
          <p className="chat-run-notice warning">
            {refusalMessage(refusalCode, validatedEvidenceCount)}
          </p>
        ) : !anyDegraded ? (
          <p className={`chat-run-notice ${coverageSufficient ? "success" : "warning"}`}>
            {coverageSufficient
              ? "首轮召回候选已达到数量门槛，无需进行第二轮补充检索。"
              : "首轮召回候选未达到数量门槛，系统会尝试补充检索或安全拒答。"}
          </p>
        ) : null}
        {distinctQueries.length === 0 ? (
          <p>检索直接使用了原始问题，未重复展示相同文本。</p>
        ) : (
          <div className="chat-subquery-summary">
            <strong>已拆分检索</strong>
            <ul>{distinctQueries.map((query) => <li key={query}>{query}</li>)}</ul>
          </div>
        )}
        {routeSelectedMode ? (
          <p>
            <strong>本次检索方式</strong>
            {graphModeLabel(routeSelectedMode)}
            {routeRequestedMode === "AUTO" && !routeDegraded ? "（由系统智能选择）" : ""}
          </p>
        ) : null}
        {hasCandidateFlow ? (
          <div className="chat-candidate-flow" aria-label="候选证据流转">
            <p>
              共召回 {retrievedCandidateCount} 个去重候选；权限与版本复核后保留 {authorizedCandidateCount} 个；
              {rerankCalls ? ` ${rerankedCandidateCount} 个进入相关性排序；` : " 未启用额外精排；"}
              选出 {evidenceCandidateCount} 个证据候选，最终 {validatedEvidenceCount} 个完成权限、版本与来源定位复核。
            </p>
            {Boolean(retrievedCandidateCount && validatedEvidenceCount === 0) ? (
              <small>候选数量不等于可引用证据：权限、文档版本、相关性与来源定位都会继续过滤。</small>
            ) : null}
          </div>
        ) : null}
        <details className="chat-technical-details">
          <summary>技术详情</summary>
          <dl>
            <div><dt>Planner</dt><dd>{plannerCalls ?? 0} 次</dd></div>
            <div><dt>召回</dt><dd>{retrievalCalls ?? slots.length} 次</dd></div>
            <div><dt>Rerank</dt><dd>{rerankCalls ?? 0} 次</dd></div>
            <div><dt>Router</dt><dd>{routerCalls ?? 0}/1</dd></div>
            <div><dt>请求 / 实际模式</dt><dd>{routeRequestedMode ?? "HYBRID"} → {routeSelectedMode ?? "UNRESOLVED"}</dd></div>
            <div><dt>路由原因</dt><dd><code>{routeReasonCode ?? "NONE"}</code></dd></div>
            <div><dt>Planner 状态</dt><dd><code>{degradationCode ?? "QUERY_PLAN_OK"}</code></dd></div>
            <div><dt>Router 状态</dt><dd><code>{routeDegradationCode ?? "ROUTE_OK"}</code></dd></div>
          </dl>
          <ol>
            {slots.map((slot) => (
              <li key={`${slot.round}-${slot.slot}`}>
                R{slot.round}S{slot.slot} · {slot.status} · {slot.candidateCount} 候选
                {slot.degradationCode ? ` · ${slot.degradationCode}` : ""}
                <span>{slot.query}</span>
              </li>
            ))}
          </ol>
        </details>
      </div>
    </details>
  );
}

function MessageCard({
  message,
  run,
  onCitation,
  onMemory,
  onRemember,
  onFeedback,
  feedbackSent,
}: {
  message: ChatMessage;
  run?: ChatRunSummary;
  onCitation: (citationId: string) => void;
  onMemory: (runId: string) => void;
  onRemember: (message: ChatMessage) => void;
  onFeedback: (runId: string, rating: number) => void;
  feedbackSent: boolean;
}) {
  const stateLabel = messageStatusLabel(message);
  const author = message.role === "USER" ? "你" : message.role === "SYSTEM" ? "系统" : "知境回答";
  const showDecompositionWarning = message.role === "ASSISTANT" && run
    ? decompositionIncomplete({
        standaloneQuery: run.standaloneQuery,
        slots: run.querySlots,
        degraded: run.queryDegraded,
        degradationCode: run.queryDegradationCode,
      })
    : false;
  return (
    <article className={`chat-message ${message.role.toLowerCase()}`}>
      <header>
        <strong>{author}</strong>
        <time dateTime={message.createdAt}>{formatDate(message.createdAt)}</time>
      </header>
      <div className="chat-message-content">
        {message.hidden
          ? <span className="chat-message-placeholder">该回答依赖的证据已不可访问，正文已隐藏。</span>
          : message.content}
      </div>
      {showDecompositionWarning ? (
        <aside className="chat-answer-caveat" role="note">
          本次未能对问题中的每一项单独检索，回答可能不完整。建议按每个实体分别提问，以获得更完整的证据。
        </aside>
      ) : null}
      {!message.hidden && message.citations && message.citations.length > 0 ? (
        <div className="chat-citation-list" aria-label="回答引用">
          {message.citations.map((citation) => (
            <button type="button" key={citation.id} onClick={() => onCitation(citation.id)}>
              {citation.label}
            </button>
          ))}
        </div>
      ) : null}
      {message.role === "ASSISTANT" && !message.hidden && run && (run.memoryUsedCount ?? 0) > 0 ? (
        <div className="chat-memory-summary">
          <button type="button" onClick={() => onMemory(run.id)}>
            本次使用 {run.memoryUsedCount} 条记忆
          </button>
        </div>
      ) : null}
      {message.role === "USER" && message.status === "COMPLETED" && !message.hidden ? (
        <div className="chat-memory-capture">
          <button className="text-button" type="button" onClick={() => onRemember(message)}>
            记住这条
          </button>
          {message.memorySuggestionStatus === "PENDING"
          || message.memorySuggestionStatus === "RUNNING" ? (
            <span>正在生成记忆建议…</span>
          ) : null}
          {message.memorySuggestionStatus === "SUCCEEDED"
          && (message.memorySuggestionCount ?? 0) > 0 ? (
            <Link to="/memory">
              {message.memorySuggestionCount} 条候选待确认
            </Link>
          ) : null}
          {message.memorySuggestionStatus === "SUCCEEDED"
          && (message.memorySuggestionCount ?? 0) === 0 ? (
            <span>未发现适合长期保存的内容</span>
          ) : null}
          {message.memorySuggestionStatus === "FAILED" ? (
            <span>建议生成失败，不影响本次回答</span>
          ) : null}
          {message.memorySuggestionStatus === "SKIPPED" ? (
            <span>记忆建议已关闭或来源已失效</span>
          ) : null}
        </div>
      ) : null}
      {stateLabel ? <p className={`chat-message-state ${message.status.toLowerCase()}`}>{stateLabel}</p> : null}
      {message.role === "ASSISTANT" && run ? (
        <details className="chat-run-details">
          <summary>
            运行详情 · {graphModeRunLabel(run.graphModeRequested, run.graphModeUsed)} · {message.citations?.length ?? 0} 条引用
          </summary>
          {run.graphModeUsed ? (
            <p className="chat-message-state">
              本次使用{graphModeLabel(run.graphModeUsed)}。
              {run.graphDegraded ? ` ${degradationMessage(run.graphDegradationCode)}` : ""}
            </p>
          ) : null}
          {run.globalGeneration ? (
            <p className="chat-message-state">
              {globalExecutionMessage(
                run.answerStrategyRequested,
                run.answerStrategyUsed,
                run.mapCallCount,
                run.reduceCallCount,
              )}
            </p>
          ) : null}
          {run.queryProfileVersion ? (
            <p className="chat-message-state">
              参考了 {run.historyMessageIds?.length ?? 0} 条安全历史消息，使用 {run.historyTokenCount ?? 0} Token
              {(run.historyTrimReasons?.length ?? 0) > 0
                ? ` · ${run.historyTrimReasons!.map(historyTrimLabel).join("、")}`
                : ""}
            </p>
          ) : null}
          <RetrievalTrace
            standaloneQuery={run.standaloneQuery}
            slots={run.querySlots}
            plannerCalls={run.plannerCallCount}
            retrievalCalls={run.retrievalCallCount}
            rerankCalls={run.rerankCallCount}
            coverageSufficient={run.coverageSufficient}
            degraded={run.queryDegraded}
            degradationCode={run.queryDegradationCode}
            routeRequestedMode={run.graphModeRequested}
            routeSelectedMode={run.routeSelectedMode}
            routerCalls={run.routerCallCount}
            routeReasonCode={run.routeReasonCode}
            routeDegraded={run.routeDegraded}
            routeDegradationCode={run.routeDegradationCode}
            retrievedCandidateCount={run.retrievedCandidateCount}
            authorizedCandidateCount={run.authorizedCandidateCount}
            rerankedCandidateCount={run.rerankedCandidateCount}
            evidenceCandidateCount={run.evidenceCandidateCount}
            validatedEvidenceCount={run.validatedEvidenceCount}
            runStatus={run.status}
            refusalCode={run.errorCode}
          />
          <details className="chat-technical-details chat-run-technical-details">
            <summary>运行技术信息</summary>
            <dl>
              <div><dt>Graph</dt><dd><code>{run.graphModeRequested ?? "HYBRID"} → {run.graphModeUsed ?? "UNRESOLVED"}</code></dd></div>
              <div><dt>Graph 状态</dt><dd><code>{run.graphDegradationCode ?? "GRAPH_OK"}</code></dd></div>
              <div><dt>Graph Generation</dt><dd>{run.graphGeneration ?? "NONE"}</dd></div>
              <div><dt>Global Generation</dt><dd>{run.globalGeneration ?? "NONE"}</dd></div>
              <div><dt>回答策略</dt><dd><code>{run.answerStrategyUsed ?? run.answerStrategyRequested ?? "STANDARD"}</code></dd></div>
              <div><dt>Map / Reduce</dt><dd>{run.mapCallCount ?? 0} / {run.reduceCallCount ?? 0}</dd></div>
              <div><dt>历史计数器</dt><dd><code>{run.historyCounterVersion ?? "NONE"}</code></dd></div>
            </dl>
          </details>
        </details>
      ) : null}
      {message.role === "ASSISTANT"
      && run
      && (run.status === "COMPLETED" || run.status === "REFUSED") ? (
        <div className="chat-feedback-actions" aria-label="回答反馈">
          {feedbackSent ? <span>感谢反馈</span> : (
            <>
              <button className="text-button" type="button" onClick={() => onFeedback(run.id, 5)}>有帮助</button>
              <button className="text-button" type="button" onClick={() => onFeedback(run.id, 2)}>需改进</button>
            </>
          )}
        </div>
      ) : null}
    </article>
  );
}

function memoryTypeLabel(value: ChatRunMemoryUsage["memoryType"]) {
  switch (value) {
    case "USER_PREFERENCE":
      return "用户偏好";
    case "USER_FACT":
      return "用户事实";
    case "SESSION_SUMMARY":
      return "会话摘要";
    case "DOCUMENT_FACT":
      return "文档事实";
  }
}

function memoryUsageLabel(value: ChatRunMemoryUsage["usageStatus"]) {
  switch (value) {
    case "USED":
      return "已用于回答";
    case "INJECTED":
      return "已注入，未采用";
    case "DOCUMENT_EVIDENCE":
      return "已转为文档证据";
    case "TRIMMED":
      return "已裁剪";
    case "REMOTE_BLOCKED":
      return "远程发送未许可";
  }
}

function memoryTrimLabel(value: string | null) {
  switch (value) {
    case "MEMORY_ITEM_LIMIT":
      return "超过 5 条上限";
    case "MEMORY_TOKEN_BUDGET":
      return "超过 512 Token 预算";
    case "REMOTE_MEMORY_NOT_ALLOWED":
      return "远程 Memory 许可关闭";
    case "MEMORY_DOCUMENT_NOT_SELECTED":
      return "这段原文未进入本次有效依据";
    default:
      return value;
  }
}

function MemoryDrawer({
  runId,
  onClose,
}: {
  runId: string;
  onClose: () => void;
}) {
  const { expireSession } = useAuth();
  const dialogRef = useRef<HTMLElement>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [items, setItems] = useState<ChatRunMemoryUsage[]>([]);
  const [error, setError] = useState("记忆使用记录暂时无法读取");

  useEffect(() => {
    const controller = new AbortController();
    setState("loading");
    apiRequest<ChatRunMemoryUsage[]>(`/api/v1/chat/runs/${runId}/memories`, {
      signal: controller.signal,
    })
      .then((result) => {
        setItems(result);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setError(
          caught instanceof ApiError && [403, 404].includes(caught.status)
            ? "该 Run 不存在或不属于当前用户"
            : caught instanceof ApiError
              ? caught.message
              : "记忆使用记录暂时无法读取",
        );
        setState("error");
      });
    return () => controller.abort();
  }, [expireSession, runId]);

  const usedCount = items.filter((item) =>
    item.usageStatus === "USED" || item.usageStatus === "DOCUMENT_EVIDENCE").length;
  useDialogFocus(dialogRef, onClose);

  return (
    <div className="drawer-layer" role="presentation">
      <aside ref={dialogRef} className="drawer memory-drawer" role="dialog" aria-modal="true" aria-labelledby="memory-usage-title">
        <header className="drawer-header">
          <div>
            <small>与引用证据分离</small>
            <h2 id="memory-usage-title">本次使用的记忆</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭记忆">
            <CloseIcon />
          </button>
        </header>
        <div className="drawer-body">
          {state === "loading" ? (
            <div className="chat-drawer-state" aria-live="polite">
              <span className="spinner" aria-hidden="true" />
              <p>正在读取并重新校验记忆</p>
            </div>
          ) : null}
          {state === "error" ? (
            <div className="chat-drawer-state error-state" role="alert"><p>{error}</p></div>
          ) : null}
          {state === "ready" && items.length === 0 ? (
            <div className="chat-drawer-state"><p>本轮没有使用长期记忆。</p></div>
          ) : null}
          {state === "ready" && items.length > 0 ? (
            <div className="memory-usage-list">
              <p className="memory-usage-overview">
                实际使用 {usedCount} 条 · 独立于 Citation
              </p>
              {items.map((item) => (
                <article key={item.memoryId} className={item.available ? "" : "unavailable"}>
                  <header>
                    <strong>{memoryTypeLabel(item.memoryType)}</strong>
                    <span>{memoryUsageLabel(item.usageStatus)}</span>
                  </header>
                  {item.available ? (
                    <>
                      <h3>{item.memoryKey}</h3>
                      <p>{item.content}</p>
                    </>
                  ) : (
                    <p className="chat-message-placeholder">
                      该记忆已撤销、过期、遗忘或来源失权，旧正文不再展示。
                    </p>
                  )}
                  <footer>
                    <span title={item.tokenCounterVersion ?? "legacy"}>
                      {item.tokenCountExact ? "" : "约 "}
                      {item.tokenCount} Token / 上限 {item.tokenLimit ?? 512}
                    </span>
                    <span>{item.sourceTypes.length > 0 ? item.sourceTypes.join(" / ") : "用户直接保存"}</span>
                    {item.trimReason ? <span>{memoryTrimLabel(item.trimReason)}</span> : null}
                  </footer>
                </article>
              ))}
            </div>
          ) : null}
        </div>
      </aside>
    </div>
  );
}

function RememberDrawer({
  sessionId,
  message,
  onClose,
  onSaved,
}: {
  sessionId: string;
  message: ChatMessage;
  onClose: () => void;
  onSaved: (memory: MemoryItem) => void;
}) {
  const { expireSession } = useAuth();
  const dialogRef = useRef<HTMLElement>(null);
  const [memoryType, setMemoryType] =
    useState<Extract<MemoryType, "USER_PREFERENCE" | "USER_FACT">>("USER_FACT");
  const [memoryKey, setMemoryKey] = useState("");
  const [content, setContent] = useState(() => message.content);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useDialogFocus(dialogRef, onClose);

  async function save(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const created = await apiRequest<MemoryItem>("/api/v1/memories", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": requestKey("chat-remember"),
        },
        body: JSON.stringify({
          memoryType,
          memoryKey,
          content,
          candidate: false,
          expiresAt: null,
          sources: [{
            sourceType: "CHAT_MESSAGE",
            chatSessionId: sessionId,
            chatMessageId: message.id,
            documentId: null,
            revisionId: null,
            childChunkId: null,
            sourceSpanId: null,
          }],
        }),
      });
      onSaved(created);
      onClose();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "记忆保存失败");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="drawer-layer" role="presentation">
      <aside ref={dialogRef} className="drawer remember-drawer" role="dialog" aria-modal="true" aria-labelledby="remember-title">
        <header className="drawer-header">
          <div>
            <small>由你决定是否保存</small>
            <h2 id="remember-title">记住这条</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭记忆保存">
            <CloseIcon />
          </button>
        </header>
        <form className="drawer-body remember-form" onSubmit={save}>
          <p>内容来自你自己的消息；保存后立即生效，也可以在长期记忆页撤销或忘记。</p>
          <label>
            类型
            <select
              value={memoryType}
              onChange={(event) => setMemoryType(
                event.target.value as "USER_PREFERENCE" | "USER_FACT",
              )}
            >
              <option value="USER_FACT">用户事实</option>
              <option value="USER_PREFERENCE">用户偏好</option>
            </select>
          </label>
          <label>
            记忆名称
            <input
              required
              maxLength={160}
              value={memoryKey}
              onChange={(event) => setMemoryKey(event.target.value)}
              placeholder="例如：所在城市、回答风格"
            />
          </label>
          <label>
            记忆内容
            <textarea
              required
              maxLength={1200}
              rows={7}
              value={content}
              onChange={(event) => setContent(event.target.value)}
            />
          </label>
          {error ? <div className="chat-alert failed" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={saving}>
            {saving ? "保存中" : "保存并生效"}
          </button>
        </form>
      </aside>
    </div>
  );
}

function CitationDrawer({
  citationId,
  onClose,
}: {
  citationId: string;
  onClose: () => void;
}) {
  const { expireSession } = useAuth();
  const dialogRef = useRef<HTMLElement>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [citation, setCitation] = useState<ChatCitationDetail | null>(null);
  const [error, setError] = useState("引用暂时无法读取");
  useDialogFocus(dialogRef, onClose);

  useEffect(() => {
    const controller = new AbortController();
    setState("loading");
    setCitation(null);
    apiRequest<ChatCitationDetail>(`/api/v1/chat/citations/${citationId}`, {
      signal: controller.signal,
    })
      .then((result) => {
        setCitation(result);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setError(
          caught instanceof ApiError && [403, 404].includes(caught.status)
            ? "引用不可用，权限或文档版本可能已经变化"
            : caught instanceof ApiError
              ? caught.message
              : "引用暂时无法读取",
        );
        setState("error");
      });
    return () => controller.abort();
  }, [citationId, expireSession]);

  return (
    <div className="drawer-layer" role="presentation">
      <aside ref={dialogRef} className="drawer citation-drawer" role="dialog" aria-modal="true" aria-labelledby="citation-title">
        <header className="drawer-header">
          <div>
            <small>实时权限复核</small>
            <h2 id="citation-title">引用证据</h2>
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭引用">
            <CloseIcon />
          </button>
        </header>
        <div className="drawer-body">
          {state === "loading" ? (
            <div className="chat-drawer-state" aria-live="polite">
              <span className="spinner" aria-hidden="true" />
              <p>正在重新校验引用权限</p>
            </div>
          ) : null}
          {state === "error" ? (
            <div className="chat-drawer-state error-state" role="alert">
              <p>{error}</p>
            </div>
          ) : null}
          {state === "ready" && citation ? (
            <div className="citation-detail">
              <section>
                <span className="citation-label">{citation.label}</span>
                <h3>{citation.documentTitle}</h3>
                <p>
                  <SourceLocation
                    source={citation}
                    documentId={citation.documentId}
                    revisionId={citation.revisionId}
                    revisionNumber={citation.revisionNumber}
                  />
                </p>
              </section>
              {citation.headingPath.length > 0 ? (
                <section>
                  <h4>标题路径</h4>
                  <p>{citation.headingPath.join(" / ")}</p>
                </section>
              ) : null}
              <section>
                <h4>命中原文</h4>
                <blockquote>{citation.childText}</blockquote>
              </section>
              {citation.parentText ? (
                <section>
                  <h4>扩展上下文</h4>
                  <blockquote>{citation.parentText}</blockquote>
                </section>
              ) : null}
              <section>
                <h4>原文位置</h4>
                <dl>
                  <div>
                    <dt>来源位置</dt>
                    <dd>
                      <SourceLocation
                        source={{
                          ...citation.sourceSpan,
                          documentFormat: citation.sourceSpan.documentFormat
                            ?? citation.documentFormat,
                        }}
                        linkToSource={false}
                      />
                    </dd>
                  </div>
                </dl>
                <details className="chat-technical-details">
                  <summary>技术详情</summary>
                  <dl>
                    <div><dt>UTF-16 offset</dt><dd>{citation.sourceSpan.startOffset}–{citation.sourceSpan.endOffset}</dd></div>
                    <div><dt>SourceSpan order</dt><dd>{citation.sourceSpan.order}</dd></div>
                  </dl>
                </details>
              </section>
            </div>
          ) : null}
        </div>
      </aside>
    </div>
  );
}

export function ChatPage() {
  const { expireSession } = useAuth();
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [sessionsState, setSessionsState] = useState<LoadState>("loading");
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChatSessionDetail | null>(null);
  const [detailState, setDetailState] = useState<LoadState>("ready");
  const [question, setQuestion] = useState("");
  const [graphMode, setGraphMode] = useState<GraphMode>("AUTO");
  const [answerStrategy, setAnswerStrategy] =
    useState<AnswerStrategy>("STANDARD");
  const [liveRun, setLiveRun] = useState<LiveRun | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null);
  const [sessionTitleDraft, setSessionTitleDraft] = useState("");
  const [savingSessionTitle, setSavingSessionTitle] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [feedbackSent, setFeedbackSent] = useState<Set<string>>(() => new Set());
  const [selectedCitationId, setSelectedCitationId] = useState<string | null>(null);
  const [selectedMemoryRunId, setSelectedMemoryRunId] = useState<string | null>(null);
  const [rememberMessage, setRememberMessage] = useState<ChatMessage | null>(null);
  const streamControllerRef = useRef<AbortController | null>(null);
  const runIdRef = useRef<string | null>(null);

  const handleApiError = useCallback((caught: unknown, fallback: string) => {
    if (caught instanceof ApiError && caught.status === 401) {
      expireSession();
      return null;
    }
    return caught instanceof ApiError ? caught.message : fallback;
  }, [expireSession]);

  const refreshSessions = useCallback(async () => {
    try {
      const result = await apiRequest<ChatSessionsResponse>("/api/v1/chat/sessions");
      setSessions(result.items);
      setSessionsState("ready");
      setActiveSessionId((current) => {
        if (current && result.items.some((session) => session.id === current)) {
          return current;
        }
        return result.items[0]?.id ?? null;
      });
      return result.items;
    } catch (caught) {
      const message = handleApiError(caught, "会话列表加载失败");
      if (message) {
        setActionError(message);
        setSessionsState("error");
      }
      return [];
    }
  }, [handleApiError]);

  useEffect(() => {
    void refreshSessions();
    return () => streamControllerRef.current?.abort();
  }, [refreshSessions]);

  useEffect(() => {
    if (!activeSessionId) {
      setDetail(null);
      setDetailState("ready");
      return;
    }
    const controller = new AbortController();
    setDetailState("loading");
    apiRequest<ChatSessionDetail>(`/api/v1/chat/sessions/${activeSessionId}`, {
      signal: controller.signal,
    })
      .then((result) => {
        setDetail(result);
        setDetailState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        const message = handleApiError(caught, "会话内容加载失败");
        if (message) {
          setActionError(message);
          setDetailState("error");
        }
      });
    return () => controller.abort();
  }, [activeSessionId, handleApiError]);

  const hasPendingSuggestions = detail?.messages.some((message) =>
    message.memorySuggestionStatus === "PENDING"
      || message.memorySuggestionStatus === "RUNNING") ?? false;

  useEffect(() => {
    if (!activeSessionId || !hasPendingSuggestions) return;
    const controller = new AbortController();
    let timer: number | undefined;
    let stopped = false;
    const schedule = (delay: number) => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => void poll(), delay);
    };
    const poll = async () => {
      if (stopped || controller.signal.aborted) return;
      if (document.visibilityState === "hidden") {
        schedule(3000);
        return;
      }
      try {
        const result = await apiRequest<ChatMemorySuggestionStatusResponse>(
          `/api/v1/chat/sessions/${activeSessionId}/memory-suggestions`,
          { signal: controller.signal },
        );
        if (stopped || controller.signal.aborted) return;
        const states = new Map(result.items.map((item) => [item.messageId, item]));
        setDetail((current) => current?.id === activeSessionId ? {
          ...current,
          messages: current.messages.map((message) => {
            const state = states.get(message.id);
            return state ? {
              ...message,
              memorySuggestionStatus: state.status,
              memorySuggestionCount: state.suggestionCount,
              memorySuggestionErrorCode: state.errorCode,
            } : message;
          }),
        } : current);
        if (result.pending) schedule(2000);
      } catch (caught) {
        if (stopped || controller.signal.aborted) return;
        handleApiError(caught, "记忆建议状态刷新失败");
        schedule(3000);
      }
    };
    const resume = () => {
      if (document.visibilityState === "visible") schedule(0);
    };
    document.addEventListener("visibilitychange", resume);
    schedule(1500);
    return () => {
      stopped = true;
      controller.abort();
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [activeSessionId, handleApiError, hasPendingSuggestions]);

  const refreshAfterRun = useCallback(async (sessionId: string) => {
    const [sessionResult, listResult] = await Promise.allSettled([
      apiRequest<ChatSessionDetail>(`/api/v1/chat/sessions/${sessionId}`),
      apiRequest<ChatSessionsResponse>("/api/v1/chat/sessions"),
    ]);
    if (sessionResult.status === "fulfilled" && activeSessionId === sessionId) {
      setDetail(sessionResult.value);
      setDetailState("ready");
    }
    if (listResult.status === "fulfilled") {
      setSessions(listResult.value.items);
      setSessionsState("ready");
    }
  }, [activeSessionId]);

  const consumeRun = useCallback(async (
    path: string,
    body: {
      question: string;
      graphModeRequested: GraphMode;
      answerStrategyRequested?: AnswerStrategy;
    } | undefined,
    optimisticQuestion: string | null,
    modeSnapshot?: RunModeSnapshot,
  ) => {
    const controller = new AbortController();
    streamControllerRef.current = controller;
    runIdRef.current = null;
    setActionError(null);
    setLiveRun({
      runId: null,
      question: optimisticQuestion,
      answer: "",
      citations: [],
      status: "RUNNING",
      code: null,
      message: null,
      graphProfileVersion: null,
      graphGeneration: null,
      graphModeRequested:
        body?.graphModeRequested ?? modeSnapshot?.graphModeRequested ?? "HYBRID",
      graphModeUsed: null,
      graphDegraded: false,
      graphDegradationCode: null,
      globalConfigVersion: null,
      globalGeneration: null,
      answerStrategyRequested:
        body?.answerStrategyRequested
        ?? modeSnapshot?.answerStrategyRequested
        ?? "STANDARD",
      answerStrategyUsed: null,
      mapCallCount: 0,
      reduceCallCount: 0,
      queryProfileVersion: null,
      historyMessageCount: 0,
      historyTokenCount: 0,
      historyTrimReasons: [],
      standaloneQuery: null,
      querySlots: [],
      plannerCallCount: 0,
      retrievalCallCount: 0,
      rerankCallCount: 0,
      coverageSufficient: false,
      queryDegraded: false,
      queryDegradationCode: null,
      retrievedCandidateCount: 0,
      authorizedCandidateCount: 0,
      rerankedCandidateCount: 0,
      evidenceCandidateCount: 0,
      validatedEvidenceCount: 0,
      routeSelectedMode: null,
      routerCallCount: 0,
      routeReasonCode: null,
      routeDegraded: false,
      routeDegradationCode: null,
      memoryUsedCount: 0,
    });
    let terminal = false;

    try {
      const response = await apiStreamRequest(path, {
        method: "POST",
        signal: controller.signal,
        headers: body ? { "Content-Type": "application/json" } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      if (optimisticQuestion && activeSessionId) {
        const automaticTitle = automaticSessionTitle(optimisticQuestion);
        setSessions((current) => current.map((session) =>
          session.id === activeSessionId && session.title === "新对话"
            ? { ...session, title: automaticTitle }
            : session));
        setDetail((current) => current?.id === activeSessionId && current.title === "新对话"
          ? { ...current, title: automaticTitle }
          : current);
      }
      const responseRunId = response.headers.get("X-Chat-Run-Id");
      if (responseRunId) {
        runIdRef.current = responseRunId;
        setLiveRun((current) => current ? { ...current, runId: responseRunId } : current);
      }
      await consumeEventStream(response, (event) => {
        if (event.type === "answer_delta") {
          const payload = parseEvent<ChatAnswerDeltaEvent>(event, "answer_delta");
          runIdRef.current = payload.runId;
          setLiveRun((current) => current ? {
            ...current,
            runId: payload.runId,
            answer: current.answer + payload.text,
          } : current);
        } else if (event.type === "memory_used") {
          const payload = parseEvent<ChatMemoryUsedEvent>(event, "memory_used");
          runIdRef.current = payload.runId;
          setLiveRun((current) => current ? {
            ...current,
            runId: payload.runId,
            memoryUsedCount: payload.memories.length,
          } : current);
        } else if (event.type === "citation") {
          const payload = parseEvent<ChatCitationEvent>(event, "citation");
          runIdRef.current = payload.runId;
          setLiveRun((current) => current ? {
            ...current,
            runId: payload.runId,
            citations: uniqueCitations(current.citations, payload.citation),
          } : current);
        } else if (event.type === "completed") {
          const payload = parseEvent<ChatCompletedEvent>(event, "completed");
          terminal = true;
          runIdRef.current = payload.runId;
          setLiveRun((current) => current ? {
            ...current,
            runId: payload.runId,
            status: payload.status,
            code: payload.refusalCode ?? null,
            message: payload.status === "REFUSED"
              ? refusalMessage(payload.refusalCode, payload.validatedEvidenceCount)
              : null,
            graphProfileVersion: payload.graphProfileVersion,
            graphGeneration: payload.graphGeneration,
            graphModeRequested: payload.graphModeRequested,
            graphModeUsed: payload.graphModeUsed,
            graphDegraded: payload.graphDegraded,
            graphDegradationCode: payload.graphDegradationCode,
            globalConfigVersion: payload.globalConfigVersion ?? null,
            globalGeneration: payload.globalGeneration ?? null,
            answerStrategyRequested:
              payload.answerStrategyRequested ?? current.answerStrategyRequested,
            answerStrategyUsed: payload.answerStrategyUsed ?? null,
            mapCallCount: payload.mapCallCount ?? 0,
            reduceCallCount: payload.reduceCallCount ?? 0,
            queryProfileVersion: payload.queryProfileVersion,
            historyMessageCount: payload.historyMessageCount,
            historyTokenCount: payload.historyTokenCount,
            historyTrimReasons: payload.historyTrimReasons,
            standaloneQuery: payload.standaloneQuery,
            querySlots: payload.querySlots,
            plannerCallCount: payload.plannerCallCount,
            retrievalCallCount: payload.retrievalCallCount,
            rerankCallCount: payload.rerankCallCount,
            coverageSufficient: payload.coverageSufficient,
            queryDegraded: payload.queryDegraded,
            queryDegradationCode: payload.queryDegradationCode,
            retrievedCandidateCount: payload.retrievedCandidateCount,
            authorizedCandidateCount: payload.authorizedCandidateCount,
            rerankedCandidateCount: payload.rerankedCandidateCount,
            evidenceCandidateCount: payload.evidenceCandidateCount,
            validatedEvidenceCount: payload.validatedEvidenceCount,
            routeSelectedMode: payload.routeSelectedMode,
            routerCallCount: payload.routerCallCount,
            routeReasonCode: payload.routeReasonCode,
            routeDegraded: payload.routeDegraded,
            routeDegradationCode: payload.routeDegradationCode,
          } : current);
        } else if (event.type === "failed") {
          const payload = parseEvent<ChatFailedEvent>(event, "failed");
          terminal = true;
          runIdRef.current = payload.runId;
          setLiveRun((current) => current ? {
            ...current,
            runId: payload.runId,
            status: payload.status,
            code: payload.code,
            message: payload.message,
          } : current);
        }
      });
      if (!terminal && !controller.signal.aborted) {
        throw new Error("回答数据流在终态事件前中断");
      }
    } catch (caught) {
      if (!controller.signal.aborted) {
        const message = handleApiError(caught, "回答连接中断，请重试");
        if (message) {
          setLiveRun((current) => current ? {
            ...current,
            status: "FAILED",
            code: "STREAM_INTERRUPTED",
            message,
          } : current);
        }
      }
    } finally {
      if (streamControllerRef.current === controller) {
        streamControllerRef.current = null;
      }
      if (activeSessionId) {
        await refreshAfterRun(activeSessionId);
      }
    }
  }, [activeSessionId, handleApiError, refreshAfterRun]);

  async function createSession() {
    setCreating(true);
    setActionError(null);
    try {
      const created = await apiRequest<ChatSessionSummary>("/api/v1/chat/sessions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      setSessions((current) => [created, ...current.filter((session) => session.id !== created.id)]);
      setActiveSessionId(created.id);
      setLiveRun(null);
      setQuestion("");
    } catch (caught) {
      const message = handleApiError(caught, "新建会话失败");
      if (message) setActionError(message);
    } finally {
      setCreating(false);
    }
  }

  async function deleteSession(session: ChatSessionSummary) {
    if (!window.confirm(`删除会话“${session.title}”？此操作不会删除文档。`)) return;
    setActionError(null);
    try {
      await apiRequest<void>(`/api/v1/chat/sessions/${session.id}`, { method: "DELETE" });
      if (activeSessionId === session.id) {
        setActiveSessionId(null);
        setDetail(null);
        setLiveRun(null);
      }
      await refreshSessions();
    } catch (caught) {
      const message = handleApiError(caught, "删除会话失败");
      if (message) setActionError(message);
    }
  }

  function beginSessionRename(session: ChatSessionSummary) {
    setRenamingSessionId(session.id);
    setSessionTitleDraft(session.title);
    setActionError(null);
  }

  async function saveSessionTitle(sessionId: string) {
    const title = sessionTitleDraft.trim();
    if (!title || savingSessionTitle) return;
    setSavingSessionTitle(true);
    setActionError(null);
    try {
      const updated = await apiRequest<ChatSessionSummary>(
        `/api/v1/chat/sessions/${sessionId}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title }),
        },
      );
      setSessions((current) => current.map((session) =>
        session.id === updated.id ? updated : session));
      setDetail((current) => current?.id === updated.id
        ? { ...current, title: updated.title, updatedAt: updated.updatedAt }
        : current);
      setRenamingSessionId(null);
      setSessionTitleDraft("");
    } catch (caught) {
      const message = handleApiError(caught, "会话重命名失败");
      if (message) setActionError(message);
    } finally {
      setSavingSessionTitle(false);
    }
  }

  async function submitQuestion(event: FormEvent) {
    event.preventDefault();
    const value = question.trim();
    if (!activeSessionId || !value || liveRun?.status === "RUNNING") return;
    setQuestion("");
    await consumeRun(
      `/api/v1/chat/sessions/${activeSessionId}/runs`,
      {
        question: value,
        graphModeRequested: graphMode,
        ...(graphMode === "GLOBAL_GRAPH"
          ? { answerStrategyRequested: answerStrategy }
          : {}),
      },
      value,
    );
  }

  async function stopRun() {
    if (liveRun?.status !== "RUNNING") return;
    const runId = runIdRef.current;
    if (!runId) return;
    setStopping(true);
    try {
      await apiRequest<void>(`/api/v1/chat/runs/${runId}/cancel`, { method: "POST" });
    } catch (caught) {
      const message = handleApiError(caught, "取消请求未被服务端确认");
      if (message) setActionError(message);
    } finally {
      streamControllerRef.current?.abort();
      setLiveRun((current) => current?.status === "RUNNING" ? {
          ...current,
          runId: runId ?? current.runId,
          status: "CANCELLED",
          code: "USER_CANCELLED",
          message: "回答已由你停止。",
        } : current);
      setStopping(false);
    }
  }

  async function retryRun(runId: string) {
    if (liveRun?.status === "RUNNING") return;
    const sourceRun = detail?.runs.find((run) => run.id === runId);
    await consumeRun(
      `/api/v1/chat/runs/${runId}/retry`,
      undefined,
      null,
      {
        graphModeRequested:
          sourceRun?.graphModeRequested
          ?? (liveRun?.runId === runId ? liveRun.graphModeRequested : null)
          ?? "HYBRID",
        answerStrategyRequested:
          sourceRun?.answerStrategyRequested
          ?? (liveRun?.runId === runId ? liveRun.answerStrategyRequested : null)
          ?? "STANDARD",
      },
    );
  }

  async function submitFeedback(runId: string, rating: number) {
    const consentToShare = window.confirm(
      "是否同意将脱敏后的问答样本提交给管理员审核？取消仍会保存仅自己可见的评分。",
    );
    try {
      await apiRequest(`/api/v1/chat/runs/${runId}/feedback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ rating, comment: null, consentToShare }),
      });
      setFeedbackSent((current) => new Set(current).add(runId));
      setActionError(null);
    } catch (caught) {
      const message = handleApiError(caught, "反馈提交失败");
      if (message) setActionError(message);
    }
  }

  const isRunning = liveRun?.status === "RUNNING";
  const liveRunPersisted = useMemo(
    () => Boolean(liveRun?.runId && detail?.messages.some((message) => message.runId === liveRun.runId)),
    [detail?.messages, liveRun?.runId],
  );
  const liveRetryable = Boolean(
    liveRun?.runId && (liveRun.status === "FAILED" || liveRun.status === "CANCELLED"),
  );
  const persistedRetryRun = useMemo(() => {
    if (liveRun || !detail) return null;
    const latestRun = detail.runs[detail.runs.length - 1];
    return latestRun?.status === "FAILED" || latestRun?.status === "CANCELLED"
      ? latestRun
      : null;
  }, [detail, liveRun]);

  return (
    <section className="chat-page">
      <aside className="chat-session-panel" aria-label="问答会话">
        <header>
          <div>
            <h2>会话</h2>
            <p>系统会在权限允许的范围内参考近期对话。</p>
          </div>
          <button className="primary-button chat-new-button" type="button" onClick={createSession} disabled={creating || isRunning}>
            {creating ? "新建中" : "新建"}
          </button>
        </header>
        {sessionsState === "loading" ? (
          <div className="chat-session-state"><span className="spinner" aria-hidden="true" /><span>加载会话</span></div>
        ) : null}
        {sessionsState === "error" ? (
          <div className="chat-session-state error-state">
            <span>会话列表加载失败</span>
            <button className="text-button" type="button" onClick={() => void refreshSessions()}>重试</button>
          </div>
        ) : null}
        {sessionsState === "ready" && sessions.length === 0 ? (
          <div className="chat-session-state">
            <span>还没有问答会话</span>
            <small>新建后即可基于授权文档提问。</small>
          </div>
        ) : null}
        {sessions.length > 0 ? (
          <ul className="chat-session-list">
            {sessions.map((session) => (
              <li key={session.id} className={activeSessionId === session.id ? "active" : ""}>
                {renamingSessionId === session.id ? (
                  <form
                    className="chat-session-rename"
                    onSubmit={(event) => {
                      event.preventDefault();
                      void saveSessionTitle(session.id);
                    }}
                  >
                    <label className="sr-only" htmlFor={`session-title-${session.id}`}>会话标题</label>
                    <input
                      id={`session-title-${session.id}`}
                      value={sessionTitleDraft}
                      onChange={(event) => setSessionTitleDraft(event.target.value)}
                      maxLength={200}
                      autoFocus
                    />
                    <div>
                      <button type="submit" disabled={!sessionTitleDraft.trim() || savingSessionTitle}>保存</button>
                      <button type="button" onClick={() => setRenamingSessionId(null)} disabled={savingSessionTitle}>取消</button>
                    </div>
                  </form>
                ) : (
                  <>
                    <button
                      className="chat-session-select"
                      type="button"
                      onClick={() => {
                        setActiveSessionId(session.id);
                        setLiveRun(null);
                        setActionError(null);
                      }}
                      disabled={isRunning}
                    >
                      <strong>{session.title}</strong>
                      <time dateTime={session.updatedAt}>{formatDate(session.updatedAt)}</time>
                    </button>
                    <div className="chat-session-actions">
                      <button
                        className="chat-session-rename-button"
                        type="button"
                        aria-label={`重命名会话 ${session.title}`}
                        onClick={() => beginSessionRename(session)}
                        disabled={isRunning}
                      >
                        编辑
                      </button>
                      <button
                        className="chat-session-delete"
                        type="button"
                        aria-label={`删除会话 ${session.title}`}
                        onClick={() => void deleteSession(session)}
                        disabled={isRunning}
                      >
                        删除
                      </button>
                    </div>
                  </>
                )}
              </li>
            ))}
          </ul>
        ) : null}
      </aside>

      <div className="chat-workspace">
        {actionError ? <div className="chat-alert failed" role="alert">{actionError}</div> : null}
        {actionNotice ? <div className="chat-alert completed" role="status">{actionNotice}</div> : null}
        {!activeSessionId ? (
          <div className="chat-empty">
            <DocumentIcon />
            <h2>从授权知识开始提问</h2>
            <p>回答只使用你有权查看的文档；依据不足时会明确说明。</p>
            <button className="primary-button" type="button" onClick={createSession} disabled={creating}>
              新建第一个会话
            </button>
          </div>
        ) : (
          <>
            <div className="chat-transcript" aria-live="polite">
              {detailState === "loading" ? (
                <div className="chat-empty compact"><span className="spinner" aria-hidden="true" /><p>加载会话内容</p></div>
              ) : null}
              {detailState === "error" ? (
                <div className="chat-empty compact"><p>会话内容暂时不可用</p></div>
              ) : null}
              {detailState === "ready" && detail?.messages.length === 0 && !liveRun ? (
                <div className="chat-empty compact">
                  <h2>提出一个可验证的问题</h2>
                  <p>支持中文和英文；每条引用都可以打开并查看原文位置。</p>
                </div>
              ) : null}
              {detail?.messages.map((message) => (
                <MessageCard
                  key={message.id}
                  message={message}
                  run={detail.runs.find((run) => run.id === message.runId)}
                  onCitation={setSelectedCitationId}
                  onMemory={setSelectedMemoryRunId}
                  onRemember={setRememberMessage}
                  onFeedback={(runId, rating) => void submitFeedback(runId, rating)}
                  feedbackSent={Boolean(message.runId && feedbackSent.has(message.runId))}
                />
              ))}
              {liveRun && !liveRunPersisted ? (
                <>
                  {liveRun.question ? (
                    <article className="chat-message user optimistic">
                      <header><strong>你</strong><span>本次问题</span></header>
                      <div className="chat-message-content">{liveRun.question}</div>
                    </article>
                  ) : null}
                  <article className={`chat-message assistant live ${liveRun.status.toLowerCase()}`}>
                    <header>
                      <strong>知境回答</strong>
                      <span className={`chat-run-badge ${liveRun.status.toLowerCase()}`}>{statusLabel(liveRun.status)}</span>
                    </header>
                    <div className="chat-message-content">
                      {liveRun.answer || (liveRun.status === "RUNNING" ? <span className="typing-indicator">正在核验证据并生成</span> : null)}
                    </div>
                    {decompositionIncomplete({
                      standaloneQuery: liveRun.standaloneQuery,
                      slots: liveRun.querySlots,
                      degraded: liveRun.queryDegraded,
                      degradationCode: liveRun.queryDegradationCode,
                    }) ? (
                      <aside className="chat-answer-caveat" role="note">
                        本次未能对问题中的每一项单独检索，回答可能不完整。建议按每个实体分别提问，以获得更完整的证据。
                      </aside>
                    ) : null}
                    {liveRun.citations.length > 0 ? (
                      <div className="chat-citation-list" aria-label="本次回答引用">
                        {liveRun.citations.map((citation) => (
                          <button type="button" key={citation.id} onClick={() => setSelectedCitationId(citation.id)}>
                            {citation.label}
                          </button>
                        ))}
                      </div>
                    ) : null}
                    {liveRun.runId && liveRun.memoryUsedCount > 0 ? (
                      <div className="chat-memory-summary">
                        <button type="button" onClick={() => setSelectedMemoryRunId(liveRun.runId!)}>
                          本次使用 {liveRun.memoryUsedCount} 条记忆
                        </button>
                      </div>
                    ) : null}
                    <details className="chat-run-details">
                      <summary>
                        运行详情 · {graphModeRunLabel(liveRun.graphModeRequested, liveRun.graphModeUsed)} · {liveRun.citations.length} 条引用
                      </summary>
                      {liveRun.graphModeUsed ? (
                        <p className="chat-message-state">
                          本次使用{graphModeLabel(liveRun.graphModeUsed)}。
                          {liveRun.graphDegraded
                            ? ` ${degradationMessage(liveRun.graphDegradationCode)}`
                            : ""}
                        </p>
                      ) : null}
                      {liveRun.globalGeneration ? (
                        <p className="chat-message-state">
                          {globalExecutionMessage(
                            liveRun.answerStrategyRequested,
                            liveRun.answerStrategyUsed,
                            liveRun.mapCallCount,
                            liveRun.reduceCallCount,
                          )}
                        </p>
                      ) : null}
                      {liveRun.queryProfileVersion ? (
                        <p className="chat-message-state">
                          参考了 {liveRun.historyMessageCount} 条安全历史消息，使用 {liveRun.historyTokenCount} Token
                          {liveRun.historyTrimReasons.length > 0
                            ? ` · ${liveRun.historyTrimReasons.map(historyTrimLabel).join("、")}`
                            : ""}
                        </p>
                      ) : null}
                      <RetrievalTrace
                        standaloneQuery={liveRun.standaloneQuery}
                        slots={liveRun.querySlots}
                        plannerCalls={liveRun.plannerCallCount}
                        retrievalCalls={liveRun.retrievalCallCount}
                        rerankCalls={liveRun.rerankCallCount}
                        coverageSufficient={liveRun.coverageSufficient}
                        degraded={liveRun.queryDegraded}
                        degradationCode={liveRun.queryDegradationCode}
                        routeRequestedMode={liveRun.graphModeRequested}
                        routeSelectedMode={liveRun.routeSelectedMode}
                        routerCalls={liveRun.routerCallCount}
                        routeReasonCode={liveRun.routeReasonCode}
                        routeDegraded={liveRun.routeDegraded}
                        routeDegradationCode={liveRun.routeDegradationCode}
                        retrievedCandidateCount={liveRun.retrievedCandidateCount}
                        authorizedCandidateCount={liveRun.authorizedCandidateCount}
                        rerankedCandidateCount={liveRun.rerankedCandidateCount}
                        evidenceCandidateCount={liveRun.evidenceCandidateCount}
                        validatedEvidenceCount={liveRun.validatedEvidenceCount}
                        runStatus={liveRun.status}
                        refusalCode={liveRun.code}
                      />
                      <details className="chat-technical-details chat-run-technical-details">
                        <summary>运行技术信息</summary>
                        <dl>
                          <div><dt>Graph</dt><dd><code>{liveRun.graphModeRequested} → {liveRun.graphModeUsed ?? "UNRESOLVED"}</code></dd></div>
                          <div><dt>Graph 状态</dt><dd><code>{liveRun.graphDegradationCode ?? "GRAPH_OK"}</code></dd></div>
                          <div><dt>Graph Generation</dt><dd>{liveRun.graphGeneration ?? "NONE"}</dd></div>
                          <div><dt>Global Generation</dt><dd>{liveRun.globalGeneration ?? "NONE"}</dd></div>
                          <div><dt>回答策略</dt><dd><code>{liveRun.answerStrategyUsed ?? liveRun.answerStrategyRequested}</code></dd></div>
                          <div><dt>Map / Reduce</dt><dd>{liveRun.mapCallCount} / {liveRun.reduceCallCount}</dd></div>
                          <div><dt>Query Profile</dt><dd><code>{liveRun.queryProfileVersion ?? "NONE"}</code></dd></div>
                        </dl>
                      </details>
                    </details>
                  </article>
                </>
              ) : null}
            </div>

            {liveRun && liveRun.status !== "RUNNING" ? (
              <div className={`chat-alert ${liveRun.status.toLowerCase()}`} role="status">
                <div>
                  <strong>{statusLabel(liveRun.status)}</strong>
                  {liveRun.message ? <span>{liveRun.message}</span> : null}
                  {liveRun.code ? (
                    <details className="chat-alert-technical">
                      <summary>技术详情</summary>
                      <code>{liveRun.code}</code>
                    </details>
                  ) : null}
                </div>
                {liveRetryable && liveRun.runId ? (
                  <button className="secondary-button" type="button" onClick={() => void retryRun(liveRun.runId!)}>
                    创建新 Run 重试
                  </button>
                ) : null}
              </div>
            ) : null}
            {!liveRun && persistedRetryRun ? (
              <div className={`chat-alert ${persistedRetryRun.status.toLowerCase()}`} role="status">
                <div>
                  <strong>{persistedRetryRun.status === "FAILED" ? "上次生成失败" : "上次回答已取消"}</strong>
                  {persistedRetryRun.errorCode ? (
                    <details className="chat-alert-technical">
                      <summary>技术详情</summary>
                      <code>{persistedRetryRun.errorCode}</code>
                    </details>
                  ) : null}
                </div>
                <button className="secondary-button" type="button" onClick={() => void retryRun(persistedRetryRun.id)}>
                  创建新 Run 重试
                </button>
              </div>
            ) : null}

            <form className="chat-composer" onSubmit={submitQuestion}>
              <label htmlFor="chat-question" className="sr-only">问题</label>
              <textarea
                id="chat-question"
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="基于已授权文档提问…"
                maxLength={500}
                rows={3}
                disabled={isRunning}
              />
              <details className="chat-advanced-settings">
                <summary>高级设置 · {graphModeLabel(graphMode)}</summary>
                <div>
                  <label>
                    检索方式
                    <select
                      aria-label="检索模式"
                      value={graphMode}
                      onChange={(event) => {
                        const mode = event.target.value as GraphMode;
                        setGraphMode(mode);
                        if (mode !== "GLOBAL_GRAPH") setAnswerStrategy("STANDARD");
                      }}
                      disabled={isRunning}
                    >
                      <option value="AUTO">智能选择 · 推荐</option>
                      <option value="HYBRID">标准检索 · 固定混合路径</option>
                      <option value="LOCAL_GRAPH">关系检索 · 多跳路径</option>
                      <option value="GLOBAL_GRAPH">全局分析 · 公共报告</option>
                    </select>
                  </label>
                  {graphMode === "GLOBAL_GRAPH" ? (
                    <label>
                      分析深度
                      <select
                        aria-label="回答策略"
                        value={answerStrategy}
                        onChange={(event) =>
                          setAnswerStrategy(event.target.value as AnswerStrategy)}
                        disabled={isRunning}
                      >
                        <option value="STANDARD">标准 · 报告检索</option>
                        <option value="DEEP_GLOBAL">深度 · 多轮资料归纳</option>
                      </select>
                    </label>
                  ) : null}
                </div>
              </details>
              <div>
                <p>
                  {graphMode === "GLOBAL_GRAPH" && answerStrategy === "DEEP_GLOBAL"
                    ? "深度全局分析会进行多轮资料归纳，最长等待 30 秒；运行中可随时停止。"
                    : graphMode === "AUTO"
                      ? "系统会为本次问题选择合适的检索方式，不会自动启用耗时更高的深度分析。"
                      : "回答可能拒绝无证据的问题；引用正文打开时会再次检查权限。"}
                </p>
                {isRunning ? (
                  <button
                    className="danger-button"
                    type="button"
                    onClick={() => void stopRun()}
                    disabled={stopping || !liveRun.runId}
                  >
                    {stopping ? "停止中" : liveRun.runId ? "停止回答" : "正在建立连接"}
                  </button>
                ) : (
                  <button className="primary-button" type="submit" disabled={!question.trim()}>
                    发送问题
                  </button>
                )}
              </div>
            </form>
          </>
        )}
      </div>

      {selectedCitationId ? (
        <CitationDrawer citationId={selectedCitationId} onClose={() => setSelectedCitationId(null)} />
      ) : null}
      {selectedMemoryRunId ? (
        <MemoryDrawer runId={selectedMemoryRunId} onClose={() => setSelectedMemoryRunId(null)} />
      ) : null}
      {rememberMessage && detail ? (
        <RememberDrawer
          key={rememberMessage.id}
          sessionId={detail.id}
          message={rememberMessage}
          onClose={() => setRememberMessage(null)}
          onSaved={(memory) => setActionNotice(`${memory.memoryKey} 已保存并生效。`)}
        />
      ) : null}
    </section>
  );
}
