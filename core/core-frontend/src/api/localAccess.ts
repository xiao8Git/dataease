import request from '@/config/axios'

export type Feature = 'dashboard' | 'dataset' | 'datasource'
export interface Tenant {
  id?: string
  code: string
  name: string
  enabled: boolean
}
export interface Role {
  id?: string
  tenantId: string
  name: string
  permissions: Feature[]
  enabled: boolean
}
export interface LocalUser {
  id?: string
  account: string
  name: string
  tenantId: string | null
  // Legacy wire field: the assigned role ID.
  departmentId: string
  dataUser: string
  enabled: boolean
  password?: string
}
export interface Dashboard {
  id: string
  name: string
  type: string
}
export interface LocalSession {
  enabled: boolean
  platformAdmin: boolean
  tenantId: string | null
  tenantName: string | null
  permissions: Feature[]
  tenants: Tenant[]
}
const base = '/localAccess'
export const localSession = () => request.get({ url: `${base}/session` })
export const switchLocalTenant = (id: string) => request.post({ url: `${base}/switch/${id}` })
export const localTenants = () => request.get({ url: `${base}/tenants` })
export const saveLocalTenant = (data: Tenant) => request.post({ url: `${base}/tenants`, data })
// Keep existing endpoint names compatible with deployed clients.
export const localRoles = (id: string) => request.get({ url: `${base}/departments/${id}` })
export const saveLocalRole = (data: Role) => request.post({ url: `${base}/departments`, data })
export const localUsers = (id?: string) =>
  request.get({ url: `${base}/users`, params: id ? { tenantId: id } : {} })
export const saveLocalUser = (data: LocalUser) => request.post({ url: `${base}/users`, data })
export const localDashboards = () => request.get({ url: `${base}/dashboards` })
export const localGrants = (id: string) => request.get({ url: `${base}/grants/${id}` })
export const saveLocalGrants = (tenantId: string, resourceIds: string[]) =>
  request.post({ url: `${base}/grants`, data: { tenantId, resourceIds } })
