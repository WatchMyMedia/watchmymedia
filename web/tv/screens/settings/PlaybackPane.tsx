import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery, preferencesQuery } from '../../../src/api/queries'
import { DEFAULT_LEAD, leadMinutes } from '../../../src/lib/liveReminders'
import { qualitiesFor } from '../../../src/lib/quality'
import { skipModeOf } from '../../../src/lib/youSettings'
import { setLocalSettings, useLocalSettings } from '../../app/localSettings'
import { useSession } from '../../app/hooks'
import { from35 } from '../../session/features'
import { SUBTITLE_PX } from '../../player'
import { setDefaultQuality, useDefaultQuality } from './defaultQuality'
import { AccountNotLoaded, ChoicePanel } from './Panels'
import { usePreferenceSaves } from './saves'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { PaneHeading, PaneNote, SettingRow } from './SettingRow'
import { LEADS, leadLabel, qualityDescription, SKIP_ROWS, skipExplanation, skipLabel, SUBTITLE_SIZES, subtitleSizeLabel, TV_SKIP_MODES } from './settingsWords'

type Panel = { kind: 'quality' } | { kind: 'size' } | { kind: 'lead' } | { kind: 'skip'; part: string }

const id = (row: string) => paneRowId('playback', row)

/**
 * Settings › Playback: this TV's Default
 * quality and, below API level 35, Subtitle size (from 35 it is the
 * person's, in Audio & subtitles), then the account's, on every device:
 * Autoplay next episode, the five skip rows and the Live TV reminders'
 * lead time. The account's rows wait for its settings, so a default is
 * never saved over someone's choice.
 */
export function PlaybackPane({ first }: PaneProps) {
  const session = useSession()
  const apiLevel = session.server.apiLevel
  const quality = useDefaultQuality(apiLevel)
  const { subtitleSize } = useLocalSettings()
  const prefs = useQuery(preferencesQuery)
  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const saves = usePreferenceSaves()
  const [panel, setPanel] = useState<Panel | null>(null)
  const p = prefs.data
  const liveTV = !!capabilities.data?.enabled && !!capabilities.data.allowed
  const lead = leadMinutes(p?.liveTVReminderMinutes)
  const close = () => setPanel(null)

  return <>
    {prefs.isError && <AccountNotLoaded id={id('not-loaded')} error={prefs.error} retrying={prefs.isFetching} onRetry={() => void prefs.refetch()}/>}
    <PaneHeading>On this TV</PaneHeading>
    <SettingRow id={id('quality')} label="Default quality" detail={qualityDescription(quality.id)} value={quality.label} chevron autoFocus={first && !prefs.isError}
      onPress={() => setPanel({ kind: 'quality' })}/>
    {from35(apiLevel)
      ? <PaneNote>Subtitle size is in Audio &amp; subtitles now, the same on all your devices.</PaneNote>
      : <SettingRow id={id('subtitle-size')} label="Subtitle size" value={subtitleSizeLabel(subtitleSize)} chevron onPress={() => setPanel({ kind: 'size' })}/>}

    <PaneHeading>Your account · on every device</PaneHeading>
    <SettingRow id={id('autoplay')} label="Autoplay next episode" detail="Counts down 10 s from the credits" toggle={p?.autoplayNext ?? true} disabled={!p} save={saves.status('autoplay')}
      onPress={saves.press('autoplay', () => p && saves.save('autoplay', { autoplayNext: !p.autoplayNext }, p.autoplayNext ? 'On' : 'Off'))}/>
    {SKIP_ROWS.map((row) => {
      const mode = skipModeOf(p?.skip, row.kind)
      const key = `skip-${row.kind}`
      return <SettingRow key={key} id={id(key)} label={row.label} detail={row.note} value={skipLabel(mode)} chevron disabled={!p} save={saves.status(key)}
        onPress={saves.press(key, () => setPanel({ kind: 'skip', part: row.kind }))}/>
    })}
    {liveTV && <SettingRow id={id('lead')} label="Live TV reminders" detail="The same on all your devices" value={leadLabel(lead)} chevron disabled={!p} save={saves.status('lead')}
      onPress={saves.press('lead', () => setPanel({ kind: 'lead' }))}/>}

    {panel?.kind === 'quality' && <ChoicePanel idPrefix="choice/quality" title="Default quality" context="Playback · on this TV" onClose={close}
      choices={qualitiesFor(apiLevel).map((q) => ({ value: q.id, label: q.label, detail: qualityDescription(q.id) }))} value={quality.id}
      onChoose={(v) => { const q = qualitiesFor(apiLevel).find((x) => x.id === v); if (q) setDefaultQuality(q) }}
      note="The player’s Quality button changes it for one video."/>}
    {panel?.kind === 'size' && <ChoicePanel idPrefix="choice/size" title="Subtitle size" context="Playback · on this TV" onClose={close} keepOpen
      choices={SUBTITLE_SIZES.map((s) => ({ value: s.id, label: s.label }))} value={subtitleSize} onChoose={(v) => setLocalSettings({ subtitleSize: v })}>
      <p className="settings-heading settings-heading--pane">Preview</p>
      <div className="settings-preview" aria-hidden="true"><p><span style={{ fontSize: `${SUBTITLE_PX[subtitleSize] / 16}rem` }}>Who moved the markers, Maren?</span></p></div>
    </ChoicePanel>}
    {panel?.kind === 'skip' && p && (() => {
      const row = SKIP_ROWS.find((r) => r.kind === panel.part)!
      const mode = skipModeOf(p.skip, row.kind)
      return <ChoicePanel idPrefix={`choice/skip-${row.kind}`} title={row.label} context="Playback · your account" onClose={close}
        choices={TV_SKIP_MODES.map((m) => ({ value: m.value, label: m.label, detail: skipExplanation(m.value, row.kind) }))} value={mode}
        onChoose={(v) => { if (v !== mode) saves.save(`skip-${row.kind}`, { skip: { [row.kind]: v } }, skipLabel(mode)) }}
        note="A part you seek into is never skipped for you."/>
    })()}
    {panel?.kind === 'lead' && <ChoicePanel idPrefix="choice/lead" title="Live TV reminders" context="Playback · your account" onClose={close}
      choices={LEADS.map((m) => ({ value: m, label: leadLabel(m), detail: m === DEFAULT_LEAD ? 'The default' : undefined }))} value={lead}
      onChoose={(v) => { if (v !== lead) saves.save('lead', { liveTVReminderMinutes: v }, leadLabel(lead)) }}
      note="A reminder shows on this TV while WatchMyMedia is open. The same lead time on all your devices."/>}
  </>
}
