import { relativeTime } from '../../../src/lib/format'
import { displayAddress } from '../../session/address'
import type { RememberedServer } from '../../session/servers'
import { MIN_SERVER_VERSION, type StartProblem, type Target } from '../../session/session'
import type { IconName } from '../../ui/Icon'

const DAY = 86_400_000

/** A remembered server's line: "192.168.1.20:27587 · signed in as Sam · last used 3 days ago". */
export function serverDetail(server: RememberedServer, now: number): string {
  const parts = [displayAddress(server.baseUrl), server.account ? `signed in as ${server.account.name}` : 'not signed in']
  if (server.lastUsedAt && now - server.lastUsedAt > DAY) parts.push(`last used ${relativeTime(new Date(server.lastUsedAt).toISOString())}`)
  return parts.join(' · ')
}

/** What a start problem says: an address to open in a browser, or a note, when it has one. */
export type StartProblemWords = { icon: IconName; title: string; body: string; retry: string; address?: { label: string; value: string }; note?: string }

/** The start screen's problems. */
export function startProblemWords(problem: StartProblem, server: Target): StartProblemWords {
  const address = displayAddress(server.baseUrl)
  switch (problem) {
    case 'unreachable':
      return { icon: 'offline', title: `Can’t reach ${server.name}`, retry: 'Try again',
        body: `This TV couldn’t connect to ${address}. Check that the server is switched on and that this TV is on the same network, then try again.` }
    case 'notSetUp':
      return { icon: 'setup', title: `${server.name} isn’t set up yet`, retry: 'Try again',
        body: 'Finish setting it up in a browser on your phone or computer, at this address. Then come back and try again.',
        address: { label: 'Open in a browser', value: server.baseUrl.replace(/\/+$/, '') } }
    case 'tooOld':
      return { icon: 'update', title: 'This server is too old for this TV', retry: 'Try again',
        body: `${server.name} runs ${server.version ? `WatchMyMedia ${server.version.replace(/^v/, '')}` : 'an older WatchMyMedia'}, and this TV needs ${MIN_SERVER_VERSION} or newer. Update WatchMyMedia on the server, then try again.`,
        note: 'Your library, watch history and settings stay as they are when the server updates.' }
    case 'signedOut':
      return { icon: 'signedOut', title: 'This TV was signed out', retry: 'Sign in again',
        body: `Someone signed this TV out of ${server.name} from another device. Your watch history and settings are still on the server.` }
    case 'moved':
      return { icon: 'server', title: `${server.name} isn’t at this address now`, retry: 'Connect to it',
        body: `Another WatchMyMedia server answers at ${address}. Connect to it to sign in there, or change server.` }
  }
}
