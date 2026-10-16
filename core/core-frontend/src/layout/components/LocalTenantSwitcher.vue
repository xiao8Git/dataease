<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { localSession, switchLocalTenant, type LocalSession } from '@/api/localAccess'
import { useUserStoreWithOut } from '@/store/modules/user'
import { useI18n } from '@/hooks/web/useI18n'
import { cancelAllRequest } from '@/config/axios/service'

const { t } = useI18n()
const user = useUserStoreWithOut()
const session = ref<LocalSession>()
const switching = ref(false)
onMounted(async () => {
  if (user.localAccess) session.value = (await localSession()).data
})
const change = async (id: string) => {
  if (!id || switching.value || id === session.value?.tenantId) return
  switching.value = true
  try {
    cancelAllRequest()
    const { data } = await switchLocalTenant(id)
    user.setToken(data.token)
    user.setExp(data.exp)
    user.setTime(Date.now())
    user.setOid(id)
    // Recreate all stores and routes; never carry canvas/query state across tenants.
    window.location.assign(window.location.pathname + '#/local-access/index')
    window.location.reload()
  } finally {
    switching.value = false
  }
}
</script>

<template>
  <el-select
    v-if="session?.platformAdmin"
    :model-value="session.tenantId || '0'"
    :placeholder="t('local_access.choose_tenant')"
    :disabled="switching"
    style="width: 180px; margin-right: 12px"
    @change="change"
  >
    <el-option value="0" :label="t('local_access.admin_scope')" />
    <el-option
      v-for="tenant in session.tenants"
      :key="tenant.id"
      :label="tenant.name"
      :value="tenant.id"
    />
  </el-select>
  <span v-else-if="session?.enabled" style="margin-right: 12px">{{ session.tenantName }}</span>
</template>
