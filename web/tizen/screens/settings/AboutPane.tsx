import { useState } from 'react'
import { useSession } from '../../app/hooks'
import { usePlaybackErrors } from '../../app/playbackErrors'
import { TV_APP_VERSION } from '../../session/device'
import { InfoPanel, PanelQr } from './Panels'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { InfoRow, PaneHeading, SettingRow } from './SettingRow'
import { LICENCES, PRIVACY_URL } from './settingsWords'
import { playbackErrorCode, playbackErrorWhen, playbackErrorWords } from './aboutWords'
import { thisTvLine } from './thisTv'

const id = (row: string) => paneRowId('about', row)

/**
 * Settings › About: the versions,
 * the last five playback errors as codes someone helping can read off the
 * screen (there is no clipboard, so no Copy), the privacy policy as a QR
 * code and the licences.
 */
export function AboutPane({ first }: PaneProps) {
  const session = useSession()
  const errors = usePlaybackErrors()
  const [panel, setPanel] = useState<'privacy' | 'licences' | null>(null)
  const [now] = useState(() => Date.now())
  return <>
    <PaneHeading>Versions</PaneHeading>
    <InfoRow label="App" value={`WatchMyMedia for Samsung TV ${TV_APP_VERSION}`}/>
    <InfoRow label="This TV" value={thisTvLine()}/>
    <InfoRow label="Server" value={`${session.server.name} · WatchMyMedia ${session.server.version.replace(/^v/, '')}`}/>

    <PaneHeading>Recent playback errors · {errors.length ? `last ${errors.length}` : 'none'}</PaneHeading>
    {errors.length ? errors.map((e, i) => <button key={`${e.at}-${i}`} type="button" data-focus={id(`error/${i}`)} data-autofocus={(first && i === 0) || undefined} className="settings-row">
      <span className="settings-code">{playbackErrorCode(e)}</span>
      <span className="settings-row__what">{playbackErrorWords(e)}</span>
      <span className="settings-row__value" style={{ color: 'var(--muted)' }}>{playbackErrorWhen(e.at, now)}</span>
    </button>) : <InfoRow label="No playback errors yet."/>}
    <p className="settings-note">The last five, newest first, kept on this TV. If someone is helping you, read them the codes or take a photo of this screen. Nothing is sent.</p>

    <PaneHeading>Legal</PaneHeading>
    <SettingRow id={id('privacy')} label="Privacy policy" detail="watchmy.media/privacy" chevron autoFocus={first && !errors.length} onPress={() => setPanel('privacy')}/>
    <SettingRow id={id('licences')} label="Open-source licences" chevron onPress={() => setPanel('licences')}/>
    <p className="settings-note settings-note--faint">This product uses the TMDB API but is not endorsed or certified by TMDB.</p>

    {panel === 'privacy' && <InfoPanel title="Privacy policy" onClose={() => setPanel(null)}>
      <p className="settings-panel__body">Scan the code with your phone, or open this address on any device.</p>
      <PanelQr url={PRIVACY_URL} label="QR code for the privacy policy"/>
    </InfoPanel>}
    {panel === 'licences' && <InfoPanel title="Open-source licences" onClose={() => setPanel(null)}>
      <p className="settings-panel__body">WatchMyMedia is built with these. Thank you to their authors.</p>
      {LICENCES.map((l) => <div key={l.name} className="settings-licence"><b>{l.name}</b><span>{l.licence}</span></div>)}
    </InfoPanel>}
  </>
}
