import { displayAddress, AddressError } from '../../session/address'
import type { RememberedServer } from '../../session/servers'
import type { ServerInfo } from '../../session/active'
import { MIN_SERVER_VERSION, tooOldForApp, type StartProblem, type Target } from '../../session/session'
import { statusOf, problemWords } from '../../ui/problemWords'
import { lastUsedWords, SIGNED_OUT_LINE } from '../servers/serverRows'

// The start screens' words: the servers this TV has, Add a server, signing
// in and why a server can't be used. Addresses and what a server answered
// are kept off the first screen: they are for whoever looks after the
// server, one press away behind Details.

/** What this TV is called where the server lists its devices. */
export const THIS_TV = 'This TV is called Samsung TV'

/** A remembered server's line: "Sam · last used today", or why it needs something first. The address is left off. */
export function serverDetail(server: RememberedServer, now: number, locale?: string): string {
  if (tooOldForApp(server)) return 'Needs an update before this app can use it'
  if (!server.account) return SIGNED_OUT_LINE
  return `${server.account.name} · last used ${lastUsedWords(server.lastUsedAt, now, locale)}`
}

/** The server last used: where focus starts on the list. */
export function lastUsed(servers: readonly Pick<RememberedServer, 'id' | 'lastUsedAt'>[]): string | undefined {
  let best: Pick<RememberedServer, 'id' | 'lastUsedAt'> | undefined
  for (const s of servers) if (!best || s.lastUsedAt > best.lastUsedAt) best = s
  return best?.id
}

/** Under Add a server's field, before anything is typed. */
export const ADDRESS_HINT = 'OK opens the TV’s keyboard. At home it looks like 192.168.1.20:27587. Away from home it starts with https://'

/** What pressing Connect says while there is no address. */
export const CONNECT_WHY = 'Type an address first'

/** While an address is tried. */
export const lookingFor = (address: string) => `Looking for a server at ${displayAddress(address.trim()) || address.trim()}…`

/** Why an address didn't lead to a server, in our own words. */
export function addressFailed(error: unknown): { title: string; body: string } {
  if (error instanceof AddressError) return problemWords(error)
  if (statusOf(error) === undefined) {
    return { title: 'No WatchMyMedia server answered at that address.', body: 'Check the spelling, and that the server is on. At home the TV and the server have to be on the same network.' }
  }
  return problemWords(error)
}

/** What a start problem says: a title, why, what to do, its first action, and whether Details has anything for whoever looks after the server. */
export type StartProblemWords = { title: string; why: string; what: string; first: string; details: boolean }

/** The start screen's problems. */
export function startProblemWords(problem: StartProblem, server: Target): StartProblemWords {
  switch (problem) {
    case 'unreachable':
      return { title: `Can’t reach ${server.name}`, first: 'Try again', details: true,
        why: 'The server isn’t answering. It may be off, restarting, or on a different network from this TV.',
        what: 'Check that the computer it runs on is awake, then try again.' }
    case 'notSetUp':
      return { title: `${server.name} isn’t set up yet`, first: 'Try again', details: true,
        why: 'It is running, but nobody has finished setting it up.',
        what: 'Open it in a browser on a computer or phone to finish, then come back here. Details has its address.' }
    case 'tooOld':
      return { title: `${server.name} needs an update`, first: 'Try again', details: false,
        why: `This TV app works with WatchMyMedia ${MIN_SERVER_VERSION} or newer. ${server.version ? `The server has ${server.version.replace(/^v/, '')}.` : 'The server has an older one.'}`,
        what: 'Update WatchMyMedia on the computer it runs on, then try again. Nothing on the server is lost.' }
    case 'signedOut':
      return { title: 'This TV was signed out', first: 'Sign in again', details: false,
        why: `Someone signed this TV out of ${server.name} from another device.`,
        what: 'Your place in everything you were watching is kept. Sign in again to carry on.' }
    case 'moved':
      return { title: `${server.name} isn’t at this address now`, first: 'Connect to it', details: true,
        why: 'Another WatchMyMedia server answers at its address.',
        what: 'Connect to it to sign in there, or choose another server.' }
  }
}

/** Under the actions when Details has something. */
export const DETAILS_LINE = 'Details shows the address and what the TV heard back, for whoever looks after the server.'

/** Details: the address, and what the TV heard back. */
export function problemDetails(problem: StartProblem, server: Target, found?: Pick<ServerInfo, 'name' | 'version'>): { label: string; value: string }[] {
  const heard = (): string => {
    switch (problem) {
      case 'unreachable': return 'Nothing: no server answered at this address.'
      case 'notSetUp': return `WatchMyMedia${found?.version ? ` ${found.version.replace(/^v/, '')}` : ''}, waiting to be set up`
      case 'moved': return found ? `Another server, ${found.name}${found.version ? `, WatchMyMedia ${found.version.replace(/^v/, '')}` : ''}` : 'Another WatchMyMedia server'
      case 'tooOld': return server.version ? `WatchMyMedia ${server.version.replace(/^v/, '')}` : 'An older WatchMyMedia'
      case 'signedOut': return 'This TV’s sign-in has ended'
    }
  }
  return [
    { label: problem === 'notSetUp' ? 'Open in a browser' : 'Address', value: problem === 'notSetUp' ? server.baseUrl.replace(/\/+$/, '') : displayAddress(server.baseUrl) },
    { label: 'What this TV heard back', value: heard() },
  ]
}

/** What pressing Sign in says before a name and password are typed. */
export const SIGN_IN_WHY = 'Type your name and password first'
