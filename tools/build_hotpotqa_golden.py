from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SOURCES_PATH = ROOT / "data" / "public-golden" / "sources.json"
RAW_PATH = (
    ROOT / "data" / "public-golden" / "raw" / "hotpotqa"
    / "distractor-validation-window.json"
)
ROWS_ENDPOINT = "https://datasets-server.huggingface.co/rows"
ROWS_PER_PAGE = 100
QUESTION_TYPES = ("bridge", "comparison")
DEFAULT_CASES_PER_TYPE = {1: 9, 2: 25}
V1_CASES_PER_TYPE = DEFAULT_CASES_PER_TYPE[1]
RAW_ROWS_SHA256 = "f34cfc467d9ae9e24607a1ac3ae1b9d4e8a8f91c040ae43354fd6756bcd1a57d"


@dataclass(frozen=True)
class BuildConfig:
    suite: str

    @property
    def version(self) -> int:
        return int(self.suite.removeprefix("v"))

    @property
    def cases_per_type(self) -> int:
        return DEFAULT_CASES_PER_TYPE[self.version]

    @property
    def suite_version(self) -> str:
        return f"hotpotqa-answer-citation-v{self.version}"

    @property
    def generator_version(self) -> str:
        return f"hotpotqa-golden-builder-v{self.version}"

    @property
    def work_path(self) -> Path:
        return ROOT / "data" / "public-golden" / "work" / f"hotpotqa-v{self.version}"

    @property
    def document_path(self) -> Path:
        return self.work_path / "documents"

    @property
    def manifest_path(self) -> Path:
        return self.work_path / "corpus-manifest.json"

    @property
    def dataset_path(self) -> Path:
        return (
            ROOT / "backend" / "src" / "test" / "resources"
            / "hotpotqa-golden" / f"v{self.version}" / "dataset.json"
        )


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def write_markdown(path: Path, value: str) -> bytes:
    """Write an explicitly identified UTF-8 document for the strict parser."""
    payload = b"\xef\xbb\xbf" + value.replace("\r\n", "\n").encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return payload


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_json(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return sha256_bytes(encoded)


def source() -> dict[str, Any]:
    sources = read_json(SOURCES_PATH)
    matches = [item for item in sources["sources"] if item["id"] == "hotpotqa"]
    if len(matches) != 1:
        raise ValueError("sources.json must contain exactly one hotpotqa entry")
    return matches[0]


def fetch_rows(source_record: dict[str, Any]) -> list[dict[str, Any]]:
    if RAW_PATH.is_file():
        cached = read_json(RAW_PATH)
        if (
            cached.get("repositoryRevision") == source_record["repositoryRevision"]
            and cached.get("selectionWindow") == source_record["selectionWindow"]
        ):
            rows = cached["rows"]
            if sha256_json(rows) != RAW_ROWS_SHA256:
                raise ValueError("cached HotpotQA source window hash is invalid")
            return rows

    rows: list[dict[str, Any]] = []
    for offset in range(0, source_record["selectionWindow"], ROWS_PER_PAGE):
        query = urllib.parse.urlencode(
            {
                "dataset": "hotpotqa/hotpot_qa",
                "config": source_record["config"],
                "split": source_record["split"],
                "offset": offset,
                "length": min(
                    ROWS_PER_PAGE,
                    source_record["selectionWindow"] - offset,
                ),
                "revision": source_record["repositoryRevision"],
            }
        )
        request = urllib.request.Request(
            f"{ROWS_ENDPOINT}?{query}",
            headers={"User-Agent": "zhijing-rag-hotpotqa-builder/1"},
        )
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    payload = json.load(response)
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(1 + attempt)
        rows.extend(item["row"] for item in payload["rows"])

    if len(rows) != source_record["selectionWindow"]:
        raise ValueError(
            f"expected {source_record['selectionWindow']} rows, found {len(rows)}"
        )
    if sha256_json(rows) != RAW_ROWS_SHA256:
        raise ValueError("downloaded HotpotQA source window hash is invalid")
    write_json(
        RAW_PATH,
        {
            "repositoryRevision": source_record["repositoryRevision"],
            "selectionWindow": source_record["selectionWindow"],
            "rows": rows,
        },
    )
    return rows


def parallel_context(row: dict[str, Any]) -> list[tuple[str, list[str]]]:
    titles = row["context"]["title"]
    sentences = row["context"]["sentences"]
    if len(titles) != len(sentences):
        raise ValueError(f"context arrays differ for {row['id']}")
    return [(str(title), [str(sentence) for sentence in body])
            for title, body in zip(titles, sentences, strict=True)]


def support_map(row: dict[str, Any]) -> dict[str, list[int]]:
    titles = row["supporting_facts"]["title"]
    sentence_ids = row["supporting_facts"]["sent_id"]
    if len(titles) != len(sentence_ids):
        raise ValueError(f"support arrays differ for {row['id']}")
    result: dict[str, list[int]] = {}
    for title, sentence_id in zip(titles, sentence_ids, strict=True):
        result.setdefault(str(title), []).append(int(sentence_id))
    return result


def eligible(row: dict[str, Any]) -> bool:
    if row.get("type") not in QUESTION_TYPES or row.get("level") != "hard":
        return False
    context_pairs = parallel_context(row)
    if len(context_pairs) != 10:
        return False
    contexts = dict(context_pairs)
    if len(contexts) != 10:
        return False
    supports = support_map(row)
    if len(supports) != 2:
        return False
    return all(
        title in contexts
        and sentence_ids
        and all(0 <= sentence_id < len(contexts[title])
                for sentence_id in sentence_ids)
        for title, sentence_ids in supports.items()
    )


def ranked_cases(
    rows: list[dict[str, Any]], question_type: str
) -> list[dict[str, Any]]:
    return sorted(
        [
            row for row in rows
            if eligible(row) and row["type"] == question_type
        ],
        key=lambda row: sha256_text(row["id"]),
    )


def select_cases(
    rows: list[dict[str, Any]], cases_per_type: int
) -> list[dict[str, Any]]:
    buckets: dict[str, list[dict[str, Any]]] = {}
    for question_type in QUESTION_TYPES:
        bucket = ranked_cases(rows, question_type)
        if len(bucket) < cases_per_type:
            raise ValueError(
                f"not enough {question_type}/hard cases: {len(bucket)}"
            )
        buckets[question_type] = bucket[:cases_per_type]

    if cases_per_type <= V1_CASES_PER_TYPE:
        return [
            row
            for question_type in QUESTION_TYPES
            for row in buckets[question_type]
        ]

    prefix = [
        row
        for question_type in QUESTION_TYPES
        for row in buckets[question_type][:V1_CASES_PER_TYPE]
    ]
    additions = [
        row
        for question_type in QUESTION_TYPES
        for row in buckets[question_type][V1_CASES_PER_TYPE:]
    ]
    return prefix + additions


def selection_rule(config: BuildConfig) -> str:
    if config.version == 1:
        return (
            "stable SHA-256 rank within the hard validation split; "
            "9 bridge and 9 comparison cases"
        )
    additions = config.cases_per_type - V1_CASES_PER_TYPE
    return (
        "stable SHA-256 rank within the hard validation split; "
        "the v1 selection (9 bridge and 9 comparison cases) is the stable "
        f"prefix, followed by {additions} additional bridge and "
        f"{additions} additional comparison cases"
    )


def wikipedia_url(title: str) -> str:
    article = urllib.parse.quote(title.replace(" ", "_"), safe="()_',-")
    return f"https://en.wikipedia.org/wiki/{article}"


def build(
    config: BuildConfig,
    source_record: dict[str, Any],
    rows: list[dict[str, Any]],
) -> dict[str, Any]:
    selected = select_cases(rows, config.cases_per_type)
    corpus: dict[str, dict[str, Any]] = {}
    cases: list[dict[str, Any]] = []

    for row in selected:
        contexts = parallel_context(row)
        supports = support_map(row)
        refs: list[dict[str, Any]] = []
        for title, sentences in contexts:
            body = "".join(sentences).strip()
            identity = "\n".join(
                (source_record["repositoryRevision"], title, body)
            )
            evidence_key = f"hotpotqa:{sha256_text(identity)[:32]}"
            document = corpus.setdefault(
                evidence_key,
                {
                    "evidenceKey": evidence_key,
                    "title": title,
                    "sourceUrl": wikipedia_url(title),
                    "sourceContentHash": sha256_text(body),
                    "sentences": sentences,
                    "caseIds": [],
                },
            )
            if document["sentences"] != sentences:
                raise ValueError(f"evidence key collision for {title}")
            if row["id"] not in document["caseIds"]:
                document["caseIds"].append(row["id"])
            if title in supports:
                refs.append(
                    {
                        "evidenceKey": evidence_key,
                        "title": title,
                        "sentenceIds": supports[title],
                        "supportingSentences": [
                            sentences[index] for index in supports[title]
                        ],
                    }
                )
        cases.append(
            {
                "id": row["id"],
                "source": "hotpotqa",
                "language": "en",
                "type": row["type"],
                "level": row["level"],
                "input": {"query": row["question"]},
                "expected": {
                    "answer": row["answer"],
                    "expectedAnswer": row["answer"],
                    "shouldRefuse": False,
                    "supportingFactCount": sum(
                        len(indices) for indices in supports.values()
                    ),
                },
                "evidenceRefs": refs,
            }
        )

    documents: list[dict[str, Any]] = []
    for sequence, document in enumerate(
        sorted(corpus.values(), key=lambda item: item["evidenceKey"]),
        start=1,
    ):
        filename = f"{sequence:03d}-{document['evidenceKey'].split(':')[1][:12]}.md"
        markdown = "# " + document["title"] + "\n\n" + "\n\n".join(
            sentence.strip() for sentence in document["sentences"]
            if sentence.strip()
        ) + "\n"
        write_markdown(config.document_path / filename, markdown)
        item = dict(document)
        item["file"] = (
            config.document_path / filename
        ).relative_to(ROOT).as_posix()
        # Pin canonical UTF-8 content; the BOM is a transport-level encoding marker.
        item["fileSha256"] = sha256_text(markdown)
        documents.append(item)

    result = {
        "suiteVersion": config.suite_version,
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "en",
        "source": {
            "dataset": "hotpotqa/hotpot_qa",
            "repository": source_record["repository"],
            "revision": source_record["repositoryRevision"],
            "license": source_record["license"],
            "config": source_record["config"],
            "split": source_record["split"],
        },
        "selection": {
            "windowRows": source_record["selectionWindow"],
            "rule": selection_rule(config),
            "difficulty": "hard",
            "casesPerType": config.cases_per_type,
            "caseCount": len(cases),
            "corpusDocumentCount": len(documents),
        },
        "cases": cases,
        "corpus": documents,
    }
    write_json(config.dataset_path, result)
    write_json(
        config.manifest_path,
        {
            "suiteVersion": config.suite_version,
            "generatorVersion": config.generator_version,
            "sourceRevision": source_record["repositoryRevision"],
            "sourceWindowSha256": sha256_json(rows),
            "datasetSha256": sha256_json(result),
            "caseCount": len(cases),
            "corpusDocumentCount": len(documents),
            "documents": [
                {
                    "evidenceKey": item["evidenceKey"],
                    "file": item["file"],
                    "fileSha256": item["fileSha256"],
                }
                for item in documents
            ],
        },
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a pinned, deterministic HotpotQA golden resource."
    )
    parser.add_argument("--suite", choices=("v1", "v2"), default="v1")
    arguments = parser.parse_args()
    config = BuildConfig(arguments.suite)
    source_record = source()
    result = build(config, source_record, fetch_rows(source_record))
    print(json.dumps({
        "suiteVersion": result["suiteVersion"],
        "caseCount": result["selection"]["caseCount"],
        "corpusDocumentCount": result["selection"]["corpusDocumentCount"],
        "dataset": str(config.dataset_path),
        "manifest": str(config.manifest_path),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
