import { useCallback, useEffect, useMemo, useState, type KeyboardEvent } from "react";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { ParserDecisionSummary, ParserOverrideForm } from "./ParserRoutingPanel";
import {
  SourceLocation,
  sourceDocumentHref,
  sourceLocationText,
} from "./SourceLocation";
import type {
  DocumentRevision,
  PipelineJob,
  RevisionArtifacts,
  RevisionStructure,
  StructureTable,
} from "../types";

type ArtifactView = "markdown" | "outline" | "blocks" | "parents" | "children" | "tables" | "images" | "spans";

const artifactViewOrder: ArtifactView[] = [
  "markdown",
  "outline",
  "blocks",
  "parents",
  "children",
  "tables",
  "images",
  "spans",
];

interface RevisionProcessingPanelProps {
  documentId: string;
  revisions: DocumentRevision[];
}

function shortHash(hash: string | null | undefined) {
  return hash ? `${hash.slice(0, 12)}…` : "未记录";
}

function bytesLabel(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
}

function boxLabel(box: { x0: number; y0: number; x1: number; y1: number }) {
  return `(${box.x0}, ${box.y0})–(${box.x1}, ${box.y1})`;
}

function boxSourceLabel(box: {
  pageNumber: number | null;
  sourceUnitKind?: string;
  sourceUnitOrder?: number;
}) {
  if (box.pageNumber != null) return `p.${box.pageNumber}`;
  return `${box.sourceUnitKind ?? "SOURCE"} ${box.sourceUnitOrder ?? "?"}`;
}

function sheetNameFromCellRange(value: string) {
  const range = value.trim();
  if (range.startsWith("'")) {
    let sheet = "";
    for (let index = 1; index < range.length; index += 1) {
      const character = range[index];
      if (character !== "'") {
        sheet += character;
        continue;
      }
      if (range[index + 1] === "'") {
        sheet += "'";
        index += 1;
        continue;
      }
      return range[index + 1] === "!" ? sheet : null;
    }
    return null;
  }
  const separator = range.lastIndexOf("!");
  return separator > 0 ? range.slice(0, separator) : null;
}

function tableSheetName(table: StructureTable) {
  const sourceLabel = table.sourceLabel?.trim()
    || table.sourceLocator?.sourceLabel?.trim()
    || "";
  const labelSheet = sheetNameFromCellRange(sourceLabel);
  if (labelSheet) return labelSheet;
  let address = table.sourceLocator?.address?.split("#", 1)[0] ?? "";
  if (address.startsWith("{")) {
    try {
      address = String(JSON.parse(address).address ?? "");
    } catch {
      address = "";
    }
  }
  const addressSheet = sheetNameFromCellRange(address);
  if (addressSheet) return addressSheet;
  return table.caption?.split(" · ", 1)[0] || `Table ${table.order + 1}`;
}

function StructuredTable({
  table,
  showFormulas,
}: {
  table: StructureTable;
  showFormulas: boolean;
}) {
  const rows = [...new Set(table.cells.map((cell) => cell.rowIndex))].sort((left, right) => left - right);
  return (
    <div className="structure-table-scroll">
      <table className="structure-table">
        <tbody>
          {rows.map((row) => (
            <tr key={row}>
              {table.cells
                .filter((cell) => cell.rowIndex === row)
                .sort((left, right) => left.columnIndex - right.columnIndex)
                .map((cell) => {
                  const Cell = cell.header ? "th" : "td";
                  const display = cell.displayValue ?? cell.text;
                  return (
                    <Cell
                      key={cell.id}
                      rowSpan={cell.rowSpan}
                      colSpan={cell.columnSpan}
                      data-cell-reference={cell.cellReference ?? undefined}
                    >
                      {cell.cellReference ? <small className="cell-reference">{cell.cellReference}</small> : null}
                      <span>{display || "—"}</span>
                      {showFormulas && cell.formulaText ? <code className="cell-formula">{cell.formulaText}</code> : null}
                      {showFormulas && cell.numberFormat ? <small className="cell-format">{cell.numberFormat}</small> : null}
                    </Cell>
                  );
                })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function RevisionProcessingPanel({ documentId, revisions }: RevisionProcessingPanelProps) {
  const { expireSession, user } = useAuth();
  const [requestedSourceUnit] = useState(() =>
    new URLSearchParams(window.location.search).get("source"));
  const [revisionId, setRevisionId] = useState(() => {
    const requested = new URLSearchParams(window.location.search).get("revision");
    return revisions.some((revision) => revision.id === requested)
      ? requested!
      : revisions[0]?.id ?? "";
  });
  const [jobs, setJobs] = useState<PipelineJob[]>([]);
  const [artifacts, setArtifacts] = useState<RevisionArtifacts | null>(null);
  const [structure, setStructure] = useState<RevisionStructure | null>(null);
  const [structureError, setStructureError] = useState(false);
  const [selectedSheet, setSelectedSheet] = useState("");
  const [showFormulas, setShowFormulas] = useState(true);
  const [view, setView] = useState<ArtifactView>(
    requestedSourceUnit ? "blocks" : "markdown",
  );
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const revision = revisions.find((item) => item.id === revisionId) ?? revisions[0];
  const spreadsheet = revision?.documentFormat === "XLSX"
    || revision?.documentFormat === "CSV";
  const sheetNames = useMemo(() => {
    if (!structure || !spreadsheet) return [];
    return [...new Set(structure.tables.map(tableSheetName))];
  }, [spreadsheet, structure]);
  const visibleTables = spreadsheet && selectedSheet
    ? structure?.tables.filter((table) => tableSheetName(table) === selectedSheet) ?? []
    : structure?.tables ?? [];

  const load = useCallback((signal?: AbortSignal) => {
    if (!revisionId) {
      setState("ready");
      return;
    }
    setState("loading");
    setStructureError(false);
    const pipelineRequest = apiRequest<PipelineJob[]>(
      `/api/v1/documents/${documentId}/revisions/${revisionId}/pipeline`,
      { signal },
    );
    const artifactsRequest = apiRequest<RevisionArtifacts>(
      `/api/v1/documents/${documentId}/revisions/${revisionId}/artifacts`,
      { signal },
    ).catch((caught: unknown) => {
      if (caught instanceof ApiError && caught.status === 404) return null;
      throw caught;
    });
    const structureRequest = apiRequest<RevisionStructure>(
      `/api/v1/documents/${documentId}/revisions/${revisionId}/structure`,
      { signal },
    ).then((value) => ({ value, failed: false })).catch((caught: unknown) => {
      if (caught instanceof ApiError && caught.status === 404) {
        return { value: null, failed: false };
      }
      if (caught instanceof ApiError && caught.status === 401) throw caught;
      return { value: null, failed: true };
    });
    Promise.all([pipelineRequest, artifactsRequest, structureRequest])
      .then(([nextJobs, nextArtifacts, nextStructure]) => {
        if (signal?.aborted) return;
        setJobs(nextJobs);
        setArtifacts(nextArtifacts);
        setStructure(nextStructure.value);
        if (nextStructure.value && spreadsheet) {
          const sheets = [...new Set(nextStructure.value.tables.map(tableSheetName))];
          setSelectedSheet((current) => sheets.includes(current) ? current : sheets[0] ?? "");
        }
        setStructureError(nextStructure.failed);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (signal?.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setState("error");
      });
  }, [documentId, expireSession, revisionId, spreadsheet]);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (state !== "ready" || !requestedSourceUnit) return;
    if (spreadsheet && view !== "tables") {
      setView("tables");
      return;
    }
    const target = document.getElementById(`source-unit-${requestedSourceUnit}`);
    target?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [requestedSourceUnit, spreadsheet, state, view]);

  if (!revision) return null;

  const visibleChunks = artifacts?.chunks.filter((chunk) => chunk.type === (view === "parents" ? "PARENT" : "CHILD")) ?? [];
  const artifactTabs = artifacts ? [
    { value: "markdown" as const, label: "Markdown" },
    {
      value: "outline" as const,
      label: spreadsheet
        ? "工作表结构"
        : revision.documentFormat === "PPTX"
        ? "幻灯片结构"
        : revision.documentFormat === "DOCX"
          ? "文档结构"
          : "来源结构",
    },
    { value: "blocks" as const, label: `ContentBlock (${artifacts.contentBlocks.length})` },
    { value: "parents" as const, label: "Parent Chunk" },
    { value: "children" as const, label: "Child Chunk" },
    { value: "tables" as const, label: `Tables (${structureError ? "不可用" : structure?.tables.length ?? 0})` },
    { value: "images" as const, label: `Images (${structureError ? "不可用" : structure?.images.length ?? 0})` },
    { value: "spans" as const, label: `SourceSpan (${structureError ? "不可用" : structure?.sourceSpans.length ?? 0})` },
  ] : [];

  function handleArtifactTabKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    currentView: ArtifactView,
  ) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const currentIndex = artifactViewOrder.indexOf(currentView);
    const nextIndex = event.key === "Home"
      ? 0
      : event.key === "End"
        ? artifactViewOrder.length - 1
        : event.key === "ArrowLeft"
          ? (currentIndex - 1 + artifactViewOrder.length) % artifactViewOrder.length
          : (currentIndex + 1) % artifactViewOrder.length;
    const nextView = artifactViewOrder[nextIndex];
    setView(nextView);
    document.getElementById(`artifact-tab-${nextView}`)?.focus();
  }

  function addOverride(created: PipelineJob) {
    setJobs((current) => [
      ...current.map((item) => (
        item.stage === "PARSE" && item.revisionId === created.revisionId
          ? { ...item, retryable: false }
          : item
      )),
      created,
    ]);
  }

  return (
    <section className="processing-card" aria-labelledby="processing-title">
      <header className="processing-header">
        <div><h3 id="processing-title">解析与 Chunk</h3><p>Pipeline 状态和产物均来自所选不可变 Revision。</p></div>
        <label><span>Revision</span><select aria-label="选择解析 Revision" value={revision.id} onChange={(event) => setRevisionId(event.target.value)}>{revisions.map((item) => <option key={item.id} value={item.id}>R{item.revisionNumber} · {item.status}</option>)}</select></label>
      </header>

      {state === "loading" ? <div className="processing-state"><span className="spinner" /><p>正在加载解析状态与产物</p></div> : null}
      {state === "error" ? <div className="processing-state error-state"><p>解析信息加载失败</p><button className="secondary-button" type="button" onClick={() => load()}>重试</button></div> : null}
      {state === "ready" ? (
        <>
          <ol className="pipeline-timeline">
            {jobs.map((job) => (
              <li key={job.id} className={job.status.toLowerCase()}>
                <span className="timeline-dot" aria-hidden="true" />
                <div><strong>{job.stage}</strong><span className={`pipeline-status ${job.status.toLowerCase()}`}>{job.status}</span><small>尝试 {job.attempt}/{job.maxAttempts}</small></div>
                {job.durationMs !== null ? <p>耗时 {job.durationMs < 1000 ? `${job.durationMs} ms` : `${(job.durationMs / 1000).toFixed(1)} s`}</p> : null}
                {job.stage === "PARSE" ? <ParserDecisionSummary job={job} /> : null}
                {job.quarantineReason ?? job.errorMessage ? <p className="pipeline-error"><b>{job.errorCode ?? job.status}</b>{job.quarantineReason ?? job.errorMessage}</p> : null}
                {user?.role === "ADMIN" ? <ParserOverrideForm job={job} onCreated={addOverride} /> : null}
              </li>
            ))}
          </ol>
          {jobs.length === 0 ? <p className="artifact-empty">该 Revision 尚无 Pipeline 任务。</p> : null}

          {artifacts ? (
            <div className="artifact-viewer">
              {structureError ? (
                <div className="artifact-empty" role="alert">
                  <p>结构资产加载失败，Markdown、ContentBlock 与 Chunk 仍可查看。</p>
                  <button className="secondary-button" type="button" onClick={() => load()}>重试结构资产</button>
                </div>
              ) : null}
              <div className="artifact-summary">
                <span>Parser {artifacts.parserVersion}</span><span>Chunker {artifacts.chunkerVersion}</span><span>Token {artifacts.tokenCounterVersion}</span>
                {structure ? (
                  <>
                    <span>Schema {structure.resultPackage.schemaVersion}</span>
                    <span>Offset {structure.resultPackage.offsetEncoding}</span>
                    <span>
                      {structure.resultPackage.sourceUnitCount ?? structure.resultPackage.pageCount}
                      {revision.documentFormat === "PDF" || !revision.documentFormat ? " 页" : " 个来源单元"}
                    </span>
                    {structure.resultPackage.textEncoding
                      ? <span>Encoding {structure.resultPackage.textEncoding}</span>
                      : null}
                    {structure.resultPackage.delimiter
                      ? <span>Delimiter {structure.resultPackage.delimiter}</span>
                      : null}
                    {structure.resultPackage.parseDecision
                      ? <span>{structure.resultPackage.parseDecision}</span>
                      : null}
                    {structure.resultPackage.sanitization
                      ? <span title={structure.resultPackage.sanitization}>已执行安全标准化</span>
                      : null}
                  </>
                ) : null}
              </div>
              <div className="artifact-tabs" role="tablist" aria-label="解析产物">
                {artifactTabs.map((tab) => (
                  <button
                    key={tab.value}
                    id={`artifact-tab-${tab.value}`}
                    role="tab"
                    type="button"
                    aria-controls="artifact-panel"
                    aria-selected={view === tab.value}
                    tabIndex={view === tab.value ? 0 : -1}
                    onClick={() => setView(tab.value)}
                    onKeyDown={(event) => handleArtifactTabKeyDown(event, tab.value)}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>

              <div
                id="artifact-panel"
                role="tabpanel"
                aria-labelledby={`artifact-tab-${view}`}
                tabIndex={0}
              >
              {view === "markdown" ? (
                <>
                  {structure ? <div className="result-package">
                    <span>input {shortHash(structure.resultPackage.inputHash)}</span>
                    <span>output {shortHash(structure.resultPackage.outputHash)}</span>
                    {structure.resultPackage.parserRevision ? <span>revision {structure.resultPackage.parserRevision}</span> : null}
                  </div> : null}
                  <pre className="markdown-preview">{artifacts.markdown}</pre>
                </>
              ) : null}
              {view === "outline" ? (
                <ol className="artifact-list structure-list">
                  {artifacts.contentBlocks.map((block) => {
                    const label = block.sourceLabel
                      || block.sourceLocator?.sourceLabel
                      || `来源单元 ${block.order + 1}`;
                    const kind = spreadsheet
                      ? block.type === "HEADING" ? "工作表" : "表格区域"
                      : revision.documentFormat === "PPTX"
                      ? label.includes("备注")
                        ? "备注"
                        : block.type === "TABLE"
                          ? "表格单元格"
                          : block.type === "HEADING"
                            ? "幻灯片标题"
                            : "形状"
                      : revision.documentFormat === "DOCX"
                        ? block.type === "HEADING"
                          ? "章节"
                          : block.type === "TABLE"
                            ? "表格单元格"
                            : "段落"
                        : block.type;
                    return (
                      <li key={block.id}>
                        <header>
                          <strong>{kind} · {label}</strong>
                          <SourceLocation
                            source={{
                              ...block,
                              documentFormat: block.documentFormat ?? revision.documentFormat,
                            }}
                            documentId={documentId}
                            revisionId={revision.id}
                            revisionNumber={revision.revisionNumber}
                          />
                        </header>
                        {block.headingPath.length > 0
                          ? <small>{block.headingPath.join(" / ")}</small>
                          : null}
                        <p>{block.text}</p>
                      </li>
                    );
                  })}
                </ol>
              ) : null}
              {view === "blocks" ? (
                <ol className="artifact-list">{artifacts.contentBlocks.map((block) => (
                  <li
                    key={block.id}
                    id={block.sourceLocator?.startUnit
                      ? `source-unit-${block.sourceLocator.startUnit}`
                      : undefined}
                    className={block.sourceLocator?.startUnit === requestedSourceUnit
                      ? "source-location-target"
                      : undefined}
                  >
                    <header>
                      <strong>#{block.order} · {block.type}</strong>
                      <SourceLocation
                        source={{
                          ...block,
                          documentFormat: block.documentFormat ?? revision.documentFormat,
                        }}
                        documentId={documentId}
                        revisionId={revision.id}
                        revisionNumber={revision.revisionNumber}
                      />
                    </header>
                    {block.headingPath.length > 0 ? <small>{block.headingPath.join(" / ")}</small> : null}
                    <p>{block.text}</p><footer>{block.tokenCount} tokens · {block.charCount} chars · offset {block.startOffset}–{block.endOffset}</footer>
                  </li>
                ))}</ol>
              ) : null}
              {view === "parents" || view === "children" ? (
                <ol className="artifact-list chunk-list">{visibleChunks.map((chunk) => (
                  <li key={chunk.id}>
                    <header>
                      <strong>#{chunk.order} · {chunk.type}</strong>
                      <SourceLocation
                        source={{
                          ...chunk,
                          documentFormat: chunk.documentFormat ?? revision.documentFormat,
                        }}
                        documentId={documentId}
                        revisionId={revision.id}
                        revisionNumber={revision.revisionNumber}
                      />
                    </header>
                    {chunk.headingPath.length > 0 ? <small>{chunk.headingPath.join(" / ")}</small> : null}
                    <p>{chunk.text}</p>
                    <footer>{chunk.tokenCount} tokens · {chunk.charCount} chars{chunk.parentChunkId ? ` · Parent ${chunk.parentChunkId.slice(0, 8)}` : ""} · {chunk.searchable ? "可检索" : "上下文块"}</footer>
                  </li>
                ))}</ol>
              ) : null}
              {view === "tables" ? (
                structureError ? <p className="artifact-empty">结构化表格暂时不可用。</p> : structure?.tables.length ? (
                  <>
                    {spreadsheet ? (
                      <div className="sheet-toolbar" aria-label="工作表预览设置">
                        <label>
                          <span>工作表</span>
                          <select
                            aria-label="选择工作表"
                            value={selectedSheet}
                            onChange={(event) => setSelectedSheet(event.target.value)}
                          >
                            {sheetNames.map((sheet) => <option key={sheet} value={sheet}>{sheet}</option>)}
                          </select>
                        </label>
                        <label className="formula-toggle">
                          <input
                            type="checkbox"
                            checked={showFormulas}
                            onChange={(event) => setShowFormulas(event.target.checked)}
                          />
                          显示公式与格式
                        </label>
                        <span>{visibleTables.length} 个范围</span>
                      </div>
                    ) : null}
                    <ol className="artifact-list structure-list">{visibleTables.map((table) => (
                  <li
                    key={table.id}
                    id={table.sourceLocator?.startUnit
                      ? `source-unit-${table.sourceLocator.startUnit}`
                      : undefined}
                    className={table.sourceLocator?.startUnit === requestedSourceUnit
                      ? "source-location-target"
                      : undefined}
                  >
                    <header>
                      <strong>{spreadsheet ? tableSheetName(table) : `Table #${table.order}`}</strong>
                      <SourceLocation
                        source={{
                          ...table,
                          documentFormat: table.documentFormat ?? revision.documentFormat,
                          startPage: table.startPage ?? table.pageNumber,
                          endPage: table.endPage ?? table.pageNumber,
                        }}
                        documentId={documentId}
                        revisionId={revision.id}
                        revisionNumber={revision.revisionNumber}
                      />
                    </header>
                    {table.caption ? <p>{table.caption}</p> : null}
                    <StructuredTable table={table} showFormulas={showFormulas} />
                    <footer>
                      {table.cells.length} cells
                      {spreadsheet ? "" : ` · bbox ${boxLabel(table.boundingBox)}`}
                      {" · "}hash {shortHash(table.sourceTextHash)}
                    </footer>
                  </li>
                ))}</ol>
                  </>
                ) : <p className="artifact-empty">当前 Revision 没有结构化表格。</p>
              ) : null}
              {view === "images" ? (
                structureError ? <p className="artifact-empty">图片资产暂时不可用。</p> : structure?.images.length ? <div className="structure-image-grid">{structure.images.map((image) => {
                  const source = {
                    ...image,
                    documentFormat: image.documentFormat ?? revision.documentFormat,
                    startPage: image.startPage ?? image.pageNumber,
                    endPage: image.endPage ?? image.pageNumber,
                  };
                  const href = sourceDocumentHref(source, documentId, revision.id);
                  const preview = <img src={image.contentUrl} alt={image.caption || image.filename} loading="lazy" />;
                  return (
                    <figure key={image.id}>
                      {href ? <a href={href} target="_blank" rel="noreferrer">{preview}</a> : preview}
                      <figcaption>
                        <strong>{image.caption || image.filename}</strong>
                        <span>
                          {image.type} · R{revision.revisionNumber} · {sourceLocationText(source)}
                          {" · "}{bytesLabel(image.byteSize)}
                        </span>
                        <span>bbox {boxLabel(image.boundingBox)} · hash {shortHash(image.contentHash)}</span>
                      </figcaption>
                    </figure>
                  );
                })}</div> : <p className="artifact-empty">当前 Revision 没有图片资产。</p>
              ) : null}
              {view === "spans" ? (
                structureError ? <p className="artifact-empty">SourceSpan 暂时不可用。</p> : structure?.sourceSpans.length ? <ol className="artifact-list structure-list">{structure.sourceSpans.map((span) => (
                  <li key={span.id}>
                    <header>
                      <strong>{span.chunkType} #{span.chunkOrder} · Span #{span.order}</strong>
                      <SourceLocation
                        source={{
                          ...span,
                          documentFormat: span.documentFormat ?? revision.documentFormat,
                        }}
                        documentId={documentId}
                        revisionId={revision.id}
                        revisionNumber={revision.revisionNumber}
                      />
                    </header>
                    <p>
                      Chunk offset {span.chunkStartOffset}–{span.chunkEndOffset}
                      {" · "}Source offset {span.sourceLocator?.startOffset ?? span.pageStartOffset}
                      –{span.sourceLocator?.endOffset ?? span.pageEndOffset}
                    </p>
                    <footer>{span.boundingBoxes.length} bbox · {span.boundingBoxes.map((box) => `${boxSourceLabel(box)} ${boxLabel(box)}`).join(" · ") || "无坐标"} · hash {shortHash(span.sourceTextHash)}</footer>
                  </li>
                ))}</ol> : <p className="artifact-empty">当前 Revision 没有 SourceSpan。</p>
              ) : null}
              {structure?.truncated ? <p className="structure-truncated">结构数据较多，当前仅展示前一批结果。</p> : null}
              </div>
            </div>
          ) : <p className="artifact-empty">解析尚未成功，当前没有可预览产物。</p>}
        </>
      ) : null}
    </section>
  );
}
