import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { SearchIcon } from "../components/Icons";
import {
  SourceLocation,
  sourceLocationText,
} from "../components/SourceLocation";
import {
  readSearchContinuity,
  updateSearchContinuityView,
  writeSearchContinuity,
  type SearchContinuityEntry,
} from "../searchContinuityCache";
import {
  readSearchHistoryState,
  type SearchHistoryState,
} from "../searchHistory";
import type {
  DocumentVisibility,
  GraphMode,
  SearchPage as SearchPageResponse,
} from "../types";

const EMPTY_PAGE: SearchPageResponse = {
  items: [],
  page: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  tookMs: 0,
  profileVersion: "",
  indexGeneration: 0,
  modeRequested: "BM25",
  modeUsed: "BM25",
  degraded: false,
  degradationCode: null,
  totalRelation: "EXACT",
  graphProfileVersion: null,
  graphGeneration: null,
  graphModeRequested: "HYBRID",
  graphModeUsed: "HYBRID",
  graphDegraded: false,
  graphDegradationCode: null,
  routeExecution: {
    requestedMode: "HYBRID",
    selectedMode: "HYBRID",
    routerCallCount: 0,
    reasonCode: "EXPLICIT_MODE",
    degraded: false,
    degradationCode: null,
  },
};

type SearchState = "idle" | "restoring" | "loading" | "ready" | "error";

export function SearchPage() {
  const { expireSession, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [restoredSearch] = useState(() => readSearchHistoryState(
    (location.state as { search?: unknown } | null)?.search,
    user?.id,
  ));
  const [restoredContinuity] = useState(() => (
    restoredSearch ? readSearchContinuity(restoredSearch) : null
  ));
  const [draftQuery, setDraftQuery] = useState(restoredSearch?.query ?? "");
  const [draftVisibility, setDraftVisibility] = useState<"" | DocumentVisibility>(
    restoredSearch?.visibility ?? "",
  );
  const [draftGraphMode, setDraftGraphMode] = useState<GraphMode>(
    restoredSearch?.graphMode ?? "HYBRID",
  );
  const [query, setQuery] = useState(restoredSearch?.query ?? "");
  const [visibility, setVisibility] = useState<"" | DocumentVisibility>(
    restoredSearch?.visibility ?? "",
  );
  const [graphMode, setGraphMode] = useState<GraphMode>(restoredSearch?.graphMode ?? "HYBRID");
  const [page, setPage] = useState(restoredSearch?.page ?? 0);
  const [results, setResults] = useState<SearchPageResponse>(EMPTY_PAGE);
  const [continuityShell, setContinuityShell] = useState<SearchContinuityEntry | null>(
    restoredContinuity,
  );
  const [state, setState] = useState<SearchState>(
    restoredSearch ? (restoredContinuity ? "restoring" : "loading") : "idle",
  );
  const [message, setMessage] = useState<string | null>(null);
  const [selectedChunkId, setSelectedChunkId] = useState<string | null>(
    restoredSearch?.selectedChunkId ?? null,
  );
  const requestRef = useRef<AbortController | null>(null);
  const evidenceHeadingRef = useRef<HTMLHeadingElement>(null);
  const evidencePanelRef = useRef<HTMLElement>(null);

  const persistSearch = useCallback((
    nextQuery: string,
    nextVisibility: "" | DocumentVisibility,
    nextGraphMode: GraphMode,
    nextPage: number,
    nextSelectedChunkId: string | null,
  ) => {
    if (!user) return;
    const persisted: SearchHistoryState = {
      version: 1,
      ownerUserId: user.id,
      query: nextQuery,
      visibility: nextVisibility,
      graphMode: nextGraphMode,
      page: nextPage,
      selectedChunkId: nextSelectedChunkId,
    };
    navigate("/search", { replace: true, state: { search: persisted } });
  }, [navigate, user]);

  const search = useCallback((
    nextQuery: string,
    nextVisibility: "" | DocumentVisibility,
    nextGraphMode: GraphMode,
    nextPage: number,
    preferredChunkId: string | null = null,
    restoreView: SearchContinuityEntry | null = null,
  ) => {
    if (!nextQuery) return;
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setQuery(nextQuery);
    setVisibility(nextVisibility);
    setGraphMode(nextGraphMode);
    setPage(nextPage);
    setState(restoreView ? "restoring" : "loading");
    setMessage(null);
    persistSearch(
      nextQuery,
      nextVisibility,
      nextGraphMode,
      nextPage,
      preferredChunkId,
    );

    const body = {
      query: nextQuery,
      page: nextPage,
      size: 10,
      graphModeRequested: nextGraphMode,
      ...(nextVisibility ? { visibility: nextVisibility } : {}),
    };
    apiRequest<SearchPageResponse>("/api/v1/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
      .then((response) => {
        if (controller.signal.aborted) return;
        setResults(response);
        const nextSelectedChunkId = response.items.some((hit) => hit.chunkId === preferredChunkId)
          ? preferredChunkId
          : response.items[0]?.chunkId ?? null;
        setSelectedChunkId(nextSelectedChunkId);
        setContinuityShell(null);
        if (user) {
          writeSearchContinuity(
            {
              version: 1,
              ownerUserId: user.id,
              query: nextQuery,
              visibility: nextVisibility,
              graphMode: nextGraphMode,
              page: nextPage,
              selectedChunkId: nextSelectedChunkId,
            },
            response,
            {
              selectedChunkId: nextSelectedChunkId,
              scrollY: restoreView?.scrollY ?? 0,
              evidenceScrollTop: restoreView?.evidenceScrollTop ?? 0,
            },
          );
        }
        persistSearch(
          nextQuery,
          nextVisibility,
          nextGraphMode,
          nextPage,
          nextSelectedChunkId,
        );
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        if (restoreView) {
          setResults(EMPTY_PAGE);
          setSelectedChunkId(preferredChunkId);
        }
        setMessage(caught instanceof ApiError ? caught.message : "搜索失败，请稍后重试");
        setState("error");
      })
      .finally(() => {
        if (requestRef.current === controller) requestRef.current = null;
      });
  }, [expireSession, persistSearch, user]);

  const restoreStartedRef = useRef(false);
  useEffect(() => {
    if (!restoredSearch || restoreStartedRef.current) return;
    restoreStartedRef.current = true;
    search(
      restoredSearch.query,
      restoredSearch.visibility,
      restoredSearch.graphMode,
      restoredSearch.page,
      restoredSearch.selectedChunkId,
      restoredContinuity,
    );
  }, [restoredContinuity, restoredSearch, search]);

  useEffect(() => {
    return () => requestRef.current?.abort();
  }, []);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalized = draftQuery.trim();
    if (!normalized) {
      requestRef.current?.abort();
      setQuery("");
      setResults(EMPTY_PAGE);
      setSelectedChunkId(null);
      setState("idle");
      setMessage("请输入搜索内容");
      navigate("/search", { replace: true });
      return;
    }
    search(normalized, draftVisibility, draftGraphMode, 0);
  }

  const hasResults = results.items.length > 0;
  const selectedHit = results.items.find((hit) => hit.chunkId === selectedChunkId)
    ?? results.items[0]
    ?? null;

  const currentSearchState: SearchHistoryState | null = user && query
    ? {
        version: 1,
        ownerUserId: user.id,
        query,
        visibility,
        graphMode,
        page,
        selectedChunkId,
      }
    : null;

  const captureContinuity = useCallback((nextSelectedChunkId = selectedChunkId) => {
    if (!user || !query || state !== "ready" || results.items.length === 0) return;
    const rowHeights = [...document.querySelectorAll<HTMLElement>(".search-results > li")]
      .map((element) => element.getBoundingClientRect().height);
    updateSearchContinuityView({
      version: 1,
      ownerUserId: user.id,
      query,
      visibility,
      graphMode,
      page,
      selectedChunkId: nextSelectedChunkId,
    }, {
      selectedChunkId: nextSelectedChunkId,
      scrollY: globalThis.scrollY,
      evidenceScrollTop: evidencePanelRef.current?.scrollTop ?? 0,
      rowHeights,
    });
  }, [
    graphMode,
    page,
    query,
    results.items.length,
    selectedChunkId,
    state,
    user,
    visibility,
  ]);

  function selectEvidence(chunkId: string) {
    setSelectedChunkId(chunkId);
    persistSearch(query, visibility, graphMode, page, chunkId);
    captureContinuity(chunkId);
    const focusEvidence = () => {
      evidenceHeadingRef.current?.focus();
      evidenceHeadingRef.current?.scrollIntoView?.({ block: "nearest" });
    };
    if (typeof globalThis.requestAnimationFrame === "function") {
      globalThis.requestAnimationFrame(focusEvidence);
    } else {
      globalThis.setTimeout(focusEvidence, 0);
    }
  }

  useEffect(() => {
    if (state !== "ready") return;
    const frame = globalThis.requestAnimationFrame(() => captureContinuity());
    return () => globalThis.cancelAnimationFrame(frame);
  }, [captureContinuity, state]);

  const shellScrollRestoredRef = useRef(false);
  useLayoutEffect(() => {
    if (!continuityShell || shellScrollRestoredRef.current) return;
    shellScrollRestoredRef.current = true;
    let settledFrame: number | null = null;
    const restoreScroll = () => {
      if (continuityShell.scrollY > 0) {
        globalThis.scrollTo({ top: continuityShell.scrollY, behavior: "auto" });
      }
      if (evidencePanelRef.current) {
        evidencePanelRef.current.scrollTop = continuityShell.evidenceScrollTop;
      }
    };
    const frame = globalThis.requestAnimationFrame(() => {
      restoreScroll();
      settledFrame = globalThis.requestAnimationFrame(restoreScroll);
    });
    return () => {
      globalThis.cancelAnimationFrame(frame);
      if (settledFrame !== null) globalThis.cancelAnimationFrame(settledFrame);
    };
  }, [continuityShell]);

  const evidenceScrollRestoredRef = useRef(false);
  useLayoutEffect(() => {
    if (
      state !== "ready"
      || !restoredContinuity
      || evidenceScrollRestoredRef.current
    ) {
      return;
    }
    evidenceScrollRestoredRef.current = true;
    let settledFrame: number | null = null;
    const restoreScroll = () => {
      if (restoredContinuity.scrollY > 0) {
        globalThis.scrollTo({ top: restoredContinuity.scrollY, behavior: "auto" });
      }
      if (evidencePanelRef.current) {
        evidencePanelRef.current.scrollTop = restoredContinuity.evidenceScrollTop;
      }
    };
    const frame = globalThis.requestAnimationFrame(() => {
      restoreScroll();
      settledFrame = globalThis.requestAnimationFrame(restoreScroll);
    });
    return () => {
      globalThis.cancelAnimationFrame(frame);
      if (settledFrame !== null) globalThis.cancelAnimationFrame(settledFrame);
    };
  }, [restoredContinuity, state]);

  const showContinuityShell = Boolean(continuityShell)
    && (state === "restoring" || state === "error");

  return (
    <section className="search-page">
      <form className="search-toolbar" onSubmit={submit}>
        <label className="search-field search-query-field">
          <SearchIcon />
          <span className="sr-only">搜索知识库</span>
          <input
            type="search"
            value={draftQuery}
            onChange={(event) => setDraftQuery(event.target.value)}
            placeholder="输入中文、English 或混合查询"
          />
        </label>
        <button
          className="primary-button"
          type="submit"
          disabled={state === "loading" || state === "restoring"}
        >
          {state === "loading" || state === "restoring" ? "正在搜索" : "搜索"}
        </button>
      </form>

      <div className="search-preferences">
        <label>
          <span>检索方式</span>
          <select
            aria-label="常用检索方式"
            value={draftGraphMode}
            onChange={(event) => setDraftGraphMode(event.target.value as GraphMode)}
          >
            <option value="HYBRID">标准检索</option>
            <option value="AUTO">智能选择</option>
            {draftGraphMode === "LOCAL_GRAPH"
              ? <option value="LOCAL_GRAPH">高级 · Local Graph</option>
              : null}
            {draftGraphMode === "GLOBAL_GRAPH"
              ? <option value="GLOBAL_GRAPH">高级 · Global Graph</option>
              : null}
          </select>
        </label>
        <details className="search-advanced-settings">
          <summary>高级设置</summary>
          <div className="search-advanced-fields">
            <label>
              <span>文档范围</span>
              <select
                aria-label="筛选文档可见性"
                value={draftVisibility}
                onChange={(event) => setDraftVisibility(
                  event.target.value as "" | DocumentVisibility,
                )}
              >
                <option value="">全部可见性</option>
                <option value="ALL_USERS">所有用户</option>
                <option value="RESTRICTED">受限文档</option>
              </select>
            </label>
            <label>
              <span>GraphRAG 模式</span>
              <select
                aria-label="GraphRAG 检索模式"
                value={draftGraphMode}
                onChange={(event) => setDraftGraphMode(event.target.value as GraphMode)}
              >
                <option value="AUTO">AUTO · 自动选择</option>
                <option value="HYBRID">HYBRID · 标准混合检索</option>
                <option value="LOCAL_GRAPH">LOCAL_GRAPH · 关系路径</option>
                <option value="GLOBAL_GRAPH">GLOBAL_GRAPH · 公共全局报告</option>
              </select>
            </label>
          </div>
          <p className="search-guidance">
            Graph 模式不可用时会安全回退；最终引用始终锚定 Child 原文。
          </p>
        </details>
      </div>

      {message ? <div className="form-error search-message" role="alert">{message}</div> : null}

      {state === "idle" ? (
        <div className="table-state search-state">
          <SearchIcon />
          <strong>从一个清晰的问题开始</strong>
          <p>支持中文、英文和中英混合内容，也可搜索精确编号与专有名词。</p>
        </div>
      ) : null}
      {state === "loading" && !hasResults && !continuityShell ? (
        <div className="table-state search-state" aria-live="polite">
          <span className="spinner" />
          <strong>正在检索知识库</strong>
          <p>正在筛选可访问内容并整理相关证据。</p>
        </div>
      ) : null}
      {state === "error" && !hasResults && !continuityShell ? (
        <div className="table-state error-state search-state"><p>搜索结果加载失败</p><button className="secondary-button" type="button" onClick={() => search(query, visibility, graphMode, page, selectedChunkId)}>重试</button></div>
      ) : null}
      {state === "ready" && results.items.length === 0 ? (
        <div className="table-state search-state"><SearchIcon /><strong>没有找到相关内容</strong><p>尝试更换关键词或调整筛选条件。</p></div>
      ) : null}

      {showContinuityShell && continuityShell ? (
        <section
          className="search-results-region search-continuity-shell"
          aria-busy={state === "restoring"}
          aria-label="正在恢复上次检索结果"
        >
          <div
            className={state === "error"
              ? "search-update-status search-update-error"
              : "search-update-status"}
            role="status"
            aria-live="polite"
          >
            {state === "restoring" ? <span className="spinner" /> : null}
            <span>
              {state === "restoring"
                ? "已恢复上次浏览位置，正在确认最新权限与版本。"
                : "刷新失败，已保留浏览位置；旧正文仍处于隐藏状态。"}
            </span>
            {state === "error" ? (
              <button
                className="secondary-button"
                type="button"
                onClick={() => search(
                  query,
                  visibility,
                  graphMode,
                  page,
                  selectedChunkId,
                  continuityShell,
                )}
              >
                重试
              </button>
            ) : null}
          </div>
          <div className="search-result-heading">
            <strong>正在恢复 {continuityShell.items.length} 个结果位置</strong>
            <span>复核完成后显示最新正文与证据</span>
          </div>
          <div className="search-workspace">
            <ol className="search-results" aria-label="搜索结果恢复骨架">
              {continuityShell.items.map((item, index) => (
                <li
                  key={item.chunkId}
                  className={item.chunkId === continuityShell.selectedChunkId
                    ? "is-selected search-continuity-row"
                    : "search-continuity-row"}
                  data-testid="search-continuity-row"
                  data-selected={item.chunkId === continuityShell.selectedChunkId}
                  style={{ minHeight: `${item.height}px` }}
                >
                  <span className="sr-only">
                    {item.chunkId === continuityShell.selectedChunkId
                      ? `上次选中的第 ${index + 1} 个结果位置`
                      : `第 ${index + 1} 个结果位置`}
                  </span>
                  <div className="search-skeleton-line search-skeleton-title" aria-hidden="true" />
                  <div className="search-skeleton-line" aria-hidden="true" />
                  <div className="search-skeleton-line search-skeleton-short" aria-hidden="true" />
                </li>
              ))}
            </ol>
            <aside
              ref={evidencePanelRef}
              className="search-evidence-panel search-continuity-evidence"
              aria-label="正在重新确认选中结果的证据"
            >
              <span className="spinner" aria-hidden="true" />
              <strong>正在重新确认 Evidence</strong>
              <p>权限与当前 Revision 通过复核后，才会显示 Child、Parent 和 Graph 内容。</p>
              <div className="search-skeleton-line search-skeleton-title" aria-hidden="true" />
              <div className="search-skeleton-line" aria-hidden="true" />
              <div className="search-skeleton-line search-skeleton-short" aria-hidden="true" />
            </aside>
          </div>
        </section>
      ) : null}

      {hasResults ? (
        <>
          {state === "loading" ? (
            <div className="search-update-status" role="status" aria-live="polite">
              <span className="spinner" />
              <span>正在更新结果，当前内容仍可查看。</span>
            </div>
          ) : null}
          {state === "error" ? (
            <div className="search-update-status search-update-error" role="status">
              <span>更新失败，已保留上一次结果。</span>
              <button
                className="secondary-button"
                type="button"
                onClick={() => search(query, visibility, graphMode, page, selectedChunkId)}
              >
                重试
              </button>
            </div>
          ) : null}
          <div className="search-results-region" aria-busy={state === "loading"}>
          <div className="search-result-heading" role="status">
            <strong>找到 {results.totalElements} 条相关结果</strong>
            <span>选择一条结果查看完整证据</span>
          </div>
          {(results.degraded || results.graphDegraded || results.routeExecution?.degraded) ? (
            <div className="search-degradation" role="status">
              本次检索已使用安全回退，结果仍可查看。具体原因见“运行详情”。
            </div>
          ) : null}
          <details className="search-run-details">
            <summary>运行详情</summary>
            <div className="search-summary">
              <span>共 {results.totalElements} 条结果 · {results.tookMs} ms</span>
              <span>{results.modeUsed} · Generation {results.indexGeneration}</span>
              <span>
                Graph {results.graphModeRequested} → {results.graphModeUsed}
                {results.graphGeneration ? ` · G${results.graphGeneration}` : ""}
              </span>
              <span>
                Router {results.routeExecution?.requestedMode ?? results.graphModeRequested}
                {" → "}{results.routeExecution?.selectedMode ?? results.graphModeUsed}
                {" · "}{results.routeExecution?.routerCallCount ?? 0}/1
              </span>
              {user?.role === "ADMIN" ? (
                <>
                  <span>
                    Profile {results.profileVersion || "—"}
                    {" · Graph Profile "}{results.graphProfileVersion ?? "—"}
                  </span>
                  {results.queryExecution ? (
                    <span>
                      Planner {results.queryExecution.plannerCallCount}
                      {" · Retrieval "}{results.queryExecution.retrievalCallCount}
                      {" · Rerank "}{results.queryExecution.rerankCallCount}
                    </span>
                  ) : null}
                </>
              ) : null}
              {results.globalExecution ? (
                <span>
                  Global G{results.globalExecution.globalGeneration ?? "—"}
                  {" · "}{results.globalExecution.reportCount}/{results.globalExecution.reportLimit} Reports
                </span>
              ) : null}
            </div>
            {results.degraded ? (
              <div className="search-degradation" role="status">
                已安全降级：{results.degradationCode ?? "UNKNOWN"}
              </div>
            ) : null}
            {results.graphDegraded ? (
              <div className="search-degradation" role="status">
                Graph {results.graphModeRequested} → {results.graphModeUsed}：
                {results.graphDegradationCode ?? "GRAPH_UNAVAILABLE"}
              </div>
            ) : null}
            {results.routeExecution?.degraded ? (
              <div className="search-degradation" role="status">
                AUTO 安全回退：{results.routeExecution.degradationCode ?? "ROUTER_UNAVAILABLE"}
              </div>
            ) : null}
          </details>

          <div className="search-workspace">
            <ol className="search-results" aria-label="搜索结果">
              {results.items.map((hit) => {
                const selected = hit.chunkId === selectedHit?.chunkId;
                return (
                  <li key={hit.chunkId} className={selected ? "is-selected" : undefined}>
                    <header>
                      <div>
                        <Link to={`/documents/${hit.documentId}`}>{hit.documentTitle}</Link>
                        <SourceLocation
                          source={hit}
                          revisionNumber={hit.revisionNumber}
                          linkToSource={false}
                        />
                      </div>
                      <button
                        className="secondary-button search-result-select"
                        type="button"
                        aria-pressed={selected}
                        aria-controls="search-evidence-panel"
                        aria-label={`${selected ? "已选中" : "查看证据"}：${hit.documentTitle}，${sourceLocationText(hit)}`}
                        onClick={() => selectEvidence(hit.chunkId)}
                      >
                        {selected ? "已选中" : "查看证据"}
                      </button>
                    </header>
                    {hit.headingPath.length > 0 ? <small>{hit.headingPath.join(" / ")}</small> : null}
                    <p>{hit.snippet}</p>
                    <div className="search-result-actions">
                      <Link
                        className="chunk-link"
                        to={`/chunks/${hit.chunkId}`}
                        onClick={() => {
                          persistSearch(query, visibility, graphMode, page, hit.chunkId);
                          captureContinuity(hit.chunkId);
                        }}
                        state={currentSearchState
                          ? {
                              searchReturn: {
                                ...currentSearchState,
                                selectedChunkId: hit.chunkId,
                              },
                            }
                          : undefined}
                      >
                        查看 Chunk
                      </Link>
                      <SourceLocation
                        className="source-link"
                        source={hit}
                        documentId={hit.documentId}
                        revisionId={hit.revisionId}
                        labelPrefix="打开原文 "
                      />
                    </div>
                  </li>
                );
              })}
            </ol>

            <aside
              ref={evidencePanelRef}
              id="search-evidence-panel"
              className="search-evidence-panel"
              aria-label="选中结果的证据详情"
            >
              {selectedHit ? (
                <>
                  <header>
                    <p className="eyebrow">EVIDENCE</p>
                    <h3 ref={evidenceHeadingRef} tabIndex={-1}>
                      {selectedHit.documentTitle}
                    </h3>
                    <span>
                      Revision {selectedHit.revisionNumber} ·{" "}
                      {sourceLocationText(selectedHit)}
                    </span>
                  </header>
                  {selectedHit.evidence ? (
                    <div className="search-evidence-metrics" aria-label="证据排序信息">
                      <span>Evidence #{selectedHit.evidence.rank}</span>
                      <span>召回 {selectedHit.evidence.retrievalScore.toFixed(4)}</span>
                      {selectedHit.evidence.rerankScore === null
                        ? null
                        : <span>Rerank {selectedHit.evidence.rerankScore.toFixed(4)}</span>}
                    </div>
                  ) : null}
                  {selectedHit.headingPath.length > 0
                    ? <p className="search-evidence-path">{selectedHit.headingPath.join(" / ")}</p>
                    : null}
                  {selectedHit.evidence ? (
                    selectedHit.evidence.childText === selectedHit.snippet
                      ? <p className="search-evidence-empty">Child 证据与结果摘要一致，可打开 Chunk 查看完整上下文。</p>
                      : <p>{selectedHit.evidence.childText}</p>
                  ) : (
                    <p className="search-evidence-empty">
                      此结果未附带扩展证据，可打开 Chunk 或原文继续查看。
                    </p>
                  )}
                  {selectedHit.evidence?.parent ? (
                    <details className="parent-evidence">
                      <summary>
                        Parent 上下文 · {sourceLocationText(selectedHit.evidence.parent)}
                        {" · "}{selectedHit.evidence.parent.contributedTokens} tokens
                      </summary>
                      <p>{selectedHit.evidence.parent.text}</p>
                      {selectedHit.evidence.parent.truncated
                        ? (
                          <small>
                            已按单 Parent {selectedHit.evidence.parent.contributedTokens} Token 上限确定性裁剪
                          </small>
                        )
                        : null}
                    </details>
                  ) : null}
                  {selectedHit.evidence?.graphPaths?.length ? (
                    <details className="parent-evidence">
                      <summary>
                        Local Graph 路径 · {selectedHit.evidence.graphPaths.length} 条
                      </summary>
                      <ol>
                        {selectedHit.evidence.graphPaths.map((path) => (
                          <li key={path.relationshipId}>
                            <strong>{path.relationshipType}</strong>
                            <span>
                              {path.documentTitle} · {sourceLocationText(path)}
                              {" · "}{path.depth} hop
                            </span>
                            <p>{path.evidenceText}</p>
                          </li>
                        ))}
                      </ol>
                    </details>
                  ) : null}
                  {selectedHit.evidence?.globalClaims?.length ? (
                    <details className="parent-evidence global-report-evidence">
                      <summary>
                        Global Community Claims · {selectedHit.evidence.globalClaims.length} 条
                      </summary>
                      <ol>
                        {selectedHit.evidence.globalClaims.map((claim) => (
                          <li key={`${claim.reportId}-${claim.claimId}-${claim.sourceSpanId}`}>
                            <strong>{claim.reportTitle} · Community {claim.communityKey}</strong>
                            <p>{claim.claimText}</p>
                            <blockquote>{claim.evidenceText}</blockquote>
                            <span>
                              {claim.documentTitle} · {sourceLocationText(claim)}
                              {" · "}
                              <Link
                                to={`/chunks/${claim.supportingChunkId}`}
                                onClick={() => captureContinuity()}
                                state={currentSearchState
                                  ? { searchReturn: currentSearchState }
                                  : undefined}
                              >
                                查看 Evidence Child
                              </Link>
                              {" · "}
                              <Link to={`/documents/${claim.documentId}`}>查看文档</Link>
                            </span>
                          </li>
                        ))}
                      </ol>
                      <small>Report 和 Community 仅提供上下文；引用仍锚定 Child + SourceSpan。</small>
                    </details>
                  ) : null}
                </>
              ) : (
                <div className="table-state">
                  <strong>选择一条搜索结果</strong>
                  <p>这里会展示对应的 Child、Parent 与 Graph Evidence。</p>
                </div>
              )}
            </aside>
          </div>
          </div>
        </>
      ) : null}

      {hasResults && results.totalPages > 1 ? (
        <nav className="pagination" aria-label="搜索结果分页">
          <button
            className="secondary-button"
            type="button"
            disabled={state === "loading" || results.page === 0}
            onClick={() => search(query, visibility, graphMode, results.page - 1)}
          >
            上一页
          </button>
          <span>第 {results.page + 1} / {results.totalPages} 页，共 {results.totalElements} 项</span>
          <button
            className="secondary-button"
            type="button"
            disabled={state === "loading" || results.page + 1 >= results.totalPages}
            onClick={() => search(query, visibility, graphMode, results.page + 1)}
          >
            下一页
          </button>
        </nav>
      ) : null}
    </section>
  );
}
