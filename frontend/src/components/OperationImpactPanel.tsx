import type { OperationImpact } from "../types";

const COUNT_LABELS: Record<string, string> = {
  activeSessions: "将失效的会话",
  explicitGrants: "当前明确授权",
  requestedChanges: "本次权限变化",
  currentExplicitGrants: "当前明确授权",
};

export function OperationImpactPanel({ impact }: { impact: OperationImpact }) {
  return (
    <section className={`operation-impact ${impact.blockers.length > 0 ? "blocked" : ""}`} aria-label="操作影响">
      <header>
        <div>
          <span className="eyebrow">执行前影响预检</span>
          <h3>{impact.blockers.length > 0 ? "当前操作被阻断" : "请确认实际影响"}</h3>
        </div>
        <span className="status-badge">事实版本 {impact.factVersion}</span>
      </header>

      {Object.keys(impact.affectedCounts).length > 0 ? (
        <dl className="impact-counts">
          {Object.entries(impact.affectedCounts).map(([key, value]) => (
            <div key={key}><dt>{COUNT_LABELS[key] ?? key}</dt><dd>{value}</dd></div>
          ))}
        </dl>
      ) : null}

      <div className="impact-columns">
        <div><strong>立即影响</strong><ul>{impact.immediateEffects.map((item) => <li key={item}>{item}</li>)}</ul></div>
        <div><strong>异步影响</strong><ul>{impact.asynchronousEffects.length > 0 ? impact.asynchronousEffects.map((item) => <li key={item}>{item}</li>) : <li>无</li>}</ul></div>
        <div><strong>不会影响</strong><ul>{impact.notAffected.map((item) => <li key={item}>{item}</li>)}</ul></div>
      </div>

      {impact.blockers.length > 0 ? (
        <div className="impact-blockers" role="alert">
          {impact.blockers.map((item) => <p key={item}>{item}</p>)}
        </div>
      ) : null}
      <p className="impact-rollback"><strong>回滚边界：</strong>{impact.rollback}</p>
    </section>
  );
}
