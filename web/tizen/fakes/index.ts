import { ServerStore } from '../session/servers'
import { FakeServer } from './server'
import { registerStart } from './start'
import { registerBrowse } from './browse'

// The browser preview's fictional server (`pnpm dev:tizen`, then open
// /?fakes). Development only: main.tsx imports this behind
// import.meta.env.DEV, so the TV build leaves it out. A feature adds its
// routes in fakes/<area>.ts and registers them below.

/** The fake server's address: an mDNS name, so it reads as at home and nothing on the network is asked. */
export const FAKE_ORIGIN = 'http://lakeside.local:27587'
/** A remembered server away from home over plain http, which never answers (TEST-NET-3). */
export const AWAY_ORIGIN = 'http://203.0.113.20:27587'

export function createFakeServer(mode = ''): FakeServer {
  const server = new FakeServer(FAKE_ORIGIN)
  registerStart(server, mode)
  registerBrowse(server, mode)
  return server
}

/**
 * Puts the fake server in front of `fetch`: requests to FAKE_ORIGIN get its
 * replies, the page's own address goes through, and everything else fails as
 * an unreachable server would. `mode` (the ?fakes= value) is "" for a first
 * run, "away" to remember a server away from home over http, or one of the
 * server problems registerStart knows ("old", "setup", "expire"). Returns
 * the address to put in the server field.
 */
export function installFakes(mode: string, storage: Storage | undefined): string {
  const server = createFakeServer(mode)
  const real = window.fetch.bind(window)
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request && !init ? input : new Request(input, init)
    const origin = new URL(request.url, location.href).origin
    if (origin === FAKE_ORIGIN) return server.handle(request)
    if (origin === location.origin) return real(input, init)
    return Promise.reject(new TypeError('Failed to fetch'))
  }
  if (mode === 'away') {
    const store = new ServerStore(storage)
    if (!store.get('fake-lake-house')) store.remember({ id: 'fake-lake-house', name: 'Lake house', baseUrl: AWAY_ORIGIN, version: 'v1.8.0' }, Date.now() - 2 * 86_400_000)
  }
  document.documentElement.dataset.fakes = mode || 'on'
  return 'lakeside.local'
}
