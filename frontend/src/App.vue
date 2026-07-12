<script setup>
import { AlertTriangle, Code2, X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getHealth } from './api/client'
import AppHeader from './components/AppHeader.vue'
import ChatComposer from './components/ChatComposer.vue'
import ChatSidebar from './components/ChatSidebar.vue'
import EmptyState from './components/EmptyState.vue'
import MessageBubble from './components/MessageBubble.vue'
import { useChat } from './composables/useChat'

const {
  conversations,
  activeConversationId,
  messages,
  user,
  ready,
  initializing,
  generating,
  errorMessage,
  initialize,
  startStream,
  stopGeneration,
  regenerateLast,
  newConversation,
  selectConversation,
  clearCurrentConversation,
  dismissError,
  dispose
} = useChat()

const draft = ref('')
const sidebarOpen = ref(false)
const showClearDialog = ref(false)
const healthStatus = ref('checking')
const healthDetails = ref(null)
const messagesViewport = ref(null)
const composer = ref(null)
const sidebarComponent = ref(null)
const headerComponent = ref(null)
const dialogElement = ref(null)
const dialogCancelButton = ref(null)
const autoScroll = ref(true)
const mobileMediaQuery = typeof window !== 'undefined' ? window.matchMedia('(max-width: 800px)') : null
const isMobileSidebar = ref(mobileMediaQuery?.matches ?? false)
let dialogTrigger = null

const backgroundBlocked = computed(() => (
  showClearDialog.value || (isMobileSidebar.value && sidebarOpen.value)
))

const composerDisabledLabel = computed(() => {
  if (initializing.value) return '正在建立安全会话…'
  if (!ready.value) return '连接后端服务后即可开始对话'
  return ''
})

const errorTitle = computed(() => (
  !ready.value ? '后端连接失败' : '回复没有完成'
))

const lastAssistantMessage = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].role === 'assistant') return messages.value[index]
  }
  return null
})

const lastAssistantId = computed(() => lastAssistantMessage.value?.id ?? '')

const generationAnnouncement = computed(() => {
  const status = lastAssistantMessage.value?.status
  if (status === 'done') return 'AI 回复已完成'
  if (status === 'error') return 'AI 回复失败，请查看错误提示'
  if (status === 'stopped') return '已停止生成 AI 回复'
  return ''
})

const serviceLabel = computed(() => {
  const payload = healthDetails.value?.data ?? healthDetails.value ?? {}
  const provider = String(payload.chatProvider ?? payload.provider ?? payload.aiProvider ?? '')
  const model = String(payload.chatModel ?? payload.modelName ?? payload.model ?? '')
  const signature = `${provider} ${model}`.toLowerCase()
  if (/qwen|tongyi|通义千问/.test(signature)) {
    return model ? `通义千问 · ${model}` : '通义千问 · 服务正常'
  }
  return '后端服务正常'
})

async function checkServiceHealth() {
  healthStatus.value = 'checking'
  try {
    healthDetails.value = await getHealth()
    healthStatus.value = 'online'
  } catch {
    healthStatus.value = 'offline'
  }
}

async function retryConnection() {
  await Promise.allSettled([initialize(), checkServiceHealth()])
}

function scrollToBottom(behavior = 'smooth') {
  nextTick(() => {
    const viewport = messagesViewport.value
    if (!viewport) return
    viewport.scrollTo({ top: viewport.scrollHeight, behavior })
  })
}

function handleScroll() {
  const viewport = messagesViewport.value
  if (!viewport) return
  autoScroll.value = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 120
}

function focusAfterDomUpdate(callback) {
  nextTick(() => window.requestAnimationFrame(callback))
}

async function sendDraft() {
  const prompt = draft.value.trim()
  if (!prompt || generating.value || initializing.value || !ready.value) return

  draft.value = ''
  autoScroll.value = true
  scrollToBottom()
  await startStream(prompt)
}

function sendSuggestion(prompt) {
  if (initializing.value || !ready.value) return
  draft.value = prompt
  nextTick(sendDraft)
}

function openSidebar() {
  sidebarOpen.value = true
  focusAfterDomUpdate(() => sidebarComponent.value?.focusClose())
}

function closeSidebar(returnFocus = true) {
  const shouldRestoreFocus = returnFocus && isMobileSidebar.value && sidebarOpen.value
  sidebarOpen.value = false
  if (shouldRestoreFocus) focusAfterDomUpdate(() => headerComponent.value?.focusMenu())
}

function createNewChat() {
  newConversation()
  draft.value = ''
  sidebarOpen.value = false
  focusAfterDomUpdate(() => composer.value?.focus())
}

function switchConversation(id) {
  selectConversation(id)
  draft.value = ''
  autoScroll.value = true
  scrollToBottom('auto')
}

function confirmClear() {
  clearCurrentConversation()
  showClearDialog.value = false
  draft.value = ''
  focusAfterDomUpdate(() => composer.value?.focus())
}

function openClearDialog() {
  dialogTrigger = document.activeElement
  showClearDialog.value = true
  focusAfterDomUpdate(() => dialogCancelButton.value?.focus())
}

function closeClearDialog({ restoreFocus = true } = {}) {
  if (!showClearDialog.value) return
  showClearDialog.value = false
  if (restoreFocus) {
    const target = dialogTrigger
    focusAfterDomUpdate(() => target?.focus?.())
  }
}

function trapDialogFocus(event) {
  if (event.key !== 'Tab') return
  const focusable = [...dialogElement.value.querySelectorAll(
    'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
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

function handleMobileChange(event) {
  isMobileSidebar.value = event.matches
  if (!event.matches) sidebarOpen.value = false
}

async function handleRegenerate() {
  autoScroll.value = true
  await regenerateLast()
  scrollToBottom()
}

function handleGlobalKeydown(event) {
  if (showClearDialog.value) {
    if (event.key === 'Escape') closeClearDialog()
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') event.preventDefault()
    return
  }

  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    createNewChat()
  }
  if (event.key === 'Escape') {
    if (sidebarOpen.value) closeSidebar()
  }
}

watch(
  () => messages.value.map((message) => `${message.id}:${message.content.length}:${message.status}`).join('|'),
  () => {
    if (autoScroll.value) scrollToBottom(generating.value ? 'auto' : 'smooth')
  }
)

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  if (mobileMediaQuery?.addEventListener) mobileMediaQuery.addEventListener('change', handleMobileChange)
  else mobileMediaQuery?.addListener?.(handleMobileChange)
  Promise.allSettled([initialize(), checkServiceHealth()])
  scrollToBottom('auto')
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  if (mobileMediaQuery?.removeEventListener) mobileMediaQuery.removeEventListener('change', handleMobileChange)
  else mobileMediaQuery?.removeListener?.(handleMobileChange)
  dispose()
})
</script>

<template>
  <a
    class="skip-link"
    href="#chat-content"
    :aria-hidden="backgroundBlocked ? 'true' : undefined"
    :inert="backgroundBlocked ? '' : null"
  >跳到对话内容</a>
  <div class="app-shell">
    <ChatSidebar
      ref="sidebarComponent"
      :conversations="conversations"
      :active-conversation-id="activeConversationId"
      :user="user"
      :open="sidebarOpen"
      :mobile="isMobileSidebar"
      :background-inert="showClearDialog"
      @new="createNewChat"
      @select="switchConversation"
      @close="closeSidebar"
    />

    <main
      id="chat-content"
      class="main-panel"
      :aria-hidden="backgroundBlocked ? 'true' : undefined"
      :inert="backgroundBlocked ? '' : null"
    >
      <AppHeader
        ref="headerComponent"
        :health-status="healthStatus"
        :service-label="serviceLabel"
        :has-messages="messages.length > 0"
        :sidebar-open="sidebarOpen"
        @menu="openSidebar"
        @clear="openClearDialog"
      />

      <div class="chat-stage">
        <div
          ref="messagesViewport"
          class="messages-viewport"
          :class="{ 'is-empty': messages.length === 0 }"
          @scroll.passive="handleScroll"
        >
          <EmptyState
            v-if="messages.length === 0"
            :disabled="initializing || !ready"
            @choose="sendSuggestion"
          />

          <div
            v-else
            class="message-list"
            role="log"
            aria-label="对话消息"
            aria-live="off"
          >
            <MessageBubble
              v-for="message in messages"
              :key="message.id"
              :message="message"
              :can-regenerate="message.id === lastAssistantId && !generating && ready"
              @regenerate="handleRegenerate"
            />
          </div>
          <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">
            {{ generationAnnouncement }}
          </p>
        </div>

        <div class="composer-area">
          <ChatComposer
            ref="composer"
            v-model="draft"
            :generating="generating"
            :disabled="initializing || !ready"
            :disabled-label="composerDisabledLabel"
            @send="sendDraft"
            @stop="stopGeneration"
          />
        </div>
      </div>
    </main>

    <Transition name="toast">
      <div
        v-if="errorMessage"
        class="error-toast"
        role="alert"
        :aria-hidden="backgroundBlocked ? 'true' : undefined"
        :inert="backgroundBlocked ? '' : null"
      >
        <span class="error-toast__icon"><AlertTriangle :size="17" /></span>
        <div>
          <strong>{{ errorTitle }}</strong>
          <p>{{ errorMessage }}</p>
          <button
            v-if="!ready"
            type="button"
            class="error-toast__retry"
            :disabled="initializing"
            @click="retryConnection"
          >{{ initializing ? '正在重连…' : '重新连接' }}</button>
        </div>
        <button type="button" aria-label="关闭提示" @click="dismissError"><X :size="17" /></button>
      </div>
    </Transition>

    <Transition name="modal">
      <div v-if="showClearDialog" class="dialog-layer" role="presentation" @click.self="closeClearDialog()">
        <section
          ref="dialogElement"
          class="confirm-dialog"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="clear-dialog-title"
          aria-describedby="clear-dialog-description"
          @keydown="trapDialogFocus"
        >
          <div class="confirm-dialog__icon" aria-hidden="true"><Code2 :size="22" /></div>
          <h2 id="clear-dialog-title">清空当前对话？</h2>
          <p id="clear-dialog-description">消息会从这台设备上删除，同时开始一段全新的会话。此操作无法撤销。</p>
          <div class="confirm-dialog__actions">
            <button ref="dialogCancelButton" type="button" class="button-secondary" @click="closeClearDialog()">取消</button>
            <button type="button" class="button-danger" @click="confirmClear">确认清空</button>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>
