import { unwrap, type ApiClient } from '../../src/api/client'
import type { ClientInfo } from './device'

export type PairingCode = {
  /** Six characters from A–Z and 2–9, as the server gave it. */
  code: string
  /** The server's link page with the code filled in, for the QR code. */
  linkUrl: string
  expiresAt: number
}

/** A sign-in that worked, by code or by password: what the account store keeps. */
export type Grant = { token: string; sessionId: string; user: { id: number; name: string; isAdmin: boolean } }

/** "KX7P4M" → "KX7-P4M", as the TV shows a code. */
export function formatCode(code: string): string {
  return code.length === 6 ? `${code.slice(0, 3)}-${code.slice(3)}` : code
}

function cancelled() { return new DOMException('Cancelled', 'AbortError') }

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(cancelled()); return }
    const abort = () => { clearTimeout(timer); reject(cancelled()) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}

/**
 * One code (POST /api/auth/pair): shown through `show`, then claimed every
 * `intervalMs` until someone approves it, or 'expired' once it runs out (the
 * server answers 404 then too). The claim secret never leaves here. An
 * approval that lands after the TV gave up is signed out again, so no
 * session is left behind.
 */
export async function pairOnce(api: ApiClient, baseUrl: string, client: ClientInfo, signal: AbortSignal,
  show: (code: PairingCode) => void, now: () => number = () => Date.now()): Promise<Grant | 'expired'> {
  const start = unwrap(await api.POST('/api/auth/pair', { body: { client }, signal }))
  const expiresAt = Date.parse(start.expiresAt)
  show({ code: start.code, linkUrl: `${baseUrl}${start.linkPath}`, expiresAt })
  for (;;) {
    await delay(Math.max(1000, start.intervalMs), signal)
    if (now() >= expiresAt) return 'expired'
    const reply = await api.POST('/api/auth/pair/claim', { body: { secret: start.secret }, signal })
    if (reply.response.status === 404) return 'expired'
    const claim = unwrap(reply)
    if (claim.status !== 'approved') continue
    if (!claim.token || !claim.sessionId || !claim.user) throw new Error('incomplete approval')
    if (signal.aborted) {
      await revoke(api, claim.token)
      throw cancelled()
    }
    return { token: claim.token, sessionId: claim.sessionId, user: claim.user }
  }
}

/** POST /api/auth/login with the name and password typed on the TV. */
export async function passwordSignIn(api: ApiClient, client: ClientInfo, name: string, password: string, signal?: AbortSignal): Promise<Grant> {
  const reply = unwrap(await api.POST('/api/auth/login', { body: { name, password, client }, signal }))
  if (!reply.token) throw new Error('no token')
  return { token: reply.token, sessionId: reply.sessionId, user: { id: reply.id, name: reply.name, isAdmin: reply.isAdmin } }
}

/** Ends a session with its own token, best effort and briefly: the TV moves on either way. */
export async function revoke(api: ApiClient, token: string): Promise<boolean> {
  try {
    const reply = await api.POST('/api/auth/logout', { headers: { Authorization: `Bearer ${token}` }, signal: timeoutSignal(5000) })
    return reply.response.ok
  } catch { return false }
}

function timeoutSignal(ms: number) {
  const controller = new AbortController()
  setTimeout(() => controller.abort(), ms)
  return controller.signal
}
