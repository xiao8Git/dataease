import { reactive } from 'vue'

// Memory only: never persisted in dashboard definitions or shared URL parameters.
export const dashboardPreviewScope = reactive({ tenant: '', user: '', active: false })
export function previewScopeHeaders() {
  const scope = dashboardPreviewScope
  return scope.active
    ? { 'X-DE-PREVIEW-TENANT': scope.tenant, 'X-DE-PREVIEW-USER': scope.user }
    : {}
}
export function clearPreviewScope() {
  Object.assign(dashboardPreviewScope, { tenant: '', user: '', active: false })
}
