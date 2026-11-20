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

/** The start screen's problems (ServerProblems on the Samsung TV canvas). */
export function startProblemWords(problem: StartProblem, server: Target): { icon: IconName; title: string; body: string; retry: string } {
  const address = displayAddress(server.baseUrl)
  switch (problem) {
    case 'unreachable':
      return { icon: 'offline', title: `Can’t reach ${server.name}`, retry: 'Try again',
        body: `Nothing answered at ${address}. Check that the server is on and that this TV is on the same network, then try again.` }
    case 'notSetUp':
      return { icon: 'setup', title: `${server.name} isn’t set up yet`, retry: 'Try again',
        body: `It has no accounts yet. Finish setting it up in a browser at ${address}, then come back and try again.` }
    case 'tooOld':
      return { icon: 'update', title: `${server.name} needs an update`, retry: 'Try again',
        body: `This TV needs WatchMyMedia ${MIN_SERVER_VERSION} or newer on the server${server.version ? `, and it has ${server.version.replace(/^v?/, 'v')}` : ''}. Update it, then try again.` }
    case 'signedOut':
      return { icon: 'signedOut', title: 'This TV was signed out', retry: 'Sign in again',
        body: `It was signed out of ${server.name} from another device. Sign in again to keep watching.` }
    case 'moved':
      return { icon: 'server', title: `${server.name} isn’t at this address now`, retry: 'Connect to it',
        body: `Another WatchMyMedia server answers at ${address}. Connect to it to sign in there, or choose another server.` }
  }
}
