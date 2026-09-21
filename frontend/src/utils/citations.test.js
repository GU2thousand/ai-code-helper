import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

function render(content, sources) {
  const element = document.createElement('div')
  element.innerHTML = renderMarkdown(content, sources)
  return element
}

describe('sanitized Markdown citations', () => {
  it('matches exact IDs to stable source-order numbers across repeated and reordered references', () => {
    const rendered = render('Second [chunk:two], first [chunk:one], second again [chunk:two].', [
      { chunkId: 'one', title: 'First' },
      { chunkId: 'two', title: 'Second' }
    ])
    expect([...rendered.querySelectorAll('button')].map(button => button.textContent)).toEqual(['[2]', '[1]', '[2]'])
  })

  it('does not resolve missing, case-mismatched, or ambiguous IDs', () => {
    const rendered = render('[chunk:missing] [chunk:ONE] [chunk:duplicate]', [
      { chunkId: 'one', title: 'First' },
      { chunkId: 'duplicate', title: 'Second' },
      { chunkId: 'duplicate', title: 'Conflicting source' }
    ])
    expect(rendered.querySelectorAll('button')).toHaveLength(0)
    expect(rendered.querySelectorAll('.citation-unmatched')).toHaveLength(3)
  })

  it('leaves code examples and link labels untouched without nesting interactive controls', () => {
    const rendered = render('`[chunk:one]`\n\n```text\n[chunk:one]\n```\n\n[example [chunk:one]](https://example.com)', [
      { chunkId: 'one', title: 'First' }
    ])
    expect(rendered.querySelector('[data-citation-index]')).toBeNull()
    expect(rendered.querySelector('code').textContent).toBe('[chunk:one]')
    expect(rendered.querySelector('a').textContent).toBe('example [chunk:one]')
    expect(rendered.querySelector('[data-copy-code]')).not.toBeNull()
  })

  it('preserves sanitizer protections and cannot forge citation controls from raw HTML', () => {
    const rendered = render(
      '<button data-citation-index="0" onclick="alert(1)">forged</button>\n\n' +
      '<script>alert(2)</script>\n\n' +
      '![pixel](https://evil.example/track) [danger](javascript:alert(3)) [chunk:one]',
      [{ chunkId: 'one', title: '<img src=x onerror="alert(4)">' }]
    )
    expect(rendered.querySelector('script, img, [onclick], [onerror]')).toBeNull()
    expect(rendered.querySelector('a').hasAttribute('href')).toBe(false)
    expect(rendered.querySelectorAll('[data-citation-index]')).toHaveLength(1)
    expect(rendered.querySelector('[data-citation-index]').textContent).toBe('[1]')
    expect(rendered.querySelector('[data-citation-index]').getAttribute('title')).toBe('<img src=x onerror="alert(4)">')
  })
})
