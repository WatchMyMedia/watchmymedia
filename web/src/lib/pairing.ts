/** What is waiting behind a pairing code, from the platform its app reported. */
export type PairingKind = 'tv' | 'app' | 'device'

export function pairingKind(platform?: string): PairingKind {
  if (platform === 'androidtv' || platform === 'tvos' || platform === 'tizen') return 'tv'
  if (platform === 'android' || platform === 'ios') return 'app'
  return 'device'
}

/**
 * Whether this browser runs where a phone or tablet app of that platform
 * could, so a link back to the app can open it. iPadOS Safari calls itself
 * a Mac, so a Mac with a touch screen counts as an iPad.
 */
export function onAppPlatform(platform: string | undefined, userAgent: string, touchPoints: number): boolean {
  if (platform === 'android') return /Android/.test(userAgent)
  if (platform === 'ios') return /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && touchPoints > 1)
  return false
}

/** Opens the phone or tablet app where it was; both apps ignore a link they don't know. */
export const APP_LINK = 'watchmymedia://home'
