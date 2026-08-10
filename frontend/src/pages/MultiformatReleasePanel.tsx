import { useState } from "react";
import type { FormEvent } from "react";

import type { MultiformatRelease } from "../types";

interface Props {
  release: MultiformatRelease | null;
  working: boolean;
  onFreeze: (reason: string) => Promise<void>;
}

function shortHash(value: string | null) {
  return value ? value.slice(0, 12) : "—";
}

export function MultiformatReleasePanel({ release, working, onFreeze }: Props) {
  const [reason, setReason] = useState("冻结当前多格式运行版本");
  const [confirmed, setConfirmed] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!confirmed || reason.trim().length < 8) return;
    await onFreeze(reason.trim());
  }

  if (!release) {
    return <section className="evaluation-card empty-copy">正在检查八种格式的当前 Revision 与来源位置…</section>;
  }

  const complete = release.readyFormats === release.totalFormats;
  return (
    <section className="evaluation-card evaluation-release-card" aria-labelledby="multiformat-release-title">
      <header>
        <div>
          <h3 id="multiformat-release-title">多格式发布事实</h3>
        </div>
        <span className={`evaluation-status ${release.state === "FROZEN" ? "succeeded" : complete ? "ready" : "blocked-prerequisite"}`}>
          {release.state === "FROZEN" ? "已冻结" : `${release.readyFormats}/${release.totalFormats} 可冻结`}
        </span>
      </header>
      <p>
        冻结八种格式的文件 Hash、当前 Revision、Child、SourceLocator、Parser 与安全断言。
        此操作不会切换在线 Index、Graph、Global、Retrieval、Answer 或 Baseline。
      </p>

      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>格式</th><th>状态</th><th>代表文档</th><th>文件事实</th><th>Parser</th><th>来源位置</th></tr>
          </thead>
          <tbody>{release.formats.map((item) => (
            <tr key={item.documentFormat}>
              <td><strong>{item.documentFormat}</strong></td>
              <td>
                <span className={`evaluation-status ${item.mappingStatus.toLowerCase().replaceAll("_", "-")}`}>
                  {item.mappingStatus === "READY" ? "就绪" : item.mappingStatus === "UNMAPPED" ? "未映射" : "前置条件失效"}
                </span>
                {item.blockedReason ? <small>{item.blockedReason}</small> : null}
              </td>
              <td><strong>{item.documentTitle ?? "—"}</strong><small>{item.documentVisibility ?? "—"} · ACL v{item.aclVersion || "—"}</small></td>
              <td><span>{item.originalFilename ?? "—"}</span><small>{item.sourceLicense ?? "—"} · {shortHash(item.fileSha256)}</small></td>
              <td><span>{item.expectedParserProvider ?? "—"}</span><small>{item.expectedParserVersion ?? "—"}</small></td>
              <td><span>{item.sourceLabel ?? "—"}</span><small>{item.locatorKind ?? "—"} · {shortHash(item.locatorHash)}</small></td>
            </tr>
          ))}</tbody>
        </table>
      </div>

      {release.state === "FROZEN" ? (
        <div className="evaluation-release-summary">
          <strong>{release.version}</strong>
          <span>Dataset {shortHash(release.datasetVersionId)} · Subject {shortHash(release.subjectId)}</span>
          <span>{release.subjectReadinessStatus === "BLOCKED_PREREQUISITE" ? release.subjectBlockedReason : "Subject 已就绪"}</span>
        </div>
      ) : null}
      <form className="evaluation-release-form" onSubmit={submit}>
        <label>冻结理由
          <input value={reason} onChange={(event) => setReason(event.target.value)} minLength={8} maxLength={500} required />
        </label>
        <label className="checkbox-row">
          <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
          我确认冻结当前八格式事实与运行版本，且不改变在线发布版本
        </label>
        <button className="primary-button" disabled={working || !complete || !confirmed || reason.trim().length < 8}>
          {release.state === "FROZEN" ? "刷新冻结配置" : `冻结 ${release.version}`}
        </button>
      </form>
    </section>
  );
}
