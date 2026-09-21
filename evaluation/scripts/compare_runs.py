#!/usr/bin/env python3
"""Compare comparable measured runs, rejecting corpus/dataset/provider drift."""
import argparse
import json
from pathlib import Path


def comparability_errors(baseline, candidate, allow_provider_change=False):
    errors = []
    for key in ("schemaVersion", "kind", "scope"):
        if baseline.get(key) != candidate.get(key):
            errors.append(f"{key} differs")
    left, right = baseline.get("provenance", {}), candidate.get("provenance", {})
    for key in ("datasetHash", "selectedIdsHash", "corpusFilesHash"):
        if not left.get(key) or left.get(key) != right.get(key):
            errors.append(f"{key} missing or differs")
    if not allow_provider_change and left.get("providersModels") != right.get("providersModels"):
        errors.append("answer/agent provider-model identity differs")
    if baseline.get("kind") == "retrieval":
        if left.get("config", {}).get("k") != right.get("config", {}).get("k"):
            errors.append("retrieval k differs")
        for key in ("corpusHash", "embeddingProvider", "embeddingModel", "embeddingVersion", "backend", "vectorMinScore"):
            if key in ("embeddingProvider", "embeddingModel", "embeddingVersion", "backend") and allow_provider_change:
                continue
            if left.get("server", {}).get(key) != right.get("server", {}).get(key):
                errors.append(f"server {key} differs")
        for key in ("candidates", "rrfK"):
            if left.get("serverStatus", {}).get("retrievalConfig", {}).get(key) != right.get("serverStatus", {}).get("retrievalConfig", {}).get(key):
                errors.append(f"retrieval configuration {key} differs")
        if not baseline.get("summary", {}).get("provenanceConsistent") or not candidate.get("summary", {}).get("provenanceConsistent"):
            errors.append("one run has inconsistent server provenance")
    return errors


def flatten_scalars(value, prefix=""):
    result = {}
    for key, item in value.items():
        name = f"{prefix}.{key}" if prefix else key
        if isinstance(item, dict):
            result.update(flatten_scalars(item, name))
        elif isinstance(item, (int, float)) and not isinstance(item, bool):
            result[name] = item
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--allow-provider-change", action="store_true", help="Explicit cross-provider experiment; preserve warning")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    candidate = json.loads(args.candidate.read_text(encoding="utf-8"))
    errors = comparability_errors(baseline, candidate, args.allow_provider_change)
    if errors:
        parser.error("Incomparable runs: " + "; ".join(errors))
    left, right = flatten_scalars(baseline["summary"]), flatten_scalars(candidate["summary"])
    report = {"baseline": str(args.baseline), "candidate": str(args.candidate),
              "providerChangeAllowed": args.allow_provider_change,
              "caution": "Single sequential local runs do not establish statistically significant latency improvements; account for cache, execution order, host load, and repeated trials.",
              "metrics": [{"metric": key, "baseline": left[key], "candidate": right[key], "delta": right[key] - left[key]}
                          for key in sorted(left.keys() & right.keys())]}
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
