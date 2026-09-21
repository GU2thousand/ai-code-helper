#!/usr/bin/env python3
"""Measure fixed QA evidence retrieval; no LLM credentials required for local mode."""
import argparse
import time

from eval_common import (SCHEMA_VERSION, common_arguments, fraction, grouped_summaries, latency_summary,
                         make_client, mean, provenance, read_jsonl, score_retrieval,
                         select_rows, validate_qa, validate_ranking, write_results)


def summarize_retrieval(rows):
    answerable = [r for r in rows if r["answerable"]]
    no_answer = [r for r in rows if not r["answerable"]]
    successes = [r for r in rows if not r["error"]]
    return {
        "count": len(rows), "answerableCount": len(answerable), "noAnswerCount": len(no_answer),
        **{key: mean([r[key] for r in answerable]) for key in ("recallAt1", "recallAt3", "recallAt5", "mrr")},
        "noAnswerAccuracy": mean([r["noAnswerCorrect"] for r in no_answer]),
        "noAnswerFalsePositiveRate": fraction(sum(not r["noAnswerCorrect"] and not r["error"] for r in no_answer), len(no_answer)),
        "errorRate": fraction(len(rows) - len(successes), len(rows)),
        "degradedRate": fraction(sum(bool(r["degraded"]) for r in successes), len(rows)),
        "serverLatency": latency_summary([r["durationMs"] for r in successes]),
        "clientLatency": latency_summary([r["clientDurationMs"] for r in rows]),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    common_arguments(parser, "retrieval_eval.jsonl", "baseline-v1.json")
    parser.add_argument("--mode", choices=("vector", "lexical", "hybrid", "hybrid_rerank", "hybrid-rerank"), default="vector")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=3)
    args = parser.parse_args(argv)
    args.mode = args.mode.replace("-", "_")
    if args.k < 5 or args.warmup < 0:
        parser.error("k must be at least 5; warmup must be nonnegative")
    rows = select_rows(validate_qa(read_jsonl(args.dataset)), args.split, args.limit)
    client = make_client(args, require_evaluation=True)
    configuration = {"mode": args.mode, "k": args.k, "warmup": args.warmup,
                     "split": args.split, "limit": args.limit, "baseUrl": args.base_url}
    run = {"schemaVersion": SCHEMA_VERSION, "kind": "retrieval", "provenance": provenance(args.dataset, rows, configuration),
           "method": "Evidence-anchor recall; answerable cases only for Recall/MRR. No-answer exact abstention reported separately.",
           "warmup": [], "rows": []}
    run["provenance"]["serverStatus"], _ = client.request("/api/evaluation/status")
    for index in range(args.warmup):
        row = rows[index % len(rows)]
        try:
            response, client_ms = client.request("/api/evaluation/retrieval", {"question": row["question"], "mode": args.mode, "k": args.k})
            validate_ranking(response, args.k)
            run["warmup"].append({"id": row["id"], "durationMs": response["durationMs"], "clientDurationMs": client_ms})
        except Exception as error:
            run["warmup"].append({"id": row["id"], "error": str(error)})
    metadata_keys = ("corpusHash", "embeddingProvider", "embeddingModel", "embeddingVersion", "backend", "reranker", "vectorMinScore")
    for case in rows:
        start = time.perf_counter()
        record = {"id": case["id"], "question": case["question"], "category": case["category"],
                  "language": case["language"], "split": case["split"], "answerable": case["answerable"]}
        try:
            response, client_ms = client.request("/api/evaluation/retrieval", {"question": case["question"], "mode": args.mode, "k": args.k})
            hits = validate_ranking(response, args.k)
            if response["mode"] != args.mode:
                raise ValueError(f"Server returned mode {response['mode']} for requested {args.mode}")
            record.update(score_retrieval(case, hits))
            record.update({"durationMs": response["durationMs"], "clientDurationMs": client_ms,
                           "degraded": bool(response.get("degraded", False)), "error": None,
                           "raw": response})
        except Exception as error:
            record.update(score_retrieval(case, []))
            # An error is not a valid abstention.
            if not case["answerable"]:
                record["noAnswerCorrect"] = False
            record.update({"durationMs": None, "clientDurationMs": (time.perf_counter() - start) * 1000,
                           "degraded": None, "error": str(error), "raw": None})
        run["rows"].append(record)
    successes = [r for r in run["rows"] if not r["error"]]
    metadata = {key: sorted({str(r["raw"].get(key, "unspecified")) for r in successes}) for key in metadata_keys}
    run["provenance"]["server"] = metadata
    run["summary"] = summarize_retrieval(run["rows"])
    run["summary"]["provenanceConsistent"] = all(len(v) == 1 for v in metadata.values()) if successes else False
    run["summary"].update(grouped_summaries(run["rows"], summarize_retrieval))
    write_results(args.output, run, [{k: v for k, v in row.items() if k != "raw"} |
                                   {"ranking": row["raw"]["results"] if row["raw"] else []} for row in run["rows"]])
    return 0 if not any(r["error"] for r in run["rows"]) and run["summary"]["provenanceConsistent"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
