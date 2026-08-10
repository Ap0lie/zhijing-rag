import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import {
  SourceLocation,
  sourceLocationText,
} from "../components/SourceLocation";
import { readSearchHistoryState } from "../searchHistory";
import type { ChunkContext } from "../types";

type ChunkState = "loading" | "ready" | "unavailable" | "error";

export function ChunkDetailPage() {
  const { chunkId } = useParams();
  const location = useLocation();
  const { expireSession, user } = useAuth();
  const [context, setContext] = useState<ChunkContext | null>(null);
  const [loadedChunkId, setLoadedChunkId] = useState<string | null>(null);
  const [state, setState] = useState<ChunkState>("loading");
  const returnSearch = readSearchHistoryState(
    (location.state as { searchReturn?: unknown } | null)?.searchReturn,
    user?.id,
  );
  const returnLinkState = returnSearch ? { search: returnSearch } : undefined;

  useEffect(() => {
    const controller = new AbortController();
    setContext(null);
    setLoadedChunkId(null);
    setState("loading");

    if (!chunkId) {
      setState("unavailable");
      return () => controller.abort();
    }

    apiRequest<ChunkContext>(`/api/v1/chunks/${chunkId}`, { signal: controller.signal })
      .then((response) => {
        if (controller.signal.aborted) return;
        setContext(response);
        setLoadedChunkId(chunkId);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        setContext(null);
        setLoadedChunkId(null);
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setState(caught instanceof ApiError && [403, 404].includes(caught.status) ? "unavailable" : "error");
      });

    return () => controller.abort();
  }, [chunkId, expireSession]);

  if (state === "loading" || (state === "ready" && loadedChunkId !== chunkId)) {
    return <div className="table-state chunk-state" aria-live="polite"><span className="spinner" /><p>正在加载 Chunk 上下文</p></div>;
  }

  if (state === "unavailable") {
    return (
      <div className="detail-error chunk-state">
        <h2>Chunk 不可访问</h2>
        <p>内容可能已删除、版本已更新，或当前账户的访问权限已经变化。</p>
        <Link to="/search" replace={Boolean(returnSearch)} state={returnLinkState}>
          返回知识检索
        </Link>
      </div>
    );
  }

  if (state === "error" || !context) {
    return (
      <div className="detail-error chunk-state">
        <h2>Chunk 加载失败</h2>
        <p>服务暂时不可用，请稍后重试。</p>
        <Link to="/search" replace={Boolean(returnSearch)} state={returnLinkState}>
          返回知识检索
        </Link>
      </div>
    );
  }

  const { child, parent } = context;
  return (
    <section className="chunk-page">
      <div className="chunk-heading">
        <div>
          <Link
            className="back-link"
            to="/search"
            replace={Boolean(returnSearch)}
            state={returnLinkState}
          >
            ← 返回知识检索
          </Link>
          <h2>{context.documentTitle}</h2>
          <p>
            R{context.revisionNumber} · Child #{child.order} ·{" "}
            {sourceLocationText({
              ...child,
              documentFormat: child.documentFormat ?? context.documentFormat,
            })}
          </p>
        </div>
        <Link className="secondary-button chunk-document-link" to={`/documents/${context.documentId}`}>查看文档</Link>
      </div>

      <article className="chunk-panel child-chunk-panel">
        <header>
          <div><strong>Child Chunk</strong><span>BM25 检索锚点</span></div>
          <SourceLocation
            source={{
              ...child,
              documentFormat: child.documentFormat ?? context.documentFormat,
            }}
            documentId={context.documentId}
            revisionId={context.revisionId}
            labelPrefix="打开原文 "
          />
        </header>
        {child.headingPath.length > 0 ? <small>{child.headingPath.join(" / ")}</small> : null}
        <p>{child.text}</p>
        <footer>{child.id} · {child.tokenCount} tokens</footer>
      </article>

      <section className="source-spans" aria-labelledby="source-spans-title">
        <h3 id="source-spans-title">原文位置</h3>
        {context.sourceSpans.length === 0 ? <p>没有可用的 SourceSpan。</p> : (
          <ol>
            {context.sourceSpans.map((span) => (
              <li key={`${span.order}-${span.sourceLocator?.startUnit ?? span.startPage}-${span.startOffset}`}>
                <span>#{span.order}</span>
                <strong>
                  <SourceLocation
                    source={{
                      ...span,
                      documentFormat: span.documentFormat ?? context.documentFormat,
                    }}
                    linkToSource={false}
                  />
                </strong>
                <small>offset {span.startOffset}–{span.endOffset}</small>
              </li>
            ))}
          </ol>
        )}
      </section>

      <article className="chunk-panel parent-chunk-panel">
        <header>
          <div><strong>Parent Chunk</strong><span>仅作上下文，不参与首轮 BM25 排名</span></div>
        </header>
        {parent ? (
          <>
            {parent.headingPath.length > 0 ? <small>{parent.headingPath.join(" / ")}</small> : null}
            <p>{parent.text}</p>
            <footer>
              {parent.id} · {parent.tokenCount} tokens ·{" "}
              <SourceLocation
                source={{
                  ...parent,
                  documentFormat: parent.documentFormat ?? context.documentFormat,
                }}
                linkToSource={false}
              />
            </footer>
          </>
        ) : <p className="missing-parent">当前 Child 没有可用 Parent，上下文回退到 Child 原文。</p>}
      </article>
    </section>
  );
}
