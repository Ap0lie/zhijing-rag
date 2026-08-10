import { Link } from "react-router-dom";

import type { GraphEntityDetail } from "../types";
import { SourceLocation } from "./SourceLocation";

interface GraphEntityDetailPanelProps {
  detail: GraphEntityDetail;
  onClose: () => void;
}

export function GraphEntityDetailPanel({
  detail,
  onClose,
}: GraphEntityDetailPanelProps) {
  return (
    <article className="graph-detail">
      <header>
        <div>
          <span>{detail.entity.entityType}</span>
          <h3>{detail.entity.canonicalName}</h3>
          <p>{detail.entity.description || "暂无描述"}</p>
        </div>
        <button type="button" className="text-button" onClick={onClose}>
          关闭
        </button>
      </header>
      <p className="graph-aliases">Alias：{detail.aliases.join("、") || "—"}</p>
      <p className="graph-generation-context">
        详情每类最多展示 200 条，关系 Evidence 最多展示 500 条。
      </p>
      <div className="graph-detail-columns">
        <section>
          <h4>Mentions</h4>
          {detail.mentions.map((mention) => (
            <div className="graph-evidence" key={mention.id}>
              <strong>{mention.surfaceText}</strong>
              <p>
                {mention.documentTitle} · Revision {mention.revisionNumber} ·{" "}
                <SourceLocation source={mention} linkToSource={false} />
              </p>
              <div>
                <Link to={`/chunks/${mention.childChunkId}`}>查看 Child</Link>
                <Link to={`/documents/${mention.documentId}`}>查看文档</Link>
              </div>
            </div>
          ))}
        </section>
        <section>
          <h4>Relationships</h4>
          {detail.relationships.map((relationship) => (
            <div className="graph-evidence" key={relationship.id}>
              <strong>
                {relationship.sourceName} — {relationship.relationshipType} → {relationship.targetName}
              </strong>
              <p>{relationship.description || "—"}</p>
              {relationship.evidence.map((evidence) => (
                <blockquote key={evidence.id}>
                  {evidence.evidenceText}
                  <footer>
                    {evidence.documentTitle} ·{" "}
                    <SourceLocation source={evidence} linkToSource={false} />
                    {" · "}
                    <Link to={`/chunks/${evidence.childChunkId}`}>Child</Link>
                  </footer>
                </blockquote>
              ))}
            </div>
          ))}
        </section>
      </div>
    </article>
  );
}
