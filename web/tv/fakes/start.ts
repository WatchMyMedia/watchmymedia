import type { components } from '../../src/api/schema'
import { REQUIRED_API_LEVEL } from '../session/session'
import { WATCHLISTS_API_LEVEL } from '../screens/watchlists/watchlistTv'
import { LEVEL_34, LEVEL_35 } from '../session/features'
import { FakeServer, problem } from './server'
import { FAKE_USER } from './data'
import { LAKESIDE, type FakeIdentity } from './others'

type ServerInfo = components['schemas']['ServerInfo']

/** The fictional password that signs in as FAKE_USER in the preview. */
export const FAKE_PASSWORD = 'watching'
/** How long after a code is shown the fake approves it. */
const APPROVE_AFTER_MS = 8000

/**
 * What every app needs before it is signed in, and the session's own
 * routes: the server's facts, pairing that approves itself after a few
 * seconds, password sign-in, who is signed in, media tokens and the event
 * stream. `mode` picks a server problem: "old" (below this TV's API level),
 * "setup" (no accounts yet) or "expire" (signed out from another device 30 s
 * after signing in). `identity` is which server it says it is.
 */
export function registerStart(server: FakeServer, mode: string, identity: FakeIdentity = LAKESIDE) {
  const info: ServerInfo = {
    id: identity.id, name: identity.name, version: mode === 'old' ? 'v1.7.0' : 'v1.8.0', platform: 'linux',
    // A server keeps watchlists from level 33; ?fakes=nowatchlists is one that doesn't yet, ?fakes=level33 one without level 34's people, counts and copies,
    // and ?fakes=level34 one without level 35's sound settings, subtitle size and timing.
    apiLevel: mode === 'old' ? REQUIRED_API_LEVEL - 1 : mode === 'nowatchlists' ? REQUIRED_API_LEVEL : mode === 'level33' ? WATCHLISTS_API_LEVEL : mode === 'level34' ? LEVEL_34 : LEVEL_35,
    setupRequired: mode === 'setup', turnstile: false, casting: false,
  }
  server.route('GET', '/api/server', () => info, { open: true })

  const pairings = new Map<string, { code: string; at: number; used: boolean }>()
  const signedIn = () => {
    const token = server.issueToken()
    if (mode === 'expire') setTimeout(() => server.revokeAll(), 30_000)
    return token
  }
  server.route('POST', '/api/auth/pair', () => {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    const code = Array.from({ length: 6 }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join('')
    const secret = `fake-secret-${code}`
    pairings.set(secret, { code, at: Date.now(), used: false })
    return { code, secret, linkPath: `/link?code=${code}`, intervalMs: 2000, expiresAt: new Date(Date.now() + 10 * 60_000).toISOString() }
  }, { open: true })
  server.route('POST', '/api/auth/pair/claim', ({ body }) => {
    const p = pairings.get((body as { secret?: string })?.secret ?? '')
    if (!p || p.used) return problem(404, 'that code is not waiting to be linked')
    if (Date.now() - p.at < APPROVE_AFTER_MS) return { status: 'pending' }
    p.used = true
    return { status: 'approved', token: signedIn(), sessionId: `fake-device-${p.code}`, user: FAKE_USER, expiresAt: new Date(Date.now() + 90 * 86_400_000).toISOString() }
  }, { open: true })
  server.route('POST', '/api/auth/login', ({ body }) => {
    const b = body as { name?: string; password?: string }
    if (b?.name?.toLowerCase() !== FAKE_USER.name.toLowerCase() || b.password !== FAKE_PASSWORD) return problem(401, 'wrong name or password')
    return { ...FAKE_USER, token: signedIn(), sessionId: 'fake-device-password' }
  }, { open: true })
  server.route('POST', '/api/auth/logout', ({ token }) => { if (token) server.revokeToken(token); return new Response(null, { status: 204 }) })
  server.route('GET', '/api/auth/me', () => FAKE_USER)
  server.route('POST', '/api/auth/media-token', () => ({ token: 'fake-media', param: 'access_token', expiresAt: new Date(Date.now() + 60 * 60_000).toISOString() }))

  // The event stream: a ping now and every 15 s, as the server sends, until the TV hangs up.
  server.route('GET', '/api/events', ({ request }) => {
    const encoder = new TextEncoder()
    let timer: ReturnType<typeof setInterval> | undefined
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(': ping\n\n'))
        timer = setInterval(() => controller.enqueue(encoder.encode(': ping\n\n')), 15_000)
        request.signal.addEventListener('abort', () => { clearInterval(timer); try { controller.close() } catch { /* Already closed. */ } })
      },
      cancel() { clearInterval(timer) },
    })
    return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
  })
}
