import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { AdminValue } from "../components/AdminValue";
import type { AdminOverview } from "../types";

export function AdminOverviewPage() {
  const { expireSession } = useAuth();
  const [overview, setOverview] = useState<AdminOverview | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const requestRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setState("loading");
    apiRequest<AdminOverview>("/api/v1/admin/overview", { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        setOverview(result);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setState("error");
      });
  }, [expireSession]);

  useEffect(() => {
    load();
    return () => requestRef.current?.abort();
  }, [load]);

  if (state === "loading" && !overview) {
    return <div className="screen-state" aria-live="polite"><span className="spinner" /><p>正在汇总管理状态</p></div>;
  }

  if (state === "error" && !overview) {
    return (
      <div className="screen-state error-state" role="alert">
        <strong>管理总览暂时不可用</strong>
        <p>其他管理模块仍可通过上方任务域进入。</p>
        <button className="primary-button" type="button" onClick={load}>重新加载</button>
      </div>
    );
  }

  return (
    <section className="admin-overview-page">
      <header className="admin-overview-heading">
        <div>
          <h2>需要处理</h2>
          <p>这里只汇总异常、阻断和待办；不在总览直接执行高风险操作。</p>
        </div>
        <button className="primary-button" type="button" onClick={load} disabled={state === "loading"}>
          {state === "loading" ? "刷新中" : "刷新状态"}
        </button>
      </header>

      {overview?.attentionItems.length ? (
        <div className="admin-attention-list" aria-label="待处理事项">
          {overview.attentionItems.map((item) => (
            <article className={`admin-attention-item ${item.severity.toLowerCase()}`} key={item.code}>
              <div>
                <AdminValue state={item.valueState} reasonCode={item.reasonCode}>
                  {item.count === null ? undefined : `${item.count} 项`}
                </AdminValue>
                <h3>{item.title}</h3>
                <p>{item.description}</p>
              </div>
              <Link
                className="admin-attention-action"
                to={item.href}
                aria-label={`查看并处理：${item.title}`}
              >
                <span>查看并处理</span>
                <span className="admin-attention-action-icon" aria-hidden="true">→</span>
              </Link>
            </article>
          ))}
        </div>
      ) : (
        <div className="admin-overview-empty">
          <strong>当前没有待处理事项</strong>
          <p>正常状态和技术明细保留在对应管理模块中。</p>
        </div>
      )}

      <section className="admin-domain-section" aria-labelledby="admin-domain-heading">
        <header>
          <h2 id="admin-domain-heading">管理任务</h2>
          <p>按工作目标进入，不必记住底层模块名称。</p>
        </header>
        <div className="admin-domain-grid">
          {overview?.domains.filter((domain) => domain.key !== "OVERVIEW").map((domain) => (
            <article key={domain.key}>
              <h3><Link to={domain.href}>{domain.title}</Link></h3>
              <p>{domain.description}</p>
              <nav aria-label={`${domain.title}入口`}>
                {domain.links.map((link) => <Link key={link.href} to={link.href}>{link.title}</Link>)}
              </nav>
            </article>
          ))}
        </div>
      </section>
    </section>
  );
}
