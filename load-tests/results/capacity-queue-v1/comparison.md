| Workload | Queue | Success / attempts | 429 | Unexpected | Success p95 ms | TTFT p95 ms | Provider / queue peak | End zero | Exit |
|---|---|---:|---:|---:|---:|---:|---:|---|---:|
| chat10 | off | 30/30 | 0 | 0 | 467.2 | — | 10 / 0 | yes | 0 |
| chat10 | on | 30/30 | 0 | 0 | 858.3 | — | 0 / 0 | yes | 0 |
| chat50 | off | 145/150 | 0 | 5 | 1002.4 | — | 2 / 0 | yes | 99 |
| chat50 | on | 150/150 | 0 | 0 | 947.2 | — | 9 / 0 | yes | 0 |
| chat100 | off | 218/300 | 36 | 46 | 1202.0 | — | 2 / 0 | yes | 99 |
| chat100 | on | 278/314 | 36 | 0 | 1190.5 | — | 1 / 0 | yes | 0 |
| rag | off | 31/31 | 0 | 0 | 435.4 | — | 10 / 0 | yes | 0 |
| rag | on | 40/40 | 0 | 0 | 366.2 | — | 0 / 0 | yes | 0 |
| sse10 | off | 32/32 | 0 | 0 | 516.0 | 323.1 | 4 / 0 | yes | 0 |
| sse10 | on | 31/31 | 0 | 0 | 489.0 | 291.1 | 2 / 0 | yes | 0 |
| sse50 | off | 93/150 | 0 | 57 | 1322.0 | 1154.7 | 16 / 0 | yes | 1 |
| sse50 | on | 150/150 | 0 | 0 | 1636.5 | 1491.4 | 16 / 28 | yes | 0 |
| sse100 | off | 173/300 | 36 | 91 | 1677.7 | 1434.4 | 16 / 0 | yes | 1 |
| sse100 | on | 264/300 | 36 | 0 | 2073.8 | 1862.5 | 16 / 42 | yes | 0 |

Failure code counts (raw client classification):

- off/chat50: AI_PROVIDER_CAPACITY=5
- off/chat100: AI_CAPACITY_REACHED=36, AI_PROVIDER_CAPACITY=46
- on/chat100: AI_CAPACITY_REACHED=36
- off/sse50: AI_PROVIDER_CAPACITY=57
- off/sse100: AI_CAPACITY_REACHED=36, AI_PROVIDER_CAPACITY=91
- on/sse100: AI_CAPACITY_REACHED=36
