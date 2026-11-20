import { ApiError, unwrap } from '../../src/api/client'
import { addressCandidates, AddressError } from './address'
import { bearerClient } from './client'
import { clientInfo, type ClientInfo } from './device'
import { pairOnce, passwordSignIn, revoke, type Grant, type PairingCode } from './pairing'
import { ServerStore, type RememberedServer } from './servers'
import { ActiveSession, type ServerInfo } from './active'

/**
 * The API level this app needs: the level at which packaged TV apps may
 * pair and send bearer requests cross-origin. Servers below it get the
 * "too old" problem; there are no branches for older levels.
 */
export const REQUIRED_API_LEVEL = 32
/** The release that has it, as the screens word it. */
export const MIN_SERVER_VERSION = '1.8'

/** The server a sign-in or a problem is about. */
export type Target = { id?: string; name: string; baseUrl: string; version?: string }

export type StartProblem =
  /** Nothing answered at the address. */
  | 'unreachable'
  /** No account exists yet: finish setup in a browser. */
  | 'notSetUp'
  /** Below REQUIRED_API_LEVEL. */
  | 'tooOld'
  /** A 401 for a remembered account: signed out from another device. */
  | 'signedOut'
  /** Another server answers at a remembered address. */
  | 'moved'

export type PairingView =
  | { state: 'starting' }
  | { state: 'waiting'; code: PairingCode }
  | { state: 'approved' }
  /** Stopped while the TV was away, or by hand: a new code is one press away. */
  | { state: 'expired' }
  | { state: 'failed'; error: unknown }

export type StartState =
  | { screen: 'booting'; server?: Target }
  | { screen: 'servers'; servers: RememberedServer[]; address: string; busy?: 'address' | string; error?: unknown; notice?: 'signedOut' }
  | { screen: 'signIn'; server: Target & { id: string }; info: ServerInfo; mode: 'code' | 'password'; pairing: PairingView; password: { busy: boolean; error?: unknown } }
  | { screen: 'problem'; problem: StartProblem; server: Target; found?: ServerInfo }
  | { screen: 'signedIn'; session: ActiveSession }

/** The problem a server's own answer gives, or none. */
export function serverProblem(info: Pick<ServerInfo, 'apiLevel' | 'setupRequired'>): StartProblem | undefined {
  if (info.setupRequired) return 'notSetUp'
  if (info.apiLevel < REQUIRED_API_LEVEL) return 'tooOld'
  return undefined
}

function cancelled(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

const PROBE_MS = 10_000

/**
 * Everything before and around a signed-in session, outside React: the
 * remembered servers, connecting to one, signing in by code or password,
 * restoring the last sign-in on launch, signing out, switching server and
 * losing a session to a 401. The start screens draw `snapshot()`; the
 * signed-in shell draws its `session`. Each flow bumps a generation, so a
 * reply from a flow that was left behind changes nothing (and a code
 * approved after it was abandoned is signed out again).
 */
export class AppSession {
  private state: StartState = { screen: 'booting' }
  private listeners = new Set<() => void>()
  private generation = 0
  private flow?: AbortController
  readonly store: ServerStore
  readonly client: ClientInfo

  constructor(storage: Storage | undefined, options: { model?: string; initialAddress?: string } = {}) {
    this.store = new ServerStore(storage)
    this.client = clientInfo(storage, options.model)
    this.initialAddress = options.initialAddress ?? ''
  }

  private initialAddress: string

  readonly snapshot = () => this.state
  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }

  private set(state: StartState) {
    const old = this.state
    if (old.screen === 'signedIn' && (state.screen !== 'signedIn' || state.session !== old.session)) old.session.detach()
    this.state = state
    for (const l of this.listeners) l()
  }

  private begin() {
    this.flow?.abort()
    const flow = new AbortController()
    this.flow = flow
    return { gen: ++this.generation, signal: flow.signal }
  }

  private current(gen: number) { return gen === this.generation }

  /** On launch: the last server's sign-in if there is one, else the server screen. */
  start() {
    const last = this.store.last()
    if (last?.account) void this.choose(last.id)
    else this.showServers()
  }

  showServers(notice?: 'signedOut') {
    this.begin()
    const address = this.state.screen === 'servers' ? this.state.address : this.initialAddress
    this.set({ screen: 'servers', servers: this.store.list(), address, ...(notice ? { notice } : {}) })
  }

  editAddress(address: string) {
    if (this.state.screen === 'servers') this.set({ ...this.state, address, error: undefined })
  }

  private async probe(baseUrl: string, signal: AbortSignal): Promise<ServerInfo> {
    const api = bearerClient(baseUrl, { timeoutMs: PROBE_MS })
    return unwrap(await api.GET('/api/server', { signal }))
  }

  /** Connects to the address typed on the server screen. */
  async connect() {
    if (this.state.screen !== 'servers') return
    const typed = this.state.address
    let candidates: string[]
    try { candidates = addressCandidates(typed) } catch (error) {
      this.set({ ...this.state, error, busy: undefined })
      return
    }
    const { gen, signal } = this.begin()
    this.set({ ...this.state, busy: 'address', error: undefined, notice: undefined })
    let lastError: unknown
    for (const baseUrl of candidates) {
      try {
        const info = await this.probe(baseUrl, signal)
        if (!this.current(gen)) return
        await this.open(info, baseUrl, gen, signal)
        return
      } catch (error) {
        if (!this.current(gen) || cancelled(error) && signal.aborted) return
        lastError = error
      }
    }
    if (this.current(gen) && this.state.screen === 'servers') this.set({ ...this.state, busy: undefined, error: lastError })
  }

  /** Connects to a remembered server: its sign-in if it has one, else the sign-in screen. */
  async choose(id: string) {
    const remembered = this.store.get(id)
    if (!remembered) return
    const { gen, signal } = this.begin()
    const target: Target = { id, name: remembered.name, baseUrl: remembered.baseUrl, version: remembered.version }
    if (this.state.screen === 'servers') this.set({ ...this.state, busy: id, error: undefined, notice: undefined })
    else this.set({ screen: 'booting', server: target })
    let info: ServerInfo
    try { info = await this.probe(remembered.baseUrl, signal) } catch (error) {
      if (this.current(gen) && !(cancelled(error) && signal.aborted)) this.set({ screen: 'problem', problem: 'unreachable', server: target })
      return
    }
    if (!this.current(gen)) return
    if (info.id !== id) { this.set({ screen: 'problem', problem: 'moved', server: target, found: info }); return }
    await this.open(info, remembered.baseUrl, gen, signal)
  }

  /** A server answered: its problem, its remembered sign-in, or the sign-in screen. */
  private async open(info: ServerInfo, baseUrl: string, gen: number, signal: AbortSignal) {
    const target = { id: info.id, name: info.name, baseUrl, version: info.version }
    const problem = serverProblem(info)
    if (problem) { this.set({ screen: 'problem', problem, server: target }); return }
    const account = this.store.get(info.id)?.account
    this.store.remember({ id: info.id, name: info.name, baseUrl, version: info.version })
    if (!account) { this.showSignIn(info, baseUrl); return }
    try {
      const api = bearerClient(baseUrl, { token: account.token, timeoutMs: PROBE_MS })
      const me = unwrap(await api.GET('/api/auth/me', { signal }))
      if (!this.current(gen)) return
      const updated = { ...account, name: me.name, isAdmin: me.isAdmin }
      this.store.remember({ id: info.id, name: info.name, baseUrl, version: info.version, account: updated })
      await this.attach(new ActiveSession(info, baseUrl, updated, this.client, () => this.expire()), gen)
    } catch (error) {
      if (!this.current(gen) || cancelled(error) && signal.aborted) return
      if (error instanceof ApiError && error.status === 401) {
        this.store.signOut(info.id)
        this.set({ screen: 'problem', problem: 'signedOut', server: target })
      } else this.set({ screen: 'problem', problem: 'unreachable', server: target })
    }
  }

  private async attach(session: ActiveSession, gen: number) {
    await session.attach()
    if (!this.current(gen)) { session.detach(); return }
    this.set({ screen: 'signedIn', session })
  }

  private showSignIn(info: ServerInfo, baseUrl: string) {
    this.set({ screen: 'signIn', server: { id: info.id, name: info.name, baseUrl, version: info.version }, info,
      mode: 'code', pairing: { state: 'starting' }, password: { busy: false } })
    void this.pair()
  }

  /** Shows codes one after another, as each runs out, until one is approved or the screen is left. */
  private async pair() {
    if (this.state.screen !== 'signIn') return
    const { gen, signal } = this.begin()
    const { server } = this.state
    const api = bearerClient(server.baseUrl)
    const update = (pairing: PairingView) => {
      if (this.current(gen) && this.state.screen === 'signIn') this.set({ ...this.state, pairing })
    }
    try {
      for (;;) {
        update({ state: 'starting' })
        const result = await pairOnce(api, server.baseUrl, this.client, signal, (code) => update({ state: 'waiting', code }))
        if (!this.current(gen)) { if (result !== 'expired') void revoke(api, result.token); return }
        if (result === 'expired') continue
        update({ state: 'approved' })
        await this.signedIn(result, gen)
        return
      }
    } catch (error) {
      if (this.current(gen) && !cancelled(error)) update({ state: 'failed', error })
    }
  }

  private async signedIn(grant: Grant, gen: number) {
    if (this.state.screen !== 'signIn') return
    const { server, info } = this.state
    const account = { userId: grant.user.id, name: grant.user.name, isAdmin: grant.user.isAdmin, token: grant.token, sessionId: grant.sessionId }
    this.store.remember({ id: server.id, name: server.name, baseUrl: server.baseUrl, version: server.version, account })
    await this.attach(new ActiveSession(info, server.baseUrl, account, this.client, () => this.expire()), gen)
  }

  /** A fresh code, after one failed or the TV was away. */
  newCode() {
    if (this.state.screen === 'signIn' && this.state.mode === 'code') void this.pair()
  }

  /** "Sign in with a password instead": the form, and no code polling meanwhile. */
  usePassword() {
    if (this.state.screen !== 'signIn' || this.state.info.turnstile) return
    this.begin()
    this.set({ ...this.state, mode: 'password', pairing: { state: 'expired' }, password: { busy: false } })
  }

  useCode() {
    if (this.state.screen !== 'signIn') return
    this.set({ ...this.state, mode: 'code', password: { busy: false } })
    void this.pair()
  }

  async signInWithPassword(name: string, password: string) {
    if (this.state.screen !== 'signIn' || this.state.mode !== 'password' || this.state.password.busy) return
    const { gen, signal } = this.begin()
    const { server } = this.state
    this.set({ ...this.state, password: { busy: true } })
    try {
      const grant = await passwordSignIn(bearerClient(server.baseUrl), this.client, name.trim(), password, signal)
      if (!this.current(gen)) { void revoke(bearerClient(server.baseUrl), grant.token); return }
      await this.signedIn(grant, gen)
    } catch (error) {
      if (this.current(gen) && this.state.screen === 'signIn') this.set({ ...this.state, password: { busy: false, error } })
    }
  }

  /** Return or Cancel on a start screen: stop what is under way and go back to the servers. */
  cancel() { this.showServers() }

  /** Try again from a problem: the same server, from the top. */
  retry() {
    if (this.state.screen !== 'problem') return
    const { server, problem, found } = this.state
    if (problem === 'moved' && found) { this.store.remember({ id: found.id, name: found.name, baseUrl: server.baseUrl, version: found.version }); void this.choose(found.id); return }
    // A remembered server from the top: signed out, it lands on the sign-in screen.
    if (server.id && this.store.get(server.id)) { void this.choose(server.id); return }
    this.set({ screen: 'servers', servers: this.store.list(), address: server.baseUrl })
    void this.connect()
  }

  /** Forgets a server, signing its account out on the server when it can, in the background. */
  remove(id: string) {
    const s = this.store.get(id)
    if (!s) return
    // Connecting to it right now: stop, so it isn't remembered again when it answers.
    if (this.state.screen === 'servers' && this.state.busy === id) this.begin()
    if (s.account) void revoke(bearerClient(s.baseUrl), s.account.token)
    this.store.remove(id)
    if (this.state.screen === 'servers') this.set({ ...this.state, servers: this.store.list(), busy: undefined })
  }

  /** Settings › Sign out: ends the session on the server too, then shows a code to link this TV again. */
  signOut() {
    if (this.state.screen !== 'signedIn') return
    const { session } = this.state
    void revoke(session.api, session.account.token)
    this.store.signOut(session.server.id)
    this.begin()
    this.showSignIn(session.server, session.baseUrl)
  }

  /** Settings › Switch server: keeps this sign-in for later and shows the servers. */
  switchServer() {
    if (this.state.screen !== 'signedIn') return
    this.begin()
    this.set({ screen: 'servers', servers: this.store.list(), address: '' })
  }

  /** A 401 anywhere: the session was ended on another device. */
  expire() {
    if (this.state.screen !== 'signedIn') return
    const { session } = this.state
    this.store.signOut(session.server.id)
    this.begin()
    this.set({ screen: 'problem', problem: 'signedOut', server: { id: session.server.id, name: session.server.name, baseUrl: session.baseUrl, version: session.server.version } })
  }

  /** The app went to the background: codes stop (one press gets a new one), the stream closes. */
  suspend() {
    if (this.state.screen === 'signIn' && this.state.mode === 'code') {
      this.begin()
      this.set({ ...this.state, pairing: { state: 'expired' } })
    } else if (this.state.screen === 'signedIn') this.state.session.suspend()
  }

  resume() {
    if (this.state.screen === 'signedIn') this.state.session.resume()
  }

  /** Leaving the app: stop everything, keep the sign-in for next time. */
  shutdown() {
    this.begin()
    if (this.state.screen === 'signedIn') this.state.session.detach()
  }
}

/** Whether an error came from what was typed rather than the network. */
export function isAddressError(error: unknown) { return error instanceof AddressError }
