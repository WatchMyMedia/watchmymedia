import type { ReactNode } from 'react'
import type { FilePlayback } from '../../player'
import { PLAY_PAUSE_ID } from './keys'
import { hintWords, recordingLabel, type PlayerKind } from './labels'
import { PlayerIcon, type PlayerIconName } from './PlayerIcons'
import { ScrubberRow, WallClock } from './Ticking'
import type { Heading } from './playerSession'

// PlayerControls' chrome over the video (and RecordingPlayer's, and
// TrailerPlayer's trailer file): the title and its line, the stream pill
// and the clock, the scrubber, the transport row and the hint line. The
// scrubber reads the position itself; the rest redraws only on a change.

export type Opener = 'episodes' | 'audio' | 'subtitles' | 'quality'

export type ChromeModel = {
  kind: PlayerKind
  heading: Heading
  stream?: string
  playing: boolean
  /** A recording still recording: the red label, written so far. */
  recordingMs?: number
  goToNow: boolean
  /** At a growing recording's edge: forward can't go further. */
  atEdge: boolean
  episodes?: string
  audio?: string
  subtitles?: string
  quality: string
  next?: string
  /** The recording's line at the end of the row (its ad breaks). */
  foot?: string
  seeking: boolean
  scrubberFocused: boolean
}

export type ChromeActions = {
  back(): void
  step(direction: 1 | -1): void
  togglePause(): void
  goToNow(): void
  open(opener: Opener): void
  playNext(): void
}

/** The title and the line under it; dimmed with "paused" while a panel is open over the video. */
export function TitleBlock({ heading, paused, dim, back }: { heading: Heading; paused?: boolean; dim?: boolean; back?: ReactNode }) {
  const line = [heading.line, paused ? 'paused' : undefined].filter(Boolean).join(' · ')
  return <div className={`pl-title${dim ? ' pl-title--dim' : ''}`}>
    {back}
    <div className="pl-title__text">
      <span className="pl-title__name">{heading.title}</span>
      {line && <span className="pl-title__line">{line}</span>}
    </div>
  </div>
}

/** A transport button: an icon, or an icon with its name and the choice it holds ("Audio English"). */
function Control({ id, icon, text, detail, label, onPress, big, disabled, autoFocus, children }: {
  id: string; icon?: PlayerIconName; text?: string; detail?: string; label?: string; onPress(): void; big?: boolean; disabled?: boolean; autoFocus?: boolean; children?: ReactNode
}) {
  const iconOnly = !text && !children
  return <button type="button" data-focus={id} data-autofocus={autoFocus || undefined} aria-disabled={disabled || undefined} aria-label={label}
    className={`pl-btn${big ? ' pl-btn--big' : iconOnly ? ' pl-btn--icon' : ''}`} onClick={disabled ? undefined : onPress}>
    {icon && <PlayerIcon name={icon}/>}
    {children}{text}
    {detail !== undefined && <small>{detail}</small>}
  </button>
}

export function PlayerChrome({ playback, model, actions }: { playback: FilePlayback; model: ChromeModel; actions: ChromeActions }) {
  const hints = hintWords(model.kind, model.seeking)
  const back = model.kind === 'recording'
    ? <button type="button" className="pl-btn pl-btn--icon" data-focus="back" aria-label="Back to Recordings" onClick={actions.back}><PlayerIcon name="chevronLeft"/></button>
    : undefined
  return <>
    <div className="pl-shade pl-shade--top"/>
    <div className="pl-shade pl-shade--bottom"/>
    <TitleBlock heading={model.heading} back={back}/>
    <div className="pl-status">
      {model.recordingMs !== undefined && <span className="pl-rec" role="status"><span className="pl-dot"/>{recordingLabel(model.recordingMs)}</span>}
      {model.stream && <span className="pl-pill">{model.stream}</span>}
      <WallClock className="pl-clock"/>
    </div>
    <ScrubberRow playback={playback} focused={model.scrubberFocused}/>
    <div className="pl-row" data-group="transport">
      <Control id="back10" icon="back10" label="Back 10 seconds" onPress={() => actions.step(-1)}/>
      <Control id={PLAY_PAUSE_ID} icon={model.playing ? 'pause' : 'play'} label={model.playing ? 'Pause' : 'Play'} big autoFocus onPress={actions.togglePause}/>
      <Control id="forward10" icon="forward10" label="Forward 10 seconds" disabled={model.atEdge} onPress={() => actions.step(1)}/>
      {model.goToNow && <Control id="goToNow" text="Go to now" onPress={actions.goToNow}><span className="pl-dot pl-dot--live"/></Control>}
      <span className="pl-row__gap"/>
      {model.episodes && <Control id="episodes" icon="episodes" detail={model.episodes} text="Episodes" onPress={() => actions.open('episodes')}/>}
      {model.audio && <Control id="audio" icon="audio" detail={model.audio} text="Audio" onPress={() => actions.open('audio')}/>}
      {model.subtitles && <Control id="subtitles" icon="subtitles" detail={model.subtitles} text="Subtitles" onPress={() => actions.open('subtitles')}/>}
      <Control id="quality" icon="quality" detail={model.quality} text="Quality" onPress={() => actions.open('quality')}/>
      <span className="pl-row__fill"/>
      {model.next && <Control id="next" icon="nextEpisode" text="Next episode" detail={model.next} onPress={actions.playNext}/>}
      {model.foot && <span className="pl-row__foot">{model.foot}</span>}
    </div>
    {hints && <p className="pl-hint" aria-hidden="true">
      <span className="pl-key">◀ ▶</span><span className="pl-key">⏪ ⏩</span>{hints.seek} · <span className="pl-key">OK</span>{hints.ok} · <span className="pl-key">Return</span>{hints.back}
    </p>}
  </>
}
