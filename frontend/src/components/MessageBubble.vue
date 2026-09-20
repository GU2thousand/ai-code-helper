<script setup>
import { Check, Clipboard, Code2, RefreshCw, UserRound } from '@lucide/vue'
import { computed, ref } from 'vue'
import { copyText } from '../utils/chat'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  message: { type: Object, required: true },
  canRegenerate: { type: Boolean, default: false }
})

defineEmits(['regenerate'])

const copied = ref(false)
const renderedContent = computed(() => renderMarkdown(props.message.content))
const displayTime = computed(() => {
  const date = new Date(props.message.createdAt)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
})

let copiedTimer

async function copyReply() {
  await copyText(props.message.content)
  copied.value = true
  clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => { copied.value = false }, 1600)
}

async function handleContentClick(event) {
  const button = event.target.closest?.('[data-copy-code]')
  if (!button) return
  const code = button.closest('.code-block')?.querySelector('code')?.textContent
  if (code == null) return

  await copyText(code)
  const previous = button.textContent
  button.textContent = '已复制'
  button.classList.add('is-copied')
  window.setTimeout(() => {
    button.textContent = previous
    button.classList.remove('is-copied')
  }, 1500)
}
</script>

<template>
  <article class="message-row" :class="`is-${message.role}`">
    <div v-if="message.role === 'assistant'" class="message-avatar is-assistant" aria-hidden="true">
      <Code2 :size="18" :stroke-width="2.4" />
    </div>

    <div class="message-body">
      <div class="message-meta">
        <strong>{{ message.role === 'assistant' ? '灵码助手' : '你' }}</strong>
        <time v-if="displayTime" :datetime="message.createdAt">{{ displayTime }}</time>
      </div>

      <div v-if="message.role === 'user'" class="user-message">
        {{ message.content }}
      </div>

      <div v-else class="assistant-message">
        <div v-if="message.content" class="markdown-body" @click="handleContentClick" v-html="renderedContent" />

        <div v-if="message.status === 'streaming' && !message.content" class="thinking-indicator" aria-label="AI 正在思考">
          <span /><span /><span />
          <em>正在思考</em>
        </div>
        <span v-else-if="message.status === 'streaming'" class="streaming-cursor" aria-label="正在生成" />

        <div v-if="message.status === 'error'" class="message-status is-error">
          <span>{{ message.error || '生成遇到问题，请重新尝试。' }}</span>
        </div>
        <div v-else-if="message.status === 'stopped'" class="message-status">
          已停止生成
        </div>

        <details v-if="message.sources?.length" class="knowledge-sources">
          <summary>参考知识库（{{ message.sources.length }}）</summary>
          <ul><li v-for="(source, index) in message.sources" :key="index">{{ source.title }}</li></ul>
        </details>

        <div v-if="message.content && message.status !== 'streaming'" class="message-actions">
          <button type="button" :aria-label="copied ? '回复已复制' : '复制整条回复'" @click="copyReply">
            <Check v-if="copied" :size="15" />
            <Clipboard v-else :size="15" />
            <span>{{ copied ? '已复制' : '复制' }}</span>
          </button>
          <button v-if="canRegenerate" type="button" aria-label="重新生成回复" @click="$emit('regenerate')">
            <RefreshCw :size="15" />
            <span>重新生成</span>
          </button>
        </div>
      </div>
    </div>

    <div v-if="message.role === 'user'" class="message-avatar is-user" aria-hidden="true">
      <UserRound :size="17" />
    </div>
  </article>
</template>
