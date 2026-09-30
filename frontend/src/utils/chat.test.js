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
  function render(content) {
    const element = document.createElement('div')
    element.innerHTML = renderMarkdown(content)
    return element
  }

  it('highlights code and adds a code-copy control', () => {
    const html = renderMarkdown('```js\nconst answer = 42\n```')
    expect(html).toContain('code-block')
    expect(html).toContain('data-copy-code')
    expect(html).toContain('hljs')
  })

  it.each(['js extra-info', 'unknown-language', ''])('preserves fenced code text and its copy control with language %s', language => {
    const code = 'const sample = "<img src=x onerror=alert(1)> & text"'
    const rendered = render('```' + language + '\n' + code + '\n```')

    expect(rendered.querySelector('code').textContent).toBe(code)
    expect(rendered.querySelector('[data-copy-code]')).not.toBeNull()
    expect(rendered.querySelector('img, [onerror]')).toBeNull()
    if (language) {
      expect(rendered.querySelector('.code-block__header span').textContent).toBe(language.split(' ')[0])
    }
  })

  it('keeps link destinations, titles, nested formatting, and safe new-tab attributes', () => {
    const rendered = render('[**Guide** and `sample`](https://example.com/docs?q=1&n=2 "A & B")')
    const link = rendered.querySelector('a')

    expect(link.getAttribute('href')).toBe('https://example.com/docs?q=1&n=2')
    expect(link.getAttribute('title')).toBe('A & B')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(link.querySelector('strong').textContent).toBe('Guide')
    expect(link.querySelector('code').textContent).toBe('sample')
    expect(link.textContent).toBe('Guide and sample')
  })

  it('renders autolinks using their real destination and label', () => {
    const rendered = render('<https://example.com/docs>')
    expect(rendered.querySelector('a').getAttribute('href')).toBe('https://example.com/docs')
    expect(rendered.querySelector('a').textContent).toBe('https://example.com/docs')
  })

  it('removes unsafe scripts and javascript links', () => {
    const html = renderMarkdown('<script>alert(1)</script>\n[危险链接](javascript:alert(1))')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('javascript:')
  })

  it.each(['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html;base64,PHNjcmlwdD4='])('strips executable link destination %s while preserving its label', destination => {
    const rendered = render('[**danger**](' + destination + ')')
    const link = rendered.querySelector('a')
    expect(link.hasAttribute('href')).toBe(false)
    expect(link.textContent).toBe('danger')
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

  it('renders image alt text as an inert placeholder without its destination', () => {
    const rendered = render('![<img src=x onerror=alert(1)> & label](https://evil.example/track "hidden")')
    expect(rendered.querySelector('.markdown-image-placeholder').textContent).toBe('[图片已隐藏：<img src=x onerror=alert(1)> & label]')
    expect(rendered.querySelector('img, [onerror], a')).toBeNull()
    expect(rendered.innerHTML).not.toContain('https://evil.example')
  })
})
