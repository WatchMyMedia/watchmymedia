import { ApiError, unwrap } from '../../src/api/client'
import { addressCandidates, AddressError } from './address'
import { bearerClient } from './client'
import { clientInfo, type ClientInfo } from './device'
import { pairOnce, passwordSignIn, revoke, type Grant, type PairingCode } from './pairing'
import { ServerStore, type Account, type RememberedServer } from './servers'
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

/** Why a switch from the signed-in app didn't happen. The app stays on the server it was on. */
export type SwitchProblem = 'unreachable' | 'moved' | 'tooOld' | 'notSetUp'

/** A title on the server being switched to: a search result's. */
export type OpenTarget = { kind: 'movie'; id: number } | { kind: 'show'; id: number; season?: number; episodeId?: number }

/** What a switch opens once it is there: Home, then Search with these words, then the title. */
export type OpenAfter = { text: string; target: OpenTarget }

/** A switch under way from the signed-in app, or the one that just failed, as Servers draws it. */
export type SwitchView =
  | { state: 'switching'; id: string; name: string }
  | { state: 'failed'; id: string; name: string; problem: SwitchProblem; open?: OpenAfter }

/** How the app came to a session from another one: what its first screen says and opens. */
export type Arrival = {
  /** 'switched' shows "Now on …" for a moment; 'added' says the server was added, and what that changes. */
  how: 'switched' | 'added'
  open?: OpenAfter
}

/** Add a server: the servers already on this TV, which its screen names instead of offering. */
export type Adding = { registered: string[] }

export type StartState =
  | { screen: 'booting'; server?: Target }
  | { screen: 'servers'; servers: RememberedServer[]; address: string; busy?: 'address' | string; error?: unknown; notice?: 'signedOut'; adding?: Adding }
  | { screen: 'signIn'; server: Target & { id: string }; info: ServerInfo; mode: 'code' | 'password'; pairing: PairingView; password: { busy: boolean; error?: unknown } }
  | { screen: 'problem'; problem: StartProblem; server: Target; found?: ServerInfo }
  | { screen: 'signedIn'; session: ActiveSession; arrival?: Arrival; switching?: SwitchView }

/** The problem a server's own answer gives, or none. */
export function serverProblem(info: Pick<ServerInfo, 'apiLevel' | 'setupRequired'>): Extract<StartProblem, 'notSetUp' | 'tooOld'> | undefined {
  if (info.setupRequired) return 'notSetUp'
  if (info.apiLevel < REQUIRED_API_LEVEL) return 'tooOld'
  return undefined
}

function cancelled(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

const PROBE_MS = 10_000
/** A switch from the signed-in app asks the other server for this long at most. */
export const SWITCH_MS = 5000

/** The session to come back to when a flow started from the signed-in app is left: Add a server, or a sign-in on another server. */
type Back = { session: ActiveSession; adding: boolean; open?: OpenAfter }

/**
 * Everything before and around a signed-in session, outside React: the
 * remembered servers, connecting to one, signing in by code or password,
 * restoring the last sign-in on launch, signing out, switching straight to
 * another server, adding and removing one, and losing a session to a 401.
 * The start screens draw `snapshot()`; the signed-in shell draws its
 * `session`. Each flow bumps a generation, so a reply from a flow that was
 * left behind changes nothing (and a code approved after it was abandoned
 * is signed out again).
 *
 * A switch, Add a server and a sign-in on another server all start from the
 * signed-in app and leave its session as it was until another one takes
 * over: whatever fails or is cancelled, the app is still on its server.
 */
export class AppSession {
  private state: StartState = { screen: 'booting' }
  private listeners = new Set<() => void>()
  private generation = 0
  private flow?: AbortController
  private back?: Back
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
    const next = state.screen === 'signedIn' ? state.session : undefined
    // The session a flow may come back to stays alive under the start screens.
    if (old.screen === 'signedIn' && old.session !== next && old.session !== this.back?.session) old.session.detach()
    if (next && this.back) {
      // Another session took over, or the app came back to the one it had.
      if (this.back.session !== next) this.back.session.detach()
      this.back = undefined
    }
    this.state = state
    for (const l of this.listeners) l()
  }

  /** A session whose 401 ends that session only, whichever one the app is on by then. */
  private session(info: ServerInfo, baseUrl: string, account: Account): ActiveSession {
    const session: ActiveSession = new ActiveSession(info, baseUrl, account, this.client, () => this.expire(session))
    return session
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
    // From Add a server's own screens it is that screen again; from a sign-in on another server, the app.
    if (this.back) { if (this.back.adding) this.showAdd(); else this.comeBack(); return }
    this.begin()
    const address = this.state.screen === 'servers' ? this.state.address : this.initialAddress
    this.set({ screen: 'servers', servers: this.store.list(), address, ...(notice ? { notice } : {}) })
  }

  /** The server screen for an address: every remembered server, or none of them while adding one. */
  private serversAt(address: string): Extract<StartState, { screen: 'servers' }> {
    return this.back?.adding
      ? { screen: 'servers', servers: [], address, adding: { registered: this.store.list().map((s) => s.name) } }
      : { screen: 'servers', servers: this.store.list(), address }
  }

  private showAdd() {
    this.begin()
    this.set(this.serversAt(this.state.screen === 'servers' ? this.state.address : ''))
  }

  /** Back in the app, on the session a flow left alive. */
  private comeBack() {
    const back = this.back
    if (!back) return
    this.begin()
    this.set({ screen: 'signedIn', session: back.session })
  }

  /**
   * Servers › Add a server: the server screen without the servers already
   * on this TV, which it names. The session stays as it is underneath;
   * Return comes back to it, and so does anything that fails.
   */
  addServer() {
    if (this.state.screen !== 'signedIn' || this.store.full) return
    this.back = { session: this.state.session, adding: true }
    this.showAdd()
  }

  editAddress(address: string) {
    if (this.state.screen === 'servers') this.set({ ...this.state, address, error: undefined })
  }

  private async probe(baseUrl: string, signal: AbortSignal, timeoutMs = PROBE_MS): Promise<ServerInfo> {
    const api = bearerClient(baseUrl, { timeoutMs })
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
  async choose(id: string, arrival?: Arrival) {
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
    await this.open(info, remembered.baseUrl, gen, signal, arrival)
  }

  /** A server answered: its problem, its remembered sign-in, or the sign-in screen. */
  private async open(info: ServerInfo, baseUrl: string, gen: number, signal: AbortSignal, arrival?: Arrival) {
    const target = { id: info.id, name: info.name, baseUrl, version: info.version }
    const problem = serverProblem(info)
    if (problem) { this.set({ screen: 'problem', problem, server: target }); return }
    // Add a server, with the address of the server the app is on: it is there already.
    if (this.back && info.id === this.back.session.server.id) { this.comeBack(); return }
    const known = this.store.get(info.id)
    const account = known?.account
    const facts = { id: info.id, name: info.name, baseUrl, version: info.version, apiLevel: info.apiLevel }
    // A server being added is remembered once it is signed in to, so leaving its sign-in leaves nothing behind.
    if (known || !this.back) this.store.remember(facts)
    if (!account) { this.showSignIn(info, baseUrl); return }
    try {
      const api = bearerClient(baseUrl, { token: account.token, timeoutMs: PROBE_MS })
      const me = unwrap(await api.GET('/api/auth/me', { signal }))
      if (!this.current(gen)) return
      const updated = { ...account, name: me.name, isAdmin: me.isAdmin }
      this.store.remember({ ...facts, account: updated })
      // Typed into Add a server, a server this TV already has is a switch to it.
      await this.attach(this.session(info, baseUrl, updated), gen, arrival ?? (this.back ? { how: 'switched' } : undefined))
    } catch (error) {
      if (!this.current(gen) || cancelled(error) && signal.aborted) return
      if (error instanceof ApiError && error.status === 401) {
        this.store.signOut(info.id)
        this.set({ screen: 'problem', problem: 'signedOut', server: target })
      } else this.set({ screen: 'problem', problem: 'unreachable', server: target })
    }
  }

  /** The session takes over once it is ready; until then the one in use, if any, is untouched. */
  private async attach(session: ActiveSession, gen: number, arrival?: Arrival) {
    await session.ready()
    if (!this.current(gen)) { session.detach(); return }
    session.attach()
    this.set({ screen: 'signedIn', session, ...(arrival ? { arrival } : {}) })
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
    // From the signed-in app: a server this TV didn't have was added; one it had (signed out here) was switched to.
    const arrival: Arrival | undefined = this.back
      ? { how: this.store.get(server.id) ? 'switched' : 'added', ...(this.back.open ? { open: this.back.open } : {}) }
      : undefined
    this.store.remember({ id: server.id, name: server.name, baseUrl: server.baseUrl, version: server.version, apiLevel: info.apiLevel, account })
    await this.attach(this.session(info, server.baseUrl, account), gen, arrival)
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

  /**
   * Return or Cancel on a start screen: stop what is under way and go back
   * to the servers. In a flow started from the signed-in app that is one
   * step back: from a sign-in or a connection under way to Add a server's
   * screen, and from there (or from a sign-in on a server this TV has) to
   * the app, on the server it was on.
   */
  cancel() {
    const back = this.back
    if (!back) { this.showServers(); return }
    const idle = this.state.screen === 'servers' && !this.state.busy
    if (back.adding && !idle) this.showAdd()
    else this.comeBack()
  }

  /** Try again from a problem: the same server, from the top. */
  retry() {
    if (this.state.screen !== 'problem') return
    const { server, problem, found } = this.state
    if (problem === 'moved' && found) { this.store.remember({ id: found.id, name: found.name, baseUrl: server.baseUrl, version: found.version }); void this.choose(found.id); return }
    // A remembered server from the top: signed out, it lands on the sign-in screen.
    if (server.id && this.store.get(server.id)) { void this.choose(server.id); return }
    this.set(this.serversAt(server.baseUrl))
    void this.connect()
  }

  /**
   * Straight to a remembered server, from Servers or from a search result
   * on it. It is asked which server it is (`info` when it just answered a
   * search here), its kept sign-in is checked, and the app opens on it with
   * "Now on …". Nothing changes until then: a server that doesn't answer,
   * or isn't the one remembered, leaves the app where it was and says so
   * (`switching` in the snapshot, with Try again). One signed out here
   * opens its sign-in, and Cancel there comes back.
   */
  async switchTo(id: string, options: { info?: ServerInfo; open?: OpenAfter } = {}) {
    if (this.state.screen !== 'signedIn') return
    const from = this.state.session
    const remembered = this.store.get(id)
    if (!remembered || id === from.server.id) return
    const { gen, signal } = this.begin()
    const live = () => this.current(gen) && this.state.screen === 'signedIn' && this.state.session === from
    const fail = (problem: SwitchProblem) => {
      if (live()) this.showSwitch(from, { state: 'failed', id, name: remembered.name, problem, ...(options.open ? { open: options.open } : {}) })
    }
    this.showSwitch(from, { state: 'switching', id, name: remembered.name })
    let info = options.info
    if (!info) {
      try { info = await this.probe(remembered.baseUrl, signal, SWITCH_MS) } catch (error) {
        if (!(cancelled(error) && signal.aborted)) fail('unreachable')
        return
      }
      if (!live()) return
    }
    if (info.id !== id) { fail('moved'); return }
    const facts = { id, name: info.name, baseUrl: remembered.baseUrl, version: info.version, apiLevel: info.apiLevel }
    const problem = serverProblem(info)
    if (problem) {
      this.store.note(id, facts)
      fail(problem)
      return
    }
    const signIn = () => {
      this.back = { session: from, adding: false, ...(options.open ? { open: options.open } : {}) }
      this.showSignIn(info, remembered.baseUrl)
    }
    const account = remembered.account
    if (!account) { signIn(); return }
    try {
      const api = bearerClient(remembered.baseUrl, { token: account.token, timeoutMs: SWITCH_MS })
      const me = unwrap(await api.GET('/api/auth/me', { signal }))
      if (!live()) return
      const updated = { ...account, name: me.name, isAdmin: me.isAdmin }
      const session = this.session(info, remembered.baseUrl, updated)
      await session.ready()
      if (!live()) { session.detach(); return }
      this.store.remember({ ...facts, account: updated })
      session.attach()
      this.set({ screen: 'signedIn', session, arrival: { how: 'switched', ...(options.open ? { open: options.open } : {}) } })
    } catch (error) {
      if (!live() || cancelled(error) && signal.aborted) return
      if (error instanceof ApiError && error.status === 401) {
        this.store.signOut(id)
        signIn()
      } else fail('unreachable')
    }
  }

  /** Try again, on a switch that failed. */
  retrySwitch() {
    if (this.state.screen !== 'signedIn' || this.state.switching?.state !== 'failed') return
    const { id, open } = this.state.switching
    void this.switchTo(id, open ? { open } : {})
  }

  /** Leaves a switch under way, or what one that failed said. */
  clearSwitch() {
    if (this.state.screen !== 'signedIn' || !this.state.switching) return
    this.begin()
    this.showSwitch(this.state.session)
  }

  /** The signed-in app with a switch under way, one that failed, or neither. */
  private showSwitch(session: ActiveSession, switching?: SwitchView) {
    const arrival = this.state.screen === 'signedIn' && this.state.session === session ? this.state.arrival : undefined
    this.set({ screen: 'signedIn', session, ...(arrival ? { arrival } : {}), ...(switching ? { switching } : {}) })
  }

  /** Search found a server's kept sign-in ended there: it stays on the list as signed out here. */
  signedOutOf(id: string) {
    if (!this.store.get(id)?.account) return
    this.store.signOut(id)
    this.changed()
  }

  /** The remembered servers changed under a screen that draws them. */
  private changed() {
    if (this.state.screen === 'signedIn') this.set({ ...this.state })
    else if (this.state.screen === 'servers' && !this.state.adding) this.set({ ...this.state, servers: this.store.list() })
  }

  /** After the server the app was on is removed or signed out of: the most recently used one still signed in, else the servers. */
  private moveOn(otherwise: () => void) {
    const next = this.store.list().find((s) => s.account)
    if (next) void this.choose(next.id, { how: 'switched' })
    else otherwise()
  }

  /**
   * Forgets a server, signing its account out on the server when it can, in
   * the background. Removing the one the app is on moves it to the most
   * recently used server it is still signed in to; with none, to the servers.
   */
  remove(id: string) {
    const s = this.store.get(id)
    if (!s) return
    // Connecting to it right now: stop, so it isn't remembered again when it answers.
    if (this.state.screen === 'servers' && this.state.busy === id) this.begin()
    if (this.state.screen === 'signedIn' && this.state.switching?.id === id) this.begin()
    if (s.account) void revoke(bearerClient(s.baseUrl), s.account.token)
    this.store.remove(id)
    if (this.state.screen === 'servers') this.set({ ...this.state, servers: this.state.adding ? [] : this.store.list(), busy: undefined })
    else if (this.state.screen !== 'signedIn') return
    else if (this.state.session.server.id === id) this.moveOn(() => { this.begin(); this.set({ screen: 'servers', servers: this.store.list(), address: '' }) })
    else this.showSwitch(this.state.session, this.state.switching?.id === id ? undefined : this.state.switching)
  }

  /**
   * Settings › Sign out: ends the session on the server too. The server
   * stays on this TV as signed out. The app moves to the most recently used
   * server it is still signed in to; with none, it shows a code to link
   * this TV again.
   */
  signOut() {
    if (this.state.screen !== 'signedIn') return
    const { session } = this.state
    void revoke(session.api, session.account.token)
    this.store.signOut(session.server.id)
    this.moveOn(() => {
      this.begin()
      this.showSignIn(session.server, session.baseUrl)
    })
  }

  /** A 401 anywhere: the session was ended on another device. */
  expire(session?: ActiveSession) {
    const current = this.state.screen === 'signedIn' ? this.state.session : undefined
    const ended = session ?? current
    if (!ended) return
    if (ended === this.back?.session) {
      // Under Add a server or another server's sign-in: there is nothing to come back to now.
      this.store.signOut(ended.server.id)
      this.back = undefined
      ended.detach()
      return
    }
    if (ended !== current) return
    this.store.signOut(ended.server.id)
    this.begin()
    this.set({ screen: 'problem', problem: 'signedOut', server: { id: ended.server.id, name: ended.server.name, baseUrl: ended.baseUrl, version: ended.server.version } })
  }

  /** The app went to the background: codes stop (one press gets a new one), the stream closes. */
  suspend() {
    if (this.state.screen === 'signIn' && this.state.mode === 'code') {
      this.begin()
      this.set({ ...this.state, pairing: { state: 'expired' } })
    } else if (this.state.screen === 'signedIn') this.state.session.suspend()
    this.back?.session.suspend()
  }

  resume() {
    if (this.state.screen === 'signedIn') this.state.session.resume()
    this.back?.session.resume()
  }

  /** Leaving the app: stop everything, keep the sign-in for next time. */
  shutdown() {
    this.begin()
    if (this.state.screen === 'signedIn') this.state.session.detach()
    this.back?.session.detach()
    this.back = undefined
  }
}

/** Whether an error came from what was typed rather than the network. */
export function isAddressError(error: unknown) { return error instanceof AddressError }
