import request from '@/config/axios'

export interface IframeWhitelist {
  origins: string[]
  enabled: boolean
  managed: boolean
  effectivePolicy: string
}

export const queryIframeWhitelist = () => request.get({ url: '/sysParameter/iframeWhitelist' })
export const saveIframeWhitelist = (origins: string[], enabled: boolean) =>
  request.post({ url: '/sysParameter/iframeWhitelist', data: origins, params: { enabled } })
