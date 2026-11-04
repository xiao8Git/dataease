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
  localDatasets,
  localDatasetGrants,
  saveLocalDatasetGrants,
  localDatasources,
  localDatasourceGrants,
  saveLocalDatasourceGrants,
  localBranding,
  uploadLocalLogo,
  resetLocalLogo,
  type Tenant,
  type Role,
  type LocalUser,
  type Dashboard,
  type DatasetResource,
  type DatasourceResource,
  type Feature
} from '@/api/localAccess'
import { useAppearanceStoreWithOut } from '@/store/modules/appearance'

const { t } = useI18n()
const tenants = ref<Tenant[]>([])
const roles = ref<Role[]>([])
const users = ref<LocalUser[]>([])
const dashboards = ref<Dashboard[]>([])
const datasets = ref<DatasetResource[]>([])
const datasetGrants = ref<string[]>([])
const datasetGrantsLoaded = ref(false)
const datasources = ref<DatasourceResource[]>([])
const datasourceGrants = ref<string[]>([])
const datasourceGrantsLoaded = ref(false)
const selectedTenant = ref('')
const grants = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref('')
const features: Feature[] = [
  'dashboard',
  'dashboard_edit',
  'dashboard_delete',
  'dataset',
  'dataset_delete'
]
const logoInput = ref<HTMLInputElement>()
const logoUrl = ref('')
const logoBusy = ref(false)
const appearance = useAppearanceStoreWithOut()
const setLogo = (value: string) => {
  logoUrl.value = value ? import.meta.env.VITE_API_BASEPATH + value : ''
  appearance.localLogo = value
}
const changeLogo = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  logoBusy.value = true
  try {
    if (file.size > 2 * 1024 * 1024 || !['image/png', 'image/jpeg'].includes(file.type)) {
      ElMessage.error(t('local_access.logo_help'))
      return
    }
    const { data } = await uploadLocalLogo(file)
    setLogo(data.logoUrl)
    ElMessage.success(t('local_access.saved'))
  } finally {
    logoBusy.value = false
    input.value = ''
  }
}
const resetLogo = async () => {
  logoBusy.value = true
  try {
    await resetLocalLogo()
    setLogo('')
    ElMessage.success(t('local_access.saved'))
  } finally {
    logoBusy.value = false
  }
}
const permissionDisabled = (feature: Feature) =>
  (['dashboard_edit', 'dashboard_delete'].includes(feature) &&
    !roleForm.value.permissions.includes('dashboard')) ||
  (feature === 'dataset_delete' && !roleForm.value.permissions.includes('dataset'))
const normalizePermissions = () => {
  roleForm.value.permissions = roleForm.value.permissions.filter(
    feature => !permissionDisabled(feature)
  )
}
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
  datasetGrants.value = []
  datasetGrantsLoaded.value = false
  datasourceGrants.value = []
  datasourceGrantsLoaded.value = false
  loading.value = true
  try {
    const [d, u, g, dg, sg] = await Promise.all([
      id ? localRoles(id) : Promise.resolve({ data: [] }),
      localUsers(id),
      id ? localGrants(id) : Promise.resolve({ data: { resourceIds: [] } }),
      id ? localDatasetGrants(id) : Promise.resolve({ data: { datasetIds: [] } }),
      id ? localDatasourceGrants(id) : Promise.resolve({ data: { datasourceIds: [] } })
    ])
    if (version !== loadVersion) return
    roles.value = d.data
    users.value = u.data
    grants.value = g.data.resourceIds
    datasetGrants.value = dg.data.datasetIds
    datasetGrantsLoaded.value = !!id
    datasourceGrants.value = sg.data.datasourceIds
    datasourceGrantsLoaded.value = !!id
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
const load = async () => {
  const [ts, ds, sets, sources, branding] = await Promise.all([
    localTenants(),
    localDashboards(),
    localDatasets(),
    localDatasources(),
    localBranding()
  ])
  setLogo(branding.data.logoUrl)
  tenants.value = ts.data
  dashboards.value = ds.data
  datasets.value = sets.data
  datasources.value = sources.data
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
const saveDatasetAssignments = async () => {
  if (!ready.value || !datasetGrantsLoaded.value || saving.value) return
  saving.value = true
  try {
    await saveLocalDatasetGrants(selectedTenant.value, datasetGrants.value)
    ElMessage.success(t('local_access.saved'))
  } finally {
    saving.value = false
  }
}
const saveDatasourceAssignments = async () => {
  if (!ready.value || !datasourceGrantsLoaded.value || saving.value) return
  saving.value = true
  try {
    await saveLocalDatasourceGrants(selectedTenant.value, datasourceGrants.value)
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
      <el-tab-pane :label="t('local_access.branding')">
        <h3>{{ t('local_access.logo') }}</h3>
        <p class="description">{{ t('local_access.logo_help') }}</p>
        <div class="logo-preview">
          <img v-if="logoUrl" :src="logoUrl" :alt="t('local_access.logo')" />
          <span v-else>DataEase</span>
        </div>
        <input
          ref="logoInput"
          type="file"
          accept="image/png,image/jpeg"
          hidden
          @change="changeLogo"
        />
        <el-button type="primary" :loading="logoBusy" @click="logoInput?.click()">{{
          t('local_access.upload_logo')
        }}</el-button>
        <el-button :disabled="logoBusy || !logoUrl" @click="resetLogo">{{
          t('local_access.reset_logo')
        }}</el-button>
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
          <h3>{{ t('local_access.dataset_grants') }}</h3>
          <p class="description">{{ t('local_access.dataset_grant_help') }}</p>
          <el-select
            v-model="datasetGrants"
            multiple
            filterable
            clearable
            :disabled="!ready || !datasetGrantsLoaded || saving"
            style="width: min(720px, 100%)"
            :placeholder="t('local_access.choose_datasets')"
          >
            <el-option
              v-for="dataset in datasets"
              :key="dataset.id"
              :label="`${dataset.name} (${dataset.id})`"
              :value="dataset.id"
            />
          </el-select>
          <el-button
            type="primary"
            :disabled="!ready || !datasetGrantsLoaded"
            :loading="saving"
            @click="saveDatasetAssignments"
            style="margin-left: 12px"
            >{{ t('local_access.save') }}</el-button
          >
          <h3>{{ t('local_access.datasource_grants') }}</h3>
          <p class="description">{{ t('local_access.datasource_grant_help') }}</p>
          <el-select
            v-model="datasourceGrants"
            multiple
            filterable
            clearable
            :disabled="!ready || !datasourceGrantsLoaded || saving"
            style="width: min(720px, 100%)"
            :placeholder="t('local_access.choose_datasources')"
          >
            <el-option
              v-for="source in datasources"
              :key="source.id"
              :label="`${source.name} (${source.type}, ${source.id})`"
              :value="source.id"
            />
          </el-select>
          <el-button
            type="primary"
            :disabled="!ready || !datasourceGrantsLoaded"
            :loading="saving"
            @click="saveDatasourceAssignments"
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
            ><el-checkbox-group v-model="roleForm.permissions" @change="normalizePermissions"
              ><el-checkbox
                v-for="feature in features"
                :key="feature"
                :label="feature"
                :disabled="permissionDisabled(feature)"
                >{{ t(`local_access.${feature}`) }}</el-checkbox
              ></el-checkbox-group
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
.logo-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 300px;
  height: 100px;
  padding: 16px;
  border: 1px solid #dee0e3;
  border-radius: 8px;
  margin-bottom: 20px;
  font-size: 28px;
  font-weight: 600;
  color: #3370ff;
  img {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
  }
}
</style>
