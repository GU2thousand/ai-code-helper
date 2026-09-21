#!/usr/bin/env python3
"""Evaluate bounded runtime fixtures, or separately imported real agent trajectories."""
import argparse
import math
from pathlib import Path

from eval_common import (SCHEMA_VERSION, common_arguments, file_hash, fraction, grouped_summaries, latency_summary,
                         make_client, mean, provenance, read_jsonl, select_rows, write_results)
from run_answer_eval import require_real_provider

FIXTURES = {"success", "timeout", "http_5xx_recovery", "http_5xx_exhausted", "malformed", "empty",
            "duplicate", "unauthorized", "provider_unavailable", "mcp_connection_failure", "loop",
            "user_cancel", "budget_exhausted", "per_tool_limit", "risk_denied"}


def validate_agent(rows):
    seen = set()
    for row in rows:
        if not isinstance(row.get("id"), str) or not row["id"] or row["id"] in seen:
            raise ValueError("Agent cases require unique ids")
        seen.add(row["id"])
        if not isinstance(row.get("question"), str) or not row["question"].strip():
            raise ValueError(f"{row['id']}: missing question")
        if row.get("split") not in ("dev", "test") or row.get("language") not in ("en", "zh"):
            raise ValueError(f"{row['id']}: invalid split/language")
        if row.get("fixture") not in FIXTURES:
            raise ValueError(f"{row['id']}: unknown controlled fixture")
        for key in ("expected_tools", "forbidden_tools"):
            if not isinstance(row.get(key), list) or any(not isinstance(t, str) or not t for t in row[key]):
                raise ValueError(f"{row['id']}: invalid {key}")
        alternatives = [row["expected_tools"]] + row.get("expected_tools_alternatives", [])
        if any(not isinstance(group, list) or any(not isinstance(t, str) or not t for t in group) for group in alternatives):
            raise ValueError(f"{row['id']}: invalid expected tool alternatives")
        if any(set(group) & set(row["forbidden_tools"]) for group in alternatives):
            raise ValueError(f"{row['id']}: expected and forbidden tools overlap")
        outcome = row.get("expected_outcome", {})
        if type(outcome.get("success")) is not bool or "error_code" not in outcome:
            raise ValueError(f"{row['id']}: expected_outcome requires success and error_code")
        if outcome["success"] != (outcome["error_code"] is None):
            raise ValueError(f"{row['id']}: inconsistent expected outcome")
    return rows


def evaluate_fixture(case, response):
    if response.get("scenario") != case["fixture"]:
        raise ValueError("Fixture response scenario does not match requested scenario")
    results = response.get("results")
    if not isinstance(results, list) or not results:
        raise ValueError("Fixture response must preserve runtime ToolResults")
    for result in results:
        if type(result.get("success")) is not bool or not isinstance(result.get("metadata"), dict):
            raise ValueError("Invalid runtime ToolResult contract")
        if not result["success"] and not isinstance((result.get("error") or {}).get("code"), str):
            raise ValueError("Failed tool result requires error code")
    terminal = results[-1]
    expected = case["expected_outcome"]
    code = (terminal.get("error") or {}).get("code")
    matches = terminal["success"] == expected["success"] and code == expected["error_code"]
    for field in ("steps", "underlyingCalls", "retries"):
        if not isinstance(response.get(field), int) or response[field] < 0:
            raise ValueError(f"Fixture response requires nonnegative {field}")
    return {"contractPass": matches, "terminalError": code, "toolResults": len(results),
            "successfulToolResults": sum(r["success"] for r in results),
            "toolCalls": response["underlyingCalls"], "agentSteps": response["steps"], "retries": response["retries"],
            "completion": bool(response.get("completion", terminal["success"])),
            "timeoutCount": sum((r.get("error") or {}).get("code") == "TOOL_TIMEOUT" for r in results),
            "loopPreventionCount": sum((r.get("error") or {}).get("code") in ("REPEATED_TOOL_CALL", "AGENT_STEP_LIMIT") for r in results),
            "toolSelectionCorrect": None}


def evaluate_live(case, response):
    require_real_provider(response.get("provider"), response.get("model"))
    tools = response.get("tools")
    if not isinstance(tools, list) or any(not isinstance(t, dict) or not isinstance(t.get("name"), str)
                                        or type(t.get("success")) is not bool for t in tools):
        raise ValueError("Live trajectory requires tools[{name,success,errorCode,retryCount}]")
    selected = {tool["name"] for tool in tools}
    allowed_sets = [set(case["expected_tools"])] + [set(group) for group in case.get("expected_tools_alternatives", [])]
    correct = selected in allowed_sets and not (selected & set(case["forbidden_tools"]))
    if type(response.get("completed")) is not bool or not isinstance(response.get("steps"), int) or response["steps"] < 0:
        raise ValueError("Live trajectory requires completed boolean and nonnegative steps")
    duration = response.get("latencyMs")
    if not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration < 0:
        raise ValueError("Live trajectory requires finite nonnegative latencyMs")
    return {"contractPass": None, "terminalError": response.get("errorCode"),
            "toolResults": len(tools), "successfulToolResults": sum(t["success"] for t in tools),
            "toolCalls": len(tools), "agentSteps": response["steps"],
            "retries": sum(t.get("retryCount", 0) for t in tools), "completion": response["completed"],
            "timeoutCount": sum(t.get("errorCode") == "TOOL_TIMEOUT" for t in tools),
            "loopPreventionCount": sum(t.get("errorCode") in ("REPEATED_TOOL_CALL", "AGENT_STEP_LIMIT") for t in tools),
            "toolSelectionCorrect": correct}


def summarize_agent(rows, scope):
    controlled = scope == "controlled_runtime"
    successful_requests = [row for row in rows if not row["error"]]
    tool_results = sum(r["toolResults"] for r in successful_requests)
    return {"count": len(rows), "uniqueFixtures": len({r["fixture"] for r in rows}) if controlled else None,
        "scope": scope, "requestErrorRate": fraction(len(rows) - len(successful_requests), len(rows)),
        "runtimeContractPassRate": mean([r["contractPass"] for r in rows]) if controlled else None,
        "toolSelectionAccuracy": mean([r["toolSelectionCorrect"] for r in rows]) if not controlled else None,
        "toolSelectionUnmeasuredReason": "No live model planner was invoked" if controlled else None,
        "toolExecutionSuccessRate": fraction(sum(r["successfulToolResults"] for r in successful_requests), tool_results),
        "averageToolCalls": mean([r["toolCalls"] for r in successful_requests]),
        "averageAgentSteps": mean([r["agentSteps"] for r in successful_requests]),
        "completionRate": mean([r["completion"] for r in rows]),
        "timeoutRate": fraction(sum(r["timeoutCount"] for r in successful_requests), tool_results),
        "loopPreventionCount": sum(r["loopPreventionCount"] for r in successful_requests),
        "retries": sum(r["retries"] for r in successful_requests),
        "latency": latency_summary([r["latencyMs"] for r in successful_requests]),
        "interpretation": "Injected failures intentionally lower tool success/completion rates; these are not production reliability estimates." if controlled else "Imported real trajectory results; preserve provider and deployment provenance for reproduction."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    common_arguments(parser, "agent_eval.jsonl", "agent-runtime-v1.json")
    parser.add_argument("--live-input", type=Path, help="Optional actual model trajectory JSONL; never synthesize from fixtures")
    args = parser.parse_args(argv)
    rows = select_rows(validate_agent(read_jsonl(args.dataset)), args.split, args.limit)
    imported = None
    if args.live_input:
        imported = {}
        for record in read_jsonl(args.live_input):
            require_real_provider(record.get("provider"), record.get("model"))
            if not record.get("id") or record["id"] in imported:
                raise ValueError("Live trajectory export requires unique ids")
            imported[record["id"]] = record
        if {case["id"] for case in rows} - set(imported):
            raise ValueError("Live trajectory export missing selected cases")
    scope = "live_agent_import" if imported is not None else "controlled_runtime"
    config = {"split": args.split, "limit": args.limit, "scope": scope, "baseUrl": args.base_url if imported is None else None}
    run = {"schemaVersion": SCHEMA_VERSION, "kind": "agent", "scope": scope,
           "provenance": provenance(args.dataset, rows, config), "rows": [],
           "method": "Controlled runtime fixtures execute fixed callables. They do not send dataset questions to a planner or measure model tool choice. Live trajectory imports are scored separately."}
    if args.live_input:
        run["provenance"]["trajectoryExportHash"] = file_hash(args.live_input)
    client = make_client(args, require_evaluation=True) if imported is None else None
    if client is not None:
        run["provenance"]["serverStatus"], _ = client.request("/api/evaluation/status")
    for case in rows:
        item = {"id": case["id"], "fixture": case["fixture"], "category": case["category"], "language": case["language"], "split": case["split"], "error": None}
        try:
            if imported is None:
                response, latency = client.request("/api/evaluation/agent", {"scenario": case["fixture"]})
                item.update(evaluate_fixture(case, response))
            else:
                response = imported[case["id"]]
                latency = response.get("latencyMs")
                item.update(evaluate_live(case, response))
            item.update({"latencyMs": latency, "raw": response})
        except Exception as error:
            item.update({"error": str(error), "contractPass": False if imported is None else None,
                         "toolSelectionCorrect": False if imported is not None else None, "raw": None,
                         "completion": False, "latencyMs": None})
        run["rows"].append(item)
    successful_requests = [row for row in run["rows"] if not row["error"]]
    if imported is not None:
        run["provenance"]["providersModels"] = sorted({r["raw"]["provider"] + "/" + r["raw"]["model"] for r in successful_requests})
    run["summary"] = summarize_agent(run["rows"], scope)
    run["summary"].update(grouped_summaries(run["rows"], lambda group: summarize_agent(group, scope)))
    write_results(args.output, run, [{k: v for k, v in row.items() if k != "raw"} for row in run["rows"]])
    return 1 if any(r["error"] or r.get("contractPass") is False for r in run["rows"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
