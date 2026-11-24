import type { ApiClient } from '../../src/api/client'
import type { ActiveSession } from './active'
import { from34 } from './features'
import type { MediaUrls } from './media'
import { PeerServer } from './peers'
import type { Account, RememberedServer } from './servers'
import { tooOldForApp, type AppSession } from './session'

// The other servers a signed-in session asks, kept for the session's life:
// one PeerServer each, so a server is asked which one it is once however
// many screens use it (search, Home, the libraries, a title's copies, a
// play), and its media token is fetched once. Made again for a server whose
// sign-in changed; stopped when the session ends.

type Signed = RememberedServer & { account: Account }

export class Peers {
  private made = new Map<string, { token: string; peer: PeerServer }>()
  private stopped = false

  constructor(private app: AppSession, private currentId: string) {}

  /** The other servers this TV is signed in to and can use, in Servers' order (the most recently used first). */
  list(): PeerServer[] {
    if (this.stopped) return []
    const signed = this.app.store.list().filter((s): s is Signed => s.id !== this.currentId && !!s.account && !tooOldForApp(s))
    for (const [id, m] of this.made) {
      const now = signed.find((s) => s.id === id)
      if (!now || now.account.token !== m.token) { m.peer.stop(); this.made.delete(id) }
    }
    return signed.map((s) => {
      let m = this.made.get(s.id)
      if (!m) {
        const peer = new PeerServer(s, {
          onSignedOut: () => this.app.signedOutOf(s.id),
          onInfo: (info) => this.app.store.note(s.id, { name: info.name, version: info.version, apiLevel: info.apiLevel }),
        })
        this.made.set(s.id, m = { token: s.account.token, peer })
      }
      return m.peer
    })
  }

  get(id: string): PeerServer | undefined {
    return this.list().find((p) => p.id === id)
  }

  stop() {
    this.stopped = true
    for (const m of this.made.values()) m.peer.stop()
    this.made.clear()
  }
}

const registries = new WeakMap<ActiveSession, Peers>()

/** The session's other servers, made on first use and stopped when the session ends. */
export function peersOf(app: AppSession, session: ActiveSession): Peers {
  let peers = registries.get(session)
  if (!peers) {
    const made = new Peers(app, session.server.id)
    registries.set(session, peers = made)
    session.onDetach(() => made.stop())
  }
  return peers
}

/**
 * Where a title's page, a person's titles or a play come from: the current
 * server (no `peer`), or another one this TV is signed in to, asked through
 * its own client. Both answer the same API; only the address, the sign-in
 * and the pictures' token differ.
 */
export type Source = {
  /** The server's id. */
  id: string
  name: string
  /** Another server; none for the current one. */
  peer?: PeerServer
  api: ApiClient
  media: MediaUrls
  /** Beyond this network: its copies may be converted to keep up. */
  away: boolean
  apiLevel?: number
}

export function currentSource(session: ActiveSession): Source {
  return { id: session.server.id, name: session.server.name, api: session.api, media: session.media, away: false, apiLevel: session.server.apiLevel }
}

export function peerSource(peer: PeerServer): Source {
  return { id: peer.id, name: peer.name, peer, api: peer.api, media: peer.media, away: peer.away, apiLevel: peer.apiLevel }
}

/** Whether a server's level answers one card across servers (its copies by TMDB id, the TMDB ids on its cards). */
export function oneCardLevel(apiLevel: number | undefined): boolean {
  return from34(apiLevel)
}

/**
 * Asks another server, but only once it has said which server it is and
 * that it answers level 34: undefined (left out, without a word) when it is
 * older.
 */
export async function askAt34<T>(peer: PeerServer, signal: AbortSignal, ask: (api: ApiClient, signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
  await peer.verify(signal)
  if (!oneCardLevel(peer.apiLevel)) return undefined
  return ask(peer.api, signal)
}
