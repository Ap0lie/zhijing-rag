import type { EvaluationReleaseReport } from "../types";

interface Props {
  report: EvaluationReleaseReport;
}

function percent(value: number | null) {
  return value == null ? "未测量" : `${(value * 100).toFixed(1)}%`;
}

function milliseconds(value: number | null) {
  return value == null ? "未测量" : `${Math.round(value)} ms`;
}

function snapshotValue(report: EvaluationReleaseReport, key: string) {
  const value = report.frozenSubject[key];
  return value == null || value === "" ? "—" : String(value);
}

export function ReleaseReportPanel({ report }: Props) {
  const ready = report.recommendation === "READY_FOR_BASELINE";
  return (
    <section className="evaluation-card evaluation-release-card" aria-labelledby="phase18d-release-title">
      <header>
        <div>
          <span>PHASE 18D · RELEASE REPORT</span>
          <h3 id="phase18d-release-title">多格式发布收口</h3>
        </div>
        <span className={`evaluation-status ${ready ? "succeeded" : "blocked-prerequisite"}`}>
          {ready ? "可发布 Baseline" : "发布阻断"}
        </span>
      </header>

      <dl className="evaluation-release-metrics">
        <div><dt>案例通过</dt><dd>{report.succeededCases}/{report.totalCases}</dd></div>
        <div><dt>Locator 闭包</dt><dd>{percent(report.locatorResolutionRate)}</dd></div>
        <div><dt>Citation 闭包</dt><dd>{percent(report.citationResolutionRate)}</dd></div>
        <div><dt>硬门禁失败</dt><dd>{report.hardGateFailures}</dd></div>
        <div><dt>降级次数</dt><dd>{report.degradationCount}</dd></div>
      </dl>

      {report.blockers.length > 0 ? (
        <div className="form-error" role="alert">
          阻断原因：{report.blockers.join(" · ")}
        </div>
      ) : null}

      <div className="table-wrap">
        <table>
          <thead><tr><th>格式</th><th>终态</th><th>来源位置</th><th>门禁</th><th>耗时</th><th>降级</th></tr></thead>
          <tbody>{report.formats.map((item) => (
            <tr key={item.caseId}>
              <td><strong>{item.documentFormat}</strong><small>{item.caseKey}</small></td>
              <td><span className={`evaluation-status ${item.status.toLowerCase().replaceAll("_", "-")}`}>{item.status}</span><small>{item.errorCode ?? "—"}</small></td>
              <td>{item.sourceLabel ?? "未解析"}<small>{item.locatorKind ?? "—"} · Revision {item.revisionId?.slice(0, 8) ?? "—"}</small></td>
              <td>{item.hardGatePassed ? "全部通过" : "失败"}<small>Citation {item.citationResolved ? "通过" : "失败"}</small></td>
              <td>{item.durationMs} ms</td>
              <td>{item.degraded ? item.degradationCode ?? "已记录降级" : "无"}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>

      <div className="evaluation-report-grid">
        <section>
          <header><h4>实机性能</h4><span>1 次预热 · 1 次测量/格式</span></header>
          <div className="table-wrap"><table>
            <thead><tr><th>阶段</th><th>样本</th><th>p50</th><th>p95</th><th>最大</th><th>错误率</th></tr></thead>
            <tbody>{Object.entries(report.performance).map(([name, stats]) => (
              <tr key={name}><td>{name}</td><td>{stats.samples}</td><td>{milliseconds(stats.p50Ms)}</td><td>{milliseconds(stats.p95Ms)}</td><td>{milliseconds(stats.maxMs)}</td><td>{percent(stats.errorRate)}</td></tr>
            ))}</tbody>
          </table></div>
        </section>
        <section>
          <header><h4>冻结运行版本</h4><code>{report.subjectSnapshotHash.slice(0, 12)}</code></header>
          <dl>
            <div><dt>Index</dt><dd>{snapshotValue(report, "indexGeneration")}</dd></div>
            <div><dt>Retrieval</dt><dd>{snapshotValue(report, "retrievalProfileVersion")}</dd></div>
            <div><dt>Graph</dt><dd>{snapshotValue(report, "graphGeneration")}</dd></div>
            <div><dt>Global</dt><dd>{snapshotValue(report, "globalGeneration")}</dd></div>
            <div><dt>Answer</dt><dd>{snapshotValue(report, "answerProfileVersion")}</dd></div>
            <div><dt>Query</dt><dd>{snapshotValue(report, "queryProfileVersion")}</dd></div>
          </dl>
          {report.unmeasuredItems.length > 0 ? <small>未测量：{report.unmeasuredItems.join(" · ")}</small> : null}
        </section>
        <section>
          <header><h4>Query / Memory 实际基线</h4><code>{report.executionBaseline.queryProfileVersion || "—"}</code></header>
          <dl>
            <div><dt>Planner 调用</dt><dd>{report.executionBaseline.plannerCallCount}</dd></div>
            <div><dt>检索调用</dt><dd>{report.executionBaseline.retrievalCallCount}</dd></div>
            <div><dt>Rerank 调用</dt><dd>{report.executionBaseline.rerankCallCount}</dd></div>
            <div><dt>Query 降级</dt><dd>{report.executionBaseline.queryDegradedCount}</dd></div>
            <div><dt>Memory 契约</dt><dd>{report.executionBaseline.memoryContractVersion}</dd></div>
            <div><dt>Memory 注入 / 使用</dt><dd>{report.executionBaseline.memoryInjectedCount} / {report.executionBaseline.memoryUsedCount}</dd></div>
            <div><dt>Memory Token</dt><dd>{report.executionBaseline.memoryTokenCount}</dd></div>
          </dl>
        </section>
      </div>
      <p className="empty-copy">安全策略绑定与恶意 OOXML、XSS、公式执行、格式欺骗等攻击样本均由本 Run 实际执行；策略声明不能代替攻击结果。</p>
    </section>
  );
}
