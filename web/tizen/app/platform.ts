import { samsungDisplay, type SamsungWindow } from '../player'

// The Samsung TV's own APIs that the app uses outside playback: keys,
// leaving the app, the screensaver and the model name. Each call is a no-op
// in the browser preview. Playback (AVPlay) is the player engine's, whose
// SamsungWindow this extends.

type TvWindow = SamsungWindow & {
  webapis?: { productinfo?: { getRealModel?(): string; getModel?(): string } }
}

const tv = () => window as TvWindow

/** Whether this is the packaged TV app rather than the browser preview. */
export function onTV() { return !!tv().tizen }

/** Loads Samsung's `webapis.js` (AVPlay, the screensaver, product info), which the packaged app serves at $WEBAPIS. */
export function loadWebApis(timeoutMs = 10_000): Promise<void> {
  if (tv().webapis) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    const timer = setTimeout(() => reject(new Error('Samsung APIs did not load')), timeoutMs)
    script.src = '$WEBAPIS/webapis/webapis.js'
    script.onload = () => { clearTimeout(timer); resolve() }
    script.onerror = () => { clearTimeout(timer); reject(new Error('Samsung APIs unavailable')) }
    document.head.append(script)
  })
}

/** Asks for each key; a key this model doesn't have is skipped (its on-screen control still works). */
export function registerKeys(names: readonly string[]) {
  const input = tv().tizen?.tvinputdevice
  if (!input) return
  for (const name of names) {
    try { input.registerKey(name) } catch { /* Not on this model's remote. */ }
  }
}

/** Leaves the app on the TV. False in the browser preview, which has nowhere to go. */
export function exitApp(): boolean {
  const app = tv().tizen?.application
  if (!app) return false
  try { app.getCurrentApplication().exit(); return true } catch { return false }
}

/**
 * Whether the TV shows HDR10, as Samsung's avinfo says. The QN90D said so and
 * showed it, so the player claims HDR10 where this
 * holds and the server tone-maps for the rest. False in the preview.
 */
export function tvTakesHdr10(): boolean {
  return samsungDisplay(tv()).hdr
}

/** The TV's model ("QN65QN90D"), for the signed-in devices list; undefined in the preview. */
export function tvModel(): string | undefined {
  const info = tv().webapis?.productinfo
  try { return info?.getRealModel?.() || info?.getModel?.() || undefined } catch { return undefined }
}

const holds = new Set<symbol>()
let saverOff: boolean | undefined

function applyScreenSaver() {
  const off = !document.hidden && holds.size > 0
  if (off === saverOff) return
  saverOff = off
  const common = tv().webapis?.appcommon
  if (!common) return
  const s = common.AppCommonScreenSaverState
  try { common.setScreenSaver(off ? s.SCREEN_SAVER_OFF : s.SCREEN_SAVER_ON, () => {}, () => {}) } catch { /* Needs checking on the TV; nothing native is logged. */ }
}

/**
 * Keeps the TV's screensaver away while something plays; call the returned
 * function when it stops. With no hold (or the app in the background) the
 * screensaver is allowed again.
 */
export function holdScreenSaver(): () => void {
  const hold = Symbol('playing')
  holds.add(hold)
  applyScreenSaver()
  return () => { holds.delete(hold); applyScreenSaver() }
}

/** Re-applies the screensaver state, as the app comes and goes. */
export function refreshScreenSaver() {
  saverOff = undefined
  applyScreenSaver()
}

type InfoWindow = { tizen?: { systeminfo?: { getCapability?(key: string): unknown } } }

/** The TV's Tizen version ("8.0"), for Settings › About; undefined in the preview. */
export function tizenVersion(): string | undefined {
  try {
    const v = (window as unknown as InfoWindow).tizen?.systeminfo?.getCapability?.('http://tizen.org/feature/platform.version')
    return typeof v === 'string' && /^\d+(\.\d+){0,3}$/.test(v) ? v : undefined
  } catch {
    return undefined
  }
}
