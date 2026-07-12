import { describe, expect, it, vi } from 'vitest'
import { buildChatStreamUrl, createChatStream, http } from './client'

describe('two-step chat stream API', () => {
  it('creates a stream ticket with the prompt in a JSON POST body', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { streamId: 'opaque-ticket' }
    })

    await expect(createChatStream(123, '含有敏感信息的问题')).resolves.toBe('opaque-ticket')
    expect(post).toHaveBeenCalledWith('/api/ai/chat/streams', {
      memoryId: 123,
      message: '含有敏感信息的问题'
    })
  })

  it('marks regeneration in the ticket request', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { data: { streamId: 'retry-ticket' } }
    })

    await createChatStream(456, '重新回答', { regenerate: true })
    expect(post).toHaveBeenCalledWith('/api/ai/chat/streams', {
      memoryId: 456,
      message: '重新回答',
      regenerate: true
    })
  })

  it('builds the EventSource URL from only an encoded opaque ticket', () => {
    const url = buildChatStreamUrl('ticket/with spaces')
    expect(url).toContain('/api/ai/chat/streams/ticket%2Fwith%20spaces')
    expect(url).not.toContain('message=')
    expect(url).not.toContain('memoryId=')
  })
})
