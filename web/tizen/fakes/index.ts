import { ServerStore } from '../session/servers'
import { FakeServer } from './server'
import { registerStart } from './start'
import { registerBrowse } from './browse'
import { registerLive } from './live'
import { registerRequests } from './requests'
import { registerSearch } from './search'
import { registerSettings } from './settings'
import { registerDetails } from './details'
import { registerPlayer } from './player'
import { registerWatchlists } from './watchlists'
import { FAKE_USER } from './data'
import { ATTIC, CABIN, CABIN_TITLES, CABIN_TOKEN, DEN, DEN_TOKEN, LAKESIDE, LAKESIDE_TOKEN, registerCabin, type FakeIdentity } from './others'

// The browser preview's fictional server (`pnpm dev:tizen`, then open
// /?fakes). Development only: main.tsx imports this behind
// import.meta.env.DEV, so the TV build leaves it out. A feature adds its
// routes in fakes/<area>.ts and registers them below.

/** The fake server's address: an mDNS name, so it reads as at home and nothing on the network is asked. */
export const FAKE_ORIGIN = LAKESIDE.origin
/** A remembered server away from home over plain http, which never answers (TEST-NET-3). */
export const AWAY_ORIGIN = 'http://203.0.113.20:27587'

/**
 * A fictional server. Lakeside by default; `identity` makes another one
 * (?fakes=servers), which answers at its own address with its own sign-ins
 * and, for Cabin, a Home and search of its own.
 */
export function createFakeServer(mode = '', identity: FakeIdentity = LAKESIDE): FakeServer {
  const server = new FakeServer(identity.origin)
  registerStart(server, mode, identity)
  if (identity.id === CABIN.id) registerCabin(server)
  // ?fakes=player plays from its own shows and files, so they answer first there.
  if (mode === 'player') registerPlayer(server, mode)
  // Live TV next, so its capabilities, channels, reminders and listings search answer before the others' copies.
  registerLive(server, mode)
  // The first registration of a route answers. Details first: in
  // ?fakes=member it makes the account a member and answers request options
  // as one. Then Requests, whose options follow the Inbox, before Browse's
  // static ones.
  registerDetails(server, mode, identity.id === CABIN.id ? CABIN_TITLES : undefined)
  registerRequests(server, mode)
  registerBrowse(server, mode)
  registerWatchlists(server)
  registerSearch(server)
  registerSettings(server, mode)
  if (mode !== 'player') registerPlayer(server, mode)
  return server
}

/**
 * Puts the fake server in front of `fetch`: requests to FAKE_ORIGIN get its
 * replies, the page's own address goes through, and everything else fails as
 * an unreachable server would. `mode` (the ?fakes= value) is "" for a first
 * run, "away" to remember a server away from home over http, "servers" for
 * a TV with several servers (below), or one of the server problems
 * registerStart knows ("old", "setup", "expire"). Returns the address to put
 * in the server field.
 *
 * "servers" is a TV that has four servers and opens on Lakeside, signed
 * in: Cabin, signed in, with titles of its own; Den, signed in, which never
 * answers; and Attic, which this TV is signed out of.
 */
export function installFakes(mode: string, storage: Storage | undefined): string {
  const servers = [createFakeServer(mode)]
  if (mode === 'servers') {
    const cabin = createFakeServer('', CABIN)
    cabin.trust(CABIN_TOKEN)
    servers[0].trust(LAKESIDE_TOKEN)
    servers.push(cabin, createFakeServer('', ATTIC))
    const store = new ServerStore(storage)
    const day = 86_400_000
    const account = (token: string) => ({ userId: FAKE_USER.id, name: FAKE_USER.name, isAdmin: FAKE_USER.isAdmin, token, sessionId: `${token}-device` })
    const facts = (identity: FakeIdentity) => ({ id: identity.id, name: identity.name, baseUrl: identity.origin, version: 'v1.8.0' })
    if (!store.get(ATTIC.id)) store.remember(facts(ATTIC), Date.now() - 9 * day)
    if (!store.get(DEN.id)) store.remember({ ...facts(DEN), account: account(DEN_TOKEN) }, Date.now() - 5 * day)
    if (!store.get(CABIN.id)) store.remember({ ...facts(CABIN), account: account(CABIN_TOKEN) }, Date.now() - 2 * day)
    if (!store.get(LAKESIDE.id)) store.remember({ ...facts(LAKESIDE), account: account(LAKESIDE_TOKEN) })
  }
  const real = window.fetch.bind(window)
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request && !init ? input : new Request(input, init)
    const origin = new URL(request.url, location.href).origin
    const server = servers.find((s) => s.origin === origin)
    if (server) return server.handle(request)
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
