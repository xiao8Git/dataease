<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus-secondary'
import { useI18n } from '@/hooks/web/useI18n'
import {
  localTenants,
  saveLocalTenant,
  localRoles,
  saveLocalRole,
  localUsers,
  saveLocalUser,
  localDashboards,
  localGrants,
  saveLocalGrants,
  type Tenant,
  type Role,
  type LocalUser,
  type Dashboard,
  type Feature
} from '@/api/localAccess'

const { t } = useI18n()
const tenants = ref<Tenant[]>([])
const roles = ref<Role[]>([])
const users = ref<LocalUser[]>([])
const dashboards = ref<Dashboard[]>([])
const selectedTenant = ref('')
const grants = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref('')
const features: Feature[] = ['dashboard', 'dataset']
const userRoles = ref<Role[]>([])
let userRoleVersion = 0
const loadUserRoles = async () => {
  const version = ++userRoleVersion
  userForm.value.departmentId = ''
  userRoles.value = []
  if (!userForm.value.tenantId) return
  const { data } = await localRoles(userForm.value.tenantId)
  if (version === userRoleVersion) userRoles.value = data
}
const tenantForm = ref<Tenant>({ code: '', name: '', enabled: true })
const roleForm = ref<Role>({ tenantId: '', name: '', permissions: [], enabled: true })
const userForm = ref<LocalUser>({
  account: '',
  name: '',
  tenantId: '',
  departmentId: '',
  dataUser: '',
  enabled: true,
  password: ''
})
const ready = computed(() => !!selectedTenant.value && !loading.value)
let loadVersion = 0
const loadTenant = async () => {
  const version = ++loadVersion
  const id = selectedTenant.value
  roles.value = []
  users.value = []
  grants.value = []
  loading.value = true
  try {
    const [d, u, g] = await Promise.all([
      id ? localRoles(id) : Promise.resolve({ data: [] }),
      localUsers(id),
      id ? localGrants(id) : Promise.resolve({ data: { resourceIds: [] } })
    ])
    if (version !== loadVersion) return
    roles.value = d.data
    users.value = u.data
    grants.value = g.data.resourceIds
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
const load = async () => {
  const [ts, ds] = await Promise.all([localTenants(), localDashboards()])
  tenants.value = ts.data
  dashboards.value = ds.data
  await loadTenant()
}
onMounted(load)
const editTenant = (row?: Tenant) => {
  tenantForm.value = row ? { ...row } : { code: '', name: '', enabled: true }
  dialog.value = 'tenant'
}
const editRole = (row?: Role) => {
  roleForm.value = row
    ? { ...row, permissions: row.permissions.filter(feature => features.includes(feature)) }
    : { tenantId: selectedTenant.value, name: '', permissions: [], enabled: true }
  dialog.value = 'role'
}
const editUser = async (row?: LocalUser) => {
  userForm.value = row
    ? { ...row, password: '' }
    : {
        account: '',
        name: '',
        tenantId: selectedTenant.value,
        departmentId: '',
        dataUser: '',
        enabled: true,
        password: ''
      }
  userRoles.value = []
  const roleId = userForm.value.departmentId
  await loadUserRoles()
  userForm.value.departmentId = roleId
  dialog.value = 'user'
}
const save = async () => {
  if (saving.value) return
  saving.value = true
  try {
    if (dialog.value === 'tenant') await saveLocalTenant(tenantForm.value)
    if (dialog.value === 'role') await saveLocalRole(roleForm.value)
    if (dialog.value === 'user') await saveLocalUser(userForm.value)
    dialog.value = ''
    await load()
    ElMessage.success(t('local_access.saved'))
  } finally {
    saving.value = false
  }
}
const saveAssignments = async () => {
  if (!ready.value || saving.value) return
  saving.value = true
  try {
    await saveLocalGrants(selectedTenant.value, grants.value)
    ElMessage.success(t('local_access.saved'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="local-access" v-loading="loading">
    <h2>{{ t('local_access.title') }}</h2>
    <p class="description">{{ t('local_access.description') }}</p>
    <el-tabs>
      <el-tab-pane :label="t('local_access.tenants')">
        <el-button type="primary" @click="editTenant()">{{
          t('local_access.new_tenant')
        }}</el-button>
        <el-table :data="tenants" row-key="id">
          <el-table-column prop="name" :label="t('local_access.name')" />
          <el-table-column prop="code" :label="t('local_access.code')" />
          <el-table-column :label="t('local_access.status')"
            ><template #default="{ row }">{{
              t(row.enabled ? 'local_access.enabled' : 'local_access.disabled')
            }}</template></el-table-column
          >
          <el-table-column width="120"
            ><template #default="{ row }"
              ><el-button text @click="editTenant(row)">{{
                t('local_access.edit')
              }}</el-button></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane :label="t('local_access.members_permissions')">
        <el-select
          v-model="selectedTenant"
          :placeholder="t('local_access.admin_scope')"
          clearable
          :disabled="saving"
          @change="loadTenant"
          style="width: 260px"
        >
          <el-option
            v-for="tenant in tenants"
            :key="tenant.id"
            :value="tenant.id"
            :label="tenant.name"
          />
        </el-select>
        <template v-if="selectedTenant">
          <h3>{{ t('local_access.roles') }}</h3>
          <el-button :disabled="!ready" @click="editRole()">{{
            t('local_access.new_role')
          }}</el-button>
          <el-table :data="roles" row-key="id">
            <el-table-column prop="name" :label="t('local_access.name')" />
            <el-table-column :label="t('local_access.features')"
              ><template #default="{ row }"
                ><el-tag v-for="feature in row.permissions" :key="feature" class="feature">{{
                  t(`local_access.${feature}`)
                }}</el-tag></template
              ></el-table-column
            >
            <el-table-column :label="t('local_access.status')"
              ><template #default="{ row }">{{
                t(row.enabled ? 'local_access.enabled' : 'local_access.disabled')
              }}</template></el-table-column
            >
            <el-table-column width="120"
              ><template #default="{ row }"
                ><el-button text @click="editRole(row)">{{
                  t('local_access.edit')
                }}</el-button></template
              ></el-table-column
            >
          </el-table>
        </template>
        <h3>{{ t('local_access.users') }}</h3>
        <el-button :disabled="loading || !tenants.length" @click="editUser()">{{
          t('local_access.new_user')
        }}</el-button>
        <el-table :data="users" row-key="id">
          <el-table-column prop="account" :label="t('local_access.account')" />
          <el-table-column :label="t('local_access.tenant')"
            ><template #default="{ row }">{{
              tenants.find(tenant => tenant.id === row.tenantId)?.name ||
              t('local_access.admin_scope')
            }}</template></el-table-column
          >
          <el-table-column prop="name" :label="t('local_access.name')" />
          <el-table-column :label="t('local_access.role')"
            ><template #default="{ row }">{{
              row.tenantId
                ? roles.find(d => d.id === row.departmentId)?.name || '-'
                : t('local_access.platform_admin')
            }}</template></el-table-column
          >
          <el-table-column prop="dataUser" :label="t('local_access.data_user')" />
          <el-table-column :label="t('local_access.status')"
            ><template #default="{ row }">{{
              t(row.enabled ? 'local_access.enabled' : 'local_access.disabled')
            }}</template></el-table-column
          >
          <el-table-column width="160"
            ><template #default="{ row }"
              ><el-button v-if="row.tenantId" text @click="editUser(row)">{{
                t('local_access.edit_user')
              }}</el-button></template
            ></el-table-column
          >
        </el-table>
        <template v-if="selectedTenant">
          <h3>{{ t('local_access.grants') }}</h3>
          <p class="description">{{ t('local_access.grant_help') }}</p>
          <el-select
            v-model="grants"
            multiple
            filterable
            :disabled="!ready || saving"
            style="width: min(720px, 100%)"
            :placeholder="t('local_access.choose_dashboards')"
          >
            <el-option
              v-for="dashboard in dashboards"
              :key="dashboard.id"
              :label="`${dashboard.name} (${dashboard.id})`"
              :value="dashboard.id"
            />
          </el-select>
          <el-button
            type="primary"
            :disabled="!ready"
            :loading="saving"
            @click="saveAssignments"
            style="margin-left: 12px"
            >{{ t('local_access.save') }}</el-button
          >
        </template>
      </el-tab-pane>
    </el-tabs>
    <el-dialog
      :model-value="!!dialog"
      :title="t(`local_access.${dialog || 'title'}`)"
      width="540px"
      :close-on-click-modal="false"
      @close="dialog = ''"
    >
      <el-form label-position="top" @submit.prevent>
        <template v-if="dialog === 'tenant'">
          <el-form-item :label="t('local_access.name')" required
            ><el-input v-model="tenantForm.name" maxlength="128"
          /></el-form-item>
          <el-form-item :label="t('local_access.code')" required
            ><el-input v-model="tenantForm.code" :disabled="!!tenantForm.id" maxlength="128" /><span
              class="description"
              >{{ t('local_access.code_help') }}</span
            ></el-form-item
          >
          <el-form-item :label="t('local_access.enabled')"
            ><el-switch v-model="tenantForm.enabled"
          /></el-form-item>
        </template>
        <template v-if="dialog === 'role'">
          <el-form-item :label="t('local_access.name')" required
            ><el-input v-model="roleForm.name" maxlength="128"
          /></el-form-item>
          <el-form-item :label="t('local_access.features')"
            ><el-checkbox-group v-model="roleForm.permissions"
              ><el-checkbox v-for="feature in features" :key="feature" :label="feature">{{
                t(`local_access.${feature}`)
              }}</el-checkbox></el-checkbox-group
            ></el-form-item
          >
          <el-form-item :label="t('local_access.enabled')"
            ><el-switch v-model="roleForm.enabled"
          /></el-form-item>
        </template>
        <template v-if="dialog === 'user'">
          <el-form-item :label="t('local_access.tenant')" required>
            <el-select
              v-model="userForm.tenantId"
              :disabled="!!userForm.id"
              @change="loadUserRoles"
            >
              <el-option
                v-for="tenant in tenants"
                :key="tenant.id"
                :value="tenant.id"
                :label="tenant.name"
                :disabled="!tenant.enabled"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('local_access.account')" required
            ><el-input v-model="userForm.account" :disabled="!!userForm.id" maxlength="64"
          /></el-form-item>
          <el-form-item :label="t('local_access.name')" required
            ><el-input v-model="userForm.name" maxlength="128"
          /></el-form-item>
          <el-form-item :label="t('local_access.role')" required
            ><el-select v-model="userForm.departmentId"
              ><el-option
                v-for="role in userRoles"
                :key="role.id"
                :value="role.id"
                :label="role.name"
                :disabled="!role.enabled" /></el-select
          ></el-form-item>
          <el-form-item :label="t('local_access.data_user')" required
            ><el-input v-model="userForm.dataUser" maxlength="128" /><span class="description">{{
              t('local_access.data_user_help')
            }}</span></el-form-item
          >
          <el-form-item :label="t('local_access.password')" :required="!userForm.id"
            ><el-input
              v-model="userForm.password"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="128"
            /><span class="description">{{ t('local_access.password_help') }}</span></el-form-item
          >
          <el-form-item :label="t('local_access.enabled')"
            ><el-switch v-model="userForm.enabled"
          /></el-form-item>
        </template>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="dialog = ''">{{
          t('local_access.cancel')
        }}</el-button
        ><el-button type="primary" :loading="saving" @click="save">{{
          t('local_access.save')
        }}</el-button></template
      >
    </el-dialog>
  </div>
</template>

<style scoped lang="less">
.local-access {
  padding: 24px;
  overflow: auto;
  height: 100%;
  background: #fff;
}
h2 {
  margin: 0 0 8px;
}
h3 {
  margin: 28px 0 12px;
}
.description {
  color: #646a73;
  line-height: 1.6;
  font-size: 13px;
}
.feature {
  margin-right: 8px;
}
</style>
