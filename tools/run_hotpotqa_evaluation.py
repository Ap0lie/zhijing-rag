#!/usr/bin/env python3
"""Import the pinned HotpotQA corpus and run the real Answer/Citation evaluator.

The script deliberately uses the platform's authenticated HTTP APIs. It never
writes document, pipeline, or evaluation facts directly to PostgreSQL.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / "backend/src/test/resources/hotpotqa-golden/v1/dataset.json"
WORK = ROOT / "data/public-golden/work/hotpotqa-v1"
STATE_FILE = WORK / "runtime-state.json"
REPORT_FILE = WORK / "run-report.json"
DATASET_KEY = "hotpotqa-answer-golden"
TERMINAL_RUNS = {"SUCCEEDED", "FAILED", "CANCELLED", "BLOCKED_PREREQUISITE"}
TERMINAL_REVISIONS = {"READY", "FAILED", "QUARANTINED", "DELETED"}


class ApiFailure(RuntimeError):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"HTTP {status} {code}: {message}")
        self.status = status
        self.code = code


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )
        self.csrf: dict[str, str] | None = None

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _decode(self, response: Any) -> Any:
        payload = response.read()
        if not payload:
            return None
        if "application/json" in (response.headers.get("Content-Type") or ""):
            return json.loads(payload.decode("utf-8"))
        return payload.decode("utf-8", errors="replace")

    def _open(self, request: urllib.request.Request) -> Any:
        try:
            with self.opener.open(request, timeout=120) as response:
                return self._decode(response)
        except urllib.error.HTTPError as error:
            body = error.read()
            try:
                parsed = json.loads(body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed = {"message": body.decode("utf-8", errors="replace")}
            raise ApiFailure(
                error.code,
                str(parsed.get("code", "REQUEST_FAILED")),
                str(parsed.get("message", parsed)),
            ) from error

    def refresh_csrf(self) -> dict[str, str]:
        self.csrf = self._open(
            urllib.request.Request(self._url("/api/v1/auth/csrf"))
        )
        return self.csrf

    def login(self, username: str, password: str) -> dict[str, Any]:
        csrf = self.refresh_csrf()
        body = urllib.parse.urlencode(
            {"username": username, "password": password}
        ).encode("utf-8")
        request = urllib.request.Request(
            self._url("/api/v1/auth/login"),
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                csrf["headerName"]: csrf["token"],
            },
        )
        user = self._open(request)
        self.csrf = None
        return user

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        headers: dict[str, str] | None = None,
        body: bytes | None = None,
        content_type: str | None = None,
    ) -> Any:
        method = method.upper()
        request_headers = dict(headers or {})
        if json_body is not None:
            body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            content_type = "application/json;charset=UTF-8"
        if content_type:
            request_headers["Content-Type"] = content_type
        if method not in {"GET", "HEAD", "OPTIONS"}:
            csrf = self.csrf or self.refresh_csrf()
            request_headers[csrf["headerName"]] = csrf["token"]
        request = urllib.request.Request(
            self._url(path), data=body, method=method, headers=request_headers
        )
        try:
            return self._open(request)
        except ApiFailure as error:
            if method not in {"GET", "HEAD", "OPTIONS"} and error.status == 403:
                csrf = self.refresh_csrf()
                request_headers[csrf["headerName"]] = csrf["token"]
                request = urllib.request.Request(
                    self._url(path), data=body, method=method, headers=request_headers
                )
                return self._open(request)
            raise

    def upload_multipart(
        self, path: str, fields: dict[str, str], file_path: Path, key: str
    ) -> Any:
        boundary = f"----rag-hotpotqa-{uuid.uuid4().hex}"
        chunks: list[bytes] = []
        for name, value in fields.items():
            chunks.extend(
                [
                    f"--{boundary}\r\n".encode(),
                    f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                    value.encode("utf-8"),
                    b"\r\n",
                ]
            )
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                (
                    'Content-Disposition: form-data; name="file"; '
                    f'filename="{file_path.name}"\r\n'
                ).encode(),
                b"Content-Type: text/markdown;charset=UTF-8\r\n\r\n",
                file_path.read_bytes(),
                b"\r\n",
                f"--{boundary}--\r\n".encode(),
            ]
        )
        return self.request(
            "POST",
            path,
            body=b"".join(chunks),
            content_type=f"multipart/form-data; boundary={boundary}",
            headers={"Idempotency-Key": key},
        )

    def upload(self, fields: dict[str, str], file_path: Path, key: str) -> Any:
        return self.upload_multipart(
            "/api/v1/admin/documents", fields, file_path, key
        )

    def upload_revision(
        self, document_id: str, fields: dict[str, str], file_path: Path, key: str
    ) -> Any:
        return self.upload_multipart(
            f"/api/v1/admin/documents/{document_id}/revisions",
            fields,
            file_path,
            key,
        )


def stable_key(prefix: str, value: str) -> str:
    return f"{prefix}:{hashlib.sha256(value.encode()).hexdigest()[:32]}"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def configure(version: int) -> None:
    global RESOURCE, WORK, STATE_FILE, REPORT_FILE
    RESOURCE = (
        ROOT / "backend/src/test/resources/hotpotqa-golden"
        / f"v{version}" / "dataset.json"
    )
    WORK = ROOT / "data/public-golden/work" / f"hotpotqa-v{version}"
    STATE_FILE = WORK / "runtime-state.json"
    REPORT_FILE = WORK / "run-report.json"


def paged_items(client: ApiClient, path: str, size: int = 100) -> dict[str, Any]:
    """Read a complete admin page without silently truncating future datasets."""
    items: list[dict[str, Any]] = []
    page = 0
    first: dict[str, Any] | None = None
    separator = "&" if "?" in path else "?"
    while True:
        payload = client.request(
            "GET", f"{path}{separator}page={page}&size={size}"
        )
        if first is None:
            first = dict(payload)
        batch = list(payload.get("items", []))
        items.extend(batch)
        total = int(payload.get("total", len(items)))
        if not batch or len(items) >= total:
            break
        page += 1
    result = first or {}
    result["items"] = items
    result["total"] = len(items)
    return result


def reusable_documents(current_state: dict[str, Any]) -> dict[str, str]:
    """Reuse stable Evidence documents imported by an earlier HotpotQA version."""
    documents: dict[str, str] = {}
    work_root = ROOT / "data/public-golden/work"
    for path in sorted(work_root.glob("hotpotqa-v*/runtime-state.json")):
        try:
            documents.update(load_json(path).get("documents", {}))
        except (OSError, ValueError, json.JSONDecodeError):
            continue
    documents.update(current_state.get("documents", {}))
    return documents


def provenance_fields(dataset: dict[str, Any], document: dict[str, Any]) -> dict[str, str]:
    source = dataset["source"]
    return {
        "evaluationSuiteVersion": dataset["suiteVersion"],
        "evaluationEvidenceKey": document["evidenceKey"],
        # Provenance uses a compact identifier; the canonical URL remains in sourceUrl.
        "sourceDataset": source["dataset"].replace("/", "."),
        "sourceTitle": document["title"],
        "sourceUrl": document["sourceUrl"],
        "sourceLicense": source["license"],
        "sourceRevision": source["revision"],
        "sourceContentHash": document["sourceContentHash"],
    }


def import_documents(
    client: ApiClient, dataset: dict[str, Any], state: dict[str, Any]
) -> dict[str, str]:
    allowed = {item["evidenceKey"] for item in dataset["corpus"]}
    documents = {
        key: value for key, value in reusable_documents(state).items()
        if key in allowed
    }
    state["documents"] = documents
    save_json(STATE_FILE, state)
    total = len(dataset["corpus"])
    for index, document in enumerate(dataset["corpus"], start=1):
        evidence_key = document["evidenceKey"]
        if evidence_key in documents:
            try:
                detail = client.request(
                    "GET", f"/api/v1/documents/{documents[evidence_key]}"
                )
                current_id = detail.get("currentRevisionId")
                revision = next(
                    (
                        item for item in detail.get("revisions", [])
                        if item.get("id") == current_id
                    ),
                    None,
                )
                provenance = (revision or {}).get("evaluationProvenance") or {}
                expected = (
                    evidence_key,
                    dataset["source"]["revision"],
                    document["sourceContentHash"],
                )
                actual = (
                    provenance.get("evaluationEvidenceKey"),
                    provenance.get("sourceRevision"),
                    provenance.get("sourceContentHash"),
                )
                if revision is None or revision.get("status") != "READY":
                    raise RuntimeError(
                        f"reusable Evidence is not READY: {evidence_key}"
                    )
                if actual != expected:
                    raise RuntimeError(
                        f"reusable Evidence provenance differs: {evidence_key}"
                    )
                continue
            except ApiFailure as error:
                if error.status != 404:
                    raise
                documents.pop(evidence_key, None)
        file_path = ROOT / document["file"]
        payload = file_path.read_bytes()
        canonical = payload[3:] if payload.startswith(b"\xef\xbb\xbf") else payload
        if hashlib.sha256(canonical).hexdigest() != document["fileSha256"]:
            raise RuntimeError(f"HotpotQA document hash differs: {evidence_key}")
        fields = provenance_fields(dataset, document)
        fields.update(
            {
                "title": f"[EVAL][PUBLIC][HOTPOTQA] {document['title']}",
                "visibility": "ALL_USERS",
            }
        )
        created = client.upload(
            fields,
            file_path,
            stable_key(
                "hotpotqa-source",
                f"{dataset['source']['revision']}:{evidence_key}",
            ),
        )
        document_id = created["document"]["id"]
        documents[evidence_key] = document_id
        state["documents"] = documents
        save_json(STATE_FILE, state)
        print(f"uploaded {index}/{total}: {document['title']}", flush=True)
    return documents


def wait_for_revisions(
    client: ApiClient,
    dataset: dict[str, Any],
    documents: dict[str, str],
    state: dict[str, Any],
    timeout_seconds: int,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    pending = dict(documents)
    corpus = {item["evidenceKey"]: item for item in dataset["corpus"]}
    repairs = set(state.get("encodingRepairs", []))
    failures: list[str] = []
    while pending and time.monotonic() < deadline:
        for evidence_key, document_id in list(pending.items()):
            detail = client.request("GET", f"/api/v1/documents/{document_id}")
            revisions = detail.get("revisions", [])
            revision = revisions[0] if revisions else {}
            status = revision.get("status", "STAGED")
            if status == "FAILED" and evidence_key not in repairs:
                document = corpus[evidence_key]
                client.upload_revision(
                    document_id,
                    provenance_fields(dataset, document),
                    ROOT / document["file"],
                    stable_key("hotpotqa-utf8-revision", evidence_key),
                )
                repairs.add(evidence_key)
                state["encodingRepairs"] = sorted(repairs)
                save_json(STATE_FILE, state)
                print(f"repaired encoding: {document['title']}", flush=True)
                continue
            # READY is only usable after the Document pointer is published;
            # those writes can become visible across consecutive reads.
            if status == "READY" and not detail.get("currentRevisionId"):
                continue
            if status in TERMINAL_REVISIONS:
                pending.pop(evidence_key)
                if status != "READY":
                    failures.append(f"{evidence_key}:{status}")
        print(
            f"pipeline ready {len(documents) - len(pending)}/{len(documents)}",
            flush=True,
        )
        if pending:
            time.sleep(3)
    if pending:
        raise RuntimeError(f"pipeline timeout; {len(pending)} revisions remain")
    if failures:
        raise RuntimeError("pipeline failures: " + ", ".join(failures[:10]))


def select_dataset(datasets: list[dict[str, Any]], suite: str) -> tuple[dict, dict]:
    for dataset in datasets:
        if dataset.get("key") != DATASET_KEY:
            continue
        for version in dataset.get("versions", []):
            if version.get("version") == suite:
                return dataset, version
    raise RuntimeError(f"dataset version {suite!r} was not imported by the backend")


def select_target(targets: list[dict[str, Any]]) -> dict[str, Any]:
    candidates = [
        target
        for target in targets
        if target.get("subjectType") == "ANSWER_CITATION"
        and target.get("readinessStatus") == "READY"
    ]
    if not candidates:
        raise RuntimeError("no READY ANSWER_CITATION evaluation target")
    candidates.sort(key=lambda value: (value.get("createdAt", ""), value["id"]))
    return candidates[-1]


def run_evaluation(
    client: ApiClient, dataset: dict[str, Any], state: dict[str, Any], timeout_seconds: int
) -> dict[str, Any]:
    datasets = client.request("GET", "/api/v1/admin/evaluations/datasets")
    _, version = select_dataset(datasets, dataset["suiteVersion"])
    mappings = paged_items(
        client,
        f"/api/v1/admin/evaluations/datasets/versions/{version['id']}/mappings",
    )
    ready_mapping_states = {"READY", "MAPPED", "NOT_REQUIRED"}
    blocked = [
        item
        for item in mappings.get("items", [])
        if item.get("effectiveStatus") not in ready_mapping_states
    ]
    if blocked:
        raise RuntimeError(
            f"{len(blocked)} HotpotQA cases are not READY: "
            + ", ".join(item["caseKey"] for item in blocked[:5])
        )

    target = select_target(client.request("GET", "/api/v1/admin/evaluations/targets"))
    subject = client.request(
        "POST",
        "/api/v1/admin/evaluations/subjects",
        json_body={
            "name": f"HotpotQA {dataset['suiteVersion']} · {target['targetKey']}"[:120],
            "targetId": target["id"],
        },
    )
    run = client.request(
        "POST",
        "/api/v1/admin/evaluations/runs",
        json_body={
            "evaluationSubjectId": subject["id"],
            "datasetVersion": dataset["suiteVersion"],
            "idempotencyKey": stable_key(
                "hotpotqa-run", f"{subject['id']}:{dataset['source']['revision']}"
            ),
        },
    )
    state.update({"subjectId": subject["id"], "runId": run["id"]})
    save_json(STATE_FILE, state)
    print(f"evaluation run {run['id']} created", flush=True)

    deadline = time.monotonic() + timeout_seconds
    while run["status"] not in TERMINAL_RUNS and time.monotonic() < deadline:
        time.sleep(3)
        run = client.request("GET", f"/api/v1/admin/evaluations/runs/{run['id']}")
        print(
            f"evaluation {run['status']} {run['completedCases']}/{run['totalCases']}",
            flush=True,
        )
    if run["status"] not in TERMINAL_RUNS:
        raise RuntimeError(f"evaluation timeout: run {run['id']}")

    results = paged_items(
        client, f"/api/v1/admin/evaluations/runs/{run['id']}/results"
    )
    return build_report(dataset, target, subject, run, mappings, results)


def build_report(
    dataset: dict[str, Any],
    target: dict[str, Any],
    subject: dict[str, Any],
    run: dict[str, Any],
    mappings: dict[str, Any],
    results: dict[str, Any],
) -> dict[str, Any]:
    values: dict[str, list[float]] = {}
    metric_statuses: Counter[str] = Counter()
    result_statuses: Counter[str] = Counter()
    cases: list[dict[str, Any]] = []
    case_metadata = report_case_metadata(dataset)
    for result in results.get("items", []):
        result_statuses[result["status"]] += 1
        case_metrics: dict[str, Any] = {}
        for metric in result.get("metrics", []):
            metric_statuses[f"{metric['key']}:{metric['status']}"] += 1
            if metric.get("value") is not None:
                values.setdefault(metric["key"], []).append(float(metric["value"]))
                case_metrics[metric["key"]] = metric["value"]
        metadata = case_metadata.get(result["caseKey"], {})
        cases.append(
            {
                "caseKey": result["caseKey"],
                "status": result["status"],
                "durationMs": result["durationMs"],
                "errorCode": result.get("errorCode"),
                "questionType": metadata.get("questionType"),
                "cohort": metadata.get("cohort"),
                "metrics": case_metrics,
            }
        )
    aggregates = {
        key: {
            "samples": len(numbers),
            "mean": sum(numbers) / len(numbers),
            "min": min(numbers),
            "max": max(numbers),
        }
        for key, numbers in sorted(values.items())
        if numbers
    }
    return {
        "generatedAtEpochSeconds": int(time.time()),
        "dataset": {
            "suiteVersion": dataset["suiteVersion"],
            "repository": dataset["source"]["repository"],
            "revision": dataset["source"]["revision"],
            "license": dataset["source"]["license"],
            "config": dataset["source"]["config"],
            "split": dataset["source"]["split"],
            "selection": dataset["selection"],
            "mappedCases": sum(
                item.get("effectiveStatus") in {"READY", "MAPPED", "NOT_REQUIRED"}
                for item in mappings.get("items", [])
            ),
        },
        "target": {
            "id": target["id"],
            "key": target["targetKey"],
            "snapshotHash": target["snapshotHash"],
        },
        "subject": {
            "id": subject["id"],
            "snapshotHash": subject["snapshotHash"],
        },
        "run": run,
        "resultStatuses": dict(result_statuses),
        "metricStatuses": dict(metric_statuses),
        "aggregates": aggregates,
        "slices": sliced_answer_metrics(cases),
        "cases": cases,
    }


def report_case_metadata(dataset: dict[str, Any]) -> dict[str, dict[str, str]]:
    legacy_resource = (
        ROOT / "backend/src/test/resources/hotpotqa-golden/v1/dataset.json"
    )
    legacy_ids = {
        item["id"] for item in load_json(legacy_resource).get("cases", [])
    }
    new_by_type: dict[str, list[str]] = {"bridge": [], "comparison": []}
    for item in dataset.get("cases", []):
        if item["id"] not in legacy_ids:
            new_by_type.setdefault(item["type"], []).append(item["id"])
    development = {
        case_id
        for values in new_by_type.values()
        for case_id in values[: len(values) // 2]
    }
    result: dict[str, dict[str, str]] = {}
    for item in dataset.get("cases", []):
        case_id = item["id"]
        cohort = (
            "legacy18" if case_id in legacy_ids
            else "development16" if case_id in development
            else "validation16"
        )
        value = {"questionType": item["type"], "cohort": cohort}
        result[case_id] = value
        result[f"dataset:{case_id}"] = value
    return result


def sliced_answer_metrics(cases: list[dict[str, Any]]) -> dict[str, Any]:
    selectors = {
        "legacy18": lambda item: item.get("cohort") == "legacy18",
        "new32": lambda item: item.get("cohort") != "legacy18",
        "development16": lambda item: item.get("cohort") == "development16",
        "validation16": lambda item: item.get("cohort") == "validation16",
        "bridge": lambda item: item.get("questionType") == "bridge",
        "comparison": lambda item: item.get("questionType") == "comparison",
    }
    result: dict[str, Any] = {}
    for name, accepts in selectors.items():
        selected = [item for item in cases if accepts(item)]
        metric_values: dict[str, list[float]] = {}
        for item in selected:
            for key, value in item["metrics"].items():
                if value is not None:
                    metric_values.setdefault(key, []).append(float(value))
        result[name] = {
            "caseCount": len(selected),
            "succeeded": sum(item["status"] == "SUCCEEDED" for item in selected),
            "metrics": {
                key: sum(values) / len(values)
                for key, values in sorted(metric_values.items()) if values
            },
        }
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default=os.getenv("RAG_EVAL_USERNAME", "admin"))
    parser.add_argument("--password", default=os.getenv("RAG_EVAL_PASSWORD"))
    parser.add_argument("--pipeline-timeout", type=int, default=1200)
    parser.add_argument("--evaluation-timeout", type=int, default=1800)
    parser.add_argument("--skip-import", action="store_true")
    parser.add_argument("--import-only", action="store_true")
    parser.add_argument("--suite", choices=("v1", "v2"), default="v1")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    configure(int(args.suite[1:]))
    if args.import_only and args.skip_import:
        raise ValueError("--import-only and --skip-import cannot be combined")
    password = args.password or getpass.getpass("RAG admin password: ")
    dataset = load_json(RESOURCE)
    state = load_json(STATE_FILE) if STATE_FILE.exists() else {}
    state["sourceRevision"] = dataset["source"]["revision"]
    state["datasetSha256"] = hashlib.sha256(RESOURCE.read_bytes()).hexdigest()
    client = ApiClient(args.base_url)
    user = client.login(args.username, password)
    if user.get("role") != "ADMIN":
        raise RuntimeError("HotpotQA import requires an ADMIN account")
    if not args.skip_import:
        documents = import_documents(client, dataset, state)
        wait_for_revisions(client, dataset, documents, state, args.pipeline_timeout)
    if args.import_only:
        save_json(STATE_FILE, state)
        print(json.dumps({
            "state": str(STATE_FILE),
            "documents": len(state.get("documents", {})),
            "status": "IMPORTED",
        }, indent=2))
        return 0
    report = run_evaluation(client, dataset, state, args.evaluation_timeout)
    save_json(REPORT_FILE, report)
    print(json.dumps({"report": str(REPORT_FILE), "run": report["run"]}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ApiFailure, RuntimeError, OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
