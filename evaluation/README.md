# Evaluation

This directory separates measured retrieval behavior, deterministic runtime contracts, and real-provider answer/agent quality. Python 3.10+ is sufficient; the runners use only the standard library. None of the committed local measurements establishes Qwen answer accuracy or real MCP availability.

## Fixed datasets and authored evidence

`datasets/retrieval_eval.jsonl` and `datasets/answer_eval.jsonl` contain the **same 112 unique questions**: 104 answerable questions and 8 no-answer questions. The eight categories are Java, Spring, databases, concurrency, APIs, system design, algorithms, and project operations. English and Chinese cases carry explicit language and dev/test labels. These are authored development benchmarks, not an independent public leaderboard or a statistically representative sample of production traffic. Keep test cases fixed; tune on dev cases, then report the held-out test split separately.

Eight substantive reference documents expand the original three knowledge-base documents. Every answerable case contains a stable source filename, exact Markdown section, and distinctive verbatim evidence anchor. The validator checks that each anchor occurs **inside its declared section**, not merely somewhere in the file. Point groups contain alternative expressions of one concept; they support a clearly labeled lexical coverage proxy, not an automated proof of correctness. No-answer cases concern facts the corpus cannot establish, including private deployment state and future changes. They intentionally test abstention even when a query shares vocabulary with a relevant topic.

`datasets/agent_eval.jsonl` has 60 unique bilingual user intents, with expected/forbidden tool sets and 15 controlled failure/success fixtures repeated four times each. Expected tools use actual registered or allowlisted names. Web tool aliases are acceptable alternative sets. Questions and fault injection descriptions are separate: a user question does not itself cause a timeout or outage. Fixture repetitions do not constitute 60 independent planner tests.

Validate all datasets and metric contracts without running the application:

```bash
python3 evaluation/scripts/validate_datasets.py
python3 -m unittest discover -s evaluation/tests -v
```

## Reproduce local retrieval and runtime measurements

Start the backend in a separate terminal, explicitly enabling the evaluation API and setting a **local evaluation access key**. This key only protects the diagnostic endpoints; no paid provider credentials are required for local hash embeddings or deterministic runtime fixtures.

```bash
APP_EVALUATION_ENABLED=true APP_EVALUATION_KEY=local-evaluation-only \
  mvn -f backend/pom.xml spring-boot:run
```

The default application port is 8081. Pass `--base-url` if using another port. `/api/evaluation/status`, `/retrieval`, and `/agent` require `X-Evaluation-Key`; runners read its value from `APP_EVALUATION_KEY`, or the variable named by `--evaluation-key-env`. Do not put secrets in command-line arguments. The endpoints remain disabled by default.

Run all four complete retrieval benchmarks with one command, then the runtime suite:

```bash
APP_EVALUATION_KEY=local-evaluation-only python3 evaluation/scripts/run_benchmark.py
APP_EVALUATION_KEY=local-evaluation-only python3 evaluation/scripts/run_agent_eval.py
```

The benchmark produces `results/baseline-v1.json` (vector), `lexical-v1.json`, `hybrid-v1.json`, and `hybrid_rerank-v1.json`, each with a matching per-query CSV. Existing paths are overwritten; use `--output-dir evaluation/results/my-run` to retain additional experiments. The runtime suite writes `agent-runtime-v1.json` and CSV. To measure one mode or the held-out split:

```bash
APP_EVALUATION_KEY=local-evaluation-only python3 evaluation/scripts/run_retrieval_eval.py \
  --mode hybrid_rerank --split test --output evaluation/results/hybrid-rerank-test.json
python3 evaluation/scripts/compare_runs.py \
  evaluation/results/baseline-v1.json evaluation/results/hybrid-v1.json
```

`--limit` is for smoke checks and is recorded in provenance. It does not produce a full benchmark. A run with an HTTP/contract error returns a nonzero exit code while preserving its result artifacts; API failures cannot count as successful no-answer abstentions. The runtime fixture endpoint accepts only known fixture names and never caller-supplied code or URLs.

## Metric definitions and provenance

- **Recall@k**: fraction of expected evidence anchors covered by top-k chunks, averaged across answerable questions. A correct filename with an unrelated section earns zero. Repeated retrieval of the same evidence does not inflate recall. **MRR** uses the first chunk matching any expected evidence anchor; missed questions contribute zero.
- **No-answer accuracy**: fraction of no-answer questions returning zero hits. **False-positive rate** counts successful responses returning hits; HTTP errors are reported separately and count as failed abstentions. Top-k ranking alone generally cannot abstain; low values here expose missing or weak rejection thresholds.
- **Latency**: server retrieval duration and HTTP wall-clock duration are separate. Three warmup requests per mode are retained outside the metric denominator. Mean, p50, p95, and p99 use measured values; percentiles use nearest rank. Server latency excludes failed requests; client latency includes them.
- **Controlled runtime**: contract pass means the last actual `ToolResult` matches the expected success/error code. Reports include actual tool result success, underlying calls, retries, steps, completion, timeouts, and loop prevention. Injected failures intentionally lower success/completion metrics; these are not estimates of production reliability. Tool-selection accuracy is `null` because fixtures do not invoke the planner.
- **Provenance**: dataset SHA256, selected IDs hash, corpus file hashes, script hashes, Git commit/dirty state, requested configuration, server status, embedding identity, index corpus hash, backend, degradation, warnings, and raw rankings are preserved. Local corpus file hashes and server index hashes use different explicitly recorded algorithms; they are not asserted to be equal.

Retrieval and agent result summaries additionally include `bySplit`, `byLanguage`, and `byCategory`; every subgroup recomputes its own denominators and latency distribution. For example, `summary.bySplit.test.recallAt5` measures only answerable test questions, with test no-answer behavior at `summary.bySplit.test.noAnswerAccuracy`. A subgroup without no-answer cases reports `null`, not a perfect score. Agent split summaries still repeat controlled fixtures and do not become held-out planner evaluations. No corpus or case is modified to produce these breakdowns.

To pin published results to a clean implementation commit, commit code first, rebuild the server from that commit, and write **all** runs to an ignored or external directory (`run_benchmark.py --output-dir ...`; `run_agent_eval.py --output ...`). Copy artifacts into `evaluation/results` only after every run finishes, then commit the evidence separately. Otherwise a preceding run's generated tracked artifacts can make a later run's Git dirty flag true. The artifact's `gitCommit` identifies measured implementation code; the later evidence commit need not be the same SHA.

`compare_runs.py` rejects differing dataset subsets, corpora, retrieval k, and provider/backend identity. An explicit `--allow-provider-change` permits cross-provider experiments while retaining the flag in the report. Keep the effective server configuration in each artifact under review: threshold, chunking, candidates, and reranker choices affect comparability. Raw score scales differ by mode and should not be compared as probabilities. Cache state, JVM warmup, query order, host load, and sequential pipeline order affect timings; one local run cannot prove a statistically significant latency improvement.

The local hash embedding model is a deterministic development fallback. The local lexical reranker is a heuristic, not a learned cross-encoder. Corpus and test cases were authored together, so overlap inflates apparent retrieval capability relative to unseen user phrasing. Report split, language, model, corpus version, and mode alongside all numbers. A benchmark table must be generated from actual result files, never filled with target numbers. See the root README for the measured results and remaining validation boundaries.

## Real answer evaluation

Real answer evaluation is deliberately unavailable with `local-mock`. This upgrade does not require external credentials, so real Qwen answer quality and LLM-judge metrics remain **unmeasured** until real exports or a configured provider are supplied.

With an already configured real backend, `--live` verifies `/api/health`, rejects local/mock identity before generating answers, creates a guest session, and uses a unique memory ID for each question. A default 3.1-second inter-request pause respects the default per-owner admission budget. This performs real model requests and may consume provider quota.

```bash
python3 evaluation/scripts/run_answer_eval.py --live --split test \
  --output evaluation/results/real-answer-test.json
```

Alternatively, import a JSONL export containing one record per selected question:

```json
{"id":"<dataset-id>","provider":"dashscope","model":"<actual-model>","answer":"<actual-generated-answer> [chunk:actual-chunk-id]","citations":[{"chunkId":"actual-chunk-id"}],"retrieved_context":[{"chunkId":"actual-chunk-id","source":"<retrieved-source.md>","text":"<actual-context>"}],"latencyMs":1250.0}
```

```bash
python3 evaluation/scripts/run_answer_eval.py --input /path/to/real-answers.jsonl
```

The illustrative record is a schema, not measurement evidence. Exported identity is declarative: preserve the provider's own raw request/response and deployment configuration for an audit. Mock, fixture, missing, and unknown provider identities are rejected. A source returned by retrieval is **context**, not proof of an answer citation. Current `[chunk:ID]` answer markers resolve through an exact, unique `chunkId` in returned source/context records. Unmatched IDs, ambiguous duplicate IDs, absent answer markers, and conflicting exported source claims receive no credit and remain in the citation denominator. Repeated identical markers are deduplicated. Legacy literal `[source.md]` markers remain supported; multiple chunks from one file are valid, but basename collisions across different source paths are ambiguous. Both live and imported records use the same resolver, so an exported filename cannot legitimize a forged chunk ID. Other citation syntaxes are not automatically scored. Live source excerpts may be truncated by the API, so they are not necessarily the full model context.

The runner separately reports whether an answer contains a citation marker and whether any marker resolves. It reports concept coverage, expected-source citation precision, contextual citation resolution precision, and abstention-phrase proxies. `citedEvidenceAnchorPrecisionProxy` additionally requires the resolved cited context to contain a dataset evidence anchor from the expected source; a correct document/ID with an unrelated paragraph earns zero. Truncated context may lower this proxy. Per-citation status and matched context indices are retained for audit. A negated or contradictory answer can still match keywords or cite real context, so these proxies cannot establish semantic correctness, faithfulness, citation entailment, or hallucination rates; those top-level metrics remain `null`.

For optional external judging, use `--judge-command '/path/to/judge-adapter' --judge-model 'provider/model-version'`. The executable receives a JSON object on stdin and returns JSON containing `correctness`, `groundedness`, `citation_correctness`, `hallucination` (0–1; citation score may be null), and an evidence-based `rationale`. It is executed directly, without a shell. Adapter credentials belong in its environment. Full judge input/output is saved per case and failures reduce reported judge coverage. Judge scores are separate uncalibrated estimates; validate them against human ratings before calling them accuracy or hallucination rates. The adapter must treat questions, answers, and retrieved text as data, not executable instructions.

## Real agent selection evaluation

The runtime suite never represents injected outcomes as live planner behavior. To score real tool selection separately, import actual trajectories with `--live-input`:

```json
{"id":"<agent-dataset-id>","provider":"dashscope","model":"<actual-model>","tools":[{"name":"webSearchPrime","success":true,"errorCode":null,"retryCount":0}],"steps":1,"completed":true,"latencyMs":850.0}
```

```bash
python3 evaluation/scripts/run_agent_eval.py --live-input /path/to/real-trajectories.jsonl \
  --output evaluation/results/real-agent.json
```

Selection correctness requires an exact acceptable tool set and no forbidden tools. Empty expected sets require no tool calls. This does not check argument correctness or evidence sufficiency; retain raw trajectories for review. Controlled-runtime and live-agent runs have different scopes and cannot be compared as equivalent measurements.

---

# 中文说明

本目录明确区分三类证据：检索实测、受控工具运行时契约、真实模型回答与工具选择质量。脚本只使用 Python 标准库。无需配置 Qwen/MCP 密钥即可完成本地检索和故障注入验证；本地结果不代表真实 Qwen 准确率或外部 MCP 可靠性。

## 固定数据集与语料

检索与回答文件使用同一组 **112 个不同问题**，不是 224 个不同问题：104 个有答案问题、8 个知识库无法回答的问题，覆盖 Java、Spring、数据库、并发、API、系统设计、算法和项目文档。每条记录包含语言和 dev/test 划分。新增 8 篇有实质内容的参考文档，保留原有 3 篇。每个有答案问题使用文件名、确切章节标题与原文证据锚点；校验器检查锚点是否位于声明章节内。关键词概念组只用于覆盖率代理指标，不能证明答案正确。

Agent 数据集含 60 个中英请求意图，对应 15 类受控故障/成功场景，每类重复 4 次。意图中的工具期望与故障注入描述分开保存。重复执行同一故障场景不等于测量了 60 次真实模型选工具。无答案问题测试私有部署状态、未来变更等语料不能支持的事实，即使查询包含知识库相关词，也应能拒绝无依据回答。

## 运行与结果

上面的命令可直接使用。后端默认端口为 8081，评估接口需要显式设置 `APP_EVALUATION_ENABLED=true` 和本地访问密钥 `APP_EVALUATION_KEY`。`local-evaluation-only` 是示例本地诊断密钥，不是外部模型凭据。脚本从环境变量读取密钥，不要求把密钥写入参数。

`validate_datasets.py` 和 Python 单元测试无需启动服务。`run_benchmark.py` 一条命令依次测量 vector、lexical、hybrid、hybrid_rerank，分别输出逐问题 JSON/CSV。`run_agent_eval.py` 运行受控契约场景。`--split test` 只测固定测试集；`--limit` 仅供冒烟检查，不是完整基线。使用 `--output-dir` 或 `--output` 保存新运行，避免覆盖旧实验。

Recall@1/3/5 按命中的证据锚点比例计算，只有文件名相同但章节不相关不能得分；MRR 使用首个相关片段的倒数排名。无答案准确率单独计算，HTTP 错误不能当作成功拒答。服务端检索延迟与客户端耗时分开保存，预热不计入结果分母。每次运行保留数据集、语料、脚本、Git、配置、模型、后端、降级状态与原始排名，以便复现和审查。

检索与 Agent 汇总额外提供 `bySplit`、`byLanguage`、`byCategory`，每组独立计算分母和延迟；例如 `summary.bySplit.test.recallAt5` 只计算测试组有答案题目。没有无答案题目的分组返回 `null`，不会自动算满分。Agent 测试划分仍是受控场景重复，不能视为真实规划器的独立测试。发布可追溯结果时，先提交实现并从该提交重建服务，把全部结果写入忽略目录或仓库外，全部结束后再复制到 `evaluation/results` 并单独提交证据，避免较早运行的结果文件影响后续运行的 Git 干净状态。

运行时契约通过率检查实际最终 `ToolResult` 的成功状态或错误码，同时记录调用数、重试、步数、超时和循环拦截。故障集故意包含失败，因此其中的工具成功率不是线上可靠性估计。受控模式的工具选择准确率保持 `null`，因为没有调用真实规划模型。

`compare_runs.py` 拒绝把不同数据集子集、语料、k 值和不同模型/后端的结果混为同一实验；跨模型实验需显式使用 `--allow-provider-change`。仍需审查有效服务端配置，避免把阈值、分块、候选数量等变化误当成单独算法改进。单次本地顺序运行受缓存、JVM 预热和主机负载影响，不能证明统计显著的延迟提升。

## 尚未测量的边界

用户不配置外部服务时，真实回答准确率、可信性、幻觉率和真实工具选择效果均保持未测。`run_answer_eval.py` 会拒绝 local/mock/未知来源答案。可在将来使用已配置的真实后端 `--live`，或导入保留模型来源的真实答案；真实调用可能产生服务费用。每道题使用独立 memoryId，默认请求间隔遵守访客限流。

回答附带的检索来源不等于答案真正引用了这些来源。当前 `[chunk:ID]` 标记必须精确匹配返回上下文中唯一的 `chunkId`，不匹配、重复歧义、正文中不存在的标记和导出记录伪造的来源都不能得分，而且仍保留在引用分母中。同一标记重复出现不会稀释无效引用的惩罚。旧式 `[source.md]` 仍支持，但不同路径同名文件会判为歧义；其他引用格式不自动评分。实时与导入记录使用同一个解析器。指标区分“出现引用标记”与“引用解析成功”，另用 `citedEvidenceAnchorPrecisionProxy` 检查所引用的具体上下文是否包含标准证据锚点；仅 ID 或文件名正确不足以获得这项分数。上下文截断会影响代理指标。关键词匹配即使遇到否定或矛盾也可能命中，所以这些指标不冒充语义正确率或引用蕴含关系。可选 judge 适配器保留逐条原始输入、输出与理由，单独展示未经人工校准的估计值。

真实 Agent 轨迹可通过 `--live-input` 独立导入。选工具评分要求实际工具集合完全匹配允许集合且不使用禁止工具；它不证明参数正确或证据充分。真实轨迹和受控故障结果不可作为同一种指标对比。语料与题目共同编写也会高估未知表达的检索能力，因此发布数字时应标明数据划分、语言、模型、语料版本与测量边界。
