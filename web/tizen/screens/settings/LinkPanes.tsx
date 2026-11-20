import { useQuery } from '@tanstack/react-query'
import { myRequestsQuery, requestOptionsQuery } from '../../../src/api/requests'
import { useNav, useSession } from '../../app/hooks'
import { HandOff } from './Panels'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { PaneNote, SettingRow } from './SettingRow'
import { requestsLine, webAppUrl } from './settingsWords'

/** Your requests: where each one stands, on a page of its own. */
export function RequestsPane({ first }: PaneProps) {
  const nav = useNav()
  const mine = useQuery(myRequestsQuery)
  return <>
    <PaneNote>Each title you’ve asked for, and where it stands.</PaneNote>
    <SettingRow id={paneRowId('requests', 'open')} label="See your requests" detail="Cancel one that is still waiting" chevron autoFocus={first}
      value={mine.data ? requestsLine(mine.data.items) : undefined} onPress={() => nav.push({ name: 'requests' })}/>
  </>
}

/** The admins' Inbox: requests to approve and problems people report, on the requests page. */
export function InboxPane({ first }: PaneProps) {
  const nav = useNav()
  const options = useQuery(requestOptionsQuery)
  const count = options.data?.inbox ?? 0
  return <>
    <PaneNote>Requests that need your approval, and problems people report.</PaneNote>
    <SettingRow id={paneRowId('inbox', 'open')} label="Open the Inbox" detail="Approve, decline or send again" chevron autoFocus={first}
      value={count ? `${count} waiting` : undefined} onPress={() => nav.push({ name: 'requests', inbox: true })}/>
  </>
}

/**
 * Server settings, for admins:
 * they are in the web app, so a QR code of its Settings and the address.
 */
export function ServerPane() {
  const session = useSession()
  const url = webAppUrl(session.baseUrl, '/settings')
  return <>
    <p className="settings-panel__body" style={{ maxWidth: '58.75rem' }}>Libraries, people, Live TV and the rest of {session.server.name}’s settings are in the web app. Scan the code with your phone, or open this address on a phone or computer.</p>
    <HandOff url={url} label="QR code that opens the server’s settings in the web app">
      <span className="tv-note" style={{ maxWidth: '35rem' }}>Sign in there as {session.account.name}. Only admins see the server’s settings.</span>
    </HandOff>
  </>
}
