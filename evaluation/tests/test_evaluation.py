"""Metric and contract tests, deliberately not product quality measurements."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from eval_common import (evidence_matches, grouped_summaries, latency_summary, score_retrieval, validate_qa, validate_ranking)
from run_answer_eval import (extract_citation_claims, require_real_provider, resolve_citations,
                             score_answer_proxy, validate_answer_record)
from run_agent_eval import evaluate_fixture, evaluate_live
from compare_runs import comparability_errors
import run_retrieval_eval


def case(answerable=True):
    return {"id": "locking", "question": "How are locks acquired?", "category": "database", "language": "en", "split": "test",
            "answerable": answerable, "expected_sources": ["db.md"] if answerable else [],
            "expected_evidence": [{"source": "db.md", "section": "Locking", "anchor": "pessimistic locking acquires locks"},
                                  {"source": "db.md", "section": "Locking", "anchor": "optimistic locking detects conflicts"}] if answerable else [],
            "expected_points": [{"id": "conflict", "any_of": ["detects conflicts", "version check"]}] if answerable else []}


def hit(text="pessimistic locking acquires locks", chunk="1", source="db.md"):
    return {"chunkId": chunk, "source": source, "text": text, "finalRank": 1}


class MetricsTests(unittest.TestCase):
    def test_partial_recall_and_first_relevant_rank(self):
        rows = [hit("unrelated", "other"), hit(), hit("optimistic locking detects conflicts", "2")]
        actual = score_retrieval(case(), rows)
        self.assertEqual(actual["recallAt1"], 0)
        self.assertEqual(actual["recallAt3"], 1)
        self.assertEqual(actual["mrr"], .5)
        self.assertEqual(score_retrieval(case(), [hit()])["recallAt1"], .5)

    def test_filename_alone_does_not_prove_section_retrieval(self):
        self.assertEqual(score_retrieval(case(), [hit("wrong section")])["recallAt5"], 0)
        self.assertFalse(evidence_matches(hit(source="other.md"), case()["expected_evidence"][0]))
        self.assertTrue(evidence_matches(hit(source="classpath:knowledge-base/db.md"), case()["expected_evidence"][0]))

    def test_duplicate_hits_do_not_inflate_recall(self):
        self.assertEqual(score_retrieval(case(), [hit(), hit()])["recallAt5"], .5)

    def test_no_answer_scored_separately(self):
        self.assertTrue(score_retrieval(case(False), [])["noAnswerCorrect"])
        self.assertFalse(score_retrieval(case(False), [hit()])["noAnswerCorrect"])
        self.assertIsNone(score_retrieval(case(False), [])["mrr"])

    def test_subgroup_metrics_use_own_answerable_and_error_denominators(self):
        records = []
        configurations = ((True, "dev", "en", None, [hit(), hit("optimistic locking detects conflicts", "2")]),
                          (True, "test", "zh", "HTTP 503", []), (False, "test", "en", None, []))
        for answerable, split, language, error, hits in configurations:
            row = {**case(answerable), **score_retrieval(case(answerable), hits), "split": split, "language": language,
                   "error": error, "degraded": False, "durationMs": None if error else 2, "clientDurationMs": 3}
            records.append(row)
        result = grouped_summaries(records, run_retrieval_eval.summarize_retrieval)
        self.assertEqual(result["bySplit"]["test"]["count"], 2)
        self.assertEqual(result["bySplit"]["test"]["answerableCount"], 1)
        self.assertEqual(result["bySplit"]["test"]["recallAt5"], 0)
        self.assertEqual(result["bySplit"]["test"]["noAnswerAccuracy"], 1)
        self.assertEqual(result["bySplit"]["test"]["errorRate"], .5)
        self.assertIsNone(result["bySplit"]["dev"]["noAnswerAccuracy"])
        self.assertEqual(result["byLanguage"]["zh"]["serverLatency"]["count"], 0)
        self.assertEqual(run_retrieval_eval.summarize_retrieval(records)["recallAt5"], .5)

    def test_p95_uses_nearest_rank_and_empty_is_missing(self):
        self.assertEqual(latency_summary(list(range(1, 101)))["p95Ms"], 95)
        self.assertIsNone(latency_summary([])["meanMs"])

    def test_ranking_requires_unique_ids_and_provider_provenance(self):
        response = {"results": [hit()], "embeddingModel": "hash", "corpusHash": "abc", "mode": "vector", "durationMs": 1}
        validate_ranking(response, 5)
        response["results"].append(hit())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate_ranking(response, 5)
        response["results"] = [hit()]
        del response["corpusHash"]
        with self.assertRaisesRegex(ValueError, "provenance"):
            validate_ranking(response, 5)

    def test_schema_checks_exact_section_evidence_and_unique_questions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "db.md").write_text("## Locking\npessimistic locking acquires locks\noptimistic locking detects conflicts\n", encoding="utf-8")
            validate_qa([case()], path)
            invalid = case()
            invalid["expected_evidence"][0]["section"] = "Absent"
            with self.assertRaisesRegex(ValueError, "section"):
                validate_qa([invalid], path)
            duplicate = case()
            duplicate["id"] = "other-id"
            with self.assertRaisesRegex(ValueError, "duplicate question"):
                validate_qa([case(), duplicate], path)

    def test_answer_proxies_never_count_retrieved_sources_as_citations(self):
        result = score_answer_proxy(case(), "A version check detects conflicts.", [], [{"source": "db.md", "text": "some evidence"}])
        self.assertEqual(result["pointCoverageProxy"], 1)
        self.assertFalse(result["citationPresent"])
        self.assertIsNone(result["expectedCitationPrecisionProxy"])
        wrong = score_answer_proxy(case(), "Versions are unavailable", ["wrong.md"], [{"source": "db.md", "text": "evidence"}])
        self.assertEqual(wrong["expectedCitationPrecisionProxy"], 0)

    def test_real_answer_guard_rejects_mocks_and_missing_identity(self):
        for provider, model in (("local", "local-mock"), ("qwen", "mock"), ("unknown", "qwen-max"), (None, "qwen")):
            with self.assertRaises(ValueError):
                require_real_provider(provider, model)
        require_real_provider("dashscope", "qwen-max")

    def test_actual_chunk_marker_resolves_source_and_evidence(self):
        answer = "Optimistic locking detects conflicts. [chunk:abc-123]"
        context = [{"chunkId": "abc-123", "source": "db.md", "text": "optimistic locking detects conflicts"}]
        citations = extract_citation_claims(answer)
        self.assertEqual(citations, [{"chunkId": "abc-123"}])
        result = score_answer_proxy(case(), answer, citations, context)
        self.assertTrue(result["citationPresent"])
        self.assertTrue(result["resolvedCitationPresent"])
        self.assertEqual(result["expectedCitationPrecisionProxy"], 1)
        self.assertEqual(result["contextCitationPrecisionProxy"], 1)
        self.assertEqual(result["citedEvidenceAnchorPrecisionProxy"], 1)

    def test_cited_chunk_context_must_contain_expected_evidence_for_support_proxy(self):
        result = score_answer_proxy(case(), "A claim [chunk:abc-123]", [],
                                    [{"chunkId": "abc-123", "source": "db.md", "text": "unrelated paragraph in the right document"}])
        self.assertEqual(result["expectedCitationPrecisionProxy"], 1)
        self.assertEqual(result["contextCitationPrecisionProxy"], 1)
        self.assertEqual(result["citedEvidenceAnchorPrecisionProxy"], 0)

    def test_forged_or_prefix_chunk_id_does_not_resolve_via_supplied_filename(self):
        context = [{"chunkId": "abc-123", "source": "db.md", "text": "optimistic locking detects conflicts"}]
        for chunk_id in ("forged-id", "abc", "abc-123-more"):
            result = score_answer_proxy(case(), f"A claim [chunk:{chunk_id}]",
                                        [{"chunkId": chunk_id, "source": "db.md"}], context)
            self.assertTrue(result["citationPresent"])
            self.assertFalse(result["resolvedCitationPresent"])
            self.assertEqual(result["contextCitationPrecisionProxy"], 0)
            self.assertEqual(result["expectedCitationPrecisionProxy"], 0)
            self.assertEqual(result["citationResolution"][0]["status"], "unresolved")

    def test_duplicate_chunk_ids_are_ambiguous_even_with_identical_sources(self):
        context = [{"chunkId": "same-id", "source": "db.md", "text": "optimistic locking detects conflicts"},
                   {"chunkId": "same-id", "source": "db.md", "text": "different paragraph"}]
        result = score_answer_proxy(case(), "A claim [chunk:same-id]", [], context)
        self.assertEqual(result["citationResolution"][0]["status"], "ambiguous_chunk_id")
        self.assertEqual(result["contextCitationPrecisionProxy"], 0)

    def test_imported_chunk_declaration_cannot_forge_source_or_marker_presence(self):
        context = [{"chunkId": "real-id", "source": "db.md", "text": "optimistic locking detects conflicts"}]
        forged = resolve_citations("Claim [chunk:real-id]", [{"chunkId": "real-id", "source": "other.md"}], context)
        self.assertEqual(forged[0]["status"], "declared_source_mismatch")
        absent = score_answer_proxy(case(), "Uncited claim", [{"chunkId": "real-id", "source": "db.md"}], context)
        self.assertFalse(absent["citationPresent"])
        self.assertEqual(absent["contextCitationPrecisionProxy"], 0)
        self.assertEqual(absent["citationResolution"][0]["status"], "marker_absent")

    def test_repeated_markers_do_not_dilute_unmatched_marker_penalty(self):
        context = [{"chunkId": "real-id", "source": "db.md", "text": "optimistic locking detects conflicts"}]
        answer = "Claim [chunk:real-id] [chunk:real-id] [chunk:fake-id]"
        result = score_answer_proxy(case(), answer, extract_citation_claims(answer), context)
        self.assertEqual(result["citationCount"], 2)
        self.assertEqual(result["unresolvedCitationCount"], 1)
        self.assertEqual(result["contextCitationPrecisionProxy"], .5)
        self.assertEqual(result["citedEvidenceAnchorPrecisionProxy"], .5)

    def test_legacy_source_markers_work_but_colliding_paths_are_ambiguous(self):
        context = [{"source": "db.md", "text": "optimistic locking detects conflicts"},
                   {"source": "db.md", "text": "pessimistic locking acquires locks"}]
        self.assertEqual(score_answer_proxy(case(), "Claim [db.md]", ["db.md"], context)["contextCitationPrecisionProxy"], 1)
        context[1]["source"] = "different/path/db.md"
        result = resolve_citations("Claim [db.md]", ["db.md"], context)
        self.assertEqual(result[0]["status"], "ambiguous_source")

    def test_chunk_only_export_contract_and_malformed_marker(self):
        record = {"provider": "dashscope", "model": "qwen-max", "answer": "Claim [chunk:real-id]",
                  "citations": [{"chunkId": "real-id"}], "retrieved_context": [{"chunkId": "real-id", "source": "db.md", "text": "evidence"}],
                  "latencyMs": 1}
        validate_answer_record(record)
        self.assertEqual(resolve_citations("Claim [chunk: has spaces ]", [], record["retrieved_context"])[0]["status"], "malformed_chunk_id")
        record["retrieved_context"][0]["chunkId"] = []
        with self.assertRaisesRegex(ValueError, "chunkId"):
            validate_answer_record(record)

    def test_fixture_checks_last_result_but_never_planner_accuracy(self):
        fixture_case = {"fixture": "duplicate", "expected_outcome": {"success": False, "error_code": "REPEATED_TOOL_CALL"}}
        response = {"scenario": "duplicate", "results": [
            {"success": True, "error": None, "metadata": {}},
            {"success": False, "error": {"code": "REPEATED_TOOL_CALL"}, "metadata": {}}],
            "steps": 2, "underlyingCalls": 1, "retries": 0, "completion": False}
        result = evaluate_fixture(fixture_case, response)
        self.assertTrue(result["contractPass"])
        self.assertEqual(result["loopPreventionCount"], 1)
        self.assertIsNone(result["toolSelectionCorrect"])

    def test_live_selection_alias_is_accepted_but_forbidden_extra_is_not(self):
        row = {"expected_tools": ["webSearchPrime"], "expected_tools_alternatives": [["web_search_prime"]], "forbidden_tools": ["delete"]}
        response = {"provider": "dashscope", "model": "qwen-max", "tools": [{"name": "web_search_prime", "success": True}],
                    "steps": 1, "completed": True, "latencyMs": 10}
        self.assertTrue(evaluate_live(row, response)["toolSelectionCorrect"])
        response["tools"].append({"name": "delete", "success": True})
        self.assertFalse(evaluate_live(row, response)["toolSelectionCorrect"])

    def test_compare_rejects_dataset_corpus_and_provider_drift(self):
        baseline = {"schemaVersion": 1, "kind": "retrieval", "summary": {"provenanceConsistent": True},
                    "provenance": {"datasetHash": "d", "selectedIdsHash": "s", "corpusFilesHash": "c", "config": {"k": 5},
                                   "server": {"corpusHash": ["c"], "embeddingModel": ["hash"], "embeddingVersion": ["1"], "backend": ["memory"]}}}
        candidate = json.loads(json.dumps(baseline))
        self.assertEqual(comparability_errors(baseline, candidate), [])
        candidate["provenance"]["server"]["embeddingModel"] = ["real"]
        self.assertTrue(comparability_errors(baseline, candidate))
        self.assertEqual(comparability_errors(baseline, candidate, True), [])
        candidate["provenance"]["datasetHash"] = "changed"
        self.assertTrue(comparability_errors(baseline, candidate, True))


class RunnerIntegrationTests(unittest.TestCase):
    def test_http_errors_are_not_abstentions_and_warmup_is_separate(self):
        class Handler(BaseHTTPRequestHandler):
            calls = 0
            def log_message(self, *args):
                pass
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"provider":"fixture","configuration":{}}')
            def do_POST(self):
                Handler.calls += 1
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(503)
                self.end_headers()
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"APP_EVALUATION_KEY": "test-only"}), contextlib.redirect_stdout(io.StringIO()):
                path = Path(directory)
                dataset = path / "test.jsonl"
                dataset.write_text(json.dumps(case(False)) + "\n")
                output = path / "result.json"
                code = run_retrieval_eval.main(["--dataset", str(dataset), "--output", str(output), "--warmup", "2",
                                               "--base-url", f"http://127.0.0.1:{server.server_port}"])
                result = json.loads(output.read_text())
                self.assertEqual(code, 1)
                self.assertEqual(Handler.calls, 3)
                self.assertEqual(result["summary"]["count"], 1)
                self.assertEqual(len(result["warmup"]), 2)
                self.assertEqual(result["summary"]["noAnswerAccuracy"], 0)
                self.assertEqual(result["summary"]["errorRate"], 1)
                self.assertTrue(output.with_suffix(".csv").exists())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
