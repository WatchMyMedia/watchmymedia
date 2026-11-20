import { pruneNotices } from './liveReminders'

/**
 * The browser's part in Live TV reminders and
 * new-episode notices: the notification
 * permission, the same-origin service worker that shows them
 * (public/reminders-sw.js; the CSP's worker-src 'self' allows it), the Web
 * Lock that lets one tab notify, and the record of what was shown. All of
 * them need a secure context; without one, reminders come as the in-app
 * banner only, and new episodes not at all.
 */

export const REMINDER_WORKER = '/reminders-sw.js'
const LOCK = 'watchmymedia.live-reminders'
/** New episodes have their own lock: a tab holds both, and a second request for one lock would wait on itself. */
export const NEW_EPISODES_LOCK = 'watchmymedia.new-episodes'
const ASKED = 'wmm.liveReminders.asked'
const NOTIFIED = 'wmm.liveReminders.notified'
const DISMISSED = 'wmm.liveReminders.dismissed'

/** This browser can show system notifications from a worker here. */
export function canNotify(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext && 'Notification' in window && 'serviceWorker' in navigator
}

export function notifyPermission(): NotificationPermission | 'unsupported' {
  return canNotify() ? Notification.permission : 'unsupported'
}

/** The first Remind me explains before the browser asks; once, whatever the answer. */
export function shouldExplain(): boolean {
  if (notifyPermission() !== 'default') return false
  try { return !localStorage.getItem(ASKED) } catch { return true }
}

export async function askToNotify(): Promise<NotificationPermission | 'unsupported'> {
  try { localStorage.setItem(ASKED, '1') } catch { /* storage may be blocked */ }
  return requestNotify()
}

/** The browser's own question, without the reminders' asked mark: new episodes keep their own. */
export async function requestNotify(): Promise<NotificationPermission | 'unsupported'> {
  if (!canNotify()) return 'unsupported'
  const answer = await Notification.requestPermission()
  if (answer === 'granted') void reminderWorker()
  return answer
}

export function noteAsked() {
  try { localStorage.setItem(ASKED, '1') } catch { /* storage may be blocked */ }
}

let worker: Promise<ServiceWorkerRegistration | undefined> | undefined
/** Registers the reminders' worker once a page load. */
export function reminderWorker(): Promise<ServiceWorkerRegistration | undefined> {
  if (!canNotify()) return Promise.resolve(undefined)
  worker ??= navigator.serviceWorker.register(REMINDER_WORKER).then(() => navigator.serviceWorker.ready).catch(() => undefined)
  return worker
}

/** Shows a reminder; its tag replaces one already showing for the same reminder. */
export function showReminder(o: { title: string; body: string; tag: string; path?: string }): Promise<boolean> {
  return showNotice({ ...o, action: o.path ? 'Watch' : undefined })
}

/** Shows a notification through the worker; its tag replaces one already showing with the same tag. */
export async function showNotice(o: { title: string; body: string; tag: string; path?: string; action?: string }): Promise<boolean> {
  if (notifyPermission() !== 'granted') return false
  const registration = await reminderWorker()
  if (!registration) return false
  try {
    await registration.showNotification(o.title, {
      body: o.body,
      tag: o.tag,
      icon: '/icon-192.png',
      data: { path: o.path },
      ...(o.action ? { actions: [{ action: 'open', title: o.action }] } : {}),
    } as NotificationOptions)
    return true
  } catch {
    return false
  }
}

/**
 * The paths a notification may open in a tab already open: a channel
 * (reminders), a show's page and Home (new episodes). Anything else the
 * worker sends is ignored.
 */
export function openablePath(path: unknown): path is string {
  return typeof path === 'string' && (path === '/' || path.startsWith('/live-tv') || path.startsWith('/shows/'))
}

/** Calls open with a path when a notification is chosen in a tab already open. */
export function onReminderOpened(open: (path: string) => void): () => void {
  if (!canNotify()) return () => {}
  const listen = (event: MessageEvent) => {
    const data = event.data as { type?: string; path?: unknown } | undefined
    if (data?.type === 'wmm.open' && openablePath(data.path)) open(data.path)
  }
  navigator.serviceWorker.addEventListener('message', listen)
  return () => navigator.serviceWorker.removeEventListener('message', listen)
}

/**
 * Waits to be the one tab that notifies, then calls held; the lock passes
 * to another tab when this one closes. Without Web Locks every tab may
 * notify, and the notifications' tags keep one each.
 */
export function holdNotifierLock(held: () => void, lock = LOCK): () => void {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (!locks) {
    held()
    return () => {}
  }
  const stop = new AbortController()
  let release: () => void = () => {}
  locks.request(lock, { signal: stop.signal }, () => {
    held()
    return new Promise<void>((resolve) => { release = resolve })
  }).catch(() => { /* aborted: this tab left before it held the lock */ })
  return () => { stop.abort(); release() }
}

function load(key: string): Record<string, number> {
  try { return pruneNotices(JSON.parse(localStorage.getItem(key) ?? '{}') as Record<string, number>, Date.now()) } catch { return {} }
}
function store(key: string, value: Record<string, number>) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage may be blocked */ }
}

/** Marks a notice shown; false when it already was, in this tab or another. */
export function claimNotice(key: string): boolean {
  const seen = load(NOTIFIED)
  if (seen[key]) return false
  store(NOTIFIED, { ...seen, [key]: Date.now() })
  return true
}

/** Banners dismissed, in every tab of this browser. */
export function dismissedNotices(): Set<string> {
  return new Set(Object.keys(load(DISMISSED)))
}
export function dismissNotice(key: string) {
  store(DISMISSED, { ...load(DISMISSED), [key]: Date.now() })
}
export const DISMISSED_KEY = DISMISSED
