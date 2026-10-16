<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router_2'
import { useI18n } from '@/hooks/web/useI18n'

const props = defineProps<{ fields?: string[] }>()
const route = useRoute()
const { t } = useI18n()
// Read only the current iframe's URL. Never interpret these values as trusted scope.
const parameters = computed(() => {
  const hashQuery = route.fullPath.split('?').slice(1).join('?')
  return [
    ...Array.from(new URLSearchParams(window.location.search).entries()),
    ...Array.from(new URLSearchParams(hashQuery).entries())
  ].filter(([key]) => props.fields === undefined || props.fields.includes(key))
})
</script>

<template>
  <section class="url-parameters" :aria-label="t('url_parameters.title')">
    <strong>{{ t('url_parameters.title') }}</strong>
    <dl v-if="parameters.length">
      <div v-for="([key, value], index) in parameters" :key="index">
        <dt>{{ key }}</dt>
        <dd>{{ value || '—' }}</dd>
      </div>
    </dl>
    <span v-else>{{ t('url_parameters.empty') }}</span>
  </section>
</template>

<style scoped lang="less">
.url-parameters {
  padding: 14px 18px;
  background: #292b49;
  color: #eef0ff;
  border-bottom: 1px solid #555979;
  font-size: 13px;
  line-height: 1.6;
  strong {
    display: block;
    margin-bottom: 6px;
  }
  dl {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 24px;
    margin: 0;
  }
  dl > div {
    display: flex;
    gap: 8px;
    max-width: 100%;
  }
  dt {
    color: #aebeea;
    flex-shrink: 0;
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
    user-select: text;
  }
}
</style>
