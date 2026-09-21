#!/usr/bin/env python3
"""Run all four retrieval pipelines sequentially with identical fixed inputs."""
import argparse
from pathlib import Path
from eval_common import ROOT
import run_retrieval_eval


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "evaluation/results")
    args, passthrough = parser.parse_known_args()
    if any(arg in ("--mode", "--output") or arg.startswith(("--mode=", "--output=")) for arg in passthrough):
        parser.error("Use --output-dir; benchmark sets --mode and --output for each pipeline")
    failures = 0
    for mode in ("vector", "lexical", "hybrid", "hybrid_rerank"):
        name = "baseline-v1.json" if mode == "vector" else mode + "-v1.json"
        failures += run_retrieval_eval.main([*passthrough, "--mode", mode, "--output", str(args.output_dir / name)])
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
