import axios from 'axios'

const configuredBaseUrl = (import.meta.env.VITE_API_BASE_URL || '').trim()
export const API_BASE_URL = configuredBaseUrl.replace(/\/+$/, '')

export const http = axios.create({
  baseURL: API_BASE_URL || undefined,
  timeout: 10_000,
  withCredentials: true,
  headers: {
    Accept: 'application/json'
  }
})

export function normalizeApiError(error) {
  const code = error?.response?.data?.code
  const messages = {
    GUARDRAIL_REJECTED: '这个请求未通过安全检查，请调整内容后重试。',
    AI_OWNER_RATE_LIMITED: '请求过于频繁，请稍后再试。',
    AI_RATE_LIMITED: '服务繁忙，请稍后再试。',
    VALIDATION_FAILED: '请求内容不符合要求，请检查后重试。',
    INVALID_GUEST_TOKEN: '访客会话已失效，请刷新页面后重试。'
  }
  const status = error?.response?.status
  const message = messages[code]
    || (status === 429 ? '请求过于频繁，请稍后再试。' : null)
    || (status === 401 ? '访客会话已失效，请刷新页面后重试。' : null)
    || (status === 403 ? '连接被拒绝，请检查服务的跨域配置。' : null)
    || (status >= 500 ? '服务器暂时无法处理请求，请稍后重试。' : null)
    || (status ? '请求失败，请检查输入后重试。' : '无法连接服务器，请检查网络和后端服务。')
  return Object.assign(new Error(message, { cause: error }), { code, status })
}

http.interceptors.response.use(response => response, error => Promise.reject(normalizeApiError(error)))

export async function getHealth() {
  const { data } = await http.get('/api/health')
  return data
}

export async function createGuestUser() {
  const { data } = await http.post('/api/users/guest', {})
  return data
}

export async function createChatStream(memoryId, message, options = {}) {
  const request = { memoryId, message }
  if (options.regenerate === true) request.regenerate = true
  const { data } = await http.post('/api/ai/chat/streams', request)
  const payload = data?.data ?? data
  const streamId = payload?.streamId

  if (streamId == null || String(streamId).trim() === '') {
    throw new Error('后端未返回有效的流标识。')
  }

  return String(streamId)
}

export function buildChatStreamUrl(streamId) {
  const origin = API_BASE_URL || window.location.origin
  return new URL(`/api/ai/chat/streams/${encodeURIComponent(streamId)}`, origin).toString()
}
