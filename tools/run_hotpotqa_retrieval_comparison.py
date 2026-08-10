#!/usr/bin/env python3
"""Run reproducible HotpotQA HYBRID versus READY LOCAL_GRAPH evaluation."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import statistics
import time
from pathlib import Path
from typing import Any

from run_hotpotqa_evaluation import (
    ApiClient,
    ROOT,
    TERMINAL_RUNS,
    load_json,
    paged_items,
    report_case_metadata,
    save_json,
    stable_key,
)


RESOURCE = ROOT / "backend/src/test/resources/hotpotqa-golden/v1/dataset.json"
REPORT = ROOT / "data/public-golden/work/hotpotqa-v1/retrieval-comparison-report.json"

MODES: dict[str, dict[str, str]] = {}


def configure(version: int) -> None:
    global RESOURCE, REPORT, MODES
    RESOURCE = (
        ROOT / "backend/src/test/resources/hotpotqa-golden"
        / f"v{version}" / "dataset.json"
    )
    REPORT = (
        ROOT / "data/public-golden/work" / f"hotpotqa-v{version}"
        / "retrieval-comparison-report.json"
    )
    MODES = {
        "HYBRID": {
            "datasetKey": "hotpotqa-retrieval-golden",
            "datasetVersion": f"hotpotqa-retrieval-v{version}",
            "subjectType": "RETRIEVAL",
            "targetKind": "ACTIVE",
            "qualityMetric": "phase11b.quality.retrieval",
        },
        "LOCAL_GRAPH": {
            "datasetKey": "hotpotqa-local-graph-golden",
            "datasetVersion": f"hotpotqa-local-graph-v{version}",
            "subjectType": "LOCAL_GRAPH",
            "targetKind": "READY",
            "qualityMetric": "phase11b.quality.local_graph",
        },
    }


def dataset_version(
    datasets: list[dict[str, Any]], key: str, version: str
) -> dict[str, Any]:
    for dataset in datasets:
        if dataset.get("key") != key:
            continue
        for candidate in dataset.get("versions", []):
            if candidate.get("version") == version:
                return candidate
    raise RuntimeError(f"dataset version is unavailable: {key}/{version}")


def target(
    targets: list[dict[str, Any]],
    subject_type: str,
    target_kind: str,
    graph_generation: int,
) -> dict[str, Any]:
    candidates = []
    for candidate in targets:
        if candidate.get("subjectType") != subject_type:
            continue
        if candidate.get("targetKind") != target_kind:
            continue
        if candidate.get("readinessStatus") != "READY":
            continue
        snapshot = candidate.get("snapshot") or {}
        if subject_type == "LOCAL_GRAPH" and int(
            snapshot.get("graphGeneration", -1)
        ) != graph_generation:
            continue
        candidates.append(candidate)
    if not candidates:
        raise RuntimeError(
            f"READY target is unavailable: {subject_type}/{target_kind}/"
            f"graph={graph_generation}"
        )
    candidates.sort(key=lambda item: (item.get("createdAt", ""), item["id"]))
    return candidates[-1]


def ready_mappings(client: ApiClient, version_id: str) -> dict[str, Any]:
    mappings = paged_items(
        client,
        f"/api/v1/admin/evaluations/datasets/versions/{version_id}/mappings",
    )
    accepted = {"READY", "MAPPED", "NOT_REQUIRED"}
    blocked = [
        item for item in mappings.get("items", [])
        if item.get("effectiveStatus") not in accepted
    ]
    if blocked:
        raise RuntimeError(
            f"{len(blocked)} cases are not mapped: "
            + ", ".join(item["caseKey"] for item in blocked[:5])
        )
    return mappings


def wait_run(
    client: ApiClient, run: dict[str, Any], timeout_seconds: int
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while run["status"] not in TERMINAL_RUNS and time.monotonic() < deadline:
        time.sleep(3)
        run = client.request(
            "GET", f"/api/v1/admin/evaluations/runs/{run['id']}"
        )
        print(
            f"{run['subjectType']} {run['status']} "
            f"{run['completedCases']}/{run['totalCases']}",
            flush=True,
        )
    if run["status"] not in TERMINAL_RUNS:
        raise RuntimeError(f"evaluation timeout: {run['id']}")
    return run


def build_graph_candidate(
    client: ApiClient,
    config_version: str,
    reason: str,
    timeout_seconds: int,
) -> int:
    created = client.request(
        "POST",
        "/api/v1/admin/graph/generations",
        json_body={
            "graphConfigVersion": config_version,
            "confirmation": "BUILD",
            "reason": reason,
        },
    )
    generation = int(created["graphGeneration"])
    print(f"Graph Generation {generation} BUILDING", flush=True)
    deadline = time.monotonic() + timeout_seconds
    current: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        overview = client.request("GET", "/api/v1/admin/graph")
        current = next(
            (
                item for item in overview.get("generations", [])
                if int(item.get("graphGeneration", -1)) == generation
            ),
            None,
        )
        if current is None:
            raise RuntimeError(f"Graph Generation {generation} disappeared")
        status = current.get("status")
        print(
            f"Graph {generation} {status} "
            f"{current.get('projectedDocumentCount', 0)}/"
            f"{current.get('expectedDocumentCount', 0)} "
            f"cache={current.get('cacheHitCount', 0)} "
            f"model={current.get('modelCallCount', 0)}",
            flush=True,
        )
        if status in {"READY", "FAILED", "DELETED"}:
            break
        time.sleep(5)
    if current is None or current.get("status") != "READY":
        code = (current or {}).get("failureCode") or "GRAPH_BUILD_TIMEOUT"
        reason_value = (current or {}).get("failureReason") or ""
        raise RuntimeError(f"Graph {generation} is not READY: {code} {reason_value}")
    closure = current.get("closure") or {}
    blockers = list(closure.get("blockers") or [])
    closed = (
        int(current.get("projectedDocumentCount", -1))
        == int(current.get("expectedDocumentCount", -2))
        and bool(current.get("caughtUp"))
        and bool(closure.get("sourceLocatorCompatible"))
        and bool(closure.get("caughtUp"))
        and int(closure.get("staleDocumentCount", -1)) == 0
        and int(closure.get("missingLocatorDocumentCount", -1)) == 0
        and int(closure.get("invalidEvidenceCount", -1)) == 0
        and not blockers
    )
    if not closed:
        raise RuntimeError(
            f"Graph {generation} READY without a complete closure: {closure}"
        )
    return generation


def summarize(
    mode: str,
    config: dict[str, Any],
    target_view: dict[str, Any],
    subject: dict[str, Any],
    run: dict[str, Any],
    results: dict[str, Any],
    source: dict[str, Any],
) -> dict[str, Any]:
    cases = []
    metadata = report_case_metadata(source)
    for result in results.get("items", []):
        output = result.get("output") or {}
        metric_values = {
            metric["key"]: metric.get("value")
            for metric in result.get("metrics", [])
            if metric.get("status") == "MEASURED"
        }
        quality = metric_values.get(config["qualityMetric"])
        expected = int(output.get("expectedRevisions", 0) or 0)
        matched = int(output.get("matchedExpectedRevisions", 0) or 0)
        recall = matched / expected if expected else 0.0
        degraded = bool(output.get("graphDegraded") or output.get("degraded"))
        graph = output.get("graphDiagnostics") or {}
        case_metadata = metadata.get(result["caseKey"], {})
        cases.append({
            "caseKey": result["caseKey"],
            "status": result["status"],
            "errorCode": result.get("errorCode"),
            "durationMs": result.get("durationMs"),
            "quality": quality,
            "resultCount": output.get("resultCount"),
            "expectedRevisions": expected,
            "matchedExpectedRevisions": matched,
            "evidenceRecall": recall,
            "graphModeRequested": output.get("graphModeRequested"),
            "graphModeUsed": output.get("graphModeUsed"),
            "graphDegraded": output.get("graphDegraded"),
            "degraded": degraded,
            "degradationCode": output.get("degradationCode"),
            "graphDegradationCode": output.get("graphDegradationCode"),
            "stageRecall": output.get("stageRecall"),
            "graphDiagnostics": graph or None,
            "questionType": case_metadata.get("questionType"),
            "cohort": case_metadata.get("cohort"),
        })
    metrics = retrieval_metrics(cases)
    return {
        "mode": mode,
        "datasetVersion": config["datasetVersion"],
        "target": {
            "id": target_view["id"],
            "key": target_view["targetKey"],
            "kind": target_view["targetKind"],
            "snapshotHash": target_view["snapshotHash"],
            "graphGeneration": (target_view.get("snapshot") or {}).get(
                "graphGeneration"
            ),
            "indexGeneration": (target_view.get("snapshot") or {}).get(
                "indexGeneration"
            ),
        },
        "subject": {
            "id": subject["id"],
            "snapshotHash": subject["snapshotHash"],
        },
        "run": run,
        "metrics": metrics,
        "slices": retrieval_slices(cases),
        "cases": cases,
    }


def retrieval_metrics(cases: list[dict[str, Any]]) -> dict[str, Any]:
    quality = [float(item["quality"]) for item in cases if item["quality"] is not None]
    recalls = [float(item["evidenceRecall"]) for item in cases]
    durations = [int(item.get("durationMs") or 0) for item in cases]
    ordered = sorted(durations)
    p95_index = max(0, int((len(ordered) - 1) * 0.95)) if ordered else 0
    metrics: dict[str, Any] = {
        "casePassRate": statistics.mean(quality) if quality else 0.0,
        "evidenceRecall": statistics.mean(recalls) if recalls else 0.0,
        "degradationCount": sum(bool(item.get("degraded")) for item in cases),
        "p50Ms": statistics.median(durations) if durations else None,
        "p95Ms": ordered[p95_index] if ordered else None,
        "maxMs": max(durations) if durations else None,
    }
    stage_names = sorted({
        key for item in cases for key in (item.get("stageRecall") or {})
    })
    metrics["stageRecall"] = {
        stage: statistics.mean(
            float(item["stageRecall"][stage])
            / max(1.0, float(item["stageRecall"].get("expected", 1)))
            for item in cases if stage in (item.get("stageRecall") or {})
        )
        for stage in stage_names if stage != "expected"
    }
    graph_diagnostics = [
        item["graphDiagnostics"] for item in cases if item.get("graphDiagnostics")
    ]
    if graph_diagnostics:
        metrics["graphDiagnostics"] = {
            "entitySeedRecall": statistics.mean(
                item.get("seededDocuments", 0)
                / max(1, item.get("expectedDocuments", 0))
                for item in graph_diagnostics
            ),
            "graphCandidateGoldRecall": statistics.mean(
                item.get("graphCandidateGoldRevisions", 0)
                / max(1, item.get("expectedRevisions", 0))
                for item in graph_diagnostics
            ),
            "pathCoverage": statistics.mean(
                item.get("pathDocuments", 0)
                / max(1, item.get("expectedDocuments", 0))
                for item in graph_diagnostics
            ),
            "averageAddedCandidates": statistics.mean(
                item.get("addedCandidates", 0) for item in graph_diagnostics
            ),
            "completePathBeforeRerankRate": statistics.mean(
                item.get("pathDocuments", 0) >= item.get("expectedDocuments", 0)
                for item in graph_diagnostics
            ),
            "completePathAfterRerankRate": statistics.mean(
                item.get("rerankedPathGoldRevisions", 0)
                >= item.get("expectedRevisions", 0)
                for item in graph_diagnostics
            ),
            "completePathInEvidenceRate": statistics.mean(
                item.get("evidencePathGoldRevisions", 0)
                >= item.get("expectedRevisions", 0)
                for item in graph_diagnostics
            ),
        }
    return metrics


def retrieval_slices(cases: list[dict[str, Any]]) -> dict[str, Any]:
    selectors = {
        "legacy18": lambda item: item.get("cohort") == "legacy18",
        "new32": lambda item: item.get("cohort") != "legacy18",
        "development16": lambda item: item.get("cohort") == "development16",
        "validation16": lambda item: item.get("cohort") == "validation16",
        "bridge": lambda item: item.get("questionType") == "bridge",
        "comparison": lambda item: item.get("questionType") == "comparison",
    }
    return {
        name: {
            "caseCount": len(selected),
            "metrics": retrieval_metrics(selected),
        }
        for name, accepts in selectors.items()
        if (selected := [item for item in cases if accepts(item)])
    }


def execute_mode(
    client: ApiClient,
    datasets: list[dict[str, Any]],
    targets: list[dict[str, Any]],
    mode: str,
    graph_generation: int,
    timeout_seconds: int,
    source: dict[str, Any],
) -> dict[str, Any]:
    config = MODES[mode]
    version = dataset_version(
        datasets, config["datasetKey"], config["datasetVersion"]
    )
    ready_mappings(client, version["id"])
    selected = target(
        targets,
        config["subjectType"],
        config["targetKind"],
        graph_generation,
    )
    subject = client.request(
        "POST",
        "/api/v1/admin/evaluations/subjects",
        json_body={
            "name": f"HotpotQA {mode} · {selected['targetKey']}"[:120],
            "targetId": selected["id"],
        },
    )
    run = client.request(
        "POST",
        "/api/v1/admin/evaluations/runs",
        json_body={
            "evaluationSubjectId": subject["id"],
            "datasetVersion": config["datasetVersion"],
            "idempotencyKey": stable_key(
                "hotpotqa-comparison",
                f"{mode}:{subject['id']}:{selected['snapshotHash']}",
            ),
        },
    )
    print(f"{mode} run {run['id']} created", flush=True)
    run = wait_run(client, run, timeout_seconds)
    results = paged_items(
        client, f"/api/v1/admin/evaluations/runs/{run['id']}/results",
    )
    return summarize(mode, config, selected, subject, run, results, source)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default=os.getenv("RAG_EVAL_USERNAME", "admin"))
    parser.add_argument("--password", default=os.getenv("RAG_EVAL_PASSWORD"))
    parser.add_argument("--graph-generation", type=int, default=7)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--build-graph", action="store_true")
    parser.add_argument(
        "--graph-config-version", default="phase8-deepseek-v4-flash-v2"
    )
    parser.add_argument("--graph-build-timeout", type=int, default=7200)
    parser.add_argument("--suite", choices=("v1", "v2"), default="v1")
    parser.add_argument(
        "--modes", nargs="+", choices=("HYBRID", "LOCAL_GRAPH"),
        default=("HYBRID", "LOCAL_GRAPH"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    configure(int(args.suite[1:]))
    password = args.password or getpass.getpass("RAG admin password: ")
    source = load_json(RESOURCE)
    client = ApiClient(args.base_url)
    user = client.login(args.username, password)
    if user.get("role") != "ADMIN":
        raise RuntimeError("HotpotQA comparison requires an ADMIN account")
    datasets = client.request("GET", "/api/v1/admin/evaluations/datasets")
    graph_generation = args.graph_generation
    if args.build_graph:
        graph_generation = build_graph_candidate(
            client,
            args.graph_config_version,
            f"HotpotQA {args.suite} 扩样候选评测；仅构建 READY，不发布",
            args.graph_build_timeout,
        )
    targets = client.request("GET", "/api/v1/admin/evaluations/targets")
    runs = {
        mode: execute_mode(
            client, datasets, targets, mode, graph_generation,
            args.timeout, source,
        )
        for mode in args.modes
    }
    report = {
        "generatedAtEpochSeconds": int(time.time()),
        "source": source["source"],
        "selection": source["selection"],
        "graphGeneration": graph_generation,
        "runs": runs,
    }
    if {"HYBRID", "LOCAL_GRAPH"} <= runs.keys():
        hybrid = runs["HYBRID"]
        local = runs["LOCAL_GRAPH"]
        report["delta"] = {
            "casePassRate": local["metrics"]["casePassRate"]
            - hybrid["metrics"]["casePassRate"],
            "evidenceRecall": local["metrics"]["evidenceRecall"]
            - hybrid["metrics"]["evidenceRecall"],
            "p95Ms": local["metrics"]["p95Ms"] - hybrid["metrics"]["p95Ms"],
        }
    save_json(REPORT, report)
    print(json.dumps({"report": str(REPORT), "delta": report.get("delta")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
