import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MessageBubble from './MessageBubble.vue'

const source = {
  chunkId: 'chunk-123',
  title: 'Java collections',
  location: 'List > ArrayList',
  excerpt: 'ArrayList provides indexed access.'
}

function message(overrides = {}) {
  return {
    id: 'reply-1',
    role: 'assistant',
    content: '支持随机访问。[chunk:chunk-123]',
    status: 'done',
    createdAt: '2026-09-20T12:00:00Z',
    sources: [source],
    ...overrides
  }
}

describe('message source citations', () => {
  it('opens and focuses the matching plain-text excerpt from a numbered reference', async () => {
    const wrapper = mount(MessageBubble, { attachTo: document.body, props: { message: message() } })
    const reference = wrapper.get('button[data-citation-index="0"]')
    expect(reference.text()).toBe('[1]')
    expect(reference.attributes('aria-label')).toBe('查看参考资料 1：Java collections')
    expect(wrapper.get('details').element.open).toBe(false)

    await reference.trigger('click')

    expect(wrapper.get('details').element.open).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('li').element)
    expect(wrapper.get('li').text()).toContain(source.title)
    expect(wrapper.get('li').text()).toContain(source.location)
    expect(wrapper.get('li').text()).toContain(source.excerpt)
    expect(wrapper.text()).toContain('未自动核验结论')
    wrapper.unmount()
  })

  it('resolves citations when streaming source metadata arrives and flags unknown references', async () => {
    const content = '支持随机访问。[chunk:chunk-123] 未知结论。[chunk:missing]'
    const wrapper = mount(MessageBubble, { props: { message: message({ sources: [], content, status: 'streaming' }) } })
    expect(wrapper.findAll('.citation-unmatched')).toHaveLength(2)
    expect(wrapper.find('button[data-citation-index]').exists()).toBe(false)

    await wrapper.setProps({ message: message({ content, status: 'streaming' }) })

    expect(wrapper.get('button[data-citation-index]').text()).toBe('[1]')
    expect(wrapper.get('.citation-unmatched').text()).toBe('[未匹配引用：missing]')
    expect(wrapper.find('.streaming-cursor').exists()).toBe(true)
    wrapper.unmount()
  })

  it('renders hostile source metadata as text without source URL navigation or executable markup', () => {
    const hostileSource = {
      ...source,
      title: '"><img src=x onerror=alert(1)>',
      location: '<script>alert(2)</script>',
      excerpt: '[click](javascript:alert(3)) <svg onload=alert(4)>',
      source: 'javascript:alert(5)'
    }
    const wrapper = mount(MessageBubble, { props: { message: message({ sources: [hostileSource] }) } })

    for (const content of [wrapper.get('.markdown-body'), wrapper.get('.knowledge-sources')]) {
      expect(content.find('img, script, svg, a, [onerror], [onload]').exists()).toBe(false)
    }
    expect(wrapper.get('button[data-citation-index]').attributes('title')).toBe(hostileSource.title)
    expect(wrapper.get('li').text()).toContain(hostileSource.excerpt)
    wrapper.unmount()
  })

  it('keeps older source records without IDs available without claiming a citation match', () => {
    const wrapper = mount(MessageBubble, { props: { message: message({ sources: [{ title: 'Legacy guide' }] }) } })
    expect(wrapper.get('li').text()).toBe('Legacy guide')
    expect(wrapper.get('.citation-unmatched').text()).toBe('[未匹配引用：chunk-123]')
    wrapper.unmount()
  })
})
