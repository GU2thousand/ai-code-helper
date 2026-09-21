"""Shared, dependency-free evaluation IO and evidence-based metrics."""
from __future__ import annotations

import csv
import hashlib
import http.cookiejar
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "backend/src/main/resources/knowledge-base"
DATASETS = ROOT / "evaluation/datasets"
SCHEMA_VERSION = 1


def normalized(text):
    return re.sub(r"\s+", " ", str(text)).strip().casefold()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def object_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def read_jsonl(path):
    rows = []
    for line_no, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_no}: invalid JSON: {error.msg}") from error
        if not isinstance(row, dict):
            raise ValueError(f"{path}:{line_no}: expected object")
        rows.append(row)
    if not rows:
        raise ValueError(f"{path}: empty dataset")
    return rows


def validate_qa(rows, corpus=CORPUS):
    seen = set()
    questions = set()
    for row in rows:
        for field in ("id", "question", "category", "language", "split", "answerable",
                      "expected_sources", "expected_evidence", "expected_points"):
            if field not in row:
                raise ValueError(f"QA row missing {field}: {row.get('id', '<unknown>')}")
        if not isinstance(row["id"], str) or not row["id"] or row["id"] in seen:
            raise ValueError(f"Missing/duplicate id {row['id']!r}")
        seen.add(row["id"])
        if not isinstance(row["question"], str) or not row["question"].strip():
            raise ValueError(f"{row['id']}: empty question")
        question = normalized(row["question"])
        if question in questions:
            raise ValueError(f"{row['id']}: duplicate question")
        questions.add(question)
        if row["split"] not in ("dev", "test") or row["language"] not in ("en", "zh"):
            raise ValueError(f"{row['id']}: invalid split/language")
        if type(row["answerable"]) is not bool:
            raise ValueError(f"{row['id']}: answerable must be boolean")
        if any(not isinstance(row[key], list) for key in ("expected_sources", "expected_evidence", "expected_points")):
            raise ValueError(f"{row['id']}: expected lists")
        if not row["answerable"]:
            if any(row[key] for key in ("expected_sources", "expected_evidence", "expected_points")):
                raise ValueError(f"{row['id']}: no-answer case must have no relevant evidence")
            continue
        if not row["expected_sources"] or not row["expected_evidence"] or not row["expected_points"]:
            raise ValueError(f"{row['id']}: answerable case requires evidence and points")
        for evidence in row["expected_evidence"]:
            if any(not isinstance(evidence.get(key), str) or not evidence[key].strip()
                   for key in ("source", "section", "anchor")):
                raise ValueError(f"{row['id']}: evidence needs source, section, and anchor")
            source = evidence["source"]
            if Path(source).name != source or source not in row["expected_sources"]:
                raise ValueError(f"{row['id']}: invalid evidence source")
            if corpus is not None:
                text = (Path(corpus) / source).read_text(encoding="utf-8")
                if normalized(evidence["anchor"]) not in normalized(text):
                    raise ValueError(f"{row['id']}: evidence anchor absent from {source}")
                if normalized(evidence["section"]) not in normalized(text):
                    raise ValueError(f"{row['id']}: evidence section absent from {source}")
                headings = list(re.finditer(r"(?m)^(#{1,6})\s+(.+?)\s*$", text))
                matching = [index for index, heading in enumerate(headings)
                            if normalized(heading.group(2)) == normalized(evidence["section"])]
                if len(matching) != 1:
                    raise ValueError(f"{row['id']}: evidence section must identify one exact heading")
                index = matching[0]
                start = headings[index].end()
                end = next((h.start() for h in headings[index + 1:] if len(h.group(1)) <= len(headings[index].group(1))), len(text))
                if normalized(evidence["anchor"]) not in normalized(text[start:end]):
                    raise ValueError(f"{row['id']}: evidence anchor is outside its declared section")
        if set(row["expected_sources"]) != {e["source"] for e in row["expected_evidence"]}:
            raise ValueError(f"{row['id']}: expected_sources must exactly match evidence sources")
        point_ids = set()
        for point in row["expected_points"]:
            if not isinstance(point, dict) or not point.get("id") or point["id"] in point_ids:
                raise ValueError(f"{row['id']}: point requires unique id")
            point_ids.add(point["id"])
            if not isinstance(point.get("any_of"), list) or not point["any_of"] or any(
                    not isinstance(p, str) or not p.strip() for p in point["any_of"]):
                raise ValueError(f"{row['id']}: point requires nonempty phrase alternatives")
    return rows


def select_rows(rows, split="all", limit=None):
    selected = [row for row in rows if split == "all" or row["split"] == split]
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be positive")
        selected = selected[:limit]
    if not selected:
        raise ValueError("No cases selected")
    return selected


def provenance(dataset, rows, config):
    files = {path.name: file_hash(path) for path in sorted(CORPUS.glob("*.md"))}
    scripts = {path.name: file_hash(path) for path in sorted((ROOT / "evaluation/scripts").glob("*.py"))}
    def git(*args):
        try:
            return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL).decode().strip()
        except (OSError, subprocess.CalledProcessError):
            return None
    return {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "gitCommit": git("rev-parse", "HEAD"),
        "gitDirty": bool(git("status", "--porcelain")),
        "workingDiffHash": object_hash(git("diff", "HEAD")),
        "dataset": Path(dataset).name,
        "datasetHash": file_hash(dataset),
        "selectedIdsHash": object_hash([r["id"] for r in rows]),
        "selectedCount": len(rows),
        "corpusFiles": files,
        "corpusFilesHash": object_hash(files),
        "evaluationScripts": scripts,
        "corpusHashMethod": "SHA256 of sorted filename-to-file-SHA256 JSON; server index hash is recorded separately",
        "config": config,
    }


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    # Nearest-rank definition; unlike interpolated percentiles this is an observed duration.
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def latency_summary(values):
    return {"meanMs": statistics.fmean(values) if values else None,
            "p50Ms": percentile(values, .50), "p95Ms": percentile(values, .95),
            "p99Ms": percentile(values, .99), "count": len(values)}


def fraction(numerator, denominator):
    return numerator / denominator if denominator else None


def mean(values):
    return statistics.fmean(values) if values else None


def grouped_summaries(rows, summarize):
    """Subgroups use their own denominators; they never alter the fixed cases."""
    result = {}
    for field, label in (("split", "bySplit"), ("language", "byLanguage"), ("category", "byCategory")):
        result[label] = {value: summarize([row for row in rows if row[field] == value])
                         for value in sorted({row[field] for row in rows})}
    return result


def source_name(source):
    # Supports classpath:knowledge-base/foo.md and plain basename metadata.
    return str(source).replace("\\", "/").rsplit("/", 1)[-1]


def evidence_matches(hit, evidence):
    return (source_name(hit.get("source", "")) == evidence["source"]
            and normalized(evidence["anchor"]) in normalized(hit.get("text", "")))


def score_retrieval(case, hits):
    evidence = case["expected_evidence"]
    if not case["answerable"]:
        return {"recallAt1": None, "recallAt3": None, "recallAt5": None, "mrr": None,
                "noAnswerCorrect": not bool(hits), "firstRelevantRank": None}
    relevant = [any(evidence_matches(hit, e) for e in evidence) for hit in hits]
    first = next((rank for rank, match in enumerate(relevant, 1) if match), None)
    out = {"mrr": 1 / first if first else 0.0, "firstRelevantRank": first,
           "noAnswerCorrect": None}
    for k in (1, 3, 5):
        covered = sum(any(evidence_matches(hit, e) for hit in hits[:k]) for e in evidence)
        out[f"recallAt{k}"] = covered / len(evidence)
    return out


def validate_ranking(response, k):
    hits = response.get("results")
    if not isinstance(hits, list) or len(hits) > k:
        raise ValueError("Retrieval response results must be a list of at most k rows")
    seen = set()
    for rank, hit in enumerate(hits, 1):
        if any(not isinstance(hit.get(key), str) or not hit[key] for key in ("chunkId", "source", "text")):
            raise ValueError("Retrieval rows require chunkId, source, text")
        if hit["chunkId"] in seen:
            raise ValueError("Retrieval response contains duplicate chunkId")
        seen.add(hit["chunkId"])
        if hit.get("finalRank", rank) != rank:
            raise ValueError("Retrieval rows must be ordered by consecutive finalRank")
    for field in ("embeddingModel", "corpusHash", "mode"):
        if not isinstance(response.get(field), str) or not response[field]:
            raise ValueError(f"Retrieval response requires provenance {field}")
    duration = response.get("durationMs")
    if not isinstance(duration, (float, int)) or not math.isfinite(duration) or duration < 0:
        raise ValueError("Retrieval response requires finite nonnegative durationMs")
    return hits


class HttpClient:
    def __init__(self, base_url, timeout=60, evaluation_key=None):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("base URL must be HTTP(S), without credentials, query, or fragment")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.key = evaluation_key
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, body=None):
        headers = {"Accept": "application/json"}
        if self.key and path.startswith("/api/evaluation/"):
            headers["X-Evaluation-Key"] = self.key
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers)
        start = time.perf_counter()
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                value = json.load(response)
        except urllib.error.HTTPError as error:
            # Do not print arbitrary upstream response bodies, tokens, or cookies.
            code = error.code
            error.close()
            raise RuntimeError(f"HTTP {code} for {path}") from error
        return value, (time.perf_counter() - start) * 1000


def write_results(output, result, csv_rows):
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    csv_path = path.with_suffix(".csv")
    fields = list(dict.fromkeys(key for row in csv_rows for key in row))
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in csv_rows:
            writer.writerow({k: json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v for k, v in row.items()})
    print(json.dumps({"output": str(path), "csv": str(csv_path), "summary": result["summary"]}, ensure_ascii=False, indent=2))


def common_arguments(parser, dataset, output):
    parser.add_argument("--dataset", type=Path, default=DATASETS / dataset)
    parser.add_argument("--output", type=Path, default=ROOT / "evaluation/results" / output)
    parser.add_argument("--split", choices=("all", "dev", "test"), default="all")
    parser.add_argument("--limit", type=int, help="Smoke subset only; recorded in provenance")
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--evaluation-key-env", default="APP_EVALUATION_KEY",
                        help="Name of environment variable, never a literal secret")


def make_client(args, require_evaluation=False):
    key = os.environ.get(args.evaluation_key_env)
    if require_evaluation and not key:
        raise ValueError(f"Set {args.evaluation_key_env} to the server's local evaluation key; also enable APP_EVALUATION_ENABLED=true on the server")
    return HttpClient(args.base_url, args.timeout, key)
