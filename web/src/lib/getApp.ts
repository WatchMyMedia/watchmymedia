/**
 * Where the phone, tablet and TV apps are offered in the web app: the bar's
 * Get the app menu on computers, and on phones and tablets a prompt for that
 * device's own app.
 */

export const APP_STORE_URL = 'https://apps.apple.com/app/watchmymedia/id6828975407'
export const GOOGLE_PLAY_URL = 'https://play.google.com/store/apps/details?id=media.watchmy.client'

export type DeviceApp = { store: 'ios' | 'android'; device: 'Android' | 'iPhone' | 'iPad' }

/** Amazon's Fire tablets and TVs run Android without Google Play. */
const FIRE = /Silk|Kindle|\bKF[A-Z]{2,}\b|\bAFT[A-Z0-9]*\b/

/**
 * The phone or tablet this browser runs on, or null on a computer or a TV.
 * iPadOS Safari asks for the desktop site and calls itself a Mac, so a Mac
 * with a touch screen counts as an iPad.
 */
export function deviceApp(userAgent: string, touchPoints: number): DeviceApp | null {
  if (/Android/i.test(userAgent)) return FIRE.test(userAgent) ? null : { store: 'android', device: 'Android' }
  if (/iPad/.test(userAgent) || (/Macintosh/.test(userAgent) && touchPoints > 1)) return { store: 'ios', device: 'iPad' }
  if (/iPhone|iPod/.test(userAgent)) return { store: 'ios', device: 'iPhone' }
  return null
}

/**
 * Whether the bar shows Get the app: on computers, not on phones and
 * tablets, which are offered their own app instead. That also keeps it out
 * of the in-app browser the iPhone and iPad app opens the server's pages in.
 */
export function showsGetAppMenu(userAgent: string, touchPoints: number): boolean {
  if (/Android/i.test(userAgent)) return false
  return deviceApp(userAgent, touchPoints) === null
}

/**
 * The app to offer above the bar, or null. Safari on an iPhone or iPad
 * draws Apple's own banner from index.html's apple-itunes-app tag, which
 * says Open once the app is installed, so only Chrome, Firefox and Edge
 * there get this one. The in-app browser the app opens pages in is Safari,
 * so it gets neither.
 */
export function appPrompt(userAgent: string, touchPoints: number): DeviceApp | null {
  const app = deviceApp(userAgent, touchPoints)
  if (app?.store === 'ios' && !/CriOS|FxiOS|EdgiOS/.test(userAgent)) return null
  return app
}

export function storeURL(app: DeviceApp): string {
  return app.store === 'ios' ? APP_STORE_URL : GOOGLE_PLAY_URL
}

export function storeName(app: DeviceApp): string {
  return app.store === 'ios' ? 'the App Store' : 'Google Play'
}

const DISMISSED = 'watchmymedia.appPrompt'

/** Whether this browser closed the prompt or followed it to the store. */
export function appPromptDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED) !== null
  } catch {
    return false
  }
}

export function dismissAppPrompt() {
  try {
    localStorage.setItem(DISMISSED, new Date().toISOString())
  } catch {
    // Storage blocked: the prompt stays closed until the page reloads.
  }
}
