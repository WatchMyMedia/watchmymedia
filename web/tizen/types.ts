// The schema types the prototype's player files import; new code imports from src/api/schema directly.
import type { components, operations } from '../src/api/schema'

export type ServerInfo = components['schemas']['ServerInfo']
export type ClientInfo = components['schemas']['Client']
export type PairingStart = components['schemas']['PairingStart']
export type PairingClaim = components['schemas']['PairingClaim']
export type Decision = components['schemas']['Decision']
export type Profile = components['schemas']['Profile']
export type MediaFile = components['schemas']['MediaFile']
export type ProgressBody = operations['reportProgress']['requestBody']['content']['application/json']
