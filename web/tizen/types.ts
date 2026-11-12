import type { components, operations } from '../src/api/schema'

export type ServerInfo = components['schemas']['ServerInfo']
export type ClientInfo = components['schemas']['Client']
export type PairingStart = components['schemas']['PairingStart']
export type PairingClaim = components['schemas']['PairingClaim']
export type Decision = components['schemas']['Decision']
export type Profile = components['schemas']['Profile']
export type MediaFile = components['schemas']['MediaFile']
export type ProgressBody = operations['reportProgress']['requestBody']['content']['application/json']

export type SavedSession = {
  baseUrl: string
  serverId: string
  serverName: string
  token: string
  sessionId: string
}
