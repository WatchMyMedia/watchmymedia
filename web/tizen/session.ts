import type { ClientInfo, PairingStart, SavedSession, ServerInfo } from './types'
import { result, serverUrl, timed, tvClient, UserError, type TVClient } from './server'

const SESSION_KEY = 'watchmymedia.tizen.prototype.session'
const DEVICE_KEY = 'watchmymedia.tizen.prototype.device'

export function deviceInfo(storage: Storage, random: Crypto = crypto): ClientInfo {
  let deviceId = storage.getItem(DEVICE_KEY)
  if (!deviceId) {
    // randomUUID requires a secure context; getRandomValues also works on LAN HTTP.
    deviceId = Array.from(random.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join('')
    storage.setItem(DEVICE_KEY, deviceId)
  }
  return { deviceId, name: 'Samsung TV prototype', product: 'WatchMyMedia for Samsung TV', platform: 'tizen', version: '0.0.1' }
}

export function readSession(storage: Storage): SavedSession | undefined {
  try {
    const s = JSON.parse(storage.getItem(SESSION_KEY) ?? 'null') as SavedSession | null
    if (s && typeof s.token === 'string' && s.token && typeof s.serverId === 'string' && typeof s.sessionId === 'string' && typeof s.serverName === 'string') {
      return { ...s, baseUrl: serverUrl(s.baseUrl) }
    }
  } catch { /* Invalid/inaccessible app-private storage is a signed-out state. */ }
  return undefined
}

export function saveSession(storage: Storage, session: SavedSession) { storage.setItem(SESSION_KEY, JSON.stringify(session)) }
export function clearSession(storage: Storage) { storage.removeItem(SESSION_KEY) }

export async function inspectServer(baseUrl: string, signal?: AbortSignal): Promise<ServerInfo> {
  const info = result(await timed((s) => tvClient(baseUrl).GET('/api/server', { signal: s }), signal))
  if (info.setupRequired) throw new UserError('Finish setting up this server in its web app first.')
  if (info.apiLevel < 1) throw new UserError('Update the server to a version that supports TV pairing.')
  return info
}

export async function restoreSession(saved: SavedSession, signal?: AbortSignal) {
  const server = await inspectServer(saved.baseUrl, signal)
  if (server.id !== saved.serverId) throw new UserError('This address now belongs to a different server. Link the TV again.')
  const api = tvClient(saved.baseUrl, saved.token)
  result(await timed((s) => api.GET('/api/auth/me', { signal: s }), signal))
  return { server, api }
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Cancelled', 'AbortError')) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) abort()
  })
}

export async function pair(
  baseUrl: string, info: ClientInfo, signal: AbortSignal,
  showCode: (pairing: Pick<PairingStart, 'code' | 'linkPath' | 'expiresAt'>) => void,
): Promise<{ saved: SavedSession; server: ServerInfo; api: TVClient }> {
  const base = serverUrl(baseUrl)
  const server = await inspectServer(base, signal)
  const api = tvClient(base)
  const pending = result(await timed((s) => api.POST('/api/auth/pair', { body: { client: info }, signal: s }), signal))
  showCode({ code: pending.code, linkPath: pending.linkPath, expiresAt: pending.expiresAt })
  while (!signal.aborted && Date.now() < Date.parse(pending.expiresAt)) {
    await delay(Math.max(1000, pending.intervalMs), signal)
    // A lost claim response may have consumed the one-time token. Never silently
    // retry that ambiguity forever; the UI offers a fresh code after any failure.
    const claim = result(await timed((s) => api.POST('/api/auth/pair/claim', { body: { secret: pending.secret }, signal: s }), signal))
    if (claim.status === 'approved') {
      if (!claim.token || !claim.sessionId) throw new UserError('The approval was incomplete. Get a new code.')
      const saved = { baseUrl: base, serverId: server.id, serverName: server.name, token: claim.token, sessionId: claim.sessionId }
      if (signal.aborted) {
        // Approval raced with cancellation; do not leave a newly issued session alive.
        await timed((s) => tvClient(base, claim.token).POST('/api/auth/logout', { signal: s })).catch(() => undefined)
        throw new DOMException('Cancelled', 'AbortError')
      }
      return { saved, server, api: tvClient(base, claim.token) }
    }
  }
  throw new UserError('The code expired. Get a new code to continue.')
}
