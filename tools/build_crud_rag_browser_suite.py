#!/usr/bin/env python3
"""Build a small, deterministic CRUD_RAG browser-test corpus.

The generated Markdown contains only searchable source text. Repository,
license, case and checksum provenance lives exclusively in manifest.json.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


SELECTIONS = {
    "event_summary": (17, 137),
    "continuing_writing": (29, 211),
    "hallu_modified": (31, 173),
    "questanswer_1doc": (23, 149),
    "questanswer_2docs": (41, 223),
    "questanswer_3docs": (53, 257),
}


def normalize_text(value: str) -> str:
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in value.splitlines()]
    return "\n\n".join(line for line in lines if line)


def safe_id(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z_-]+", "-", value).strip("-").lower()


def case_question(task: str, item: dict) -> str:
    if task.startswith("questanswer_"):
        return normalize_text(item["questions"])
    if task == "event_summary":
        return f"请根据原文概括该事件：{normalize_text(item['event'])}"
    if task == "continuing_writing":
        return f"请根据原文说明这起事件后续发生了什么：{normalize_text(item['event'])}"
    return (
        "请判断以下说法是否有原文依据，并说明原文实际记载："
        f"{normalize_text(item['hallucinatedContinuation'])}"
    )


def expected_answer(task: str, item: dict) -> str:
    if task.startswith("questanswer_"):
        return normalize_text(item["answers"])
    if task == "event_summary":
        return normalize_text(item["summary"])
    if task == "continuing_writing":
        return normalize_text(item["continuing"])
    return normalize_text(item["realContinuation"])


def source_documents(task: str, item: dict) -> list[str]:
    if task == "event_summary":
        return [item["text"]]
    if task == "continuing_writing":
        return [f"{item['beginning']}\n\n{item['continuing']}"]
    if task == "hallu_modified":
        return [f"{item['newsBeginning']}\n\n{item['newsRemainder']}"]
    count = int(task.removeprefix("questanswer_").removesuffix("doc").removesuffix("docs"))
    return [item[f"news{index}"] for index in range(1, count + 1)]


def source_title(task: str, item: dict, part: int, parts: int) -> str:
    base = item.get("title") or item.get("headLine") or item.get("event") or item["ID"]
    base = normalize_text(base).replace("\n", " ")
    if len(base) > 48:
        base = f"{base[:47]}…"
    suffix = f"（{part}/{parts}）" if parts > 1 else ""
    return f"{base}{suffix}"


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()

    dataset = json.loads(args.source.read_text(encoding="utf-8"))
    args.output.mkdir(parents=True, exist_ok=True)

    cases = []
    documents = []
    for task, indices in SELECTIONS.items():
        for index in indices:
            item = dataset[task][index]
            case_id = str(item["ID"])
            sources = source_documents(task, item)
            files = []
            for part, raw_source in enumerate(sources, start=1):
                title = source_title(task, item, part, len(sources))
                filename = f"{task}_{safe_id(case_id)}_{part}.md"
                markdown = f"# {title}\n\n{normalize_text(raw_source)}\n"
                (args.output / filename).write_text(markdown, encoding="utf-8", newline="\n")
                digest = sha256(markdown)
                files.append(filename)
                documents.append(
                    {
                        "file": filename,
                        "title": f"[EVAL][REFERENCE][CRUD-RAG-V2] {title}",
                        "task": task,
                        "caseId": case_id,
                        "part": part,
                        "parts": len(sources),
                        "sha256": digest,
                        "sourceUrl": item.get("url"),
                    }
                )
            cases.append(
                {
                    "task": task,
                    "caseId": case_id,
                    "sourceIndex": index,
                    "question": case_question(task, item),
                    "expectedAnswer": expected_answer(task, item),
                    "files": files,
                }
            )

    manifest = {
        "suite": "crud-rag-browser-smoke-v2",
        "sourceRepo": "https://github.com/IAAR-Shanghai/CRUD_RAG",
        "sourceCommit": args.source_commit,
        "license": "REPOSITORY_README_DECLARED_APACHE-2.0",
        "redistributionScope": "LOCAL_EVALUATION",
        "caseCount": len(cases),
        "documentCount": len(documents),
        "cases": cases,
        "documents": documents,
    }
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    print(f"generated {len(cases)} cases and {len(documents)} documents in {args.output}")


if __name__ == "__main__":
    main()
