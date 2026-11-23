import type { components } from '../../../src/api/schema'
import { knownLanguage, languageName } from '../../../src/lib/format'
import { Icon } from '../../ui/Icon'
import { Option, PanelLine, SidePanel } from '../../ui/Overlays'
import { showNote } from '../../ui/notes'
import { fileNumbers, playSentence, soundName, speakerWords, versionLine, versionName } from './fileWords'

// The panels a title's More opens, each in place of the one before from the
// right: More's rows, Choose a version, Audio and subtitles, File details
// (wide, a sentence first and the numbers under it) and Report a problem.
// Return closes a panel opened from More back to More.

type MediaFileDetail = components['schemas']['MediaFileDetail']
type MediaInfo = components['schemas']['MediaInfo']
type PlaybackPlan = components['schemas']['PlaybackPlan']
type ProblemKind = components['schemas']['Problem']['kind']

export type MoreRow = {
  id: string
  label: string
  detail?: string
  /** Opens the next panel: a chevron at the end. */
  next?: boolean
  /** Working: presses wait. */
  busy?: boolean
  /** Deletes or replaces something: last, under a line; it asks before doing it. */
  danger?: boolean
  onPress(): void
}

/** One row of a panel, as the kit's Option draws an action, with a chevron when it opens the next panel. */
function Row({ row, autoFocus }: { row: MoreRow; autoFocus: boolean }) {
  return <button type="button" role="menuitem" data-focus={row.id} data-autofocus={autoFocus || undefined} aria-busy={row.busy || undefined}
    className="tv-option" onClick={row.busy ? undefined : row.onPress}>
    <span className="tv-option__text"><span className="tv-option__label">{row.label}</span>{row.detail && <span className="tv-option__detail">{row.detail}</span>}</span>
    {row.busy && <span className="tv-spinner" aria-hidden="true"/>}
    {row.next && <span className="tv-option__end"><Icon name="right"/></span>}
  </button>
}

/**
 * More, from the right: the title's other actions as rows, focus on the
 * first or on the one a panel was opened from. Rows that delete or replace
 * come last, under a line.
 */
export function MorePanelRows({ title, rows, start, onClose }: { title: string; rows: MoreRow[]; start?: string; onClose(): void }) {
  const plain = rows.filter((r) => !r.danger), danger = rows.filter((r) => r.danger)
  const first = rows.some((r) => r.id === start) ? start : rows[0]?.id
  return <SidePanel title="More" sub={title} onClose={onClose}>
    <div role="menu" aria-label={`More for ${title}`} className="dt-rows">
      {plain.map((r) => <Row key={r.id} row={r} autoFocus={r.id === first}/>)}
      {danger.length > 0 && plain.length > 0 && <PanelLine/>}
      {danger.map((r) => <Row key={r.id} row={r} autoFocus={r.id === first}/>)}
    </div>
  </SidePanel>
}

/** Choose a version: a radio each, chosen for the next play, the panel staying open. */
export function VersionsPanel({ title, versions, chosen, planFor, onChoose, onClose }: {
  title: string; versions: MediaFileDetail[]; chosen?: number; planFor(id: number): PlaybackPlan | undefined; onChoose(id: number): void; onClose(): void
}) {
  return <SidePanel title="Choose a version" sub={title} onClose={onClose}>
    <div role="menu" aria-label="Versions" className="dt-rows">
      {versions.map((v) => <Option key={v.id} id={`version/${v.id}`} label={versionName(v)} detail={versionLine(v, planFor(v.id), v.id === chosen)}
        checked={v.id === chosen} onPress={() => onChoose(v.id)}/>)}
    </div>
  </SidePanel>
}

/** A track's name: its language, else its title. */
function trackName(language: string | undefined, title: string | undefined): string {
  return knownLanguage(language) ? languageName(language) : title?.trim() || 'Unknown language'
}

const CHOOSE_WHILE_PLAYING = 'Change them while it plays, from the player’s Audio and subtitles.'

/**
 * Audio and subtitles: what the file holds, in words, the sound first.
 * The player is where they are changed, so a press says so.
 */
export function AudioPanel({ probe, onClose }: { probe: Pick<MediaInfo, 'audio' | 'subtitles'>; onClose(): void }) {
  const say = () => showNote(CHOOSE_WHILE_PLAYING, 'info')
  return <SidePanel title="Audio and subtitles" sub="In this version" onClose={onClose} foot={<p className="tv-note">{CHOOSE_WHILE_PLAYING}</p>}>
    <div role="menu" aria-label="Audio and subtitles" className="dt-rows dt-rows--scroll">
      {probe.audio.length > 0 && <h3 className="dt-rows__head">Sound</h3>}
      {probe.audio.map((a, i) => <Option key={`a${a.index}`} id={`track/a${a.index}`} autoFocus={i === 0} label={a.title?.trim() && knownLanguage(a.language) ? `${languageName(a.language)}, ${a.title.trim()}` : trackName(a.language, a.title)}
        detail={[soundName(a.codec), speakerWords(a.channels, a.channelLayout), a.default ? 'plays first' : ''].filter(Boolean).join(' · ')} onPress={say}/>)}
      {probe.subtitles.length > 0 && <h3 className="dt-rows__head">Subtitles</h3>}
      {probe.subtitles.map((s, i) => <Option key={`s${s.index}`} id={`track/s${s.index}`} autoFocus={probe.audio.length === 0 && i === 0} label={trackName(s.language, s.title)}
        detail={[s.image ? 'Pictures, drawn into the video' : 'Text', s.forced ? 'shows by itself' : s.default ? 'on at first' : ''].filter(Boolean).join(' · ')} onPress={say}/>)}
    </div>
  </SidePanel>
}

/**
 * File details, wide: how the chosen version plays on this TV in a
 * sentence, the versions to choose between, then the numbers for whoever
 * wants them.
 */
export function FileDetailsPanel({ versions, chosen, planFor, onChoose, onClose }: {
  versions: MediaFileDetail[]; chosen: MediaFileDetail; planFor(id: number): PlaybackPlan | undefined; onChoose(id: number): void; onClose(): void
}) {
  const plan = planFor(chosen.id)
  const numbers = fileNumbers(chosen, plan)
  return <SidePanel title="File details" sub={<span className="dt-sentence">{playSentence(chosen, plan)}</span>} wide onClose={onClose}>
    <h3 className="dt-rows__head">{versions.length > 1 ? 'Versions' : 'Version'}</h3>
    <div role="menu" aria-label="Versions" className="dt-rows">
      {versions.length > 1
        ? versions.map((v) => <Option key={v.id} id={`version/${v.id}`} label={versionName(v)} detail={versionLine(v, planFor(v.id), v.id === chosen.id)}
          checked={v.id === chosen.id} onPress={() => onChoose(v.id)}/>)
        // One version is nothing to choose: a plain row, which a press leaves as it is.
        : <Option id={`version/${chosen.id}`} label={versionName(chosen)} detail={versionLine(chosen, plan, false)} onPress={() => onChoose(chosen.id)}/>}
    </div>
    <h3 className="dt-rows__head">The numbers, for the {versionName(chosen)} version</h3>
    <dl className="dt-numbers">
      {numbers.map((n) => <div key={n.label}><dt>{n.label}</dt><dd>{n.value}</dd></div>)}
    </dl>
  </SidePanel>
}

const KINDS: { value: ProblemKind; label: string; detail: string }[] = [
  { value: 'video', label: 'The picture', detail: 'It stutters, freezes or looks wrong' },
  { value: 'audio', label: 'The sound', detail: 'It’s missing, out of step or in the wrong language' },
  { value: 'subtitles', label: 'The subtitles', detail: 'They’re missing, wrong or out of step' },
  { value: 'other', label: 'Something else', detail: 'An admin takes a look' },
]

/** Report a problem: what is wrong, sent without a note, as the TV's report is. */
export function ReportPanel({ onPick, onClose }: { onPick(kind: ProblemKind): void; onClose(): void }) {
  return <SidePanel title="Report a problem" sub="What’s wrong?" onClose={onClose}>
    <div role="menu" aria-label="Report a problem" className="dt-rows">
      {KINDS.map((k, i) => <Option key={k.value} id={`report/${k.value}`} label={k.label} detail={k.detail} autoFocus={i === 0} onPress={() => onPick(k.value)}/>)}
    </div>
  </SidePanel>
}
