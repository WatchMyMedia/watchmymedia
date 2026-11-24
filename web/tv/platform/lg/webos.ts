// The LG TV's own APIs that the app uses outside playback: what the TV is,
// handing Back over to webOS, coming forward when opened again, and the
// screensaver. They are the system objects LG's webOSTV.js wraps
// (webOSSystem, formerly PalmSystem, and the Luna service bridge), called
// directly so the package carries no library of LG's. Each call is a no-op
// in the browser preview, and none throws.

type WebOSSystem = {
  /** JSON: modelName, platformVersion and the screen's size. */
  deviceInfo?: string
  platformBack?(): void
  activate?(): void
}

type ServiceBridge = {
  onservicecallback?: (message: string) => void
  call(uri: string, params: string): void
  cancel?(): void
}

export type WebOSWindow = {
  webOSSystem?: WebOSSystem
  PalmSystem?: WebOSSystem
  PalmServiceBridge?: new () => ServiceBridge
}

const tv = () => window as unknown as WebOSWindow
const system = (w: WebOSWindow = tv()) => w.webOSSystem ?? w.PalmSystem

/** Whether this is the packaged app on an LG TV rather than the browser preview. */
export function onTV(w: WebOSWindow = tv()) { return !!system(w) }

type Reply = Record<string, unknown>

function parse(message: string): Reply | undefined {
  try {
    const value: unknown = JSON.parse(message)
    return value && typeof value === 'object' ? value as Reply : undefined
  } catch { return undefined }
}

/**
 * Subscribes to a Luna service: each reply goes to `onReply`; a refusal
 * (`returnValue: false`) or a failed call ends it and calls `onFail`.
 * Returns a function that ends it. Where there is no service bridge,
 * `onFail` is called at once.
 */
export function lunaSubscribe(uri: string, params: Reply, onReply: (reply: Reply) => void, onFail: () => void = () => {}, w: WebOSWindow = tv()): () => void {
  const Bridge = w.PalmServiceBridge
  if (!Bridge) { onFail(); return () => {} }
  let bridge: ServiceBridge | undefined
  try {
    bridge = new Bridge()
    bridge.onservicecallback = (message) => {
      const reply = parse(message)
      if (!reply) return
      if (reply.returnValue === false) { end(); onFail(); return }
      onReply(reply)
    }
    bridge.call(uri, JSON.stringify(params))
  } catch { end(); onFail() }
  function end() {
    try { bridge?.cancel?.() } catch { /* Already gone. */ }
    bridge = undefined
  }
  return end
}

/** One Luna call: its reply, or undefined when it is refused, there is no bridge, or it takes longer than `timeoutMs`. */
export function lunaCall(uri: string, params: Reply, timeoutMs = 2000, w: WebOSWindow = tv()): Promise<Reply | undefined> {
  return new Promise((resolve) => {
    let done = false
    let end = () => {}
    let timer: ReturnType<typeof setTimeout> | undefined
    // A bridge may answer before call() returns: finish must work then too.
    const finish = (reply?: Reply) => { if (done) return; done = true; clearTimeout(timer); end(); resolve(reply) }
    end = lunaSubscribe(uri, params, finish, () => finish(), w)
    if (done) end()
    else timer = setTimeout(() => finish(), timeoutMs)
  })
}

/** What the app knows of the TV. */
export type DeviceFacts = {
  /** "OLED65C4PUA"; undefined in the preview. */
  model?: string
  /** webOS's own version, "9.2.0": webOS 24 is 9. */
  version?: string
  /** Whether the TV shows HDR10, so the profile may claim it. */
  hdr10: boolean
}

const str = (v: unknown) => typeof v === 'string' && v.trim() ? v.trim().slice(0, 64) : undefined

/**
 * Reads the TV: its model and webOS version from the system, then the real
 * model name and whether it takes HDR from Luna, as webOSTV.js's
 * deviceInfo does. A call that is refused or slow leaves its answer out:
 * no HDR10 claimed, and the server tone-maps.
 */
export async function readDevice(w: WebOSWindow = tv(), timeoutMs = 1500): Promise<DeviceFacts> {
  const facts: DeviceFacts = { hdr10: false }
  const s = system(w)
  if (!s) return facts
  const info = parse(s.deviceInfo ?? '') ?? {}
  facts.model = str(info.modelName)
  facts.version = str(info.platformVersion)
  const [systemInfo, configs] = await Promise.all([
    lunaCall('luna://com.webos.service.tv.systemproperty/getSystemInfo', { keys: ['modelName', 'sdkVersion'] }, timeoutMs, w),
    lunaCall('luna://com.webos.service.config/getConfigs', { configNames: ['tv.model.supportHDR'] }, timeoutMs, w),
  ])
  facts.model = str(systemInfo?.modelName) ?? facts.model
  facts.version = facts.version ?? str(systemInfo?.sdkVersion)
  const found = configs?.configs
  facts.hdr10 = !!found && typeof found === 'object' && (found as Reply)['tv.model.supportHDR'] === true
  return facts
}

/** "webOS 24" for webOS's own 9.x, as LG names its TVs' versions from 2022 (7.x) on; "webOS 6" before. */
export function webOSName(version?: string): string | undefined {
  const major = Number(version?.split('.')[0])
  if (!Number.isInteger(major) || major <= 0) return undefined
  return `webOS ${major >= 7 ? major + 15 : major}`
}

/**
 * Hands Back over to webOS, which asks before leaving (webOS 6 and later)
 * and goes to the TV's Home, as LG's checklist asks of an app's first
 * screen. False where there is no webOS to hand it to.
 */
export function platformBack(w: WebOSWindow = tv()): boolean {
  const back = system(w)?.platformBack
  if (!back) return false
  try { back.call(system(w)); return true } catch { return false }
}

/** Opened again while it runs (appinfo.json's handlesRelaunch): come forward, where it was. */
export function bringForward(w: WebOSWindow = tv()) {
  try { system(w)?.activate?.() } catch { /* Already in front. */ }
}

const SCREEN_SAVER = 'luna://com.webos.service.tvpower/power/'
const CLIENT = 'media.watchmy.client'

/**
 * Keeps the screensaver away while something plays. Full-screen video does
 * that by itself on webOS; a paused play or a trailer's frame doesn't. The
 * TV asks registered apps before its screensaver starts, and an app may say
 * no (Kodi's webOS port does this). LG doesn't document it for web apps:
 * where the TV refuses the registration, nothing changes and the
 * screensaver comes as it would.
 */
export class ScreenSaver {
  private holds = new Set<symbol>()
  private started = false

  /** The window is read when it starts, so making one in a module touches nothing. */
  constructor(private w?: WebOSWindow) {}

  /** Registers once for the TV's questions; a refused registration is not tried again. */
  start() {
    if (this.started) return
    this.started = true
    const w = this.w ?? tv()
    lunaSubscribe(`${SCREEN_SAVER}registerScreenSaverRequest`, { subscribe: true, clientName: CLIENT }, (reply) => {
      // Each request the TV makes carries a timestamp, which the answer returns.
      if (reply.timestamp === undefined) return
      void lunaCall(`${SCREEN_SAVER}responseScreenSaverRequest`, { clientName: CLIENT, ack: !this.holding, timestamp: reply.timestamp }, 2000, w)
    }, undefined, w)
  }

  /** Whether the screensaver is being kept away: something holds it and the app is in front. */
  get holding() { return this.holds.size > 0 && !(typeof document !== 'undefined' && document.hidden) }

  hold(): () => void {
    const hold = Symbol('playing')
    this.holds.add(hold)
    return () => { this.holds.delete(hold) }
  }
}
