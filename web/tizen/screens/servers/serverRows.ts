import { displayAddress } from '../../session/address'
import { MAX_SERVERS, type RememberedServer } from '../../session/servers'
import { MIN_SERVER_VERSION, REQUIRED_API_LEVEL, type SwitchView } from '../../session/session'

// Servers, wherever the app draws them: the panel the rail's account button
// opens, Settings' Servers page and a server's page there. Which rows, in
// what order, what each says and where focus starts.

/** The rail's account button. */
export const ACCOUNT_FOCUS = 'rail/account'
/** A server's row in the panel. */
export const serverFocus = (id: string) => `servers/server/${id}`
export const RETRY_FOCUS = 'servers/retry'
export const ADD_FOCUS = 'servers/add'
export const MANAGE_FOCUS = 'servers/manage'

export type ServerRowState = 'current' | 'ready' | 'switching' | 'signedOut' | 'tooOld'

export type ServerRow = {
  id: string
  name: string
  /** Under the name: who is signed in and where, or why it can't be opened as it is. */
  detail: string
  state: ServerRowState
  /** OK does something: a switch, or its sign-in. The current server and one too old don't. */
  canChoose: boolean
}

function stateOf(server: RememberedServer, currentId: string, switching?: SwitchView): ServerRowState {
  if (server.id === currentId) return 'current'
  if (server.apiLevel !== undefined && server.apiLevel < REQUIRED_API_LEVEL) return 'tooOld'
  if (!server.account) return 'signedOut'
  return switching?.state === 'switching' && switching.id === server.id ? 'switching' : 'ready'
}

/** Who is signed in and where: "Sam · home.example.net". */
function who(server: RememberedServer): string {
  return [server.account?.name, displayAddress(server.baseUrl)].filter(Boolean).join(' · ')
}

/**
 * The servers this TV has, as Servers lists them: the current one first,
 * then by last use. One signed out here says so, and choosing it opens its
 * sign-in; one too old for this app can't be chosen.
 */
export function serverRows(servers: readonly RememberedServer[], currentId: string, switching?: SwitchView): ServerRow[] {
  const ordered = [...servers].sort((a, b) => Number(b.id === currentId) - Number(a.id === currentId) || b.lastUsedAt - a.lastUsedAt)
  return ordered.map((s) => {
    const state = stateOf(s, currentId, switching)
    return {
      id: s.id, name: s.name, state,
      detail: state === 'signedOut' ? 'Signed out here · sign in again' : state === 'tooOld' ? 'Too old for this app' : who(s),
      canChoose: state === 'ready' || state === 'signedOut' || state === 'switching',
    }
  })
}

/**
 * Where focus starts when Servers opens: Try again after a switch that
 * failed; else the first server that can be switched to; with only the
 * current one, Add a server (Manage servers once this TV keeps all it can).
 */
export function panelFocus(rows: readonly ServerRow[], switching: SwitchView | undefined, full: boolean): string {
  if (switching?.state === 'failed') return RETRY_FOCUS
  const under = switching?.state === 'switching' ? rows.find((r) => r.id === switching.id) : undefined
  const first = under ?? rows.find((r) => r.canChoose)
  return first ? serverFocus(first.id) : full ? MANAGE_FOCUS : ADD_FOCUS
}

/**
 * Where focus goes when Servers closes, when that isn't the screen: Return
 * on a panel the rail's account button opened puts it back on that button.
 * A panel a screen opened (a search result's switch that failed) gives it
 * back to the screen, and so does a switch that worked, for the new Home.
 */
export function panelReturn(from: 'rail' | 'screen', closedByReturn: boolean): string | undefined {
  return from === 'rail' && closedByReturn ? ACCOUNT_FOCUS : undefined
}

/** A server's line on Settings' Servers page: "Sam · home.example.net · v1.8.0". */
export function manageDetail(server: RememberedServer): string {
  return [server.account?.name ?? 'Signed out here', displayAddress(server.baseUrl), server.version].filter(Boolean).join(' · ')
}

/** A server's version on its page: "WatchMyMedia 1.8.0". */
export function versionLine(version: string | undefined): string {
  return version ? `WatchMyMedia ${version.replace(/^v/, '')}` : 'Not known yet'
}

/** Settings › Account & devices › Servers: "Living room server and 2 more", or the one server and its address. */
export function serversLine(current: { name: string; baseUrl: string }, count: number): string {
  return count > 1 ? `${current.name} and ${count - 1} more` : `${current.name} · ${displayAddress(current.baseUrl)}`
}

/** Under Add a server: how (there is no discovery on this TV), or why not yet. */
export function addLine(full: boolean): string {
  return full ? `This TV keeps ${MAX_SERVERS} servers. Remove one first.` : 'By its address'
}

/** Under Add a server's field: the servers it doesn't offer again. */
export function alreadyAdded(names: readonly string[]): string {
  return names.length ? `Already added: ${names.join(', ')}.` : ''
}

/** The rail's account button: its name for a screen reader, and its label when the rail is open (the server, with more than one). */
export function accountWords(person: string, server: string, servers: number): { label: string; visible: string } {
  return { label: `Servers. ${person}, on ${server}`, visible: servers > 1 ? server : person }
}

/** The note after a switch. */
export const nowOn = (server: string) => `Now on ${server}`

/** Why a switch didn't happen, above the list. The app is still where it was, and says so. */
export function switchFailedWords(failed: Extract<SwitchView, { state: 'failed' }>, server: Pick<RememberedServer, 'baseUrl'> | undefined, current: string): { title: string; body: string } {
  const still = `You’re still on ${current}.`
  switch (failed.problem) {
    case 'moved':
      return { title: `${failed.name} isn’t at this address now`, body: `Another WatchMyMedia server answers at ${server ? displayAddress(server.baseUrl) : 'its address'}. ${still}` }
    case 'tooOld':
      return { title: `${failed.name} is too old for this app`, body: `This TV needs WatchMyMedia ${MIN_SERVER_VERSION} or newer on it. ${still}` }
    case 'notSetUp':
      return { title: `${failed.name} isn’t set up yet`, body: `Finish setting it up in a browser on your phone or computer. ${still}` }
    default:
      return { title: `Couldn’t reach ${failed.name}`, body: `It may be off, or only reachable on its own network. ${still}` }
  }
}

/** Remove this server…: the question, with focus on Keep. */
export function removeWords(name: string): { title: string; lines: [string, string] } {
  return {
    title: `Remove ${name} from this TV?`,
    lines: ['This TV is signed out of it.', 'The server, your account on it and what you’ve watched there aren’t changed. You can add it again.'],
  }
}

/** Sign out of this TV, when another server is still signed in: where the app goes. */
export function signOutMove(servers: readonly RememberedServer[], currentId: string): string | undefined {
  return [...servers].sort((a, b) => b.lastUsedAt - a.lastUsedAt).find((s) => s.id !== currentId && s.account)?.name
}

/** The last step of Add a server. */
export function addedWords(name: string, person: string, signedIn: number): { title: string; lead: string; changed: string; next: string } {
  return {
    title: `${name} is added`,
    lead: `You’re on it now, signed in as ${person}.`,
    changed: `This TV is signed in to ${signedIn === 1 ? '1 server' : `${signedIn} servers`}. Search looks in all of them.`,
    next: 'Choose your initial at the foot of the rail to switch. It doesn’t ask for a password again.',
  }
}
