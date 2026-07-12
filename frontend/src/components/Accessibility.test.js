import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppHeader from './AppHeader.vue'
import ChatSidebar from './ChatSidebar.vue'
import MessageBubble from './MessageBubble.vue'

const conversation = {
  id: 'conversation-1',
  title: '测试对话',
  updatedAt: new Date().toISOString(),
  messages: []
}

describe('navigation accessibility', () => {
  it('removes a closed mobile sidebar from focus order and traps focus when opened', async () => {
    const wrapper = mount(ChatSidebar, {
      attachTo: document.body,
      props: {
        conversations: [conversation],
        activeConversationId: conversation.id,
        mobile: true,
        open: false
      }
    })

    const sidebar = wrapper.get('aside')
    expect(sidebar.attributes('aria-hidden')).toBe('true')
    expect(sidebar.attributes()).toHaveProperty('inert')

    await wrapper.setProps({ open: true })
    expect(sidebar.attributes('aria-hidden')).toBeUndefined()
    expect(sidebar.attributes('inert')).toBeUndefined()
    wrapper.vm.focusClose()
    expect(document.activeElement).toBe(wrapper.get('[aria-label="关闭侧边栏"]').element)

    wrapper.get('.conversation-item').element.focus()
    await sidebar.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(wrapper.get('.brand').element)
    wrapper.unmount()
  })

  it('exposes sidebar state and ownership from the menu trigger', async () => {
    const wrapper = mount(AppHeader, {
      props: { sidebarOpen: false }
    })
    const menu = wrapper.get('[aria-label="打开侧边栏"]')

    expect(menu.attributes('aria-controls')).toBe('conversation-sidebar')
    expect(menu.attributes('aria-expanded')).toBe('false')
    await wrapper.setProps({ sidebarOpen: true })
    expect(menu.attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })
})

describe('message accessibility', () => {
  it('leaves assertive error announcement to the single app-level toast', () => {
    const wrapper = mount(MessageBubble, {
      props: {
        message: {
          id: 'failed-assistant',
          role: 'assistant',
          content: '部分回复',
          status: 'error',
          error: '连接中断',
          createdAt: new Date().toISOString()
        }
      }
    })

    expect(wrapper.get('.message-status').attributes('role')).toBeUndefined()
    expect(wrapper.text()).toContain('连接中断')
  })
})
