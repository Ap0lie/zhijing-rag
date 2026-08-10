from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Callable, Iterable


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data" / "public-golden" / "raw"
GRAPH_OUT = (
    ROOT
    / "backend"
    / "src"
    / "test"
    / "resources"
    / "graph-local-golden"
    / "v1"
)
ANSWER_OUT = (
    ROOT
    / "backend"
    / "src"
    / "test"
    / "resources"
    / "answer-citation-golden"
    / "v1"
)
GLOBAL_OUT = (
    ROOT
    / "backend"
    / "src"
    / "test"
    / "resources"
    / "graph-global-golden"
    / "v1"
)


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def read_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stable_rank(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def take(
    rows: Iterable[dict[str, Any]],
    count: int,
    key: Callable[[dict[str, Any]], str],
) -> list[dict[str, Any]]:
    selected = sorted(rows, key=lambda row: stable_rank(key(row)))[:count]
    if len(selected) != count:
        raise ValueError(f"needed {count} rows, found {len(selected)}")
    return selected


def build_2wiki() -> dict[str, Any]:
    path = RAW / "2wiki" / "data" / "dev.json"
    rows = read_json(path)
    selected: list[dict[str, Any]] = []
    for question_type in (
        "comparison",
        "compositional",
        "inference",
        "bridge_comparison",
    ):
        evidence_count = 4 if question_type == "bridge_comparison" else 2
        support_count = 4 if question_type == "bridge_comparison" else 2
        eligible = [
            row
            for row in rows
            if row["type"] == question_type
            and len(row["evidences"]) == evidence_count
            and len({fact[0] for fact in row["supporting_facts"]})
            == support_count
        ]
        selected.extend(take(eligible, 15, lambda row: row["_id"]))
    result = {
        "datasetVersion": "graph-local-public-2wiki-v1",
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "en",
        "selection": {
            "split": "dev",
            "casesPerType": 15,
            "caseCount": 60,
            "maximumTraversalDepthPerChain": 2,
        },
        "cases": selected,
    }
    write_json(GRAPH_OUT / "2wiki.json", result)
    return {
        "resource": "/graph-local-golden/v1/2wiki.json",
        "caseCount": len(selected),
        "sourceSha256": sha256(path),
    }


def build_multihop_rag() -> dict[str, Any]:
    cases_path = RAW / "multihop-rag" / "MultiHopRAG.json"
    corpus_path = RAW / "multihop-rag" / "corpus.json"
    rows = read_json(cases_path)
    selected: list[dict[str, Any]] = []
    for question_type in (
        "comparison_query",
        "inference_query",
        "temporal_query",
        "null_query",
    ):
        evidence_count = 0 if question_type == "null_query" else 2
        eligible = [
            row
            for row in rows
            if row["question_type"] == question_type
            and len(row["evidence_list"]) == evidence_count
        ]
        selected.extend(take(eligible, 5, lambda row: row["query"]))
    corpus = read_json(corpus_path)
    result = {
        "datasetVersion": "graph-local-rag-realism-v1",
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "en",
        "selection": {
            "casesPerType": 5,
            "caseCount": 20,
            "nonNullEvidenceDocuments": 2,
            "corpusDocuments": len(corpus),
        },
        "cases": selected,
        "corpus": corpus,
    }
    write_json(GRAPH_OUT / "multihop-rag.json", result)
    return {
        "resource": "/graph-local-golden/v1/multihop-rag.json",
        "caseCount": len(selected),
        "corpusCount": len(corpus),
        "casesSha256": sha256(cases_path),
        "corpusSha256": sha256(corpus_path),
    }


def build_xrag() -> dict[str, Any]:
    path = RAW / "xrag" / "xrag.dev.jsonl"
    rows = [
        row
        for row in read_jsonl(path)
        if row["language"] == "zh"
        and row["q_type"] in {"multihop", "aggregation", "comparison", "set"}
    ]
    type_two = sorted(
        [row for row in rows if int(row["crossdoc_type"]) == 2],
        key=lambda row: stable_rank(str(row["id"])),
    )
    if len(type_two) != 17:
        raise ValueError(f"expected 17 XRAG zh crossdoc_type=2 rows, found {len(type_two)}")
    selected = list(type_two)
    for question_type in ("aggregation", "multihop", "set"):
        selected.extend(
            take(
                [
                    row
                    for row in rows
                    if int(row["crossdoc_type"]) == 1
                    and row["q_type"] == question_type
                ],
                1,
                lambda row: str(row["id"]),
            )
        )
    result = {
        "datasetVersion": "graph-local-crosslingual-v1",
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "zh",
        "selection": {
            "split": "validation",
            "crossdocType2Cases": 17,
            "crossdocType1SupplementCases": 3,
            "caseCount": 20,
            "pairedEnglishQuestion": True,
        },
        "cases": selected,
    }
    write_json(GRAPH_OUT / "xrag-zh.json", result)
    return {
        "resource": "/graph-local-golden/v1/xrag-zh.json",
        "caseCount": len(selected),
        "sourceSha256": sha256(path),
    }


def build_global_graph() -> dict[str, Any]:
    multihop_path = RAW / "multihop-rag" / "MultiHopRAG.json"
    multihop = [
        row
        for row in read_json(multihop_path)
        if row["question_type"]
        in {"comparison_query", "inference_query", "temporal_query"}
        and len(row["evidence_list"]) == 2
    ]
    selected_multihop = take(
        multihop,
        15,
        lambda row: row["query"],
    )

    xrag_path = RAW / "xrag" / "xrag.dev.jsonl"
    xrag = [
        row
        for row in read_jsonl(xrag_path)
        if row["language"] == "zh"
        and int(row["crossdoc_type"]) == 2
        and row["q_type"]
        in {"aggregation", "comparison", "multihop", "set"}
    ]
    selected_xrag = take(
        xrag,
        15,
        lambda row: str(row["id"]),
    )
    cases = [
        {
            "source": "multihop-rag",
            "intent": row["question_type"],
            "case": row,
        }
        for row in selected_multihop
    ] + [
        {
            "source": "xrag",
            "intent": row["q_type"],
            "case": row,
        }
        for row in selected_xrag
    ]
    result = {
        "suiteVersion": "graph-global-golden-v1",
        "status": "PUBLIC_CANDIDATES_SELECTED",
        "selection": {
            "caseCount": len(cases),
            "multiHopRagCases": len(selected_multihop),
            "xragZhCrossDocumentCases": len(selected_xrag),
            "rule": (
                "stable SHA-256 rank within eligible "
                "cross-document intent slices"
            ),
        },
        "sources": {
            "multiHopRag": {
                "revision": (
                    "71ac0d0bd1f951d2d6b70311f7d2ae404e1ffa82"
                ),
                "sha256": sha256(multihop_path),
            },
            "xrag": {
                "revision": (
                    "ead86612ac265e578ffee6f838c2180fec6428d9"
                ),
                "sha256": sha256(xrag_path),
            },
        },
        "cases": cases,
    }
    write_json(GLOBAL_OUT / "dataset.json", result)
    return result


def build_qasper() -> dict[str, Any]:
    path = RAW / "qasper" / "qasper-dev-v0.3.json"
    papers = read_json(path)
    answerable: list[dict[str, Any]] = []
    unanswerable: list[dict[str, Any]] = []
    for paper_id, paper in papers.items():
        for qa in paper["qas"]:
            answers = [annotation["answer"] for annotation in qa["answers"]]
            row = {
                "paperId": paper_id,
                "paper": {
                    "title": paper["title"],
                    "abstract": paper["abstract"],
                    "full_text": paper["full_text"],
                },
                "qa": qa,
            }
            if any(
                not answer["unanswerable"] and answer["evidence"]
                for answer in answers
            ):
                answerable.append(row)
            elif answers and all(answer["unanswerable"] for answer in answers):
                unanswerable.append(row)

    def distinct_papers(
        rows: list[dict[str, Any]],
        count: int,
    ) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        paper_ids: set[str] = set()
        for row in sorted(
            rows,
            key=lambda item: stable_rank(item["qa"]["question_id"]),
        ):
            if row["paperId"] not in paper_ids:
                paper_ids.add(row["paperId"])
                result.append(row)
            if len(result) == count:
                return result
        raise ValueError(f"needed {count} distinct QASPER papers")

    selected = distinct_papers(answerable, 10) + distinct_papers(unanswerable, 10)
    result = {
        "datasetVersion": "answer-citation-qasper-v1",
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "en",
        "selection": {
            "split": "dev",
            "answerableCases": 10,
            "unanswerableCases": 10,
            "caseCount": 20,
        },
        "cases": selected,
    }
    write_json(ANSWER_OUT / "qasper.json", result)
    return {
        "resource": "/answer-citation-golden/v1/qasper.json",
        "caseCount": len(selected),
        "sourceSha256": sha256(path),
    }


def build_garage() -> dict[str, Any]:
    path = RAW / "garage" / "GaRAGe_benchmark.jsonl"
    rows = list(read_jsonl(path))
    answerable = [
        row
        for row in rows
        if row["question_complexity"] == "Multi-hop"
        and row["answer_validate"] == "YES"
        and "YES" in row["evidence_cited"]
        and row["answer_generate"].strip()
    ]
    refusals = [
        row
        for row in rows
        if row["answer_validate"] != "YES"
        and row["answer_generate"].strip().lower()
        == "there is not enough grounding for an answer."
        and "YES" not in row["evidence_cited"]
    ]
    selected = take(answerable, 10, lambda row: row["sample_id"]) + take(
        refusals,
        10,
        lambda row: row["sample_id"],
    )
    result = {
        "datasetVersion": "answer-citation-garage-v1",
        "status": "PUBLIC_SOURCE_SELECTED",
        "language": "en",
        "selection": {
            "validatedMultiHopCases": 10,
            "insufficientGroundingCases": 10,
            "caseCount": 20,
        },
        "cases": selected,
    }
    write_json(ANSWER_OUT / "garage.json", result)
    return {
        "resource": "/answer-citation-golden/v1/garage.json",
        "caseCount": len(selected),
        "sourceSha256": sha256(path),
    }


def main() -> None:
    graph_slices = {
        "2wiki": build_2wiki(),
        "multiHopRag": build_multihop_rag(),
        "xragZh": build_xrag(),
    }
    answer_slices = {
        "qasper": build_qasper(),
        "garage": build_garage(),
    }
    build_global_graph()
    write_json(
        GRAPH_OUT / "manifest.json",
        {
            "suiteVersion": "graph-local-golden-v1",
            "status": "PUBLIC_CANDIDATES_SELECTED",
            "slices": graph_slices,
        },
    )
    write_json(
        ANSWER_OUT / "manifest.json",
        {
            "suiteVersion": "answer-citation-public-v1",
            "status": "PUBLIC_CANDIDATES_SELECTED",
            "slices": answer_slices,
        },
    )


if __name__ == "__main__":
    main()
