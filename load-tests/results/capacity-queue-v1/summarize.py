#!/usr/bin/env python3
"""Summarize raw paired evidence without changing original client classifications."""
import argparse, json
from pathlib import Path

def main():
    p=argparse.ArgumentParser(); p.add_argument("results",type=Path); args=p.parse_args()
    root=args.results; manifest=json.loads((root/"manifest.json").read_text()); rows=[]
    for scenario in manifest["workload"]["scenarios"]:
        for profile in ("off","on"):
            path=root/profile
            if not (path/f"{scenario}.json").exists(): continue
            data=json.loads((path/f"{scenario}.json").read_text())
            resources=json.loads((path/f"{scenario}-resources-summary.json").read_text())
            run=next(r for r in manifest["profiles"][profile]["runs"] if r["scenario"]==scenario)
            row=dict(scenario=scenario,profile=profile,max_queued=manifest["profiles"][profile]["max_queued"],client_exit_code=run["exit_code"])
            if scenario.startswith("sse"):
                requests=data["requests"]; rates=data["throughput"]
                row.update(attempts=requests["attempted"],successes=requests["succeeded"],http_429=requests["http_429"],unexpected_errors=requests["non_saturation_failures"],
                    unexpected_error_rate=requests["unexpected_error_rate"],non_saturated_error_rate=requests["non_saturated_error_rate"],
                    error_code_counts=requests["error_code_counts"],error_counts=requests["error_counts"],http_status_counts=requests["http_status_counts"],
                    success_p95_ms=data["latency_ms"]["successful_request_duration_ms"]["p95"],success_p99_ms=data["latency_ms"]["successful_request_duration_ms"]["p99"],
                    ttft_p95_ms=data["latency_ms"]["ttft_ms"]["p95"],ttft_p99_ms=data["latency_ms"]["ttft_ms"]["p99"],
                    ticket_to_first_content_p95_ms=data["latency_ms"]["ticket_to_first_content_ms"]["p95"],
                    successes_per_second=rates["completed_streams_per_second"],client_max_active_streams=data["streams"]["max_active"],client_final_active_streams=data["streams"]["active"],worker_setup_failures=data["workers"]["setup_failures"])
            else:
                m=data["metrics"]; count=lambda key:m.get(key,{}).get("values",{}).get("count",0)
                rate=lambda key:m.get(key,{}).get("values",{}).get("rate")
                row.update(attempts=count("ai_attempts"),successes=count("ai_successes"),http_429=count("ai_saturation_429"),unexpected_errors=count("ai_unexpected_errors"),
                    unexpected_error_rate=rate("ai_unexpected_error_rate"),non_saturated_error_rate=rate("ai_non_saturated_error_rate"),
                    error_code_counts=data["failure_classification"]["error_code_counts"],http_status_counts=data["failure_classification"]["http_status_counts"],
                    success_p95_ms=m["ai_success_duration_ms"]["values"]["p(95)"],success_p99_ms=m["ai_success_duration_ms"]["values"]["p(99)"],ttft_p95_ms=None,
                    successes_per_second=rate("ai_successes"))
            row.update(provider_peak=resources["observed_peak_gauges"]["ai_provider_in_flight"],queue_peak=resources["observed_peak_gauges"]["ai_provider_queued"],
                server_stream_peak=resources["observed_peak_gauges"]["active_sse_streams"],final_gauges=resources["end_gauges"],drained_to_zero=resources["drained_to_zero"],
                container_peak_cpu_percent=resources["peak_container_cpu_percent"],java_peak_rss_bytes=resources["peak_container_java_rss_bytes"],
                client_peak_cpu_percent=resources["peak_client_interval_cpu_percent"],client_peak_rss_bytes=resources["peak_client_rss_bytes"],sampling=resources["sampling"],sampling_errors=resources["errors"])
            rows.append(row)
    result=dict(source_revision=manifest["source_revision"],complete=manifest.get("complete",False),profiles=["off","on"],rows=rows,
        notes=["Same updated source/image; off has maxQueued=0, on has maxQueued=48; physical capacity remains 16.",
               "HTTP 503 and SSE errors remain unexpected failures; original exits and error code counts are retained.",
               "Protected status preflight initializes retrieval before each sample; no chat/SSE warmup, so short local-mock samples retain request-path JIT effects. Sampled peaks are lower bounds.",
               "TTFT milestones may include attempts failing later; success latency includes successful requests only."])
    (root/"comparison.json").write_text(json.dumps(result,indent=2)+"\n")
    lines=["| Workload | Queue | Success / attempts | 429 | Unexpected | Success p95 ms | TTFT p95 ms | Provider / queue peak | End zero | Exit |",
           "|---|---|---:|---:|---:|---:|---:|---:|---|---:|"]
    fmt=lambda x:"—" if x is None else f"{x:.1f}"
    for r in rows:
        lines.append(f'| {r["scenario"]} | {r["profile"]} | {r["successes"]}/{r["attempts"]} | {r["http_429"]} | {r["unexpected_errors"]} | {fmt(r["success_p95_ms"])} | {fmt(r["ttft_p95_ms"])} | {r["provider_peak"]:g} / {r["queue_peak"]:g} | {"yes" if r["drained_to_zero"] else "NO"} | {r["client_exit_code"]} |')
    lines+=["","Failure code counts (raw client classification):",""]
    for r in rows:
        if r["error_code_counts"]: lines.append(f'- {r["profile"]}/{r["scenario"]}: '+", ".join(f"{k}={v}" for k,v in r["error_code_counts"].items()))
    (root/"comparison.md").write_text("\n".join(lines)+"\n")
    print("\n".join(lines))

if __name__=="__main__": main()
