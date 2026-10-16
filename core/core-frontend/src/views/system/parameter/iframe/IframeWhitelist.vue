<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import { useI18n } from '@/hooks/web/useI18n'
import {
  queryIframeWhitelist,
  saveIframeWhitelist,
  type IframeWhitelist
} from '@/api/iframeWhitelist'

const { t } = useI18n()
const loading = ref(false)
const ready = ref(false)
const origins = ref('')
const enabled = ref(true)
const settings = ref<IframeWhitelist>({
  origins: [],
  enabled: true,
  managed: false,
  effectivePolicy: ''
})

const load = async () => {
  loading.value = true
  try {
    const { data } = await queryIframeWhitelist()
    settings.value = data
    enabled.value = data.enabled !== false
    origins.value = data.origins.join('\n')
    ready.value = true
  } finally {
    loading.value = false
  }
}

const save = async () => {
  const values = origins.value
    .split(/\r?\n/)
    .map(value => value.trim())
    .filter(Boolean)
  if (values.length > 50) {
    ElMessage.error(t('iframe_whitelist.invalid'))
    return
  }
  for (const value of values) {
    try {
      const url = new URL(value)
      if (
        !/^https?:\/\/[^/?#]+\/?$/i.test(value) ||
        /[\s*]/.test(value) ||
        url.username ||
        url.password ||
        url.port === '0' ||
        value.length > 255
      ) {
        throw new Error('Invalid origin')
      }
    } catch {
      ElMessage.error(t('iframe_whitelist.invalid'))
      return
    }
  }
  loading.value = true
  try {
    await saveIframeWhitelist(values, enabled.value)
    ElMessage.success(t('common.save_success'))
    await load()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="iframe-whitelist">
    <h3>{{ t('iframe_whitelist.title') }}</h3>
    <p>{{ t('iframe_whitelist.help') }}</p>
    <el-alert
      v-if="ready && !settings.managed"
      :title="t('iframe_whitelist.deployment')"
      type="info"
      :closable="false"
      show-icon
    />
    <p v-if="ready" class="current-policy">
      {{ t('iframe_whitelist.current') }}:
      {{ settings.enabled ? settings.effectivePolicy : t('iframe_whitelist.unrestricted') }}
    </p>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item :label="t('iframe_whitelist.enabled')">
        <el-switch v-model="enabled" :disabled="!ready || loading" />
      </el-form-item>
      <p>{{ t('iframe_whitelist.toggle_help') }}</p>
      <el-form-item :label="t('iframe_whitelist.origins')">
        <el-input
          v-model="origins"
          type="textarea"
          :rows="8"
          :disabled="!ready || loading || !enabled"
          :placeholder="t('iframe_whitelist.placeholder')"
          :maxlength="8192"
        />
      </el-form-item>
      <p>{{ t('iframe_whitelist.effect') }}</p>
      <el-button type="primary" :disabled="!ready || loading" @click="save">
        {{ t('commons.save') }}
      </el-button>
      <el-button secondary :disabled="loading" @click="load">
        {{ t('common.cancel') }}
      </el-button>
    </el-form>
  </div>
</template>

<style scoped lang="less">
.iframe-whitelist {
  padding: 24px;
  max-width: 900px;
  p {
    line-height: 1.6;
  }
  .current-policy {
    overflow-wrap: anywhere;
  }
}
</style>
