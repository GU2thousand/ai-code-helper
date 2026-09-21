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
      unexpectedErrors.add(1);
    }
  }
  // 3.1s minimum avoids the default 20 starts/minute OWNER limit in a 30s run.
  // Global 300 starts/minute and 64 concurrent admission limits remain active.
  sleep(thinkSeconds);
}

export function handleSummary(data) {
  const output = JSON.stringify({
    scenario: preset,
    baseUrl,
    units: { ai_success_duration_ms: 'milliseconds', counter_rate: 'requests/second' },
    notes: [
      'Only successful AI responses contribute to ai_success_duration_ms.',
      'ai_attempts and ai_successes expose count and throughput; setup HTTP is excluded.',
      '429 is saturation, separately reported from unexpected error rate.',
      'This HTTP workload does not measure first-token latency; use sse_load.py.',
    ],
    ...data,
  }, null, 2);
  return { stdout: `${output}\n`, [__ENV.SUMMARY_PATH || `summary-${preset}.json`]: output };
}
