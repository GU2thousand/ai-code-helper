export const DEFAULT_CONVERSATION_TITLE = '新的对话'

export function createId(prefix = 'id') {
  if (globalThis.crypto?.randomUUID) {
    return `${prefix}-${globalThis.crypto.randomUUID()}`
  }

  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function createMemoryId() {
  if (globalThis.crypto?.getRandomValues) {
    const numbers = new Uint32Array(1)
    globalThis.crypto.getRandomValues(numbers)
    return (numbers[0] % 2_147_483_646) + 1
  }

  return (Math.floor(Math.random() * 2_147_483_646) + 1)
}

export function createConversation() {
  const now = new Date().toISOString()
  return {
    id: createId('conversation'),
    memoryId: createMemoryId(),
    title: DEFAULT_CONVERSATION_TITLE,
    createdAt: now,
    updatedAt: now,
    messages: []
  }
}

export function createMessage(role, content = '', status = 'done') {
  return {
    id: createId('message'),
    role,
    content,
    status,
    createdAt: new Date().toISOString()
  }
}

export function makeConversationTitle(message) {
  const compact = String(message).replace(/\s+/g, ' ').trim()
  if (!compact) return DEFAULT_CONVERSATION_TITLE
  return compact.length > 20 ? `${compact.slice(0, 20)}…` : compact
}

export function normalizeChunk(raw) {
  if (raw == null) return { content: '' }
  const text = String(raw)

  if (text === '[DONE]' || text === '__DONE__') {
    return { done: true, content: '' }
  }

  try {
    const payload = JSON.parse(text)
    if (payload == null || typeof payload !== 'object' && typeof payload !== 'string') return { content: text }
    if (typeof payload === 'string') return { content: payload }
    if (payload.done === true) return { done: true, content: '' }
    if (payload.error) {
      return {
        error: typeof payload.error === 'string' ? payload.error : payload.error.message || '生成失败'
      }
    }

    const content = payload.content
      ?? payload.delta?.content
      ?? payload.delta
      ?? payload.text
      ?? payload.data?.content
      ?? payload.data

    return { content: typeof content === 'string' ? content : text }
  } catch {
    return { content: text }
  }
}

export function formatConversationTime(value, now = new Date()) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const dayDifference = Math.round((today - target) / 86_400_000)

  if (dayDifference === 0) {
    return new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).format(date)
  }
  if (dayDifference === 1) return '昨天'
  if (dayDifference < 7) return `${dayDifference} 天前`
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}

export async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  textarea.remove()
}
