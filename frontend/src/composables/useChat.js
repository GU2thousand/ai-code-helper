import { computed, ref } from 'vue'
import { buildChatStreamUrl, createChatStream, createGuestUser } from '../api/client'
import {
  createConversation,
  createId,
  createMessage,
  makeConversationTitle,
  normalizeChunk
} from '../utils/chat'

export const CHAT_STORAGE_KEY = 'lingma:chat-state:v1'
export const USER_STORAGE_KEY = 'lingma:guest-user:v1'

const MAX_CONVERSATIONS = 30
const MAX_MESSAGES_PER_CONVERSATION = 120

function safeRead(key) {
  try {
    const value = localStorage.getItem(key)
    return value ? JSON.parse(value) : null
  } catch {
    return null
  }
}

function safeWrite(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Storage can be unavailable in private browsing; the in-memory chat still works.
  }
}

function restoreConversation(candidate) {
  if (!candidate || typeof candidate !== 'object' || !Array.isArray(candidate.messages)) return null

  const fallback = createConversation()
  const messages = candidate.messages
    .filter((message) => message?.role === 'user' || message?.role === 'assistant')
    .slice(-MAX_MESSAGES_PER_CONVERSATION)
    .map((message) => ({
      id: typeof message.id === 'string' ? message.id : createId('message'),
      role: message.role,
      content: typeof message.content === 'string' ? message.content : '',
      status: message.status === 'streaming' ? 'stopped' : (message.status || 'done'),
      error: typeof message.error === 'string' ? message.error : undefined,
      createdAt: message.createdAt || fallback.createdAt
    }))

  const memoryId = Number(candidate.memoryId)

  return {
    id: typeof candidate.id === 'string' ? candidate.id : fallback.id,
    memoryId: Number.isSafeInteger(memoryId) && memoryId >= 1 && memoryId <= 2_147_483_647
      ? memoryId
      : fallback.memoryId,
    title: typeof candidate.title === 'string' ? candidate.title : fallback.title,
    createdAt: candidate.createdAt || fallback.createdAt,
    updatedAt: candidate.updatedAt || fallback.updatedAt,
    messages
  }
}

function normalizeGuest(payload) {
  const body = payload?.data?.user ?? payload?.data ?? payload?.user ?? payload ?? {}
  const localId = createId('guest')
  return {
    id: String(body.userId ?? body.id ?? body.guestId ?? localId),
    name: String(body.displayName ?? body.nickname ?? body.name ?? body.username ?? '访客用户'),
    avatar: typeof body.avatar === 'string' ? body.avatar : '',
    isLocalFallback: false
  }
}

function userFacingStreamError(message) {
  const value = String(message || '').trim()
  const knownErrors = {
    AI_STREAM_ERROR: 'AI 服务暂时无法完成回复，请稍后重试。',
    STREAM_EXPIRED: '本次回复连接已过期，请重新生成。',
    STREAM_NOT_FOUND: '未找到本次回复连接，请重新发送。',
    GUARDRAIL_BLOCKED: '该请求未通过安全检查，请调整内容后重试。',
    RATE_LIMITED: '请求过于频繁，请稍后再试。'
  }

  if (knownErrors[value]) return knownErrors[value]
  if (/^[A-Z][A-Z0-9_]{2,}$/.test(value)) return '生成遇到问题，请稍后重试。'
  return value || '连接中断，请稍后重试。'
}

export function useChat(dependencies = {}) {
  const guestApi = dependencies.createGuestUser ?? createGuestUser
  const createStreamApi = dependencies.createChatStream ?? createChatStream
  const streamUrlBuilder = dependencies.buildChatStreamUrl ?? buildChatStreamUrl
  const eventSourceFactory = dependencies.eventSourceFactory
    ?? ((url, options) => new EventSource(url, options))

  const savedState = safeRead(CHAT_STORAGE_KEY)
  const savedUser = safeRead(USER_STORAGE_KEY)
  const restored = Array.isArray(savedState?.conversations)
    ? savedState.conversations.map(restoreConversation).filter(Boolean).slice(0, MAX_CONVERSATIONS)
    : []

  const conversations = ref(restored.length ? restored : [createConversation()])
  const validSavedId = conversations.value.some((item) => item.id === savedState?.activeConversationId)
  const activeConversationId = ref(validSavedId ? savedState.activeConversationId : conversations.value[0].id)
  const ownerUserId = ref(
    typeof savedState?.ownerUserId === 'string'
      ? savedState.ownerUserId
      : (typeof savedUser?.id === 'string' ? savedUser.id : '')
  )
  const user = ref(savedUser)
  const ready = ref(false)
  const initializing = ref(false)
  const generating = ref(false)
  const errorMessage = ref('')
  let activeStream = null
  let initializationPromise = null
  let persistTimer = null

  const activeConversation = computed(() => (
    conversations.value.find((item) => item.id === activeConversationId.value)
      ?? conversations.value[0]
  ))
  const messages = computed(() => activeConversation.value?.messages ?? [])

  function persistNow() {
    if (persistTimer) clearTimeout(persistTimer)
    persistTimer = null
    safeWrite(CHAT_STORAGE_KEY, {
      ownerUserId: ownerUserId.value || null,
      activeConversationId: activeConversationId.value,
      conversations: conversations.value.slice(0, MAX_CONVERSATIONS)
    })
  }

  function schedulePersist() {
    if (persistTimer) clearTimeout(persistTimer)
    persistTimer = setTimeout(persistNow, 80)
  }

  function touchConversation(conversation) {
    conversation.updatedAt = new Date().toISOString()
    schedulePersist()
  }

  async function initialize() {
    if (initializationPromise) return initializationPromise
    persistNow()
    initializing.value = true
    ready.value = false

    initializationPromise = (async () => {
      try {
        const nextUser = normalizeGuest(await guestApi())
        const hasPersistedMessages = conversations.value.some((conversation) => conversation.messages.length > 0)
        const identityChanged = (ownerUserId.value && ownerUserId.value !== nextUser.id)
          || (!ownerUserId.value && hasPersistedMessages)
        if (identityChanged) {
          stopGeneration()
          const fresh = createConversation()
          conversations.value = [fresh]
          activeConversationId.value = fresh.id
        }
        ownerUserId.value = nextUser.id
        user.value = nextUser
        ready.value = true
        errorMessage.value = ''
      } catch {
        user.value = user.value?.id
          ? { ...user.value, isLocalFallback: true }
          : {
              id: createId('guest'),
              name: '访客用户',
              avatar: '',
              isLocalFallback: true
            }
        ready.value = false
        errorMessage.value = '无法建立安全访客会话，请确认后端服务可用后刷新页面。'
      } finally {
        initializing.value = false
        safeWrite(USER_STORAGE_KEY, user.value)
        persistNow()
        initializationPromise = null
      }

      return user.value
    })()

    return initializationPromise
  }

  function settleStream(context, status, message) {
    if (!context || activeStream !== context || context.finished) return false
    context.finished = true
    context.source?.close()
    context.assistant.status = status
    if (message) context.assistant.error = message
    touchConversation(context.conversation)
    activeStream = null
    generating.value = false
    persistNow()
    return true
  }

  function restoreReplacedReply(context) {
    const rollback = context?.rollback
    if (!rollback) return false

    rollback.conversation.messages.splice(
      rollback.index,
      rollback.conversation.messages.length - rollback.index,
      ...rollback.messages
    )
    context.rollback = null
    return true
  }

  function completeStream(context) {
    settleStream(context, 'done')
  }

  function failStream(context, message = '连接中断，请稍后重试。') {
    if (activeStream !== context || context.finished) return
    const friendlyMessage = userFacingStreamError(message)
    errorMessage.value = friendlyMessage
    restoreReplacedReply(context)
    settleStream(context, 'error', friendlyMessage)
  }

  function stopGeneration() {
    if (!activeStream) return
    restoreReplacedReply(activeStream)
    settleStream(activeStream, 'stopped')
  }

  async function startStream(
    prompt,
    { appendUser = true, regenerate = false, rollback = null } = {}
  ) {
    const text = String(prompt).trim()
    if (!text || generating.value || initializing.value || !ready.value) return false

    errorMessage.value = ''
    const conversation = activeConversation.value
    if (!conversation) return false

    if (appendUser) {
      conversation.messages.push(createMessage('user', text))
      if (conversation.messages.filter((item) => item.role === 'user').length === 1) {
        conversation.title = makeConversationTitle(text)
      }
    }

    const assistant = createMessage('assistant', '', 'streaming')
    conversation.messages.push(assistant)
    conversation.messages = conversation.messages.slice(-MAX_MESSAGES_PER_CONVERSATION)
    touchConversation(conversation)
    generating.value = true

    const context = {
      source: null,
      assistant,
      conversation,
      rollback,
      finished: false
    }
    activeStream = context

    try {
      const streamId = regenerate
        ? await createStreamApi(conversation.memoryId, text, { regenerate: true })
        : await createStreamApi(conversation.memoryId, text)
      if (activeStream !== context || context.finished) return false

      const url = streamUrlBuilder(streamId)
      context.source = eventSourceFactory(url, { withCredentials: true })
    } catch (error) {
      failStream(context, error?.message || '无法建立流式连接。')
      return false
    }

    const source = context.source

    source.onmessage = (event) => {
      if (activeStream !== context || context.finished) return
      const chunk = normalizeChunk(event.data)
      if (chunk.done) {
        completeStream(context)
        return
      }
      if (chunk.error) {
        failStream(context, chunk.error)
        return
      }
      if (chunk.content) {
        assistant.content += chunk.content
        touchConversation(conversation)
      }
    }

    source.addEventListener?.('done', () => {
      if (activeStream !== context || context.finished) return
      completeStream(context)
    })
    source.onerror = (event) => {
      if (activeStream !== context || context.finished) return

      if (event?.data) {
        const chunk = normalizeChunk(event.data)
        failStream(context, chunk.error || chunk.content || '生成失败，请稍后重试。')
        return
      }

      failStream(context, '连接意外中断，回复可能不完整，请重新生成。')
    }

    return true
  }

  async function regenerateLast() {
    if (generating.value || initializing.value || !ready.value) return false
    const conversation = activeConversation.value
    const lastUserIndex = conversation.messages.findLastIndex?.((item) => item.role === 'user')
      ?? (() => {
        for (let index = conversation.messages.length - 1; index >= 0; index -= 1) {
          if (conversation.messages[index].role === 'user') return index
        }
        return -1
      })()

    if (lastUserIndex < 0) return false
    const prompt = conversation.messages[lastUserIndex].content
    const replacedMessages = conversation.messages.slice(lastUserIndex + 1)
    conversation.messages.splice(lastUserIndex + 1)
    touchConversation(conversation)
    return startStream(prompt, {
      appendUser: false,
      regenerate: true,
      rollback: {
        conversation,
        index: lastUserIndex + 1,
        messages: replacedMessages
      }
    })
  }

  function newConversation() {
    stopGeneration()
    const conversation = createConversation()
    conversations.value.unshift(conversation)
    conversations.value = conversations.value.slice(0, MAX_CONVERSATIONS)
    activeConversationId.value = conversation.id
    persistNow()
    return conversation
  }

  function selectConversation(id) {
    if (id === activeConversationId.value) return
    if (!conversations.value.some((item) => item.id === id)) return
    stopGeneration()
    activeConversationId.value = id
    persistNow()
  }

  function clearCurrentConversation() {
    stopGeneration()
    const conversation = activeConversation.value
    if (!conversation) return
    const fresh = createConversation()
    conversation.messages = []
    conversation.title = fresh.title
    conversation.memoryId = fresh.memoryId
    conversation.updatedAt = fresh.updatedAt
    persistNow()
  }

  function dismissError() {
    errorMessage.value = ''
  }

  function dispose() {
    stopGeneration()
    persistNow()
  }

  return {
    conversations,
    activeConversationId,
    activeConversation,
    messages,
    user,
    ready,
    initializing,
    generating,
    errorMessage,
    initialize,
    startStream,
    stopGeneration,
    regenerateLast,
    newConversation,
    selectConversation,
    clearCurrentConversation,
    dismissError,
    dispose
  }
}
