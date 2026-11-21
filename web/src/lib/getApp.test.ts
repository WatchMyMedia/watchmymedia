import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { appPrompt, appPromptDismissed, deviceApp, dismissAppPrompt, showsGetAppMenu, storeName, storeURL } from './getApp'

const ua = {
  pixel: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
  galaxyTab: 'Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  firefoxAndroid: 'Mozilla/5.0 (Android 14; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0',
  googleTV: 'Mozilla/5.0 (Linux; Android 12; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  fireTV: 'Mozilla/5.0 (Linux; Android 9; AFTKA Build/PS7633) AppleWebKit/537.36 (KHTML, like Gecko) Silk/131.3.1 like Chrome/131.0.0.0 Safari/537.36',
  fireTablet: 'Mozilla/5.0 (Linux; Android 11; KFTRWI) AppleWebKit/537.36 (KHTML, like Gecko) Silk/131.3.1 like Chrome/131.0.0.0 Safari/537.36',
  iPhoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  iPhoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1',
  iPhoneFirefox: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/133.0 Mobile/15E148 Safari/605.1.15',
  iPhoneEdge: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 EdgiOS/131.2903.68 Mobile/15E148 Safari/605.1.15',
  iPadChrome: 'Mozilla/5.0 (iPad; CPU OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1',
  desktopSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
  macChrome: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  windowsEdge: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0',
  linuxFirefox: 'Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0',
  samsungTV: 'Mozilla/5.0 (SMART-TV; LINUX; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) 108.0.5359.1/8.0 TV Safari/537.36',
}

describe('the bar’s Get the app menu', () => {
  it('shows on computers and TV browsers', () => {
    for (const name of ['desktopSafari', 'macChrome', 'windowsEdge', 'linuxFirefox', 'samsungTV'] as const) {
      expect(showsGetAppMenu(ua[name], 0), name).toBe(true)
    }
    expect(showsGetAppMenu(ua.windowsEdge, 10), 'a Windows touch screen').toBe(true)
  })

  it('gives way on phones and tablets, the app’s own in-app Safari included', () => {
    for (const name of ['pixel', 'galaxyTab', 'firefoxAndroid', 'googleTV', 'fireTablet', 'iPhoneSafari', 'iPhoneChrome', 'iPadChrome'] as const) {
      expect(showsGetAppMenu(ua[name], 5), name).toBe(false)
    }
    // iPadOS Safari asks for the desktop site and says it is a Mac.
    expect(showsGetAppMenu(ua.desktopSafari, 5)).toBe(false)
  })
})

describe('the prompt for the device’s own app', () => {
  it('offers Google Play on Android phones, tablets and Google TV, in any browser', () => {
    for (const name of ['pixel', 'galaxyTab', 'firefoxAndroid', 'googleTV'] as const) {
      expect(appPrompt(ua[name], 5), name).toEqual({ store: 'android', device: 'Android' })
    }
  })

  it('offers nothing on Amazon’s Fire tablets and TVs, which have no Google Play', () => {
    expect(appPrompt(ua.fireTV, 0)).toBeNull()
    expect(appPrompt(ua.fireTablet, 5)).toBeNull()
    expect(deviceApp(ua.fireTablet, 5)).toBeNull()
  })

  it('offers the App Store in Chrome, Firefox and Edge on an iPhone or iPad', () => {
    expect(appPrompt(ua.iPhoneChrome, 5)).toEqual({ store: 'ios', device: 'iPhone' })
    expect(appPrompt(ua.iPhoneFirefox, 5)).toEqual({ store: 'ios', device: 'iPhone' })
    expect(appPrompt(ua.iPhoneEdge, 5)).toEqual({ store: 'ios', device: 'iPhone' })
    expect(appPrompt(ua.iPadChrome, 5)).toEqual({ store: 'ios', device: 'iPad' })
  })

  it('leaves Safari on an iPhone or iPad to Apple’s own banner', () => {
    expect(appPrompt(ua.iPhoneSafari, 5)).toBeNull()
    expect(appPrompt(ua.desktopSafari, 5)).toBeNull()
    expect(deviceApp(ua.desktopSafari, 5)).toEqual({ store: 'ios', device: 'iPad' })
  })

  it('offers nothing on computers and TVs', () => {
    for (const name of ['desktopSafari', 'macChrome', 'windowsEdge', 'linuxFirefox', 'samsungTV'] as const) {
      expect(appPrompt(ua[name], 0), name).toBeNull()
    }
  })

  it('names each store and links to its listing', () => {
    expect(storeURL({ store: 'ios', device: 'iPhone' })).toBe('https://apps.apple.com/app/watchmymedia/id6828975407')
    expect(storeURL({ store: 'android', device: 'Android' })).toBe('https://play.google.com/store/apps/details?id=media.watchmy.client')
    expect(storeName({ store: 'ios', device: 'iPad' })).toBe('the App Store')
    expect(storeName({ store: 'android', device: 'Android' })).toBe('Google Play')
  })
})

describe('closing the prompt', () => {
  function memoryStorage() {
    const m = new Map<string, string>()
    return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) }
  }
  beforeEach(() => vi.stubGlobal('localStorage', memoryStorage()))
  afterEach(() => vi.unstubAllGlobals())

  it('is remembered in this browser', () => {
    expect(appPromptDismissed()).toBe(false)
    dismissAppPrompt()
    expect(appPromptDismissed()).toBe(true)
  })

  it('still shows the prompt when storage is blocked', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked') }, setItem: () => { throw new Error('blocked') } })
    expect(() => dismissAppPrompt()).not.toThrow()
    expect(appPromptDismissed()).toBe(false)
  })
})
