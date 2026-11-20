import { useState } from 'react'
import { useApp, useNav, useSession } from '../../app/hooks'
import { displayAddress } from '../../session/address'
import { TV_APP_VERSION } from '../../session/device'
import { Button } from '../../ui/Button'
import { Dialog } from '../../ui/Overlays'

/**
 * Settings, until the settings feature replaces it (SettingsGroups and
 * SettingsAccount on the Samsung TV canvas). It already has what the
 * session owns: Switch server, and Sign out with its confirmation.
 */
export function SettingsScreen() {
  const app = useApp()
  const nav = useNav()
  const session = useSession()
  const [confirm, setConfirm] = useState(false)
  return <div className="tv-placeholder">
    <p className="tv-kicker">Settings · placeholder</p>
    <h1 className="tv-h1">Account and devices</h1>
    <p className="tv-lede">Signed in to {session.server.name} ({displayAddress(session.baseUrl)}) as {session.account.name}. This TV runs WatchMyMedia for Samsung TV {TV_APP_VERSION}.</p>
    <div className="tv-actions">
      <Button id="settings/switch" autoFocus onPress={() => app.switchServer()}>Switch server</Button>
      <Button id="settings/requests" onPress={() => nav.push({ name: 'requests' })}>Your requests</Button>
      <Button id="settings/sign-out" onPress={() => setConfirm(true)}>Sign out of this TV…</Button>
    </div>
    {confirm && <Dialog title="Sign out of this TV?" onCancel={() => setConfirm(false)} actions={[
      { id: 'dialog/keep', label: 'Stay signed in', safe: true, onPress: () => setConfirm(false) },
      { id: 'dialog/sign-out', label: 'Sign out of this TV', variant: 'danger', onPress: () => app.signOut() },
    ]}>
      <p className="tv-body">Your account on {session.server.name}, with what you’ve watched and your settings, stays.</p>
      <p className="tv-body" style={{ color: 'var(--text-3)' }}>This TV goes back to the sign-in screen and shows a code to link it again.</p>
    </Dialog>}
  </div>
}
