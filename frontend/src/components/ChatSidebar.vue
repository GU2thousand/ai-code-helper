<script setup>
import {
  Code2,
  MessageSquareText,
  PanelLeftClose,
  Plus,
  Sparkles
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { formatConversationTime } from '../utils/chat'

const props = defineProps({
  conversations: { type: Array, required: true },
  activeConversationId: { type: String, required: true },
  user: { type: Object, default: null },
  open: { type: Boolean, default: false },
  mobile: { type: Boolean, default: false },
  backgroundInert: { type: Boolean, default: false }
})

const emit = defineEmits(['new', 'select', 'close'])
const sidebarElement = ref(null)
const closeButton = ref(null)
const isInactive = computed(() => props.backgroundInert || (props.mobile && !props.open))

const sortedConversations = computed(() => (
  [...props.conversations].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt))
))

const userInitial = computed(() => props.user?.name?.trim()?.slice(0, 1) || '访')

function selectConversation(id) {
  emit('select', id)
  emit('close')
}

function focusClose() {
  closeButton.value?.focus()
}

function trapFocus(event) {
  if (!props.mobile || !props.open || event.key !== 'Tab') return
  const focusable = [...sidebarElement.value.querySelectorAll(
    'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )]
  if (!focusable.length) return

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

defineExpose({ focusClose })
</script>

<template>
  <div
    class="sidebar-backdrop"
    :class="{ 'is-visible': open }"
    aria-hidden="true"
    @click="emit('close')"
  />

  <aside
    id="conversation-sidebar"
    ref="sidebarElement"
    class="sidebar"
    :class="{ 'is-open': open, 'is-mobile': mobile }"
    aria-label="对话侧边栏"
    :aria-hidden="isInactive ? 'true' : undefined"
    :inert="isInactive ? '' : null"
    @keydown="trapFocus"
  >
    <div class="sidebar__brand-row">
      <a href="#" class="brand" aria-label="灵码首页" @click.prevent="emit('new')">
        <span class="brand__mark" aria-hidden="true"><Code2 :size="20" :stroke-width="2.4" /></span>
        <span class="brand__name">灵码</span>
        <span class="brand__tag">AI</span>
      </a>
      <button ref="closeButton" class="icon-button sidebar__close" type="button" aria-label="关闭侧边栏" @click="emit('close')">
        <PanelLeftClose :size="19" />
      </button>
    </div>

    <button class="new-chat-button" type="button" @click="emit('new')">
      <Plus :size="18" :stroke-width="2.2" />
      <span>新建对话</span>
      <kbd>⌘ K</kbd>
    </button>

    <div class="sidebar__section-heading">
      <span>最近对话</span>
      <span>{{ conversations.length }}</span>
    </div>

    <nav class="conversation-list" aria-label="历史对话">
      <button
        v-for="conversation in sortedConversations"
        :key="conversation.id"
        type="button"
        class="conversation-item"
        :class="{ 'is-active': conversation.id === activeConversationId }"
        :aria-current="conversation.id === activeConversationId ? 'page' : undefined"
        @click="selectConversation(conversation.id)"
      >
        <MessageSquareText class="conversation-item__icon" :size="17" />
        <span class="conversation-item__content">
          <span class="conversation-item__title">{{ conversation.title }}</span>
          <span class="conversation-item__meta">
            {{ conversation.messages.length ? `${conversation.messages.length} 条消息` : '尚未开始' }}
          </span>
        </span>
        <time class="conversation-item__time" :datetime="conversation.updatedAt">
          {{ formatConversationTime(conversation.updatedAt) }}
        </time>
      </button>
    </nav>

    <div class="sidebar__tip">
      <span class="sidebar__tip-icon"><Sparkles :size="15" /></span>
      <p><strong>小提示</strong>描述目标、技术栈和报错信息，回答会更准确。</p>
    </div>

    <div class="sidebar__user">
      <span class="user-avatar" aria-hidden="true">
        <img v-if="user?.avatar" :src="user.avatar" alt="" />
        <span v-else>{{ userInitial }}</span>
      </span>
      <span class="sidebar__user-info">
        <strong>{{ user?.name || '正在连接…' }}</strong>
        <small>{{ user?.isLocalFallback ? '本地访客模式' : '访客账户' }}</small>
      </span>
      <span class="sidebar__user-status" :title="user?.isLocalFallback ? '离线模式' : '已连接'" />
    </div>
  </aside>
</template>
