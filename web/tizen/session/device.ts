import type { components } from '../../src/api/schema'

export type ClientInfo = components['schemas']['Client']

/** This app's version, as sign-ins and plays report it; config.xml carries the same (checked by a test). */
export const TV_APP_VERSION = '0.1.0'

const DEVICE_KEY = 'watchmymedia.tizen.device'
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
    name: 'Samsung TV',
    product: 'WatchMyMedia for Samsung TV',
    platform: 'tizen',
    version: TV_APP_VERSION,
    ...(model ? { model: model.slice(0, 64) } : {}),
  }
}
