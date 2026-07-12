<script setup>
import { Menu, PanelTop, Trash2 } from '@lucide/vue'
import { ref } from 'vue'

defineProps({
  healthStatus: {
    type: String,
    default: 'checking',
    validator: (value) => ['checking', 'online', 'offline'].includes(value)
  },
  serviceLabel: { type: String, default: '后端服务正常' },
  hasMessages: { type: Boolean, default: false },
  sidebarOpen: { type: Boolean, default: false }
})

const emit = defineEmits(['menu', 'clear'])
const menuButton = ref(null)
const clearButton = ref(null)

function focusMenu() {
  menuButton.value?.focus()
}

function focusClear() {
  clearButton.value?.focus()
}

defineExpose({ focusMenu, focusClear })
</script>

<template>
  <header class="app-header">
    <div class="app-header__left">
      <button
        ref="menuButton"
        class="icon-button app-header__menu"
        type="button"
        aria-label="打开侧边栏"
        aria-controls="conversation-sidebar"
        :aria-expanded="sidebarOpen"
        @click="emit('menu')"
      >
        <Menu :size="21" />
      </button>
      <span class="app-header__icon" aria-hidden="true"><PanelTop :size="18" /></span>
      <div>
        <h1>AI 编程助手</h1>
        <div class="service-status" :class="`is-${healthStatus}`">
          <span class="service-status__dot" />
          <span v-if="healthStatus === 'online'">{{ serviceLabel }}</span>
          <span v-else-if="healthStatus === 'offline'">服务暂不可用</span>
          <span v-else>正在检查服务</span>
        </div>
      </div>
    </div>

    <button
      ref="clearButton"
      class="header-action"
      type="button"
      :disabled="!hasMessages"
      aria-label="清空当前对话记录"
      @click="emit('clear')"
    >
      <Trash2 :size="16" />
      <span>清空记录</span>
    </button>
  </header>
</template>
