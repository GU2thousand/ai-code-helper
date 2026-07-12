<script setup>
import { Braces, GraduationCap, Map, MessagesSquare, Sparkles } from '@lucide/vue'

defineProps({
  disabled: { type: Boolean, default: false }
})

defineEmits(['choose'])

const suggestions = [
  {
    icon: Map,
    title: '制定学习路线',
    description: '根据我的基础规划 8 周 Java 后端学习路线',
    prompt: '我有一些 Java 基础，请帮我制定一份 8 周的后端开发学习路线，包含每周目标、练习和验收标准。',
    tone: 'violet'
  },
  {
    icon: Braces,
    title: '分析代码问题',
    description: '解释报错并给出清晰、可靠的修改建议',
    prompt: '请告诉我怎样提供一段代码和报错信息，才能让你更准确地帮我定位问题。',
    tone: 'blue'
  },
  {
    icon: Sparkles,
    title: '设计实战项目',
    description: '推荐适合作品集的全栈项目与实现步骤',
    prompt: '请为有半年开发经验的学习者设计一个能放进作品集的全栈项目，给出核心功能、技术选型和分阶段实施计划。',
    tone: 'amber'
  },
  {
    icon: MessagesSquare,
    title: '模拟技术面试',
    description: '针对目标岗位逐题追问并提供反馈',
    prompt: '请作为 Java 后端面试官对我进行模拟面试。一次只问一道题，根据我的回答继续追问，最后给出改进建议。',
    tone: 'green'
  }
]
</script>

<template>
  <section class="empty-state" aria-labelledby="welcome-title">
    <div class="empty-state__eyebrow"><Sparkles :size="14" /> 你的随身编程搭档</div>
    <div class="empty-state__hero-icon" aria-hidden="true">
      <GraduationCap :size="34" :stroke-width="1.7" />
    </div>
    <h2 id="welcome-title">今天想一起解决什么？</h2>
    <p class="empty-state__lead">
      我可以帮你梳理知识、调试代码、规划项目，也能陪你准备下一场技术面试。
    </p>

    <div class="suggestion-grid" aria-label="推荐问题">
      <button
        v-for="suggestion in suggestions"
        :key="suggestion.title"
        type="button"
        class="suggestion-card"
        :disabled="disabled"
        @click="$emit('choose', suggestion.prompt)"
      >
        <span class="suggestion-card__icon" :class="`is-${suggestion.tone}`">
          <component :is="suggestion.icon" :size="19" />
        </span>
        <span class="suggestion-card__text">
          <strong>{{ suggestion.title }}</strong>
          <small>{{ suggestion.description }}</small>
        </span>
        <span class="suggestion-card__arrow" aria-hidden="true">↗</span>
      </button>
    </div>
  </section>
</template>
