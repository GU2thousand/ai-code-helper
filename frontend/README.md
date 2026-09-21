# 灵码 · AI 编程助手前端

基于 Vue 3 + Vite 的单页聊天界面。REST 请求使用 Axios，模型回复通过原生 `EventSource` 实时追加，适配 Spring Boot 3.5 后端。

## 功能

- SSE 分段回复，兼容纯文本、常见 JSON delta、`done` 事件和 `[DONE]`
- 停止生成、重新生成、清空当前会话
- Markdown、代码高亮、复制代码与复制整条回复
- 访客用户和多会话记录本地持久化；每次启动会刷新后端 HttpOnly 会话，签名身份变化时会清除旧身份的本地会话以避免共享设备数据串用
- 服务健康状态、错误提示、建议卡片和键盘操作
- 响应式侧边栏、移动端输入体验、焦点样式与无障碍语义
- Markdown 输出经 DOMPurify 清理，SSE 凭证通过 Cookie 传递，不在 URL 中放置访问令牌

## 环境要求

- Node.js 20.19+（或 22.12+）
- 后端默认运行于 `http://localhost:8081`

需求中的 Node.js 16+ 表示最低开发方向，但 Node.js 16 已结束安全维护，旧版 Vite/Vitest 也存在已公开的开发服务器漏洞。因此项目采用当前安全工具链，并将实际运行基线提升到 Node.js 20.19+；Node 20/22 仍属于 16+ 范围。

## 本地运行

```bash
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器会将 `/api` 代理到 `http://localhost:8081`。

常用命令：

```bash
npm run test     # 执行单元测试
npm run build    # 生成 dist/ 生产包
npm run preview  # 本地预览生产包
```

## 接口配置

复制 `.env.example` 为 `.env.local`，可覆盖后端源地址：

```dotenv
VITE_API_BASE_URL=https://api.example.com
```

地址末尾不要添加 `/api`。不配置时使用同源 `/api`，开发环境由 Vite 代理。

前端调用：

| 类型 | 接口 | 用途 |
| --- | --- | --- |
| POST | `/api/users/guest` | 创建或刷新访客 Cookie 会话 |
| GET | `/api/health` | 检查服务状态及可选模型信息 |
| POST | `/api/ai/chat/streams` | 以 `{ memoryId, message }` 创建流票据并返回 `{ streamId }`；重新生成时额外发送 `regenerate: true` |
| GET (SSE) | `/api/ai/chat/streams/{streamId}` | 使用不透明流标识获取分段回复 |

Axios 与 EventSource 均启用 Cookie 凭证。前端会先完成访客会话初始化，再允许发送消息。用户问题只出现在 JSON POST 请求体中，EventSource 的 GET URL 仅携带短期、不透明的 `streamId`，避免问题正文进入地址栏、代理日志和浏览器历史。跨域部署时，后端需要明确允许前端源、允许凭证，并避免使用通配符 `Access-Control-Allow-Origin: *`。

SSE 可以返回普通 `data:` 文本，也可以返回类似以下 JSON：

```json
{"delta":{"content":"一个分片"}}
```

推荐结束时发送命名事件：

```text
event: done
data: [DONE]
```

后端必须通过 `done` 事件或普通消息中的 `[DONE]` 明确声明完成。未收到完成标记便断开的连接会被前端视为错误，避免把残缺回答误标为成功。

重新生成采用事务式界面回滚：如果新回复在任意分片阶段失败或用户停止生成，旧的完整回复会恢复，不会被半截内容覆盖。

浏览器本地保存会话列表和显示记录。后端默认将成功完成的会话记忆与签名密钥持久化到 `APP_DATA_DIR`，挂载该目录可跨重启恢复上下文；`APP_STORAGE_ENABLED=false` 时为临时内存模式。服务端存储目前仅支持单实例。RAG 来源带稳定 `chunkId`，正文 `[chunk:ID]` 转为可聚焦的编号引用；未知或歧义引用显示未匹配，来源标题与摘录按纯文本展示。

## 项目结构

```text
src/
├── api/client.js              # Axios 与 SSE URL
├── components/                # 侧栏、消息、输入框等界面组件
├── composables/useChat.js     # 会话持久化与流状态机
├── utils/chat.js              # 消息、分片与复制工具
├── utils/markdown.js          # Markdown、高亮与安全清理
├── App.vue
└── styles.css
```

浏览器本地数据键为 `lingma:guest-user:v1` 和 `lingma:chat-state:v1`。清除站点数据即可重置所有本地会话。

当前基线在 Node.js 20.19.0 下为 28 项前端测试全绿、`npm ci` 冷安装和生产构建成功，且 `npm audit` 为 0 漏洞。消息日志具有可访问名称，SSE token 更新不会逐段触发读屏播报，完成/失败/停止只通过独立状态区播报一次。
