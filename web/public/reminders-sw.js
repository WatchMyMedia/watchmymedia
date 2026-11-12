// WatchMyMedia's Live TV reminders (PLAN-LIVE-TV-GUIDE.md §6.5). An open
// WatchMyMedia tab shows each reminder through this worker
// (ServiceWorkerRegistration.showNotification works where new Notification()
// doesn't, such as Android Chrome); choosing one opens its channel. The
// worker handles no fetches, so nothing of the app is cached or served from it.

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  // Only a path on this server: the page put it there. "//host" and "/\host"
  // start with a slash but resolve to another site, so the origin is checked.
  let asked
  try { asked = new URL(event.notification.data?.path, self.location.origin) } catch { /* not a URL */ }
  const path = typeof event.notification.data?.path === 'string' && asked?.origin === self.location.origin ? asked.pathname + asked.search : '/live-tv'
  const url = new URL(path, self.location.origin).href
  event.waitUntil((async () => {
    const tabs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const tab = tabs.find((c) => new URL(c.url).origin === self.location.origin)
    if (tab) {
      await tab.focus()
      tab.postMessage({ type: 'wmm.open', path })
      return
    }
    await self.clients.openWindow(url)
  })())
})
