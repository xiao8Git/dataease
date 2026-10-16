<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from '@/hooks/web/useI18n'
import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'
import { snapshotStoreWithOut } from '@/store/modules/data-visualization/snapshot'

const { t } = useI18n()
const { canvasStyleData } = storeToRefs(dvMainStoreWithOut())
const snapshotStore = snapshotStoreWithOut()
const commonFields = ['tenant_id', 'user_id', 'ticket', 'iat', 'exp', 'nonce', 'kid', 'sig']
const recordChange = () => snapshotStore.recordSnapshotCache('onThemeChange')
const fields = computed({
  get: () => canvasStyleData.value.urlParameterFields ?? commonFields,
  set: (value: string[]) => {
    canvasStyleData.value.urlParameterFields = [
      ...new Set(value.map(v => v.trim()).filter(Boolean))
    ]
    recordChange()
  }
})
const changeVisibility = () => {
  if (canvasStyleData.value.urlParameterFields === undefined) {
    canvasStyleData.value.urlParameterFields = [...commonFields]
  }
  recordChange()
}
</script>

<template>
  <el-form size="small" label-position="top">
    <el-form-item>
      <el-checkbox v-model="canvasStyleData.showUrlParameters" @change="changeVisibility">
        {{ t('url_parameters.enabled') }}
      </el-checkbox>
    </el-form-item>
    <el-form-item :label="t('url_parameters.fields')">
      <el-select
        v-model="fields"
        multiple
        filterable
        allow-create
        default-first-option
        :disabled="!canvasStyleData.showUrlParameters"
        :placeholder="t('url_parameters.placeholder')"
        style="width: 100%"
      >
        <el-option v-for="field in commonFields" :key="field" :label="field" :value="field" />
      </el-select>
    </el-form-item>
    <p class="parameter-hint">{{ t('url_parameters.hint') }}</p>
  </el-form>
</template>

<style scoped lang="less">
.parameter-hint {
  color: #646a73;
  font-size: 12px;
  line-height: 1.6;
}
</style>
