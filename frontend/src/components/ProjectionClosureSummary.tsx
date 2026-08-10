import type {
  GenerationRecoveryProgress,
  ProjectionClosureStatus,
} from "../types";

const TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "short",
  timeStyle: "medium",
});

function formatTime(value: string | null) {
  return value ? TIME_FORMATTER.format(new Date(value)) : "—";
}

export function ProjectionClosureSummary({
  closure,
  recovery,
}: {
  closure: ProjectionClosureStatus;
  recovery: GenerationRecoveryProgress;
}) {
  return (
    <details className={`projection-closure ${closure.caughtUp ? "ready" : "blocked"}`}>
      <summary>
        {closure.caughtUp ? "闭包通过" : `${closure.blockers.length} 项阻断`}
        <span>{closure.formats.length} 种格式</span>
      </summary>
      <div className="projection-closure-body">
        <div className="projection-format-grid" aria-label="格式覆盖">
          {closure.formats.map((format) => (
            <span
              key={format.documentFormat}
              className={format.staleDocumentCount || format.locatorReadyDocumentCount !== format.expectedDocumentCount
                ? "blocked" : "ready"}
              title={`${format.documentFormat}: projection ${format.projectedDocumentCount}/${format.expectedDocumentCount}, locator ${format.locatorReadyDocumentCount}/${format.expectedDocumentCount}`}
            >
              {format.documentFormat} {format.projectedDocumentCount}/{format.expectedDocumentCount}
            </span>
          ))}
        </div>
        <dl>
          <div><dt>Locator</dt><dd>{closure.locatorReadyDocumentCount}/{closure.expectedDocumentCount}</dd></div>
          <div><dt>Stale</dt><dd>{closure.staleDocumentCount}</dd></div>
          <div><dt>Orphan</dt><dd>{closure.orphanedProjectionCount}</dd></div>
          <div><dt>Evidence</dt><dd>{closure.invalidEvidenceCount} 无效</dd></div>
          <div><dt>ALL_USERS</dt><dd>{closure.allUsersSourceDocumentCount}</dd></div>
          <div><dt>RESTRICTED</dt><dd>{closure.restrictedSourceDocumentCount}</dd></div>
        </dl>
        {closure.blockers.length ? (
          <ul>{closure.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}</ul>
        ) : null}
        <p>
          恢复：{recovery.state} · attempt {recovery.attempt}
          {recovery.heartbeatAt ? ` · heartbeat ${formatTime(recovery.heartbeatAt)}` : ""}
          {recovery.leaseExpiresAt ? ` · lease ${formatTime(recovery.leaseExpiresAt)}` : ""}
        </p>
      </div>
    </details>
  );
}
