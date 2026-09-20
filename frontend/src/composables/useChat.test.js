import { computed, watchEffect, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CHAT_STORAGE_KEY, USER_STORAGE_KEY, useChat } from './useChat'

class FakeEventSource {
  static instances = []

  constructor(url, options) {
    this.url = url
    this.options = options
    this.readyState = 1
    this.listeners = new Map()
    this.closed = false
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, handler) {
    this.listeners.set(name, handler)
  }

  emit(name, data) {
    if (name === 'message') this.onmessage?.({ data })
    else if (name === 'error') this.onerror?.({ data })
    else this.listeners.get(name)?.({ data })
  }

  close() {
    this.closed = true
    this.readyState = 2
  }
}

function createHarness(overrides = {}) {
  const createGuestUser = overrides.createGuestUser ?? vi.fn().mockResolvedValue({
    data: { userId: 'guest-from-server', displayName: '测试访客' }
  })
  let ticket = 0
  const createChatStream = overrides.createChatStream
    ?? vi.fn().mockImplementation(async () => `stream-ticket-${++ticket}`)
  const buildChatStreamUrl = overrides.buildChatStreamUrl
    ?? vi.fn((streamId) => `http://localhost/api/ai/chat/streams/${streamId}`)
  const chat = useChat({
    createGuestUser,
    createChatStream,
    buildChatStreamUrl,
    eventSourceFactory: (url, options) => new FakeEventSource(url, options)
  })
  return { chat, createGuestUser, createChatStream, buildChatStreamUrl }
}

describe('useChat', () => {
  it('updates reactive consumers before done and announces completion without reload', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('Java')
    const output = computed(() => chat.messages.value.at(-1).content)
    const status = computed(() => chat.messages.value.at(-1).status)
    let rendered = ''
    const stop = watchEffect(() => { rendered = output.value })
    expect(status.value).toBe('streaming')
    const source = FakeEventSource.instances.at(-1)
    source.emit('message', JSON.stringify({ content: ' REST API 42' }))
    await nextTick()
    expect(rendered).toBe(' REST API 42')
    source.emit('sources', JSON.stringify([{ title: 'Java guide' }]))
    source.emit('done', '[DONE]')
    expect(status.value).toBe('done')
    expect(chat.messages.value.at(-1).sources[0].title).toBe('Java guide')
    stop()
  })

  beforeEach(() => {
    FakeEventSource.instances = []
  })

  it('creates a POST ticket before opening a credentialed EventSource with only its stream id', async () => {
    const { chat, createGuestUser, createChatStream, buildChatStreamUrl } = createHarness()
    await chat.initialize()

    expect(createGuestUser).toHaveBeenCalledTimes(1)
    expect(chat.ready.value).toBe(true)
    expect(await chat.startStream('解释一下 SSE')).toBe(true)

    const memoryId = chat.activeConversation.value.memoryId
    const source = FakeEventSource.instances[0]
    expect(createChatStream).toHaveBeenCalledWith(memoryId, '解释一下 SSE')
    expect(buildChatStreamUrl).toHaveBeenCalledWith('stream-ticket-1')
    expect(source.url).toBe('http://localhost/api/ai/chat/streams/stream-ticket-1')
    expect(source.url).not.toContain('解释')
    expect(source.options).toEqual({ withCredentials: true })
    expect(chat.messages.value).toHaveLength(2)

    source.emit('message', 'Server')
    source.emit('message', '{"delta":{"content":"-Sent Events"}}')
    expect(chat.messages.value[1].content).toBe('Server-Sent Events')

    source.emit('message', '[DONE]')
    expect(chat.messages.value[1].status).toBe('done')
    expect(chat.generating.value).toBe(false)
    expect(source.closed).toBe(true)
  })

  it('blocks sending until the guest-cookie initialization has completed', async () => {
    let resolveGuest
    const createGuestUser = vi.fn(() => new Promise((resolve) => { resolveGuest = resolve }))
    const { chat, createChatStream } = createHarness({ createGuestUser })

    expect(await chat.startStream('初始化前不能发送')).toBe(false)
    expect(createChatStream).not.toHaveBeenCalled()
    expect(chat.messages.value).toHaveLength(0)

    const initialization = chat.initialize()
    expect(chat.initializing.value).toBe(true)
    expect(chat.ready.value).toBe(false)
    expect(await chat.startStream('初始化中也不能发送')).toBe(false)

    resolveGuest({ data: { userId: 'ready-user', displayName: '已连接访客' } })
    await initialization
    expect(chat.initializing.value).toBe(false)
    expect(chat.ready.value).toBe(true)
    expect(await chat.startStream('现在可以发送')).toBe(true)
    expect(createChatStream).toHaveBeenCalledTimes(1)
  })

  it('ignores late done and error events from an old stream after regeneration', async () => {
    const { chat, createChatStream } = createHarness()
    await chat.initialize()
    await chat.startStream('给我一个练习题')

    const oldSource = FakeEventSource.instances[0]
    oldSource.emit('message', '先实现一个')
    chat.stopGeneration()
    expect(oldSource.closed).toBe(true)

    expect(await chat.regenerateLast()).toBe(true)
    const newSource = FakeEventSource.instances[1]
    expect(createChatStream).toHaveBeenLastCalledWith(
      chat.activeConversation.value.memoryId,
      '给我一个练习题',
      { regenerate: true }
    )
    expect(chat.messages.value.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(chat.generating.value).toBe(true)

    oldSource.emit('done', '[DONE]')
    oldSource.emit('error', '{"error":"旧连接迟到错误"}')

    expect(chat.generating.value).toBe(true)
    expect(chat.messages.value[1].status).toBe('streaming')
    expect(newSource.closed).toBe(false)

    newSource.emit('done', '[DONE]')
    expect(chat.generating.value).toBe(false)
    expect(chat.messages.value[1].status).toBe('done')
  })

  it('keeps the previous reply when a regeneration ticket cannot be created', async () => {
    const { chat, createChatStream } = createHarness()
    await chat.initialize()
    await chat.startStream('保留旧回复')
    const source = FakeEventSource.instances[0]
    source.emit('message', '这是可靠的旧回复')
    source.emit('done', '[DONE]')

    createChatStream.mockRejectedValueOnce(new Error('票据创建失败'))
    expect(await chat.regenerateLast()).toBe(false)

    expect(chat.messages.value).toHaveLength(2)
    expect(chat.messages.value[1].content).toBe('这是可靠的旧回复')
    expect(chat.messages.value[1].status).toBe('done')
    expect(chat.errorMessage.value).toBe('票据创建失败')
  })

  it('restores the previous reply when a regenerated SSE fails before its first chunk', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('立即失败也要保留旧回复')
    const firstSource = FakeEventSource.instances[0]
    firstSource.emit('message', '可恢复的旧回复')
    firstSource.emit('done', '[DONE]')

    expect(await chat.regenerateLast()).toBe(true)
    const regeneratedSource = FakeEventSource.instances[1]
    regeneratedSource.emit('error', 'AI_STREAM_ERROR')

    expect(chat.messages.value).toHaveLength(2)
    expect(chat.messages.value[1].content).toBe('可恢复的旧回复')
    expect(chat.messages.value[1].status).toBe('done')
    expect(chat.errorMessage.value).toBe('AI 服务暂时无法完成回复，请稍后重试。')
  })

  it('restores the previous reply when a regenerated SSE fails after partial content', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('部分失败也要保留旧回复')
    const firstSource = FakeEventSource.instances[0]
    firstSource.emit('message', '完整且可靠的旧回复')
    firstSource.emit('done', '[DONE]')

    expect(await chat.regenerateLast()).toBe(true)
    const regeneratedSource = FakeEventSource.instances[1]
    regeneratedSource.emit('message', '只生成了一半')
    regeneratedSource.emit('error', 'AI_STREAM_ERROR')

    expect(chat.messages.value).toHaveLength(2)
    expect(chat.messages.value[1].content).toBe('完整且可靠的旧回复')
    expect(chat.messages.value[1].status).toBe('done')
    expect(chat.errorMessage.value).toBe('AI 服务暂时无法完成回复，请稍后重试。')
  })

  it('can retry guest initialization after a transient failure', async () => {
    const createGuestUser = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ data: { userId: 'reconnected', displayName: '已重连访客' } })
    const { chat } = createHarness({ createGuestUser })

    await chat.initialize()
    expect(chat.ready.value).toBe(false)
    expect(chat.errorMessage.value).toContain('无法建立安全访客会话')

    await chat.initialize()
    expect(chat.ready.value).toBe(true)
    expect(chat.user.value.id).toBe('reconnected')
    expect(chat.errorMessage.value).toBe('')
  })

  it('starts a clean local conversation when the signed guest identity changes', async () => {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: 'old-owner', name: '旧访客' }))
    localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify({
      ownerUserId: 'old-owner',
      activeConversationId: 'old-conversation',
      conversations: [{
        id: 'old-conversation',
        memoryId: 42,
        title: '旧身份对话',
        createdAt: '2026-07-11T00:00:00.000Z',
        updatedAt: '2026-07-11T00:00:00.000Z',
        messages: [{
          id: 'old-message',
          role: 'assistant',
          content: '不应暴露给新身份',
          status: 'done',
          createdAt: '2026-07-11T00:00:00.000Z'
        }]
      }]
    }))
    const { chat } = createHarness({
      createGuestUser: vi.fn().mockResolvedValue({
        data: { userId: 'new-owner', displayName: '新访客' }
      })
    })

    expect(chat.messages.value[0].content).toBe('不应暴露给新身份')
    await chat.initialize()

    expect(chat.user.value.id).toBe('new-owner')
    expect(chat.conversations.value).toHaveLength(1)
    expect(chat.messages.value).toHaveLength(0)
    expect(chat.activeConversation.value.id).not.toBe('old-conversation')
    expect(JSON.parse(localStorage.getItem(CHAT_STORAGE_KEY)).ownerUserId).toBe('new-owner')
  })

  it('keeps persisted conversations when the signed guest identity is renewed', async () => {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ id: 'same-owner', name: '访客' }))
    localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify({
      ownerUserId: 'same-owner',
      activeConversationId: 'kept-conversation',
      conversations: [{
        id: 'kept-conversation',
        memoryId: 84,
        title: '保留的对话',
        createdAt: '2026-07-11T00:00:00.000Z',
        updatedAt: '2026-07-11T00:00:00.000Z',
        messages: [{
          id: 'kept-message',
          role: 'user',
          content: '继续这个上下文',
          status: 'done',
          createdAt: '2026-07-11T00:00:00.000Z'
        }]
      }]
    }))
    const { chat } = createHarness({
      createGuestUser: vi.fn().mockResolvedValue({
        data: { userId: 'same-owner', displayName: '访客' }
      })
    })

    await chat.initialize()

    expect(chat.activeConversation.value.id).toBe('kept-conversation')
    expect(chat.messages.value[0].content).toBe('继续这个上下文')
  })

  it('does not remove the current reply when regeneration is unavailable', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('离线后不要删除')
    const source = FakeEventSource.instances[0]
    source.emit('message', '应当保留的回复')
    source.emit('done', '[DONE]')
    chat.ready.value = false

    expect(await chat.regenerateLast()).toBe(false)
    expect(chat.messages.value[1].content).toBe('应当保留的回复')
  })

  it('treats EOF as an error even after receiving partial content', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('不要猜测完成状态')
    const source = FakeEventSource.instances[0]

    source.emit('message', '不完整的回答')
    source.readyState = 2
    source.emit('error')

    expect(chat.messages.value[1].content).toBe('不完整的回答')
    expect(chat.messages.value[1].status).toBe('error')
    expect(chat.errorMessage.value).toContain('意外中断')
    expect(source.closed).toBe(true)
  })

  it('surfaces a named SSE error and closes the connection', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('测试护轨')
    const source = FakeEventSource.instances[0]

    source.emit('error', '{"error":"请求未通过安全检查"}')

    expect(chat.messages.value[1].status).toBe('error')
    expect(chat.errorMessage.value).toBe('请求未通过安全检查')
    expect(source.closed).toBe(true)
  })

  it('maps internal stream error codes to a user-facing message', async () => {
    const { chat } = createHarness()
    await chat.initialize()
    await chat.startStream('触发模型错误')
    const source = FakeEventSource.instances[0]

    source.emit('error', 'AI_STREAM_ERROR')

    expect(chat.errorMessage.value).toBe('AI 服务暂时无法完成回复，请稍后重试。')
    expect(chat.errorMessage.value).not.toContain('AI_STREAM_ERROR')
  })
})
