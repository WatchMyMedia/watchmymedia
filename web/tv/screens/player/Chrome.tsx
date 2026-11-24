import type { ReactNode } from 'react'
import type { FilePlayback } from '../../player'
import { showNote } from '../../ui/notes'
import { PLAY_PAUSE_ID } from './keys'
import { recordingLabel, type PlayerKind } from './labels'
import { PlayerIcon, type PlayerIconName } from './PlayerIcons'
import { ScrubberRow, SeekHints, WallClock } from './Ticking'
import type { Heading } from './playerSession'
import { platform } from '../../platform/current'
import { Icon } from '../../ui/Icon'

// The controls over the video, for files, recordings and trailer files:
// the title and its line at the top left, the clock at the top right; at
// the foot the time, what is left and when it ends over a bar that marks
// the intro and the credits, then Back 10, Pause and Forward 10, and the
// panels' buttons. Nothing technical is on it: how the stream plays is in
// Quality's Stream details. The bar reads the position itself; the rest
// redraws only on a change.

export type Opener = 'episodes' | 'tracks' | 'quality'

export type ChromeModel = {
  kind: PlayerKind
  heading: Heading
  playing: boolean
  /** A recording still recording: the red label, written so far. */
  recordingMs?: number
  goToNow: boolean
  /** At a growing recording's edge: forward can't go further. */
  atEdge: boolean
  /** An episode with a season to choose from. */
  episodes: boolean
  /** Audio and subtitles' detail ("English, off"), when there is a choice. */
  tracks?: string
  quality: string
  /** There is a next episode. */
  next: boolean
  /** The recording's line under the controls (its ad breaks). */
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

/** The title and the line under it, top left; a recording still recording says so beside it. */
export function TitleBlock({ heading, paused, dim, recordingMs }: { heading: Heading; paused?: boolean; dim?: boolean; recordingMs?: number }) {
  const line = [heading.line, paused ? 'paused' : undefined].filter(Boolean).join(' · ')
  return <header className={`pl-title${dim ? ' pl-title--dim' : ''}`}>
    <div className="pl-title__text">
      <h1 className="pl-title__name">{heading.title}</h1>
      {line && <p className="pl-title__line">{line}</p>}
    </div>
    {recordingMs !== undefined && <span className="pl-rec" role="status"><span className="pl-dot"/>{recordingLabel(recordingMs)}</span>}
  </header>
}

/** A transport button: round with an icon, the big Pause, or a pill with its name and the choice it holds ("Quality Automatic"). */
function Control({ id, icon, iconAfter, text, detail, label, onPress, kind = 'pill', disabled, why, autoFocus, children }: {
  id: string; icon?: PlayerIconName; iconAfter?: PlayerIconName; text?: string; detail?: string; label?: string; onPress(): void
  /** Dimmed and still focusable; OK says `why`. */
  disabled?: boolean; why?: string
  kind?: 'pill' | 'round' | 'big'; autoFocus?: boolean; children?: ReactNode
}) {
  const press = disabled ? () => { if (why) showNote(why, 'info') } : onPress
  return <button type="button" data-focus={id} data-autofocus={autoFocus || undefined} aria-disabled={disabled || undefined} aria-label={label}
    className={`pl-btn pl-btn--${kind}`} onClick={press}>
    {icon && <PlayerIcon name={icon}/>}
    {children}{text}
    {detail && <small>{detail}</small>}
    {iconAfter && <PlayerIcon name={iconAfter}/>}
  </button>
}

/** How far a recording still recording can go: Forward 10 says so when it can't. */
const EDGE_WHY = 'That’s as far as it has recorded so far.'

export function PlayerChrome({ playback, model, actions }: { playback: FilePlayback; model: ChromeModel; actions: ChromeActions }) {
  return <>
    <div className="pl-shade pl-shade--top"/>
    <div className="pl-shade pl-shade--bottom"/>
    {platform.pointer && <button type="button" className="pl-back tv-pointer-only" aria-label="Back" tabIndex={-1} onClick={actions.back}><Icon name="left"/></button>}
    <TitleBlock heading={model.heading} recordingMs={model.recordingMs}/>
    <WallClock className="pl-clock"/>
    <section className="pl-foot" aria-label="Playback">
      <ScrubberRow playback={playback} focused={model.scrubberFocused}/>
      {model.seeking ? <SeekHints playback={playback}/> : <>
        <div className="pl-row" data-group="transport">
          <Control id="back10" kind="round" icon="back10" label="Back 10 seconds" onPress={() => actions.step(-1)}/>
          <Control id={PLAY_PAUSE_ID} kind="big" icon={model.playing ? 'pause' : 'play'} label={model.playing ? 'Pause' : 'Play'} autoFocus onPress={actions.togglePause}/>
          <Control id="forward10" kind="round" icon="forward10" label="Forward 10 seconds" disabled={model.atEdge} why={EDGE_WHY} onPress={() => actions.step(1)}/>
          {model.goToNow && <Control id="goToNow" text="Go to now" onPress={actions.goToNow}><span className="pl-dot pl-dot--live"/></Control>}
          <span className="pl-row__fill"/>
          {model.tracks && <Control id="tracks" text="Audio and subtitles" detail={model.tracks} onPress={() => actions.open('tracks')}/>}
          {model.episodes && <Control id="episodes" text="Episodes" onPress={() => actions.open('episodes')}/>}
          <Control id="quality" text="Quality" detail={model.quality} onPress={() => actions.open('quality')}/>
          {model.next && <Control id="next" text="Next episode" iconAfter="nextEpisode" onPress={actions.playNext}/>}
        </div>
        {model.foot && <p className="pl-footline">{model.foot}</p>}
      </>}
    </section>
  </>
}
