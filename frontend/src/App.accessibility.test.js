import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const nextFrame = () => new Promise((resolve) => requestAnimationFrame(resolve))
const chatSpies = vi.hoisted(() => ({ newConversation: vi.fn() }))

vi.mock('./api/client', () => ({
  getHealth: vi.fn().mockResolvedValue({
    status: 'UP',
    chatProvider: 'DashScope',
    chatModel: 'qwen-max'
  })
}))

vi.mock('./composables/useChat', async () => {
  const { computed, ref } = await import('vue')
  return {
    useChat: () => {
      const messages = ref([{
        id: 'assistant-1',
        role: 'assistant',
        content: '一条已有回复',
        status: 'done',
        createdAt: new Date().toISOString()
      }])
      const conversations = ref([{
        id: 'conversation-1',
        title: '测试对话',
        updatedAt: new Date().toISOString(),
        messages: messages.value
      }])
      return {
        conversations,
        activeConversationId: ref('conversation-1'),
        messages: computed(() => messages.value),
        user: ref({ id: 'guest-1', name: '访客' }),
        ready: ref(true),
        initializing: ref(false),
        generating: ref(false),
        errorMessage: ref(''),
        initialize: vi.fn().mockResolvedValue(undefined),
        startStream: vi.fn().mockResolvedValue(true),
        stopGeneration: vi.fn(),
        regenerateLast: vi.fn().mockResolvedValue(true),
        newConversation: chatSpies.newConversation,
        selectConversation: vi.fn(),
        clearCurrentConversation: vi.fn(() => { messages.value = [] }),
        dismissError: vi.fn(),
        dispose: vi.fn()
      }
    }
  }
})

describe('clear dialog accessibility', () => {
  it('moves and traps focus, blocks the background, and restores the trigger on Escape', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('通义千问 · qwen-max')
    expect(wrapper.get('[role="log"]').attributes()).toMatchObject({
      'aria-label': '对话消息',
      'aria-live': 'off'
    })
    expect(wrapper.get('[role="status"]').text()).toContain('AI 回复已完成')

    const trigger = wrapper.get('[aria-label="清空当前对话记录"]')
    trigger.element.focus()
    await trigger.trigger('click')
    await nextTick()
    await nextFrame()

    const dialog = wrapper.get('[role="alertdialog"]')
    const cancel = wrapper.get('.button-secondary')
    const confirm = wrapper.get('.button-danger')
    expect(dialog.attributes('aria-describedby')).toBe('clear-dialog-description')
    expect(document.activeElement).toBe(cancel.element)
    expect(wrapper.get('main').attributes()).toHaveProperty('inert')
    expect(wrapper.get('aside').attributes()).toHaveProperty('inert')

    const shortcut = new KeyboardEvent('keydown', { key: 'k', metaKey: true, cancelable: true })
    window.dispatchEvent(shortcut)
    expect(shortcut.defaultPrevented).toBe(true)
    expect(chatSpies.newConversation).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(true)

    await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(confirm.element)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    await nextFrame()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})
