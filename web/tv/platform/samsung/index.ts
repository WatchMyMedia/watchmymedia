import type { TvPlatform } from '../platform'
import { AVPlayPlayer, type SamsungWindow } from './avplay'
import { IME_CANCEL, IME_DONE, REGISTERED_KEYS, RETURN, SAMSUNG_KEY_CODES } from './keys'
import { samsungProfiles } from './profile'
import { exitApp, holdScreenSaver, loadWebApis, onTV, refreshScreenSaver, registerKeys, tizenVersion, tvModel, tvTakesHdr10 } from './tizen'

/**
 * The Samsung TV: Tizen's web APIs, AVPlay and the Samsung remote. Its ids,
 * words and storage names are the ones the app had before there was a second
 * make of TV, and stay so: the store identity is in public/config.xml.
 */
export const platform: TvPlatform = {
  id: 'tizen',
  words: {
    tv: 'Samsung TV',
    aTv: 'a Samsung TV',
    product: 'WatchMyMedia for Samsung TV',
    back: 'Return',
    player: 'Samsung player',
    preview: 'pnpm dev:tizen',
  },
  storage: {
    servers: 'watchmymedia.tizen.servers.v1',
    device: 'watchmymedia.tizen.device',
    copies: 'watchmymedia.tizen.copies.v1',
    recentSearches: 'watchmymedia.tizen.recentSearches.v1',
    remindersSeen: 'wmm.tizen.reminders.seen',
  },
  keyCodes: SAMSUNG_KEY_CODES,
  typing: { done: [IME_DONE], cancel: [IME_CANCEL, RETURN] },
  async start() {
    // Samsung's webapis.js (AVPlay, the screensaver, the model) comes with the packaged app only.
    if (!import.meta.env.DEV || onTV()) await loadWebApis().catch(() => undefined)
    registerKeys(REGISTERED_KEYS)
  },
  exit: exitApp,
  holdScreenSaver,
  refreshScreenSaver,
  model: tvModel,
  describe() {
    const version = tizenVersion()
    return [`Samsung ${tvModel() ?? 'TV'}`, version && `Tizen ${version}`].filter(Boolean).join(' · ')
  },
  takesHdr10: tvTakesHdr10,
  createPlayer() {
    const avplay = (window as SamsungWindow).webapis?.avplay
    if (!avplay) return undefined
    // AVPlay draws on the <object> plane; the preview's <video> isn't needed.
    document.querySelector('#browser-player')?.remove()
    return new AVPlayPlayer(avplay)
  },
  profiles: samsungProfiles,
}
