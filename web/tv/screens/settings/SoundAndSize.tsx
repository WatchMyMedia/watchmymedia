import { useState } from 'react'
import type { components } from '../../../src/api/schema'
import { SOUND_LEVELS, SOUND_NOTE, SOUND_ROWS, soundLabel, soundOf, type SoundSetting } from '../../../src/lib/sound'
import { SIZE_KEEPS, SUBTITLE_SIZES, subtitleSizeLabel, subtitleSizeOf, type SubtitleSize } from '../../../src/lib/subtitleSize'
import { SUBTITLE_PX } from '../../player'
import { Icon } from '../../ui/Icon'
import { Option, SidePanel } from '../../ui/Overlays'
import { BACK } from '../../app/keys'
import { ChoicePanel } from './Panels'
import type { usePreferenceSaves } from './saves'
import { paneRowId } from './settingsGroups'
import { PaneHeading, PaneNote, SettingRow } from './SettingRow'
import { saveFailedLine } from './settingsWords'

// Settings › Audio & subtitles from API level 35: Boost dialogue and Reduce
// loud sounds, and the person's Subtitle size with its preview. Each row
// opens its choices from the right and saves at once, saying Saved in the
// row; a refused save says what it still is, and OK tries it again.

type Preferences = components['schemas']['Preferences']
type Saves = ReturnType<typeof usePreferenceSaves>

const id = (row: string) => paneRowId('languages', row)
const soundKey = (setting: SoundSetting) => `sound/${setting}`
const CONTEXT = 'Audio & subtitles · your account'

/** The line every preview shows on the TV, as Playback's did. */
export const TV_PREVIEW_LINE = 'Who moved the markers, Maren?'

/** Sound: the two settings, after the audio languages. */
export function SoundSection({ prefs, saves }: { prefs: Preferences; saves: Saves }) {
  const [open, setOpen] = useState<SoundSetting>()
  const sound = soundOf(prefs)
  const row = open && SOUND_ROWS.find((r) => r.id === open)
  return <>
    <PaneHeading>Sound</PaneHeading>
    {SOUND_ROWS.map((r) => <SettingRow key={r.id} id={id(soundKey(r.id))} label={r.label} detail={r.help} stacked value={soundLabel(sound[r.id])} chevron
      save={saves.status(soundKey(r.id))} onPress={saves.press(soundKey(r.id), () => setOpen(r.id), true)}/>)}
    <PaneNote>{SOUND_NOTE}</PaneNote>
    {row && <ChoicePanel idPrefix={`choice/${row.id}`} title={row.label} context={CONTEXT} onClose={() => setOpen(undefined)}
      choices={SOUND_LEVELS.map((l) => ({ value: l.id, label: l.label }))} value={sound[row.id]}
      onChoose={(v) => { if (v !== sound[row.id]) saves.save(soundKey(row.id), { audio: { [row.id]: v } }, soundLabel(sound[row.id]), true) }}>
      <p className="settings-panel__note">{row.help}</p>
      <p className="settings-panel__note">{SOUND_NOTE}</p>
    </ChoicePanel>}
  </>
}

/** Subtitle size: the person's, the same on all their devices, with its preview. */
export function SizeSection({ prefs, saves }: { prefs: Preferences; saves: Saves }) {
  const [open, setOpen] = useState(false)
  const size = subtitleSizeOf(prefs)
  return <>
    <PaneHeading>Subtitle size</PaneHeading>
    <SettingRow id={id('size')} label="Subtitle size" detail="The same on all your devices" value={subtitleSizeLabel(size)} chevron
      save={saves.status('size')} onPress={saves.press('size', () => setOpen(true), true)}/>
    {open && <SizePanel size={size} saves={saves} onClose={() => setOpen(false)}/>}
  </>
}

/**
 * The sizes, with a preview that changes as you choose. The panel stays
 * open; the chosen size says Saving… then Saved, and one that couldn't be
 * saved goes back to what it still is.
 */
function SizePanel({ size, saves, onClose }: { size: SubtitleSize; saves: Saves; onClose(): void }) {
  const [asked, setAsked] = useState<SubtitleSize>()
  const status = saves.status('size')
  const failed = status?.state === 'failed' ? status : undefined
  const shown = !failed && asked && status ? asked : size
  const choose = (v: SubtitleSize) => {
    if (status?.state === 'saving') return
    if (v === size && !failed) return
    setAsked(v)
    saves.save('size', { subtitles: { size: v } }, subtitleSizeLabel(size), true)
  }
  const end = (v: SubtitleSize) => v !== shown ? undefined
    : status?.state === 'saving' ? <span className="settings-panel__status"><span className="tv-spinner" aria-hidden="true"/>Saving…</span>
      : status?.state === 'saved' ? <span className="settings-panel__status settings-panel__status--ok"><Icon name="check"/>Saved</span> : undefined
  return <SidePanel title="Subtitle size" onClose={onClose}>
    <p className="settings-panel__context">{CONTEXT}</p>
    <div className="settings-panel__list" role="radiogroup" aria-label="Subtitle size">
      {SUBTITLE_SIZES.map((s, i) => <Option key={s.id} id={`choice/size/${i}`} label={s.label} checked={s.id === shown} end={end(s.id)} onPress={() => choose(s.id)}/>)}
    </div>
    {failed && <p role="alert" className="settings-panel__note settings-panel__note--error">{saveFailedLine(failed.still)}</p>}
    <p className="settings-heading settings-heading--pane">Preview</p>
    <div className="settings-preview" aria-hidden="true"><p><span style={{ fontSize: `${SUBTITLE_PX[shown] / 16}rem` }}>{TV_PREVIEW_LINE}</span></p></div>
    <p className="settings-panel__note">The same on all your devices. {SIZE_KEEPS}</p>
    <p className="settings-panel__foot">{BACK} closes this panel.</p>
  </SidePanel>
}
