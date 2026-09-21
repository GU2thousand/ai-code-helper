<script setup>
import { Check, Clipboard, Code2, RefreshCw, UserRound } from '@lucide/vue'
import { computed, nextTick, ref } from 'vue'
import { copyText } from '../utils/chat'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  message: { type: Object, required: true },
  canRegenerate: { type: Boolean, default: false }
})

defineEmits(['regenerate'])

const copied = ref(false)
const sourceDetails = ref(null)
const sourceEntries = ref([])
const sources = computed(() => Array.isArray(props.message.sources) ? props.message.sources : [])
const renderedContent = computed(() => renderMarkdown(props.message.content, sources.value))
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
  const citation = event.target.closest?.('button[data-citation-index]')
  if (citation) {
    const index = Number(citation.dataset.citationIndex)
    if (Number.isInteger(index) && sources.value[index] && sourceDetails.value) {
      sourceDetails.value.open = true
      await nextTick()
      sourceEntries.value[index]?.focus()
    }
    return
  }
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

        <details v-if="sources.length" ref="sourceDetails" class="knowledge-sources">
          <summary>参考知识库（{{ sources.length }}）</summary>
          <p class="citation-note">引用标记对应检索片段，未自动核验结论。</p>
          <ol>
            <li v-for="(source, index) in sources" :key="index" :ref="element => { sourceEntries[index] = element }" tabindex="-1">
              <strong>{{ source.title }}</strong>
              <span v-if="typeof source.location === 'string' && source.location" class="source-location">{{ source.location }}</span>
              <p v-if="typeof source.excerpt === 'string' && source.excerpt" class="source-excerpt">{{ source.excerpt }}</p>
            </li>
          </ol>
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

<style scoped>
:deep(.citation-reference) {
  padding: 0 0.18em;
  border: 0;
  border-radius: 3px;
  background: transparent;
  color: var(--accent, #2766c8);
  font: inherit;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

:deep(.citation-reference:focus-visible),
.knowledge-sources li:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 3px;
}

:deep(.citation-unmatched),
.citation-note,
.source-location {
  font-size: 0.85em;
}

.source-location {
  display: block;
}

.knowledge-sources li {
  margin-bottom: 0.65em;
}

.source-excerpt {
  margin: 0.3em 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
