from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import pdfplumber
import pypdf
import reportlab
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer


ROOT = Path(__file__).resolve().parents[1]
SOURCES_PATH = ROOT / "data" / "public-golden" / "sources.json"
DATASET_PATH = (
    ROOT
    / "backend"
    / "src"
    / "test"
    / "resources"
    / "graph-global-golden"
    / "v1"
    / "dataset.json"
)
MANIFEST_PATH = DATASET_PATH.with_name("phase10r-corpus-manifest.json")
WORK_PATH = ROOT / "data" / "public-golden" / "work" / "phase10r"
PDF_PATH = WORK_PATH / "pdfs"
MULTIHOP_CASES_PATH = (
    ROOT
    / "data"
    / "public-golden"
    / "raw"
    / "multihop-rag"
    / "MultiHopRAG.json"
)
MULTIHOP_CORPUS_PATH = MULTIHOP_CASES_PATH.with_name("corpus.json")
XRAG_PATH = (
    ROOT
    / "data"
    / "public-golden"
    / "raw"
    / "xrag"
    / "xrag.dev.jsonl"
)
FONT_PATH = Path(r"C:\Windows\Fonts\NotoSansSC-VF.ttf")

SUITE_VERSION = "graph-global-golden-v1"
GENERATOR_VERSION = "phase10r-public-corpus-v1"
REPORTLAB_VERSION = "4.4.9"
FONT_NAME = "Phase10RNotoSansSC"
FONT_SHA256 = "763146584cf0710223441356b4395e279021b0806c196614377a7a0174ae074a"
RIGHTS_STATUS = "DATASET_LICENSE_ONLY"
TRACKING_QUERY_KEYS = {"ref", "rss", "src"}


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_json(value: Any) -> str:
    return sha256_text(json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def canonical_url(value: str) -> str:
    parsed = urlsplit(value.strip())
    query = [
        (key, item)
        for key, item in parse_qsl(parsed.query, keep_blank_values=True)
        if key.lower() not in TRACKING_QUERY_KEYS
        and not key.lower().startswith("utm_")
    ]
    return urlunsplit(
        (
            parsed.scheme.lower(),
            parsed.netloc.lower(),
            parsed.path,
            urlencode(query, doseq=True),
            "",
        )
    )


def compact_text(value: str) -> str:
    return re.sub(r"\s+", "", value)


def source_map() -> dict[str, dict[str, Any]]:
    sources = read_json(SOURCES_PATH)
    require(sources.get("schemaVersion") == 1, "unsupported sources schema")
    return {source["id"]: source for source in sources["sources"]}


def validate_inputs(
    dataset: dict[str, Any],
    sources: dict[str, dict[str, Any]],
) -> None:
    require(dataset.get("suiteVersion") == SUITE_VERSION, "suiteVersion mismatch")
    require(reportlab.Version == REPORTLAB_VERSION, "ReportLab version mismatch")
    require(FONT_PATH.is_file(), f"font not found: {FONT_PATH}")
    require(sha256_file(FONT_PATH) == FONT_SHA256, "font SHA-256 mismatch")

    multihop = sources["multihop-rag"]
    xrag = sources["xrag"]
    require(
        dataset["sources"]["multiHopRag"]["revision"]
        == multihop["repositoryRevision"],
        "MultiHop-RAG revision mismatch",
    )
    require(
        dataset["sources"]["xrag"]["revision"] == xrag["repositoryRevision"],
        "XRAG revision mismatch",
    )
    require(
        sha256_file(MULTIHOP_CASES_PATH) == multihop["casesSha256"],
        "MultiHop-RAG cases SHA-256 mismatch",
    )
    require(
        sha256_file(MULTIHOP_CORPUS_PATH) == multihop["corpusSha256"],
        "MultiHop-RAG corpus SHA-256 mismatch",
    )
    require(
        sha256_file(XRAG_PATH) == xrag["validationSha256"],
        "XRAG validation SHA-256 mismatch",
    )
    require(
        dataset["sources"]["multiHopRag"]["sha256"]
        == multihop["casesSha256"],
        "dataset MultiHop-RAG SHA-256 mismatch",
    )
    require(
        dataset["sources"]["xrag"]["sha256"] == xrag["validationSha256"],
        "dataset XRAG SHA-256 mismatch",
    )


def case_key(source: str, value: str) -> str:
    return f"{source}:{sha256_text(value)[:20]}"


def evidence_key(source: str, revision: str, url: str, body_hash: str) -> str:
    identity = "\n".join((source, revision, canonical_url(url), body_hash))
    return f"evidence:{sha256_text(identity)[:32]}"


def idempotency_key(source: str, revision: str, body_hash: str) -> str:
    identity = "\n".join((SUITE_VERSION, source, revision, body_hash))
    return f"phase10r:{sha256_text(identity)}"


def document_title(
    source: str,
    source_title: str | None,
    case_id: str,
    support_number: int,
    url: str,
) -> tuple[str, bool]:
    if source_title:
        return source_title, False
    host = urlsplit(url).netloc.lower()
    return f"XRAG {case_id} support {support_number} ({host})", True


def document_record(
    *,
    sequence: int,
    source: str,
    revision: str,
    license_name: str,
    url: str,
    source_title: str | None,
    author: str | None,
    body: str,
    case_id: str,
    support_number: int,
    mapping_status: str,
) -> dict[str, Any]:
    body_hash = sha256_text(body)
    key = evidence_key(source, revision, url, body_hash)
    title, title_derived = document_title(
        source,
        source_title,
        case_id,
        support_number,
        url,
    )
    filename = f"{sequence:02d}-{source}-{key.rsplit(':', 1)[1][:12]}.pdf"
    return {
        "evidenceKey": key,
        "sourceDataset": source,
        "sourceRevision": revision,
        "sourceUrl": url,
        "canonicalUrl": canonical_url(url),
        "title": title,
        "sourceTitle": title,
        "titleDerived": title_derived,
        "author": author,
        "license": license_name,
        "rightsStatus": RIGHTS_STATUS,
        "redistributionAllowed": False,
        "rawBodySha256": body_hash,
        "pdfPath": (PDF_PATH / filename).relative_to(ROOT).as_posix(),
        "pdfSha256": None,
        "idempotencyKey": idempotency_key(source, revision, body_hash),
        "mappingStatus": mapping_status,
        "_body": body,
    }


def build_records(
    dataset: dict[str, Any],
    sources: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    selected_multihop = [
        row for row in dataset["cases"] if row["source"] == "multihop-rag"
    ][:5]
    selected_xrag = [
        row for row in dataset["cases"] if row["source"] == "xrag"
    ][:5]
    require(len(selected_multihop) == 5, "needed 5 MultiHop-RAG cases")
    require(len(selected_xrag) == 5, "needed 5 XRAG cases")

    corpus_by_url: dict[str, list[dict[str, Any]]] = {}
    for document in read_json(MULTIHOP_CORPUS_PATH):
        corpus_by_url.setdefault(document["url"], []).append(document)

    documents: list[dict[str, Any]] = []
    documents_by_key: dict[str, dict[str, Any]] = {}
    cases: list[dict[str, Any]] = []

    def add_document(record: dict[str, Any]) -> dict[str, Any]:
        existing = documents_by_key.get(record["evidenceKey"])
        if existing is not None:
            require(
                existing["rawBodySha256"] == record["rawBodySha256"],
                "evidence key collision",
            )
            return existing
        documents.append(record)
        documents_by_key[record["evidenceKey"]] = record
        return record

    multihop_source = sources["multihop-rag"]
    for selected in selected_multihop:
        case = selected["case"]
        require(
            case["question_type"] == selected["intent"],
            "MultiHop-RAG intent mismatch",
        )
        require(
            isinstance(case.get("evidence_list"), list)
            and len(case["evidence_list"]) == 2,
            "MultiHop-RAG case must have exactly two evidence documents",
        )
        key = case_key("multihop-rag", case["query"])
        refs: list[dict[str, Any]] = []
        for index, evidence in enumerate(case["evidence_list"], start=1):
            matches = corpus_by_url.get(evidence["url"], [])
            require(
                len(matches) == 1,
                f"MultiHop-RAG URL must map to one corpus document: {evidence['url']}",
            )
            corpus = matches[0]
            excerpt = evidence["fact"]
            start = corpus["body"].find(excerpt)
            require(start >= 0, f"fact not found in corpus body: {evidence['url']}")
            record = add_document(
                document_record(
                    sequence=len(documents) + 1,
                    source="multihop-rag",
                    revision=multihop_source["repositoryRevision"],
                    license_name=multihop_source["license"],
                    url=corpus["url"],
                    source_title=corpus.get("title"),
                    author=corpus.get("author"),
                    body=corpus["body"],
                    case_id=key,
                    support_number=index,
                    mapping_status="EXACT_RAW_TEXT",
                )
            )
            refs.append(
                {
                    "evidenceKey": record["evidenceKey"],
                    "mappingStatus": "EXACT_RAW_TEXT",
                    "expectedExcerpt": excerpt,
                    "rawExcerptStart": start,
                    "rawExcerptEnd": start + len(excerpt),
                    "rawExcerptOffsetUnit": "UNICODE_CODE_POINT",
                }
            )
        cases.append(
            {
                "caseKey": key,
                "sourceDataset": "multihop-rag",
                "sourceCaseId": sha256_text(case["query"]),
                "intent": selected["intent"],
                "query": case["query"],
                "answer": case["answer"],
                "mappingStatus": "RAW_EXCERPTS_READY",
                "evidenceRefs": refs,
            }
        )

    xrag_source = sources["xrag"]
    parallel_fields = ("articles", "dates", "urls", "is_support", "article_order")
    for selected in selected_xrag:
        case = selected["case"]
        require(case["language"] == "zh", "XRAG case must be Chinese")
        require(int(case["crossdoc_type"]) == 2, "XRAG case must be cross-document")
        lengths = {field: len(case[field]) for field in parallel_fields}
        require(
            len(set(lengths.values())) == 1 and next(iter(lengths.values())) > 0,
            f"XRAG parallel arrays mismatch: {lengths}",
        )
        support_indexes = [
            index
            for index, value in enumerate(case["is_support"])
            if int(value) == 1
        ]
        require(
            len(support_indexes) == 2,
            f"XRAG case {case['id']} must have exactly two support articles",
        )
        key = f"xrag:{case['id']}"
        refs: list[dict[str, Any]] = []
        for support_number, index in enumerate(support_indexes, start=1):
            body = case["articles"][index]
            require(body.strip(), f"XRAG support article is empty: {case['id']}")
            record = add_document(
                document_record(
                    sequence=len(documents) + 1,
                    source="xrag",
                    revision=xrag_source["repositoryRevision"],
                    license_name=xrag_source["license"],
                    url=case["urls"][index],
                    source_title=None,
                    author=None,
                    body=body,
                    case_id=str(case["id"]),
                    support_number=support_number,
                    mapping_status="ARTICLE_LEVEL_ONLY",
                )
            )
            refs.append(
                {
                    "evidenceKey": record["evidenceKey"],
                    "sourceArticleIndex": index,
                    "sourceArticleOrder": case["article_order"][index],
                    "sourceDate": case["dates"][index],
                    "mappingStatus": "ARTICLE_LEVEL_ONLY",
                    "expectedExcerpt": None,
                    "rawExcerptStart": None,
                    "rawExcerptEnd": None,
                    "rawExcerptOffsetUnit": None,
                }
            )
        cases.append(
            {
                "caseKey": key,
                "sourceDataset": "xrag",
                "sourceCaseId": str(case["id"]),
                "intent": selected["intent"],
                "query": case["question"],
                "answer": case["answer"],
                "mappingStatus": "UNMAPPED",
                "evidenceRefs": refs,
            }
        )

    require(len(cases) == 10, "expected 10 selected cases")
    require(len(documents) == 20, "expected 20 unique support documents")
    require(
        len({document["sourceUrl"] for document in documents}) == 20,
        "selected support URLs are not unique",
    )
    require(
        len({document["rawBodySha256"] for document in documents}) == 20,
        "selected support bodies are not unique",
    )
    return cases, documents


def paragraph(value: str, style: ParagraphStyle) -> Paragraph:
    safe = html.escape(value, quote=False).replace("\t", "    ")
    return Paragraph(safe or " ", style)


def deterministic_canvas(filename: str, **kwargs: Any) -> canvas.Canvas:
    kwargs["invariant"] = 1
    kwargs["pageCompression"] = 1
    result = canvas.Canvas(filename, **kwargs)
    result.setCreator(GENERATOR_VERSION)
    result.setProducer(f"ReportLab {REPORTLAB_VERSION}")
    return result


def build_pdf(document: dict[str, Any], path: Path) -> None:
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "Phase10RTitle",
        parent=styles["Title"],
        fontName=FONT_NAME,
        fontSize=14,
        leading=19,
        alignment=TA_CENTER,
        spaceAfter=5 * mm,
        wordWrap="CJK",
    )
    metadata_style = ParagraphStyle(
        "Phase10RMetadata",
        parent=styles["BodyText"],
        fontName=FONT_NAME,
        fontSize=7.5,
        leading=10,
        spaceAfter=1.2 * mm,
        splitLongWords=True,
        wordWrap="CJK",
    )
    body_style = ParagraphStyle(
        "Phase10RBody",
        parent=styles["BodyText"],
        fontName=FONT_NAME,
        fontSize=8.5,
        leading=13,
        spaceAfter=2.2 * mm,
        splitLongWords=True,
        wordWrap="CJK",
    )
    heading_style = ParagraphStyle(
        "Phase10RHeading",
        parent=body_style,
        fontSize=10,
        leading=14,
        spaceBefore=2 * mm,
        spaceAfter=2 * mm,
    )
    story: list[Any] = [
        paragraph("[EVAL][PUBLIC] Local evaluation source", title_style),
        paragraph(f"suite: {SUITE_VERSION}", metadata_style),
        paragraph(f"source: {document['sourceDataset']}", metadata_style),
        paragraph(f"revision: {document['sourceRevision']}", metadata_style),
        paragraph(f"URL: {document['sourceUrl']}", metadata_style),
        paragraph(f"title: {document['title']}", metadata_style),
        paragraph(
            f"author: {document['author'] or '<not supplied by dataset>'}",
            metadata_style,
        ),
        paragraph(f"license: {document['license']}", metadata_style),
        paragraph(f"rightsStatus: {RIGHTS_STATUS}", metadata_style),
        paragraph("redistributionAllowed: false", metadata_style),
        paragraph(
            f"rawBodySha256: {document['rawBodySha256']}",
            metadata_style,
        ),
        Spacer(1, 3 * mm),
        paragraph("Source body", heading_style),
    ]
    for line in document["_body"].splitlines():
        story.append(paragraph(line, body_style) if line else Spacer(1, 2.2 * mm))

    path.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(path),
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=16 * mm,
        bottomMargin=16 * mm,
        title=document["title"],
        author=document["author"] or document["sourceDataset"],
        subject=f"{SUITE_VERSION} local evaluation corpus",
    )

    def page_footer(page_canvas: canvas.Canvas, built_doc: Any) -> None:
        page_canvas.saveState()
        page_canvas.setFont(FONT_NAME, 7)
        page_canvas.drawCentredString(
            A4[0] / 2,
            8 * mm,
            f"{GENERATOR_VERSION} · page {built_doc.page}",
        )
        page_canvas.restoreState()

    doc.build(
        story,
        onFirstPage=page_footer,
        onLaterPages=page_footer,
        canvasmaker=deterministic_canvas,
    )


def public_document(document: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in document.items() if not key.startswith("_")}


def verify_pdf(document: dict[str, Any], path: Path) -> dict[str, int]:
    require(path.is_file(), f"missing PDF: {path}")
    require(sha256_file(path) == document["pdfSha256"], f"PDF hash mismatch: {path}")

    reader = pypdf.PdfReader(path)
    pypdf_text = "\n".join(page.extract_text() or "" for page in reader.pages)
    with pdfplumber.open(path) as opened:
        plumber_pages = len(opened.pages)
        plumber_text = "\n".join(page.extract_text() or "" for page in opened.pages)

    require(len(reader.pages) > 0, f"PDF has no pages: {path}")
    require(plumber_pages == len(reader.pages), f"page count mismatch: {path}")
    for extracted, tool in ((pypdf_text, "pypdf"), (plumber_text, "pdfplumber")):
        compact = compact_text(extracted)
        require(
            compact_text(SUITE_VERSION) in compact,
            f"{tool} did not extract suiteVersion: {path}",
        )
        require(
            compact_text(document["rawBodySha256"]) in compact,
            f"{tool} did not extract body hash: {path}",
        )
        probe = compact_text(document["_body"])[:120]
        require(probe and probe in compact, f"{tool} body probe mismatch: {path}")
    return {
        "pages": len(reader.pages),
        "pypdfCharacters": len(pypdf_text),
        "pdfplumberCharacters": len(plumber_text),
    }


def build() -> dict[str, Any]:
    dataset = read_json(DATASET_PATH)
    sources = source_map()
    validate_inputs(dataset, sources)
    cases, documents = build_records(dataset, sources)

    pdfmetrics.registerFont(TTFont(FONT_NAME, str(FONT_PATH)))
    PDF_PATH.mkdir(parents=True, exist_ok=True)
    expected_paths: set[Path] = set()
    for document in documents:
        path = ROOT / document["pdfPath"]
        expected_paths.add(path.resolve())
        build_pdf(document, path)
        document["pdfSha256"] = sha256_file(path)

    unexpected = {
        path.resolve() for path in PDF_PATH.glob("*.pdf")
    } - expected_paths
    require(not unexpected, f"unexpected generated PDFs: {sorted(unexpected)}")

    verification = [verify_pdf(document, ROOT / document["pdfPath"]) for document in documents]
    public_documents = [public_document(document) for document in documents]
    source_set = [
        {
            "evidenceKey": document["evidenceKey"],
            "sourceDataset": document["sourceDataset"],
            "sourceRevision": document["sourceRevision"],
            "sourceUrl": document["canonicalUrl"],
            "rawBodySha256": document["rawBodySha256"],
        }
        for document in public_documents
    ]
    pdf_manifest = [
        {
            "evidenceKey": document["evidenceKey"],
            "pdfPath": document["pdfPath"],
            "pdfSha256": document["pdfSha256"],
        }
        for document in public_documents
    ]
    manifest = {
        "suiteVersion": SUITE_VERSION,
        "status": "PHASE10R_CORPUS_GENERATED",
        "generatorVersion": GENERATOR_VERSION,
        "toolchain": {
            "reportlabVersion": reportlab.Version,
            "pypdfVersion": pypdf.__version__,
            "pdfplumberVersion": pdfplumber.__version__,
        },
        "font": {
            "fileName": FONT_PATH.name,
            "sha256": FONT_SHA256,
            "embedded": True,
        },
        "rightsPolicy": {
            "rightsStatus": RIGHTS_STATUS,
            "redistributionAllowed": False,
            "scope": "local evaluation only; dataset license does not assert third-party article redistribution rights",
        },
        "selection": {
            "rule": "first five committed cases per source in graph-global-golden-v1 order; support evidence only",
            "caseCount": len(cases),
            "multiHopRagCases": 5,
            "xragZhCases": 5,
            "documentCount": len(documents),
        },
        "verification": {
            "pdfCount": len(verification),
            "pageCount": sum(item["pages"] for item in verification),
            "sourceSetSha256": sha256_json(source_set),
            "pdfManifestSha256": sha256_json(pdf_manifest),
            "pypdfTextCharacters": sum(
                item["pypdfCharacters"] for item in verification
            ),
            "pdfplumberTextCharacters": sum(
                item["pdfplumberCharacters"] for item in verification
            ),
        },
        "documents": public_documents,
        "cases": cases,
    }
    write_json(MANIFEST_PATH, manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build the deterministic Phase 10R local public-evaluation corpus."
    )
    parser.parse_args()
    manifest = build()
    print(
        json.dumps(
            {
                "manifest": MANIFEST_PATH.relative_to(ROOT).as_posix(),
                "cases": len(manifest["cases"]),
                "documents": len(manifest["documents"]),
                "pdfs": manifest["verification"]["pdfCount"],
                "pages": manifest["verification"]["pageCount"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
