import type { DocumentFormat, SourceLocationFields } from "../types";

interface SourceLocationProps {
  source: SourceLocationFields;
  documentId?: string;
  revisionId?: string;
  revisionNumber?: number;
  linkToSource?: boolean;
  className?: string;
  labelPrefix?: string;
}

function legacyPageLabel(startPage?: number | null, endPage?: number | null) {
  if (!startPage) return null;
  return endPage && endPage !== startPage
    ? `第 ${startPage}–${endPage} 页`
    : `第 ${startPage} 页`;
}

function pdfPage(source: SourceLocationFields) {
  return source.sourceLocator?.startPage ?? source.startPage ?? null;
}

function resolvedFormat(source: SourceLocationFields): DocumentFormat | null {
  if (source.documentFormat) return source.documentFormat;
  return pdfPage(source) ? "PDF" : null;
}

export function sourceDocumentHref(
  source: SourceLocationFields,
  documentId?: string,
  revisionId?: string,
) {
  const page = pdfPage(source);
  if (!documentId || !revisionId) {
    return null;
  }
  if (resolvedFormat(source) === "PDF" && page) {
    return `/api/v1/documents/${documentId}/revisions/${revisionId}/download?inline=true#page=${page}`;
  }
  const sourceUnit = source.sourceLocator?.startUnit;
  return sourceUnit
    ? `/documents/${documentId}?revision=${revisionId}&source=${sourceUnit}`
    : null;
}

export function sourceLocationText(source: SourceLocationFields) {
  const authoritative = source.sourceLabel?.trim()
    || source.sourceLocator?.sourceLabel?.trim();
  if (authoritative) return authoritative;
  return legacyPageLabel(
    source.sourceLocator?.startPage ?? source.startPage,
    source.sourceLocator?.endPage ?? source.endPage,
  ) ?? "来源位置不可用";
}

export function DocumentFormatBadge({
  format,
}: {
  format?: DocumentFormat | null;
}) {
  return format ? <span className="document-format-badge">{format}</span> : null;
}

export function SourceLocation({
  source,
  documentId,
  revisionId,
  revisionNumber,
  linkToSource = true,
  className,
  labelPrefix = "",
}: SourceLocationProps) {
  const location = sourceLocationText(source);
  const prefix = revisionNumber ? `R${revisionNumber} · ` : "";
  const href = linkToSource
    ? sourceDocumentHref(source, documentId, revisionId)
    : null;
  const content = `${labelPrefix}${prefix}${location}`;

  if (href) {
    return (
      <a
        className={className}
        href={href}
        target="_blank"
        rel="noreferrer"
      >
        {content}
      </a>
    );
  }

  return <span className={className}>{content}</span>;
}
