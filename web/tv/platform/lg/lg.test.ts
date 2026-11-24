import { afterEach, describe, expect, it, vi } from 'vitest'
import appinfo from './public/appinfo.json'
import { TV_APP_VERSION } from '../../session/device'
import { platform as samsung } from '../samsung'
import { platform } from '.'
import { LG_KEY_CODES } from './keys'
import { lgFallbackProfile, lgLiveProfile, lgProfile } from './profile'
import { lunaCall, platformBack, readDevice, ScreenSaver, webOSName, type WebOSWindow } from './webos'
import { compatibleLiveProfile } from '../../../src/lib/liveTV'

type Call = { uri: string; params: Record<string, unknown>; bridge: FakeBridge }
class FakeBridge {
  onservicecallback?: (message: string) => void
  cancelled = false
  constructor(private calls: Call[]) {}
  call(uri: string, params: string) { this.calls.push({ uri, params: JSON.parse(params) as Record<string, unknown>, bridge: this }) }
  cancel() { this.cancelled = true }
  reply(body: Record<string, unknown>) { this.onservicecallback?.(JSON.stringify(body)) }
}

/** A webOS window whose Luna calls answer from `answer` (undefined: never answers). */
function tv(answer: (uri: string, params: Record<string, unknown>) => Record<string, unknown> | undefined, system: Record<string, unknown> = {}) {
  const calls: Call[] = []
  const w: WebOSWindow = {
    webOSSystem: { deviceInfo: JSON.stringify({ modelName: 'WEBOS9', platformVersion: '9.2.0' }), ...system },
    PalmServiceBridge: class extends FakeBridge {
      constructor() { super(calls) }
      call(uri: string, params: string) {
        super.call(uri, params)
        const reply = answer(uri, JSON.parse(params) as Record<string, unknown>)
        if (reply) queueMicrotask(() => this.reply(reply))
      }
    },
  }
  return { w, calls }
}

afterEach(() => { vi.useRealTimers() })

describe('the LG TV', () => {
  it('keeps appinfo.json at the version the app reports, under its permanent id', () => {
    expect(appinfo.version).toBe(TV_APP_VERSION)
    expect(appinfo).toMatchObject({ id: 'media.watchmy.client', type: 'web', main: 'index.html', icon: 'icon.png', largeIcon: 'largeIcon.png', disableBackHistoryAPI: true, handlesRelaunch: true })
    expect(appinfo.appDescription.length).toBeLessThanOrEqual(60)
  })

  it('names itself and keeps its own storage, apart from Samsung’s', () => {
    expect(platform.id).toBe('webos')
    expect(platform.words).toMatchObject({ tv: 'LG TV', aTv: 'an LG TV', product: 'WatchMyMedia for LG TV', back: 'Back' })
    for (const [key, name] of Object.entries(platform.storage)) {
      expect(name).toContain('webos')
      expect(name).not.toBe(samsung.storage[key as keyof typeof samsung.storage])
    }
  })

  it('takes Back as 461 and the media keys, and has no number keys but a pointer', () => {
    expect(LG_KEY_CODES[461]).toBe('back')
    expect(LG_KEY_CODES[415]).toBe('play')
    expect(LG_KEY_CODES[412]).toBe('rewind')
    expect(platform.typing.cancel).toContain(461)
    expect(platform).toMatchObject({ numberKeys: false, pointer: true, repeatMs: 100 })
  })

  it('claims what the plan lists, HDR10 only when the TV says so, and Dolby surround', () => {
    expect(lgProfile()).toMatchObject({ name: 'lg-tv', containers: ['mp4', 'mkv'], videoCodecs: ['h264', 'hevc', 'hevc10'], audioCodecs: ['aac', 'ac3', 'eac3'], maxAudioChannels: 6, hdr: false, hdrTypes: [] })
    expect(lgProfile({ hdr10: true })).toMatchObject({ hdr: true, hdrTypes: ['hdr10'] })
    expect(lgProfile().audioCodecs).not.toContain('dts')
    expect(lgFallbackProfile()).toMatchObject({ name: 'lg-tv-fallback', videoCodecs: ['h264'], audioCodecs: ['aac'], maxHeight: 1080, maxAudioChannels: 2 })
    expect(lgLiveProfile).toEqual(compatibleLiveProfile)
  })
})

describe('webOS calls', () => {
  it('names webOS by LG’s year from 7.x on', () => {
    expect(webOSName('9.2.0')).toBe('webOS 24')
    expect(webOSName('10.0.1')).toBe('webOS 25')
    expect(webOSName('7.3.0')).toBe('webOS 22')
    expect(webOSName('6.0')).toBe('webOS 6')
    expect(webOSName(undefined)).toBeUndefined()
    expect(webOSName('x')).toBeUndefined()
  })

  it('reads the model and HDR from Luna, and the version from the system', async () => {
    const { w, calls } = tv((uri) => uri.endsWith('getSystemInfo') ? { returnValue: true, modelName: 'OLED65C4PUA', sdkVersion: '9.1.0' }
      : uri.endsWith('getConfigs') ? { returnValue: true, configs: { 'tv.model.supportHDR': true } } : undefined)
    await expect(readDevice(w)).resolves.toEqual({ model: 'OLED65C4PUA', version: '9.2.0', hdr10: true })
    expect(calls.map((c) => c.uri)).toEqual(['luna://com.webos.service.tv.systemproperty/getSystemInfo', 'luna://com.webos.service.config/getConfigs'])
    expect(calls.every((c) => c.bridge.cancelled)).toBe(true)
  })

  it('claims no HDR10 when Luna refuses or doesn’t answer, and keeps the system’s model', async () => {
    vi.useFakeTimers()
    const { w } = tv((uri) => uri.endsWith('getConfigs') ? { returnValue: false, errorText: 'denied' } : undefined)
    const read = readDevice(w, 1000)
    await vi.advanceTimersByTimeAsync(1000)
    await expect(read).resolves.toEqual({ model: 'WEBOS9', version: '9.2.0', hdr10: false })
  })

  it('asks nothing in the preview', async () => {
    await expect(readDevice({})).resolves.toEqual({ hdr10: false })
    await expect(lunaCall('luna://x/y', {}, 10, {})).resolves.toBeUndefined()
  })

  it('hands Back to webOS where it can, and says when it can’t', () => {
    const back = vi.fn()
    expect(platformBack({ webOSSystem: { platformBack: back } })).toBe(true)
    expect(back).toHaveBeenCalledOnce()
    expect(platformBack({ PalmSystem: { platformBack: back } })).toBe(true)
    expect(platformBack({})).toBe(false)
  })

  it('says no to the screensaver only while something holds it', async () => {
    const { w, calls } = tv(() => undefined)
    const saver = new ScreenSaver(w)
    saver.start()
    saver.start()
    const register = calls.filter((c) => c.uri.endsWith('registerScreenSaverRequest'))
    expect(register).toHaveLength(1)
    expect(register[0].params).toMatchObject({ subscribe: true })
    const ask = (timestamp: number) => register[0].bridge.reply({ returnValue: true, timestamp })
    ask(1)
    const release = saver.hold()
    ask(2)
    release()
    ask(3)
    register[0].bridge.reply({ returnValue: true, subscribed: true })
    const answers = calls.filter((c) => c.uri.endsWith('responseScreenSaverRequest')).map((c) => c.params)
    expect(answers).toEqual([
      { clientName: 'media.watchmy.client', ack: true, timestamp: 1 },
      { clientName: 'media.watchmy.client', ack: false, timestamp: 2 },
      { clientName: 'media.watchmy.client', ack: true, timestamp: 3 },
    ])
  })
})
