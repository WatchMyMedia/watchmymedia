import type { TvPlatform } from '../platform'
import { BACK_KEY, LG_KEY_CODES, REPEAT_MS } from './keys'
import { WebOSPlayer } from './player'
import { lgProfiles } from './profile'
import { bringForward, onTV, platformBack, readDevice, ScreenSaver, webOSName, type DeviceFacts } from './webos'

let device: DeviceFacts = { hdr10: false }
const screenSaver = new ScreenSaver()

/** How long start waits for the TV to say what it is before the app goes on without it. */
const DEVICE_WAIT_MS = 1500

/**
 * The LG TV: webOS's system calls, its video element with hls.js, and the
 * Magic Remote, which points as well as steering by arrows. The store
 * identity is public/appinfo.json's id, media.watchmy.client, which never
 * changes.
 */
export const platform: TvPlatform = {
  id: 'webos',
  words: {
    tv: 'LG TV',
    aTv: 'an LG TV',
    product: 'WatchMyMedia for LG TV',
    back: 'Back',
    player: 'LG player',
    preview: 'pnpm dev:webos',
  },
  storage: {
    servers: 'watchmymedia.webos.servers.v1',
    device: 'watchmymedia.webos.device',
    copies: 'watchmymedia.webos.copies.v1',
    recentSearches: 'watchmymedia.webos.recentSearches.v1',
    remindersSeen: 'wmm.webos.reminders.seen',
  },
  keyCodes: LG_KEY_CODES,
  typing: { done: [], cancel: [BACK_KEY] },
  repeatMs: REPEAT_MS,
  numberKeys: false,
  pointer: true,
  async start() {
    // Opened again while it runs (appinfo.json's handlesRelaunch): come forward where it was.
    document.addEventListener('webOSRelaunch', () => bringForward(), true)
    screenSaver.start()
    // What the TV is, for the profile and About: waited on a moment, and kept whenever it comes.
    const read = readDevice().then((facts) => { device = facts })
    await Promise.race([read, new Promise((resolve) => setTimeout(resolve, DEVICE_WAIT_MS))])
  },
  // Leaving after the app's own question (only shown where webOS can't take Back): LG's own way to close an app.
  exit() {
    if (!onTV()) return false
    window.close()
    return true
  },
  // LG's checklist: Back on the first screen goes to the TV's Home, through webOS's own exit.
  backAtHome: platformBack,
  holdScreenSaver: () => screenSaver.hold(),
  refreshScreenSaver() {},
  model: () => device.model,
  describe() {
    const version = webOSName(device.version)
    return [`LG ${device.model ?? 'TV'}`, version].filter(Boolean).join(' · ')
  },
  takesHdr10: () => device.hdr10,
  createPlayer() {
    // The preview plays through the development players (main.tsx); on the TV, its own video.
    if (import.meta.env.DEV && !onTV()) return undefined
    const video = document.querySelector<HTMLVideoElement>('#browser-player')
    return video ? new WebOSPlayer(video) : undefined
  },
  profiles: lgProfiles,
}
