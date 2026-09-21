#!/usr/bin/env python3
"""Exercise the Compose application and telemetry without external provider keys."""
import argparse
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frontend', default='http://127.0.0.1:' + os.getenv('FRONTEND_PORT', '5173'))
    parser.add_argument('--backend', default='http://127.0.0.1:' + os.getenv('BACKEND_PORT', '8081'))
    parser.add_argument('--prometheus', default='http://127.0.0.1:' + os.getenv('PROMETHEUS_PORT', '9090'))
    parser.add_argument('--tempo', default='http://127.0.0.1:' + os.getenv('TEMPO_PORT', '3200'))
    parser.add_argument('--grafana', default='http://127.0.0.1:' + os.getenv('GRAFANA_PORT', '3000'))
    parser.add_argument('--output', type=Path, default=Path('output/ai-system/compose-smoke.json'))
    args = parser.parse_args()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    checks = []

    def request(url, body=None, headers=None, decode=True):
        headers = {'Accept': 'application/json', **(headers or {})}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
        with client.open(req, timeout=30) as response:
            text = response.read().decode()
            return json.loads(text) if decode else text

    def eventually(check, seconds=60):
        deadline = time.monotonic() + seconds
        while True:
            try:
                value = check()
                assert value, 'Response has not become ready'
                return value
            except Exception:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)

    def passed(name, detail):
        checks.append({'name': name, 'passed': True, 'detail': detail})
        print(name + ': passed', flush=True)

    try:
        health = eventually(lambda: request(args.frontend + '/api/health'))
        assert health['status'] == 'UP', health
        assert health['chatProvider'] == 'local' and health['chatModel'] == 'local-mock', health
        passed('Nginx proxies the offline backend', health)
        assert '<html' in request(args.frontend, decode=False)
        passed('Production frontend serves HTML', True)

        guest = request(args.frontend + '/api/users/guest', {})
        assert guest['userId']
        trace_id = uuid.uuid4().hex
        rag = request(args.frontend + '/api/ai/rag', {
            'memoryId': 'compose-rag-' + uuid.uuid4().hex,
            'message': 'How do optimistic and pessimistic database locks differ?',
        }, {'traceparent': '00-' + trace_id + '-' + uuid.uuid4().hex[:16] + '-01'})
        assert rag.get('answer'), rag
        assert rag.get('sources') and all(source.get('chunkId') for source in rag['sources']), rag
        indexed_health = request(args.frontend + '/api/health')
        assert indexed_health['knowledgeSegments'] > 0, indexed_health
        passed('Authenticated RAG through the reverse proxy', {'traceId': trace_id})

        evaluation_key = os.getenv('APP_EVALUATION_KEY')
        hybrid_trace_id = None
        if evaluation_key:
            hybrid_trace_id = uuid.uuid4().hex
            retrieval = request(args.backend + '/api/evaluation/retrieval', {
                'question': 'How do optimistic and pessimistic database locks differ?',
                'mode': 'hybrid', 'k': 5,
            }, {'X-Evaluation-Key': evaluation_key,
                'traceparent': '00-' + hybrid_trace_id + '-' + uuid.uuid4().hex[:16] + '-01'})
            assert retrieval['backend'] == 'pgvector' and not retrieval['degraded'], retrieval
            assert retrieval['results'], retrieval
            passed('Hybrid query actually uses PostgreSQL without fallback', {
                'backend': retrieval['backend'], 'resultCount': len(retrieval['results']),
            })

        ticket = request(args.frontend + '/api/ai/chat/streams', {
            'memoryId': 'compose-sse-' + uuid.uuid4().hex, 'message': 'Explain a Java interface.',
        })
        stream = request(args.frontend + '/api/ai/chat/streams/' + ticket['streamId'], decode=False)
        assert 'event:done' in stream.replace('event: ', 'event:'), stream[:500]
        passed('Authenticated SSE reaches completion through Nginx', True)

        metrics = request(args.backend + '/actuator/prometheus', decode=False)
        for metric in ['ai_requests_total', 'ai_request_duration_seconds_bucket', 'retrieval_duration_seconds_bucket']:
            assert metric in metrics, 'Missing metric: ' + metric
        passed('Actuator exports request and retrieval histogram metrics', True)

        def scrape_ready():
            value = request(args.prometheus + '/api/v1/query?' + urllib.parse.urlencode({'query':'up{job="ai-code-helper"}'}))
            result = value['data']['result']
            return result and result[0]['value'][1] == '1'
        eventually(scrape_ready)
        passed('Prometheus successfully scrapes the running backend', True)

        dashboard_path = Path(__file__).parent / 'grafana/dashboards/ai-engineering.json'
        dashboard = json.loads(dashboard_path.read_text())
        for panel in dashboard['panels']:
            for target in panel.get('targets', []):
                query = target['expr'].replace('$__rate_interval', '1m')
                value = request(args.prometheus + '/api/v1/query?' + urllib.parse.urlencode({'query': query}))
                assert value['status'] == 'success', value
        passed('Every provisioned dashboard query parses in Prometheus', len(dashboard['panels']))

        def span_names(value):
            names = set()
            if isinstance(value, dict):
                if 'spanId' in value and 'name' in value:
                    names.add(value['name'])
                for child in value.values():
                    names.update(span_names(child))
            elif isinstance(value, list):
                for child in value:
                    names.update(span_names(child))
            return names

        def complete_trace(selected_trace, expected_names):
            names = span_names(request(args.tempo + '/api/traces/' + selected_trace))
            assert expected_names <= names, sorted(names)
            return sorted(names)

        names = eventually(lambda: complete_trace(trace_id, {'ai.rag', 'retrieval', 'lexical', 'llm'}))
        passed('Actual request and stage spans exported through Collector and stored in Tempo', {
            'traceId': trace_id, 'spanNames': names,
        })
        if hybrid_trace_id:
            hybrid_names = eventually(lambda: complete_trace(hybrid_trace_id, {'retrieval', 'lexical', 'vector'}))
            passed('Explicit hybrid retrieval trace contains lexical and vector stages', {
                'traceId': hybrid_trace_id, 'spanNames': hybrid_names,
            })

        password = os.getenv('GRAFANA_PASSWORD', 'local-development-only')
        authorization = base64.b64encode(('admin:' + password).encode()).decode()
        provisioned = request(args.grafana + '/api/dashboards/uid/ai-engineering', headers={'Authorization':'Basic ' + authorization})
        assert len(provisioned['dashboard']['panels']) == len(dashboard['panels'])
        passed('Grafana provisions the dashboard', provisioned['dashboard']['title'])
    except Exception as error:
        checks.append({'name': 'Compose smoke failure', 'passed': False, 'detail': str(error)})
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'provider': 'local-mock', 'realModelValidated': False, 'checks': checks}, indent=2) + '\n')


if __name__ == '__main__':
    main()
