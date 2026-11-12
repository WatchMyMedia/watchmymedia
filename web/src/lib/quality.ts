import type { Profile } from './capabilities'

/**
 * Streaming quality the viewer picks in the player. "Original" plays what
 * the browser can handle; the steps cap size and bitrate, which makes the
 * server transcode anything above them (for slow connections away from home).
 * "Automatic" leaves the step to the server: it starts at the original and
 * adjusts it when the connection changes (PLAN-STREAMING.md).
 */
export type Quality = { id: string; label: string; maxWidth?: number; maxHeight?: number; maxBitrate?: number }

export const AUTO = 'auto'

export const QUALITIES: Quality[] = [
  { id: AUTO, label: 'Automatic' },
  { id: 'original', label: 'Original quality' },
  // The server only encodes 4K where its hardware proved fast enough; elsewhere this lands at 1080p.
  { id: '2160', label: '4K · 20 Mbps', maxWidth: 3840, maxHeight: 2160, maxBitrate: 20_000_000 },
  { id: '1080', label: '1080p · 8 Mbps', maxWidth: 1920, maxHeight: 1080, maxBitrate: 8_000_000 },
  { id: '720', label: '720p · 4 Mbps', maxWidth: 1280, maxHeight: 720, maxBitrate: 4_000_000 },
  { id: '480', label: '480p · 2 Mbps', maxWidth: 854, maxHeight: 480, maxBitrate: 2_000_000 },
  { id: '360', label: '360p · 1 Mbps', maxWidth: 640, maxHeight: 360, maxBitrate: 1_000_000 },
]

const ORIGINAL = QUALITIES[1]

/** The server API level (GET /api/server's apiLevel) from which a server can do Automatic. */
export const AUTO_API_LEVEL = 3

export const canAuto = (apiLevel: number | undefined) => (apiLevel ?? 0) >= AUTO_API_LEVEL

/** The qualities to offer for a server: Automatic only where it can do it. */
export function qualitiesFor(apiLevel: number | undefined): Quality[] {
  return canAuto(apiLevel) ? QUALITIES : QUALITIES.filter((q) => q.id !== AUTO)
}

/**
 * The quality an ID names for a server. An unknown ID plays as Original, and
 * so does Automatic on a server that cannot do it.
 */
export function qualityById(id: string | null | undefined, apiLevel: number | undefined): Quality {
  return qualitiesFor(apiLevel).find((q) => q.id === id) ?? ORIGINAL
}

const KEY = 'watchmymedia.quality'

/** The quality last picked on this device; Automatic when none was. */
export function savedQuality(apiLevel: number | undefined): Quality {
  let id: string | null = null
  try {
    id = localStorage.getItem(KEY)
  } catch {
    // storage blocked: as if nothing was picked
  }
  return qualityById(id ?? AUTO, apiLevel)
}

export function saveQuality(q: Quality) {
  try {
    localStorage.setItem(KEY, q.id)
  } catch {
    // storage blocked: the choice lasts for this page only
  }
}

/** "720p" for a step, "Original" for the original. */
export function stepName(id: string | undefined): string {
  if (!id || id === 'original') return 'Original'
  return id === '2160' ? '4K' : `${id}p`
}

/** "Now 720p · 4 Mbps": the step Automatic is at, for the menu. */
export function autoNow(step: string | undefined): string {
  if (!step || step === 'original') return 'Now original quality'
  return `Now ${QUALITIES.find((q) => q.id === step)?.label ?? stepName(step)}`
}

const lower = (a: number | undefined, b: number | undefined) => (a && b ? Math.min(a, b) : a || b || 0)

/** The browser's profile narrowed to a quality; Automatic sends it uncapped. */
export function applyQuality(p: Profile, q: Quality): Profile {
  return {
    ...p,
    maxWidth: lower(p.maxWidth, q.maxWidth),
    maxHeight: lower(p.maxHeight, q.maxHeight),
    maxBitrate: lower(p.maxBitrate, q.maxBitrate),
  }
}
