<script lang="ts" setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import { useI18n } from '@/hooks/web/useI18n'
import { queryEmbedKeys, saveEmbedKey, type EmbedKey } from '@/api/embedKeys'

const { t } = useI18n()
const loading = ref(false)
const ready = ref(false)
const rows = ref<EmbedKey[]>([])
const visible = ref(false)
const editing = ref(false)
const maskedSecret = ref('')
const form = reactive({ kid: '', tenant: '', secretBase64: '', enabled: true })
const load = async () => {
  loading.value = true
  try {
    const { data } = await queryEmbedKeys()
    rows.value = data
    ready.value = true
  } finally {
    loading.value = false
  }
}
const open = (row?: EmbedKey) => {
  editing.value = !!row
  maskedSecret.value = row?.maskedSecret ?? ''
  Object.assign(form, {
    kid: row?.kid ?? '',
    tenant: row?.tenant ?? '',
    enabled: row?.enabled ?? true,
    secretBase64: ''
  })
  visible.value = true
}
const generate = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  form.secretBase64 = btoa(String.fromCharCode(...bytes))
}
const save = async () => {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(form.kid) || !/^[A-Za-z0-9_.:@/-]{1,128}$/.test(form.tenant)) {
    ElMessage.error(t('embed_keys.invalid_identity'))
    return
  }
  try {
    if (form.secretBase64) {
      const bytes = atob(form.secretBase64)
      if (bytes.length < 32 || bytes.length > 128 || btoa(bytes) !== form.secretBase64) {
        throw new Error('Invalid key')
      }
    } else if (!editing.value) {
      throw new Error('Missing key')
    }
  } catch {
    ElMessage.error(t('embed_keys.invalid_secret'))
    return
  }
  loading.value = true
  try {
    await saveEmbedKey({ ...form })
    form.secretBase64 = ''
    visible.value = false
    ElMessage.success(t('common.save_success'))
    await load()
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="embed-keys">
    <h3>{{ t('embed_keys.title') }}</h3>
    <p>{{ t('embed_keys.help') }}</p>
    <el-button type="primary" :disabled="!ready || loading" @click="open()">
      {{ t('embed_keys.add') }}
    </el-button>
    <el-table :data="rows" class="key-table">
      <el-table-column prop="tenant" :label="t('embed_keys.tenant')" />
      <el-table-column prop="kid" label="kid" />
      <el-table-column :label="t('embed_keys.status')">
        <template #default="{ row }">
          {{ t(row.enabled ? 'embed_keys.enabled' : 'embed_keys.disabled') }}
        </template>
      </el-table-column>
      <el-table-column :label="t('embed_keys.source')">
        <template #default="{ row }">
          {{ t(row.managed ? 'embed_keys.managed' : 'embed_keys.deployment') }}
        </template>
      </el-table-column>
      <el-table-column :label="t('embed_keys.actions')" width="140">
        <template #default="{ row }">
          <el-button text :disabled="loading" @click="open(row)">
            {{ t('embed_keys.edit') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="visible"
      :title="t(editing ? 'embed_keys.edit' : 'embed_keys.add')"
      width="600px"
      :close-on-click-modal="false"
      :close-on-press-escape="!loading"
      :show-close="!loading"
      destroy-on-close
      @closed="form.secretBase64 = ''"
    >
      <el-form label-position="top" :disabled="loading" @submit.prevent="save">
        <el-form-item :label="t('embed_keys.tenant')">
          <el-input v-model="form.tenant" :disabled="editing" :maxlength="128" />
        </el-form-item>
        <el-form-item label="kid">
          <el-input v-model="form.kid" :disabled="editing" :maxlength="64" />
        </el-form-item>
        <p>{{ t('embed_keys.identity_help') }}</p>
        <el-form-item :label="t('embed_keys.secret')">
          <el-input
            v-model="form.secretBase64"
            type="password"
            show-password
            autocomplete="new-password"
            :maxlength="172"
            :placeholder="
              editing ? maskedSecret || t('embed_keys.keep_secret') : t('embed_keys.invalid_secret')
            "
          />
        </el-form-item>
        <p v-if="editing">{{ t('embed_keys.keep_secret') }}</p>
        <el-button :disabled="loading" @click="generate">{{ t('embed_keys.generate') }}</el-button>
        <p>{{ t('embed_keys.secret_help') }}</p>
        <el-form-item :label="t('embed_keys.status')">
          <el-switch v-model="form.enabled" :active-text="t('embed_keys.enabled')" />
        </el-form-item>
        <p>{{ t('embed_keys.effect') }}</p>
      </el-form>
      <template #footer>
        <el-button :disabled="loading" @click="visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :disabled="loading" @click="save">{{
          t('commons.save')
        }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="less">
.embed-keys {
  padding: 24px;
  p {
    line-height: 1.6;
  }
  .key-table {
    margin-top: 16px;
  }
}
</style>
