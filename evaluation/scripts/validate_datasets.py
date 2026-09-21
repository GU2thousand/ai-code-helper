#!/usr/bin/env python3
"""Validate schemas, corpus evidence, splits, and QA pairing without any service."""
import json
from collections import Counter
from eval_common import DATASETS, read_jsonl, validate_qa
from run_agent_eval import validate_agent


def main():
    retrieval = validate_qa(read_jsonl(DATASETS / "retrieval_eval.jsonl"))
    answers = validate_qa(read_jsonl(DATASETS / "answer_eval.jsonl"))
    agents = validate_agent(read_jsonl(DATASETS / "agent_eval.jsonl"))
    if retrieval != answers:
        raise ValueError("Retrieval and answer evaluation must retain paired identical fixed cases")
    if len(retrieval) < 100 or len(agents) < 50:
        raise ValueError("Roadmap requires at least 100 QA and 50 agent cases")
    print(json.dumps({"qaCount": len(retrieval), "answerable": sum(r["answerable"] for r in retrieval),
                      "noAnswer": sum(not r["answerable"] for r in retrieval),
                      "categories": dict(Counter(r["category"] for r in retrieval)),
                      "languages": dict(Counter(r["language"] for r in retrieval)),
                      "splits": dict(Counter(r["split"] for r in retrieval)),
                      "agentCases": len(agents), "uniqueRuntimeFixtures": len({r["fixture"] for r in agents})}, indent=2))


if __name__ == "__main__":
    main()
