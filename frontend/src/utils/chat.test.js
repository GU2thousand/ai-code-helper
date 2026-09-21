import { describe, expect, it } from 'vitest'
import { makeConversationTitle, normalizeChunk } from './chat'
import { renderMarkdown } from './markdown'

describe('chat utilities', () => {
  it('normalizes text, JSON deltas, completion, and error chunks', () => {
    expect(normalizeChunk('你好')).toEqual({ content: '你好' })
    expect(normalizeChunk('{"delta":{"content":"世界"}}')).toEqual({ content: '世界' })
    expect(normalizeChunk('[DONE]')).toEqual({ done: true, content: '' })
    expect(normalizeChunk('{"error":"内容被拦截"}')).toEqual({ error: '内容被拦截' })
  })

  it.each(['42', '0', 'true', 'false', 'null', '{"answer":42}', '[1,2]'])('preserves plain JSON-like text %s', raw => {
    expect(normalizeChunk(raw)).toEqual({ content: raw })
  })

  it('preserves whitespace, code and literal completion markers inside envelopes', () => {
    for (const content of [' REST API', '\n    return 42\n', '[DONE]', 'true']) {
      expect(normalizeChunk(JSON.stringify({ content }))).toEqual({ content })
    }
  })

  it('creates a compact title from the first prompt', () => {
    expect(makeConversationTitle('  帮我   制定 Java 学习路线  ')).toBe('帮我 制定 Java 学习路线')
    expect(makeConversationTitle('这是一个明显超过二十个字符的会话标题用于测试截断效果')).toMatch(/…$/)
  })
})

describe('Markdown rendering', () => {
  it('highlights code and adds a code-copy control', () => {
    const html = renderMarkdown('```js\nconst answer = 42\n```')
    expect(html).toContain('code-block')
    expect(html).toContain('data-copy-code')
    expect(html).toContain('hljs')
  })

  it('removes unsafe scripts and javascript links', () => {
    const html = renderMarkdown('<script>alert(1)</script>\n[危险链接](javascript:alert(1))')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('javascript:')
  })

  it('drops raw form controls and never renders Markdown images', () => {
    const html = renderMarkdown(
      '<form action="https://evil.example"><input type="password" name="secret"></form>\n\n' +
      '![tracking pixel](https://evil.example/pixel.png)'
    )

    expect(html).not.toContain('<form')
    expect(html).not.toContain('<input')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('https://evil.example')
    expect(html).toContain('图片已隐藏')
  })
})
