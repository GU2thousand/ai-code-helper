import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// One preset per process keeps percentiles and rate-limit state interpretable.
const preset = __ENV.SCENARIO || 'chat10';
const presets = {
  chat10: { vus: 10, kind: 'chat' },
  chat50: { vus: 50, kind: 'chat' },
  chat100: { vus: 100, kind: 'chat' },
  rag: { vus: 10, kind: 'rag' },
  tools: { vus: 10, kind: 'tools' },
};
if (!presets[preset]) throw new Error(`Unknown SCENARIO: ${preset}`);
const selected = presets[preset];
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const thinkSeconds = Number(__ENV.THINK_SECONDS || '3.1');
if (!Number.isFinite(thinkSeconds) || thinkSeconds < 0) throw new Error('Invalid THINK_SECONDS');
const timeout = __ENV.REQUEST_TIMEOUT || '120s';
const saturationLimit = Number(__ENV.MAX_SATURATION_RATE || '1');
if (!Number.isFinite(saturationLimit) || saturationLimit < 0 || saturationLimit > 1) {
  throw new Error('MAX_SATURATION_RATE must be between 0 and 1');
}

const attempts = new Counter('ai_attempts');
const successes = new Counter('ai_successes');
const saturation = new Counter('ai_saturation_429');
const unexpectedErrors = new Counter('ai_unexpected_errors');
const saturationRate = new Rate('ai_saturation_rate');
const unexpectedErrorRate = new Rate('ai_unexpected_error_rate');
const admittedErrorRate = new Rate('ai_non_saturated_error_rate');
const successLatency = new Trend('ai_success_duration_ms', true);
const failureResponses = new Counter('ai_failure_responses');

// Fixed labels prevent a server-controlled code/body from becoming metric data.
const knownErrorCodes = [
  'AI_CAPACITY_REACHED', 'STREAM_CAPACITY_REACHED', 'AI_MEMORY_CAPACITY_REACHED',
  'AI_PROVIDER_CAPACITY', 'AI_PROVIDER_QUEUE_TIMEOUT', 'AI_PROVIDER_TIMEOUT',
  'AI_PROVIDER_CANCELLED', 'AI_UPSTREAM_ERROR', 'AI_STREAM_ERROR',
  'CONVERSATION_BUSY', 'STREAM_NOT_FOUND', 'INVALID_STREAM_OWNER',
  'INVALID_GUEST_SESSION', 'GUARDRAIL_REJECTED', 'VALIDATION_FAILED',
  'INVALID_JSON', 'INVALID_REQUEST', 'NOT_FOUND', 'METHOD_NOT_ALLOWED',
  'UNSUPPORTED_MEDIA_TYPE', 'INTERNAL_ERROR',
];
const errorCodeBuckets = [...knownErrorCodes, 'unknown', 'missing', 'invalid_payload', 'payload_too_large'];
const knownStatuses = [0, 200, 400, 401, 403, 404, 405, 408, 409, 410, 413, 415, 422, 429, 500, 502, 503, 504];
const statusBuckets = [...knownStatuses.map(String), 'other_1xx', 'other_2xx', 'other_3xx', 'other_4xx', 'other_5xx', 'other'];
const diagnosticSubmetrics = {};
for (const code of errorCodeBuckets) diagnosticSubmetrics[`ai_failure_responses{code:${code}}`] = [];
for (const status of statusBuckets) diagnosticSubmetrics[`ai_failure_responses{status:${status}}`] = [];

function errorCode(response) {
  if (typeof response.body !== 'string') return 'missing';
  if (response.body.length > 16384) return 'payload_too_large';
  try {
    const body = response.json();
    if (!body || typeof body !== 'object' || Array.isArray(body)) return 'invalid_payload';
    if (!Object.prototype.hasOwnProperty.call(body, 'code')) return 'missing';
    return knownErrorCodes.includes(body.code) ? body.code : 'unknown';
  } catch (_) { return 'invalid_payload'; }
}

function recordFailure(response) {
  const status = knownStatuses.includes(response.status) ? String(response.status)
    : (response.status >= 100 && response.status < 600 ? `other_${Math.floor(response.status / 100)}xx` : 'other');
  failureResponses.add(1, { status, code: errorCode(response) });
}

export const options = {
  noCookiesReset: true, // Each VU keeps its own signed guest cookie across iterations.
  scenarios: {
    [preset]: {
      executor: 'constant-vus',
      vus: selected.vus,
      duration: __ENV.DURATION || '30s',
      gracefulStop: __ENV.GRACEFUL_STOP || '130s',
    },
  },
  summaryTrendStats: ['min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Empty arrays expose bounded diagnostic submetrics without new pass/fail gates.
    ...diagnosticSubmetrics,
    ai_unexpected_error_rate: ['rate<0.01'],
    ai_non_saturated_error_rate: ['rate<0.01'],
    ai_saturation_rate: [`rate<=${saturationLimit}`],
    ai_successes: ['count>0'], // A run that is entirely rejected can never pass.
  },
};

const queries = {
  chat: [
    'Explain Java interface versus abstract class with a short example.',
    'Explain event loop microtasks in JavaScript.',
    'How should a Spring Boot REST API validate a request?',
  ],
  rag: [
    'How does Spring Boot dependency injection work?',
    'What is the difference between Vue ref and reactive?',
    'How can I prepare a Java backend interview project?',
    'How do I troubleshoot an SSE connection that closes before done?',
    'Compare transaction isolation and optimistic locking.',
  ],
  tools: [
    'Use web search to find recent official Spring Boot release notes and include source links.',
    'Use web search to find current official Java release documentation and include links.',
    'Search the web for official Vue documentation about computed properties and cite the result.',
  ],
};

let userId;

export function setup() {
  const response = http.get(`${baseUrl}/api/health`, {
    timeout: '10s', tags: { name: 'health', phase: 'setup' },
  });
  if (response.status !== 200) throw new Error(`Health check failed: HTTP ${response.status}`);
  const health = response.json();
  if (selected.kind === 'tools' &&
      (health.chatModel === 'local-mock' || !health.mcpConfigured)) {
    throw new Error('tools requires a real model and configured MCP; local-mock cannot prove tool execution');
  }
  // Retain only public runtime labels. No credentials, cookies, prompts or answers in output.
  console.log(JSON.stringify({
    scenario: preset,
    chatModel: health.chatModel,
    embeddingModel: health.embeddingModel,
    mcpConfigured: health.mcpConfigured,
    warning: selected.kind === 'tools'
      ? 'Check actual tool invocation counters; a chat answer alone does not prove an MCP call.'
      : 'Throughput applies only to the reported runtime and configured limits.',
  }));
  return { runId: `${Date.now()}-${Math.floor(Math.random() * 1e9)}` };
}

function ensureGuest() {
  if (userId) return;
  const response = http.post(`${baseUrl}/api/users/guest`, JSON.stringify({
    displayName: `load-vu-${exec.vu.idInTest}`,
  }), {
    headers: { 'Content-Type': 'application/json' }, timeout: '10s',
    tags: { name: 'guest', phase: 'identity' },
  });
  if (response.status !== 201) {
    exec.test.abort(`Guest setup failed for VU ${exec.vu.idInTest}: HTTP ${response.status}`);
    return;
  }
  userId = response.json('userId');
  if (!userId) exec.test.abort('Guest response omitted userId');
}

export default function (data) {
  ensureGuest();
  const memoryId = `load-${data.runId}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;
  const messages = queries[selected.kind];
  const message = messages[exec.scenario.iterationInTest % messages.length];
  const endpoint = selected.kind === 'rag' ? 'rag' : 'chat';
  const response = http.post(`${baseUrl}/api/ai/${endpoint}`, JSON.stringify({
    userId, memoryId, message,
  }), {
    headers: { 'Content-Type': 'application/json' }, timeout,
    tags: { name: `ai_${selected.kind}`, phase: 'workload' },
    responseCallback: http.expectedStatuses(200, 429),
  });
  attempts.add(1);
  successes.add(0); // Materialize zero so an all-429 run fails the success threshold.
  saturation.add(0);
  unexpectedErrors.add(0);
  const rejected = response.status === 429;
  saturationRate.add(rejected);
  if (rejected) {
    recordFailure(response);
    saturation.add(1);
    unexpectedErrorRate.add(false);
  } else {
    let valid = false;
    if (response.status === 200) {
      try {
        const body = response.json();
        valid = body.memoryId === memoryId && typeof body.answer === 'string' &&
          body.answer.trim().length > 0 &&
          (selected.kind !== 'rag' || Array.isArray(body.sources));
      } catch (_) { /* Malformed JSON is a failed request even when HTTP status is 200. */ }
    }
    unexpectedErrorRate.add(!valid);
    admittedErrorRate.add(!valid);
    if (valid) {
      successes.add(1);
      successLatency.add(response.timings.duration);
    } else {
      recordFailure(response);
      unexpectedErrors.add(1);
    }
  }
  // 3.1s minimum avoids the default 20 starts/minute OWNER limit in a 30s run.
  // Global 300 starts/minute and 64 concurrent admission limits remain active.
  sleep(thinkSeconds);
}

export function handleSummary(data) {
  const counts = (tag, buckets) => Object.fromEntries(buckets.flatMap((bucket) => {
    const metric = data.metrics[`ai_failure_responses{${tag}:${bucket}}`];
    return metric && metric.values.count > 0 ? [[bucket, metric.values.count]] : [];
  }));
  const output = JSON.stringify({
    scenario: preset,
    baseUrl,
    units: { ai_success_duration_ms: 'milliseconds', counter_rate: 'requests/second' },
    failure_classification: {
      http_status_counts: counts('status', statusBuckets),
      error_code_counts: counts('code', errorCodeBuckets),
    },
    notes: [
      'Only successful AI responses contribute to ai_success_duration_ms.',
      'ai_attempts and ai_successes expose count and throughput; setup HTTP is excluded.',
      '429 is saturation, separately reported from unexpected error rate.',
      'Failure classifications contain only allowlisted codes/statuses and fixed fallback buckets; HTTP 503 remains unexpected.',
      'This HTTP workload does not measure first-token latency; use sse_load.py.',
    ],
    ...data,
  }, null, 2);
  return { stdout: `${output}\n`, [__ENV.SUMMARY_PATH || `summary-${preset}.json`]: output };
}
