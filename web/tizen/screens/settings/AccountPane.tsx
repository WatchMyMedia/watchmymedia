import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { revokeSession, sessionsQuery, type Session } from '../../../src/api/account'
import { deviceName, signOutError } from '../../../src/lib/youSettings'
import { useApp, useAppState, useFocus, useSession } from '../../app/hooks'
import { thisTvLine } from './thisTv'
import { Dialog } from '../../ui/Overlays'
import { ManageServers } from '../servers/ManageServers'
import { serversLine, signOutMove } from '../servers/serverRows'
import { InfoPanel, PanelQr } from './Panels'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { InfoRow, PaneHeading, PaneNote, SettingRow } from './SettingRow'
import { deviceLine, webAppUrl } from './settingsWords'

const id = (row: string) => paneRowId('account', row)

/**
 * Settings › Account & devices: this TV,
 * the signed-in devices (each other one asks before it is signed out),
 * Change password on a phone, Servers (the servers this TV has: switch to
 * one, add one, remove one), and Sign out of this TV apart at the foot with
 * its confirmation.
 */
export function AccountPane({ first, servers }: PaneProps) {
  const app = useApp()
  useAppState()
  const session = useSession()
  const focus = useFocus()
  const qc = useQueryClient()
  const sessions = useQuery(sessionsQuery(false))
  const [password, setPassword] = useState(false)
  const [signingOut, setSigningOut] = useState(false)
  const [managing, setManaging] = useState(!!servers)
  const remembered = app.store.list()
  const moveTo = signOutMove(remembered, session.server.id)
  const [revoking, setRevoking] = useState<{ session: Session; state: 'asking' | 'working' | 'failed'; text?: string }>()
  const list = sessions.data?.items ?? []
  const mine = list.find((s) => s.current)
  const others = list.filter((s) => !s.current)

  const revoke = (s: Session) => {
    setRevoking({ session: s, state: 'working' })
    revokeSession(s.id).then(() => done(s), (error: unknown) => {
      const why = signOutError(error)
      if (why.gone) done(s)
      else setRevoking({ session: s, state: 'failed', text: why.text })
    })
  }
  /** Signed out: the dialog closes, the row goes, and focus moves to the next device, else the one before, else Change password. */
  const done = (s: Session) => {
    const at = others.findIndex((o) => o.id === s.id)
    const next = others[at + 1] ?? others[at - 1]
    // Before the dialog closes, so it puts focus back there and not on a row that has gone.
    if (!(next && focus.focusId(id(`device/${next.id}`)))) focus.focusId(id('password'))
    setRevoking(undefined)
    qc.setQueryData(sessionsQuery(false).queryKey, (old: typeof sessions.data) => old && { ...old, items: old.items.filter((o) => o.id !== s.id) })
    void qc.invalidateQueries({ queryKey: ['sessions'] })
  }

  return <>
    <PaneHeading>This TV</PaneHeading>
    <InfoRow label="Device name" detail={thisTvLine()} value={mine?.name ?? session.client.name}/>

    <PaneHeading>Signed-in devices</PaneHeading>
    {sessions.isPending && <PaneNote>Loading…</PaneNote>}
    {sessions.isError && <SettingRow id={id('devices-failed')} label="Couldn’t load the list" detail="Check that the server is on, then try again." value="Try again" onPress={() => void sessions.refetch()}/>}
    {mine && <InfoRow label={deviceName(mine)} detail={deviceLine(mine)} value={<span className="settings-badge">This TV</span>}/>}
    {others.map((s, i) => <SettingRow key={s.id} id={id(`device/${s.id}`)} label={deviceName(s)} detail={deviceLine(s)} autoFocus={first && !servers && i === 0}
      value={revoking?.session.id === s.id && revoking.state === 'working' ? 'Signing out…' : 'Sign out'} chevron
      onPress={() => setRevoking({ session: s, state: 'asking' })}/>)}
    {sessions.data && !others.length && <PaneNote>No other devices are signed in.</PaneNote>}

    <PaneHeading>Account</PaneHeading>
    <SettingRow id={id('password')} label="Change password" detail="On your phone or computer" chevron autoFocus={first && !servers && !others.length} onPress={() => setPassword(true)}/>
    <SettingRow id={id('servers')} label="Servers" detail={serversLine({ name: session.server.name, baseUrl: session.baseUrl }, remembered.length)} chevron autoFocus={servers} onPress={() => setManaging(true)}/>
    <div className="settings-rule" aria-hidden="true"/>
    <SettingRow id={id('sign-out')} label="Sign out of this TV…" icon="signedOut" tone="danger" onPress={() => setSigningOut(true)}/>

    {password && <InfoPanel title="Change password" onClose={() => setPassword(false)}>
      <p className="settings-panel__body">A remote types one character at a time, and this screen is shared. So your password is changed on a phone or computer.</p>
      <PanelQr url={webAppUrl(session.baseUrl, '/settings/account')} label="QR code that opens Account and devices in the web app">
        <p className="settings-panel__note">Changing it signs this TV out too. It will show a code to link it again.</p>
      </PanelQr>
    </InfoPanel>}

    {managing && <ManageServers onClose={() => setManaging(false)}/>}

    {signingOut && <Dialog title="Sign out of this TV?" onCancel={() => setSigningOut(false)} actions={[
      { id: 'dialog/keep', label: 'Stay signed in', safe: true, onPress: () => setSigningOut(false) },
      { id: 'dialog/sign-out', label: 'Sign out of this TV', variant: 'danger', onPress: () => app.signOut() },
    ]}>
      <div><span className="settings-label">Stays</span><p className="tv-body">Your account on {session.server.name}, with what you’ve watched and your settings.</p></div>
      <p className="tv-body" style={{ color: 'var(--text-3)' }}>{moveTo ? `This TV stays signed in to ${moveTo}, and moves to it.`
        : 'This TV goes back to the sign-in screen and shows a code to link it again.'}</p>
      <p className="tv-faint">Return keeps things as they are.</p>
    </Dialog>}

    {revoking && <Dialog title={`Sign out ${deviceName(revoking.session)}?`} onCancel={() => setRevoking(undefined)} actions={[
      { id: 'dialog/keep', label: 'Keep it signed in', safe: true, onPress: () => setRevoking(undefined) },
      { id: 'dialog/revoke', label: revoking.state === 'working' ? 'Signing out…' : revoking.state === 'failed' ? 'Try again' : `Sign out ${deviceName(revoking.session)}`,
        variant: 'danger', busy: revoking.state === 'working', onPress: () => revoke(revoking.session) },
    ]}>
      <p className="tv-body">It is signed out at once, and needs your password or a code to sign in again.</p>
      {revoking.text && <p role="alert" className="tv-error">{revoking.text}</p>}
      <p className="tv-faint">Return keeps things as they are.</p>
    </Dialog>}
  </>
}
