<template>
  <div class="empty-state">
    <div class="empty-illustration">
      <slot name="illustration">
        <img v-if="illustration" :src="illustration" :alt="title" class="empty-img" />
      </slot>
    </div>
    <h4 class="empty-title">{{ title }}</h4>
    <p v-if="desc" class="empty-desc">{{ desc }}</p>
    <div v-if="$slots.action" class="empty-action">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'EmptyState' })
defineProps<{
  title: string
  desc?: string
  illustration?: string
}>()
</script>

<style scoped>
.empty-state {
  display: flex; flex-direction: column; align-items: center;
  padding: 48px 24px; text-align: center;
}
.empty-illustration { margin-bottom: 16px; }
.empty-img { width: 120px; height: 120px; opacity: 0.85; }
.empty-title { margin: 0 0 6px; font-size: 15px; font-weight: 600; color: var(--c-text); }
.empty-desc { margin: 0 0 16px; font-size: 13px; color: var(--c-text-2); line-height: 1.6; max-width: 320px; }
.empty-action { display: flex; gap: 8px; }
</style>
