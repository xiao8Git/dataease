import request from '@/config/axios'

export interface EmbedKey {
  kid: string
  tenant: string
  enabled: boolean
  managed: boolean
  maskedSecret?: string
}
export interface EmbedKeyEditor {
  kid: string
  tenant: string
  enabled: boolean
  secretBase64: string
}
export const queryEmbedKeys = () => request.get({ url: '/sysParameter/embedKeys' })
export const saveEmbedKey = (data: EmbedKeyEditor) =>
  request.post({ url: '/sysParameter/embedKeys', data })
