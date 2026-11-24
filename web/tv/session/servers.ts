import { platform } from '../platform/current'
import { originOf } from './address'

/** The account this TV is signed in with on a server. The token never leaves this store but for the bearer header. */
export type Account = {
  userId: number
  name: string
  isAdmin: boolean
  token: string
  sessionId: string
}

/** A server this TV has connected to, remembered by its ID (GET /api/server), not its address. */
export type RememberedServer = {
  id: string
  name: string
  baseUrl: string
  version?: string
  /** The API level it last reported, so a server too old for this app says so before it is asked again. */
  apiLevel?: number
  account?: Account
  lastUsedAt: number
}

const KEY = platform.storage.servers
/** How many servers this TV keeps. */
export const MAX_SERVERS = 12

function str(v: unknown): v is string { return typeof v === 'string' && v.length > 0 }

function readAccount(v: unknown): Account | undefined {
  if (!v || typeof v !== 'object') return undefined
  const a = v as Record<string, unknown>
  if (typeof a.userId !== 'number' || !str(a.name) || !str(a.token) || !str(a.sessionId)) return undefined
  return { userId: a.userId, name: a.name, isAdmin: a.isAdmin === true, token: a.token, sessionId: a.sessionId }
}

function readServer(v: unknown): RememberedServer | undefined {
  if (!v || typeof v !== 'object') return undefined
  const s = v as Record<string, unknown>
  const baseUrl = str(s.baseUrl) ? originOf(s.baseUrl) : undefined
  if (!str(s.id) || !str(s.name) || !baseUrl) return undefined
  return {
    id: s.id, name: s.name, baseUrl,
    ...(str(s.version) ? { version: s.version } : {}),
    ...(typeof s.apiLevel === 'number' ? { apiLevel: s.apiLevel } : {}),
    ...(readAccount(s.account) ? { account: readAccount(s.account) } : {}),
    lastUsedAt: typeof s.lastUsedAt === 'number' ? s.lastUsedAt : 0,
  }
}

/**
 * The servers this TV remembers, newest first, in app storage. Unreadable
 * or refused storage reads as no servers and keeps changes for this run.
 */
export class ServerStore {
  private memory?: RememberedServer[]

  constructor(private storage: Storage | undefined) {}

  list(): RememberedServer[] {
    if (this.memory) return this.memory
    let items: RememberedServer[] = []
    try {
      const parsed: unknown = JSON.parse(this.storage?.getItem(KEY) ?? '[]')
      if (Array.isArray(parsed)) items = parsed.map(readServer).filter((s): s is RememberedServer => !!s)
    } catch { /* A broken or refused store is an empty one. */ }
    this.memory = items.sort((a, b) => b.lastUsedAt - a.lastUsedAt)
    return this.memory
  }

  get(id: string) { return this.list().find((s) => s.id === id) }

  /** The server used last: the one to restore on launch. */
  last() { return this.list()[0] }

  /** Adds or updates a server by ID and makes it the newest. */
  remember(server: Omit<RememberedServer, 'lastUsedAt' | 'account'> & { account?: Account | null }, now = Date.now()) {
    const old = this.get(server.id)
    const account = server.account === null ? undefined : server.account ?? old?.account
    const apiLevel = server.apiLevel ?? old?.apiLevel
    const next: RememberedServer = { id: server.id, name: server.name, baseUrl: server.baseUrl, ...(server.version ? { version: server.version } : {}),
      ...(apiLevel !== undefined ? { apiLevel } : {}), ...(account ? { account } : {}), lastUsedAt: now }
    this.write([next, ...this.list().filter((s) => s.id !== server.id)].slice(0, MAX_SERVERS))
    return next
  }

  /** What a server said of itself when it was asked without connecting to it (a search): its name, version and level, in place. */
  note(id: string, facts: Pick<RememberedServer, 'name' | 'version' | 'apiLevel'>) {
    this.write(this.list().map((s) => s.id === id ? { ...s, name: facts.name, ...(facts.version ? { version: facts.version } : {}), ...(facts.apiLevel !== undefined ? { apiLevel: facts.apiLevel } : {}) } : s))
  }

  /** Whether another server can be added. */
  get full() { return this.list().length >= MAX_SERVERS }

  /** Forgets the account's token (signed out), keeping the server. */
  signOut(id: string) {
    this.write(this.list().map((s) => s.id === id ? { ...s, account: undefined } : s))
  }

  remove(id: string) {
    this.write(this.list().filter((s) => s.id !== id))
  }

  private write(items: RememberedServer[]) {
    this.memory = items.sort((a, b) => b.lastUsedAt - a.lastUsedAt)
    try { this.storage?.setItem(KEY, JSON.stringify(this.memory)) } catch { /* Kept for this run only. */ }
  }
}
