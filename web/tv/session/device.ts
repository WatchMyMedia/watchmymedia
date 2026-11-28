import type { components } from '../../src/api/schema'
import { platform } from '../platform/current'

export type ClientInfo = components['schemas']['Client']

/** This app's version, as sign-ins and plays report it; each TV's package carries the same (checked by its tests). */
export const TV_APP_VERSION = '2.3.0'

const DEVICE_KEY = platform.storage.device
let fallback = ''

function randomId(random: Pick<Crypto, 'getRandomValues'>) {
  // crypto.randomUUID needs a secure context; getRandomValues works on LAN http too.
  return Array.from(random.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join('')
}

/** This TV's device ID: random, made once and kept, so the server tells this TV from others. */
export function deviceId(storage: Storage | undefined, random: Pick<Crypto, 'getRandomValues'> = crypto): string {
  try {
    const saved = storage?.getItem(DEVICE_KEY)
    if (saved) return saved
    const id = randomId(random)
    storage?.setItem(DEVICE_KEY, id)
    if (storage) return id
  } catch { /* Storage refused: one ID for this run. */ }
  fallback ||= randomId(random)
  return fallback
}

/** How this TV describes itself when it signs in and plays. */
export function clientInfo(storage: Storage | undefined, model?: string): ClientInfo {
  return {
    deviceId: deviceId(storage),
    name: platform.words.tv,
    product: platform.words.product,
    platform: platform.id,
    version: TV_APP_VERSION,
    ...(model ? { model: model.slice(0, 64) } : {}),
  }
}
