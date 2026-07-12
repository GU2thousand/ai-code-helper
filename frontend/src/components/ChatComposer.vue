<script setup>
import { ArrowUp, BookOpenCheck, Square } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  generating: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  disabledLabel: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'send', 'stop'])
const textarea = ref(null)
const isComposing = ref(false)
const MAX_LENGTH = 4000

const canSend = computed(() => (
  props.modelValue.trim().length > 0 && !props.generating && !props.disabled
))

function resizeTextarea() {
  const element = textarea.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 180)}px`
}

function updateValue(event) {
  emit('update:modelValue', event.target.value)
  resizeTextarea()
}

function send() {
  if (canSend.value) emit('send')
}

function handleKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || isComposing.value || event.isComposing) return
  event.preventDefault()
  send()
}

function focus() {
  textarea.value?.focus()
}

watch(() => props.modelValue, () => nextTick(resizeTextarea))
defineExpose({ focus })
</script>

<template>
  <div class="composer-shell">
    <div class="composer" :class="{ 'is-generating': generating, 'is-disabled': disabled }">
      <textarea
        ref="textarea"
        :value="modelValue"
        :maxlength="MAX_LENGTH"
        :disabled="disabled"
        rows="1"
        aria-label="给 AI 编程助手发送消息"
        :placeholder="disabled && disabledLabel ? disabledLabel : '描述你的问题、粘贴代码或说说学习目标…'"
        @input="updateValue"
        @keydown="handleKeydown"
        @compositionstart="isComposing = true"
        @compositionend="isComposing = false"
      />

      <div class="composer__toolbar">
        <div class="composer__context" :title="disabled ? disabledLabel : '回答会结合项目知识库'">
          <BookOpenCheck :size="15" />
          <span>{{ disabled ? '等待连接' : '知识增强' }}</span>
        </div>
        <span v-if="modelValue.length > 3200" class="composer__count">
          {{ modelValue.length }}/{{ MAX_LENGTH }}
        </span>
        <button
          v-if="generating"
          type="button"
          class="send-button is-stop"
          aria-label="停止生成"
          title="停止生成"
          @click="emit('stop')"
        >
          <Square :size="14" fill="currentColor" />
        </button>
        <button
          v-else
          type="button"
          class="send-button"
          :disabled="!canSend"
          aria-label="发送消息"
          title="发送消息"
          @click="send"
        >
          <ArrowUp :size="19" :stroke-width="2.4" />
        </button>
      </div>
    </div>
    <p class="composer-hint">
      <span>Enter 发送 · Shift + Enter 换行</span>
      <span>AI 生成内容仅供参考，请核实关键信息</span>
    </p>
  </div>
</template>
