import { vi } from 'vitest'
import { REQUIRED_API_LEVEL } from './session'

// Stand-ins for several WatchMyMedia servers in tests, each at its own
// address with its own sign-in. Every protected route wants that server's
// own token, so a token sent to the wrong address is refused as well as
// recorded. Every name and address is made up.

export type StubServer = {
  origin: string
  id: string
  name: string
  /** The session token this server accepts. */
  token: string
  apiLevel?: number
  /** Nothing answers at its address. */
  down?: boolean
  /** Replies of its own, by "METHOD /path": a Response, or undefined to fall through to the usual one. */
  routes?: Record<string, (req: Request) => Response | Promise<Response> | undefined>
}

export function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return { get length() { return m.size }, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => { m.set(k, v) },
    removeItem: (k) => { m.delete(k) }, clear: () => m.clear(), key: (i) => [...m.keys()][i] ?? null }
}

/** The account a test puts in the store for a stub server, as if this TV had signed in to it. */
export const accountOn = (server: Pick<StubServer, 'token'>) => ({ userId: 4, name: 'Sam', isAdmin: false, token: server.token, sessionId: `${server.token}-device` })

const OPEN = /^\/api\/(server|auth\/(login|pair|pair\/claim))$/

/** Puts the servers in front of `fetch` and records every request. Change a server's fields to change how it answers. */
export function stubServers(servers: StubServer[]): { calls: Request[] } {
  const calls: Request[] = []
  vi.stubGlobal('fetch', async (input: Request | string, init?: RequestInit) => {
    const req = typeof input === 'string' ? new Request(input, init) : input
    calls.push(req)
    const url = new URL(req.url)
    const server = servers.find((s) => s.origin === url.origin)
    if (!server || server.down) throw new TypeError('Failed to fetch')
    const own = await server.routes?.[`${req.method} ${url.pathname}`]?.(req)
    if (own) return own
    if (!OPEN.test(url.pathname) && req.headers.get('Authorization') !== `Bearer ${server.token}`) return new Response(null, { status: 401 })
    switch (`${req.method} ${url.pathname}`) {
      case 'GET /api/server': return Response.json({ id: server.id, name: server.name, version: 'v1.8.0', apiLevel: server.apiLevel ?? REQUIRED_API_LEVEL, setupRequired: false, turnstile: false })
      case 'POST /api/auth/login': return Response.json({ id: 4, name: 'Sam', isAdmin: false, token: server.token, sessionId: `${server.token}-device` })
      case 'POST /api/auth/pair': return Response.json({ code: 'KX7P4M', secret: 'claim-secret', linkPath: '/link?code=KX7P4M', intervalMs: 1000, expiresAt: new Date(Date.now() + 600_000).toISOString() })
      case 'POST /api/auth/pair/claim': return Response.json({ status: 'pending' })
      case 'GET /api/auth/me': return Response.json({ id: 4, name: 'Sam', isAdmin: false })
      case 'POST /api/auth/media-token': return Response.json({ token: `media-${server.id}`, param: 'access_token', expiresAt: new Date(Date.now() + 3600_000).toISOString() })
      case 'POST /api/auth/logout': return new Response(null, { status: 204 })
      case 'GET /api/events': return new Response(new ReadableStream({ start() { /* Stays open. */ } }))
      case 'GET /api/libraries': return Response.json({ items: [] })
      case 'GET /api/search': return Response.json({ movies: [], shows: [], episodes: [] })
      default: throw new Error(`unexpected ${req.method} ${url.pathname}`)
    }
  })
  return { calls }
}

/** The addresses a token was sent to. */
export function sentTo(calls: readonly Request[], token: string): string[] {
  return [...new Set(calls.filter((r) => r.headers.get('Authorization') === `Bearer ${token}`).map((r) => new URL(r.url).origin))]
}
