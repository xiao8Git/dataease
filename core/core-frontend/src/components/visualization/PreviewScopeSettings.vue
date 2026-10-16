<script setup lang="ts">
import { computed, onBeforeUnmount, reactive } from 'vue'
import { useUserStoreWithOut } from '@/store/modules/user'
import { useLinkStoreWithOut } from '@/store/modules/link'
import { useEmbedded } from '@/store/modules/embedded'
import { useI18n } from '@/hooks/web/useI18n'
import { ElMessage } from 'element-plus-secondary'
import { clearPreviewScope, dashboardPreviewScope } from '@/utils/dashboardPreviewScope'
const props = defineProps({ publicLink: Boolean })
const emit = defineEmits(['apply'])
const { t } = useI18n()
const user = useUserStoreWithOut()
const link = useLinkStoreWithOut()
const embedded = useEmbedded()
const allowed = computed(
  () => !props.publicLink && !link.getLinkToken && !embedded.token && String(user.getUid) === '1'
)
const draft = reactive({ tenant: dashboardPreviewScope.tenant, user: dashboardPreviewScope.user })
const apply = () => {
  if (!allowed.value) return
  if (![draft.tenant, draft.user].every(value => /^[A-Za-z0-9_.:@/-]{1,128}$/.test(value))) {
    ElMessage.warning(t('preview_scope.invalid'))
    return
  }
  Object.assign(dashboardPreviewScope, draft, { active: true })
  emit('apply')
}
const clear = () => {
  clearPreviewScope()
  Object.assign(draft, { tenant: '', user: '' })
  emit('apply')
}
onBeforeUnmount(clearPreviewScope)
</script>

<template>
  <div v-if="allowed" class="preview-scope">
    <strong>{{ t('preview_scope.title') }}</strong>
    <el-input
      v-model="draft.tenant"
      placeholder="tenant_id"
      aria-label="tenant_id"
      maxlength="128"
    />
    <el-input v-model="draft.user" placeholder="user_id" aria-label="user_id" maxlength="128" />
    <el-button type="primary" @click="apply">{{ t('preview_scope.apply') }}</el-button>
    <el-button :disabled="!dashboardPreviewScope.active" @click="clear">{{
      t('preview_scope.clear')
    }}</el-button>
    <span>{{
      dashboardPreviewScope.active
        ? t('preview_scope.active') +
          ' · ' +
          dashboardPreviewScope.tenant +
          ' / ' +
          dashboardPreviewScope.user
        : t('preview_scope.hint')
    }}</span>
  </div>
</template>

<style scoped lang="less">
.preview-scope {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
  background: #f5f8ff;
  border-bottom: 1px solid #dce6fa;
  color: #4e5969;
  font-size: 12px;
  strong {
    color: #1f2329;
    font-size: 13px;
  }
  .ed-input {
    width: 160px;
  }
  .ed-button + .ed-button {
    margin-left: 0;
  }
}
</style>
