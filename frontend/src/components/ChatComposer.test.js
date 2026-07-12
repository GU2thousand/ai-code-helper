import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatComposer from './ChatComposer.vue'

describe('ChatComposer', () => {
  it('sends on Enter and keeps Shift+Enter for a new line', async () => {
    const wrapper = mount(ChatComposer, { props: { modelValue: '解释这段代码' } })
    const textarea = wrapper.get('textarea')

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('send')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('shows a stop control while a reply is streaming', async () => {
    const wrapper = mount(ChatComposer, {
      props: { modelValue: '', generating: true }
    })
    const stopButton = wrapper.get('[aria-label="停止生成"]')
    await stopButton.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
  })

  it('disables input and sending while the secure guest session is not ready', async () => {
    const wrapper = mount(ChatComposer, {
      props: {
        modelValue: '不能提前发送',
        disabled: true,
        disabledLabel: '正在建立安全会话…'
      }
    })

    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.get('textarea').attributes('placeholder')).toBe('正在建立安全会话…')
    expect(wrapper.get('[aria-label="发送消息"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="发送消息"]').trigger('click')
    expect(wrapper.emitted('send')).toBeUndefined()
  })
})
