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
