import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import type { LiveChannel, LiveQuality } from '../../../src/api/liveTV'
import { nowAndNext, panelPicture, progress, timeRange } from '../../../src/lib/liveGuide'
import type { LivePlaybackState } from '../../player'
import { useKeys } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { showNote } from '../../ui/notes'
import { Row } from '../../ui/Row'
import { placeholderTone } from '../../ui/tones'
import { VScroll } from '../../ui/VScroll'
import { ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { LiveIcon } from './LiveIcon'
import type { LiveProblem, ProblemAction } from './liveProblems'
import { stillWatchingWords } from './liveProblems'
import { PROMPT_GRACE_S } from './liveRemote'
import { AT_LIVE_NOTE, CONTROLS_NOTE, favouritesFirst, liveBadge, nextWords, onNowLine, takeoverLine } from './livePlayerModel'
import { channelLabel, clockWords, leftWords, onNowCards, qualityOptions } from './liveWords'
import { OnNowCardButton } from './OnNowRow'
import { BACK } from '../../app/keys'

// The Live TV player's parts, drawn to the couch: the banner that leads
// with the programme, how long is left and what is next; the controls a
// press on OK brings up; On now from the left; Quality from the right; and
// the problems as dialogs over the picture or a page of their own.

const ms = (iso: string) => Date.parse(iso)

/** The channel's number in a white chip, as the guide draws it. */
export function ChannelChip({ number }: { number: string }) {
  return <span className="lp-chan">{number}</span>
}

/** Live in red, or where the picture is when it isn't (Paused, Behind live, Tuning) in grey. */
function Badge({ state, tuning }: { state: Pick<LivePlaybackState, 'paused' | 'behind'>; tuning: boolean }) {
  const b = liveBadge({ tuning, paused: state.paused, behind: state.behind })
  return <span className={`lp-badge${b.live ? '' : ' lp-badge--grey'}`}><span className="lp-badge__dot"/>{b.words}</span>
}

/** The clock, top right inside the safe area. */
export function TopClock({ now }: { now: number }) {
  return <p className="lp-clock" aria-hidden="true">{clockWords(now)}</p>
}

/**
 * The banner at the foot, on its own: the programme's picture, the channel,
 * the title (and New), how long is left with the programme's bar and its
 * times, what is next, and at the right the keys that work now.
 */
export function ChannelBanner({ channel, state, now, listings, tuning }: { channel: LiveChannel; state: LivePlaybackState; now: number; listings: boolean; tuning: boolean }) {
  const { now: on, next } = nowAndNext(channel, now)
  const named = listings && on ? on.title : channel.name
  const tone = placeholderTone(named)
  return <section className="lp-banner" role="status" aria-label={`Now on ${channel.number}`}>
    <div className="lp-banner__pic" style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties}>
      {listings && on && panelPicture(on) ? <ProgrammeArt channel={channel} programme={on} size="banner" overlay={false}/> : <span className="lp-banner__plain">{named}</span>}
    </div>
    <div className="lp-banner__body">
      <div className="lp-banner__chan"><ChannelChip number={channel.number}/><span className="lp-banner__name">{channel.name}</span><Badge state={state} tuning={tuning}/></div>
      <div className="lp-banner__titleline">
        <h1 className="lp-banner__title">{listings && on ? on.title : channel.name}</h1>
        {listings && on?.new && <span className="tv-tag">New</span>}
      </div>
      {listings && on ? <>
        <div className="lp-banner__time">
          <b>{leftWords(ms(on.end), now)}</b>
          <span className="tv-bar lp-banner__bar" role="img" aria-label={`${Math.round(progress(on, now))}% through`}><i style={{ width: `${progress(on, now)}%` }}/></span>
          <span className="tv-muted">{timeRange(ms(on.start), ms(on.end), ' – ')}</span>
        </div>
        {nextWords(next) && <p className="lp-banner__next">{nextWords(next)}</p>}
      </> : <p className="lp-banner__next">No listings for this channel</p>}
    </div>
    <p className="lp-hints lp-banner__hints" aria-hidden="true">
      <span><span className="tv-kbd">▲▼</span>Channel</span><span><span className="tv-kbd">◀</span>On now</span><span><span className="tv-kbd">OK</span>More</span>
    </p>
  </section>
}

export type ControlsProps = {
  state: LivePlaybackState
  channel: LiveChannel
  now: number
  listings: boolean
  /** The programme on now can be recorded here: Record shows. */
  canRecord: boolean
  /** A recording takes this tuner soon, and this person may stop it: Don't record it shows. */
  takeoverCancel: boolean
  quality: string
  onToggle(): void
  onLive(): void
  onRecord(): void
  onCancelTakeover(): void
  onOnNow(): void
  onGuide(): void
  onQuality(): void
}

/**
 * The controls, on OK: the channel and programme at the top left; at the
 * foot Live (or where the picture is), what is left and next over the
 * programme's bar, then Pause, Go live (dimmed while live), Record, and On
 * now, Guide and Quality; one line says what the keys do with them hidden.
 */
export function Controls(p: ControlsProps) {
  const { state, channel, now, listings } = p
  const { now: on, next } = nowAndNext(channel, now)
  const atLive = !state.behind && !state.paused
  const line = [channel.name, listings && on ? timeRange(ms(on.start), ms(on.end), ' – ') : undefined].filter(Boolean).join(' · ')
  return <>
    <header className="lp-head">
      <ChannelChip number={channel.number}/>
      <div className="lp-head__text"><h1 className="lp-head__title">{listings && on ? on.title : channel.name}</h1>{line && <p className="lp-head__line">{line}</p>}</div>
    </header>
    <section className="lp-foot" aria-label="Live controls">
      <div className="lp-foot__line">
        <Badge state={state} tuning={false}/>
        {listings && on && <span className="lp-foot__left"><b>{leftWords(ms(on.end), now)}</b>{nextWords(next) && <span>{nextWords(next)}</span>}</span>}
      </div>
      <div className="lp-bar" role="img" aria-label={atLive ? 'At live' : 'Behind live'}>
        <i className="lp-bar__got" style={{ width: `${listings && on ? progress(on, now) : 100}%` }}/>
        <i className="lp-bar__knob" style={{ left: `${listings && on ? progress(on, now) : 100}%` }}/>
      </div>
      <div className="lp-row" data-group="live-controls">
        <button type="button" className="lp-btn lp-btn--big" data-focus="live/play" data-autofocus aria-label={state.paused ? 'Play' : 'Pause'} onClick={p.onToggle}>
          <LiveIcon name={state.paused ? 'play' : 'pauseBars'}/>
        </button>
        <button type="button" className="lp-btn" data-focus="live/golive" aria-disabled={atLive || undefined} onClick={atLive ? () => showNote(AT_LIVE_NOTE, 'info') : p.onLive}>Go live</button>
        {p.canRecord && <button type="button" className="lp-btn" data-focus="live/record" onClick={p.onRecord}><span className="lp-dot"/>Record</button>}
        {p.takeoverCancel && <button type="button" className="lp-btn" data-focus="live/cancel" onClick={p.onCancelTakeover}>Don’t record it</button>}
        <span className="lp-row__fill"/>
        <button type="button" className="lp-btn" data-focus="live/onnow" onClick={p.onOnNow}>On now</button>
        <button type="button" className="lp-btn" data-focus="live/guide" onClick={p.onGuide}>Guide</button>
        <button type="button" className="lp-btn" data-focus="live/quality" onClick={p.onQuality}>Quality<small>{p.quality}</small></button>
      </div>
      <p className="lp-note">{CONTROLS_NOTE}</p>
    </section>
  </>
}

/** On now, from the left: every channel with what is on, favourites first; OK watches, ▶ or Return closes. */
export function OnNowPanel({ channels, current, listings, now, onWatch }: { channels: readonly LiveChannel[]; current?: string; listings: boolean; now: number; onWatch(c: LiveChannel): void }) {
  const { cards, favourites } = favouritesFirst(onNowCards(channels, now))
  return <aside className="tv-panel tv-panel--left lp-onnow" aria-label="On now">
    <h2 className="tv-panel__title">{listings ? 'On now' : 'Channels'}</h2>
    {favourites && <p className="tv-panel__sub">Favourites first</p>}
    <div className="tv-panel__body">
      <VScroll className="lp-onnow__list" pad={24}>
        {cards.map((c) => {
          const on = c.channel.id === current
          const next = listings ? nowAndNext(c.channel, now).next : undefined
          return <button key={c.channel.id} type="button" className="tv-option lp-crow" data-focus={`onnow/${c.channel.id}`} data-autofocus={on || undefined} aria-current={on || undefined}
            aria-label={on ? `Watching ${channelLabel(c.channel)}` : c.label} onClick={() => { if (!on) onWatch(c.channel) }}>
            <ChannelChip number={c.channel.number}/>
            <span className="lp-crow__text">
              <span className="lp-crow__title">{listings && c.programme ? c.title : c.channel.name}</span>
              <span className="lp-crow__sub">{listings ? onNowLine(c, next) : c.channel.name}</span>
              {listings && c.programme && <span className="tv-bar lp-crow__bar"><i style={{ width: `${Math.round(c.progress * 100)}%` }}/></span>}
            </span>
            {on && <span className="lp-crow__watching">Watching</span>}
          </button>
        })}
      </VScroll>
    </div>
    <p className="lp-hints tv-panel__foot" aria-hidden="true"><span><span className="tv-kbd">OK</span>Watch</span><span><span className="tv-kbd">{BACK}</span>Close</span></p>
  </aside>
}

/** Quality, from the right: Automatic and the fixed steps, kept for this TV; a fixed one tunes again at live. */
export function QualityPanel({ state, onPick }: { state: LivePlaybackState; onPick(q: LiveQuality): void }) {
  return <aside className="tv-panel lp-quality" aria-label="Quality">
    <h2 className="tv-panel__title">Quality</h2>
    <p className="tv-panel__sub">Automatic follows your connection without stopping the picture. A fixed quality tunes {state.channel?.number ?? 'the channel'} again, at live.</p>
    <div className="tv-panel__body">
      {qualityOptions(state.qualities, state.autoStep).map((q) => <button key={q.id} type="button" role="menuitemradio" aria-checked={q.id === state.quality} className="tv-option" data-focus={`quality/${q.id}`}
        data-autofocus={q.id === state.quality || undefined} onClick={() => onPick(q.id)}>
        <span className={`tv-radio${q.id === state.quality ? ' tv-radio--on' : ''}`} aria-hidden="true"/>
        <span className="tv-option__text"><span className="tv-option__label">{q.label}</span>{q.detail && <span className="tv-option__detail">{q.detail}</span>}</span>
      </button>)}
    </div>
    <p className="lp-panel-foot tv-panel__foot">Your choice is kept for this TV. Other devices keep their own.</p>
  </aside>
}

/** A dialog over the picture: a title, why, what to do, and its buttons with the first focused. */
function LiveDialog({ title, why, what, children }: { title: string; why: ReactNode; what?: ReactNode; children: ReactNode }) {
  return <div className="lp-dialog-host">
    <section role="alertdialog" aria-label={title} className="tv-dialog lp-dialog">
      <h2 className="tv-dialog__title">{title}</h2>
      <p className="tv-lede">{why}</p>
      {what && <p className="tv-note">{what}</p>}
      <div className="tv-dialog__actions">{children}</div>
    </section>
  </div>
}

/** Still watching?, with the minute before the tuner is given back. Focus stays on Keep watching. */
export function StillWatching({ channel, onKeep, onStop }: { channel?: LiveChannel; onKeep(): void; onStop(): void }) {
  const [asked] = useState(() => Date.now())
  const now = useNow(1000)
  return <LiveDialog title={channel ? `Still watching ${channel.number}?` : 'Still watching?'} why="Nothing has been pressed for 4 hours."
    what={<>Live TV stops in <b>{stillWatchingWords(PROMPT_GRACE_S - (now - asked) / 1000)}</b> so the tuner is free for someone else.</>}>
    <Button id="prompt/keep" variant="primary" autoFocus onPress={onKeep}>Keep watching</Button><Button id="prompt/stop" onPress={onStop}>Stop</Button>
  </LiveDialog>
}

/** A problem as a dialog over the picture: the tuner was given back, or every tuner is busy. */
export function ProblemDialog({ problem, onAction }: { problem: LiveProblem; onAction(a: ProblemAction): void }) {
  return <LiveDialog title={problem.title} why={problem.body} what={problem.adminNote}>
    {problem.actions.map((a, i) => <Button key={a.id} id={`problem/${a.id}`} variant={i === 0 ? 'primary' : 'ghost'} autoFocus={i === 0} onPress={() => onAction(a.id)}>{a.label}</Button>)}
  </LiveDialog>
}

/**
 * Every tuner is recording: what is recording and until when, when one is
 * free, and what can be watched meanwhile, from its start.
 */
export function BusyDialog({ why, what, watchTitle, onWatch, onRecordings, onBack }: { why: string; what: string; watchTitle: string; onWatch(): void; onRecordings(): void; onBack(): void }) {
  return <LiveDialog title="All tuners are in use" why={why} what={what}>
    <Button id="problem/watch" variant="primary" autoFocus onPress={onWatch}>{`Watch ${watchTitle}`}</Button>
    <Button id="problem/recordings" onPress={onRecordings}>Recordings</Button>
    <Button id="problem/back" onPress={onBack}>Go back</Button>
  </LiveDialog>
}

/** Why the channel can't play, what to do, and other channels on now where another may work. */
export function ProblemPage({ problem, channel, channels, current, admin, now, onAction, onWatch }: {
  problem: LiveProblem; channel?: LiveChannel; channels: readonly LiveChannel[]; current?: string; admin: boolean; now: number; onAction(a: ProblemAction): void; onWatch(c: LiveChannel): void
}) {
  const others = problem.others ? onNowCards(channels, now).filter((c) => c.channel.id !== current) : []
  return <div className={`lp-problem${others.length ? ' lp-problem--others' : ''}`}>
    {channel && <div className="lp-problem__chan"><ChannelChip number={channel.number}/><span>{channel.name}</span></div>}
    <section role="alert" aria-label={problem.title} className="lp-problem__body">
      <h1 className="tv-title">{problem.title}</h1>
      <p className="tv-lede">{problem.body}</p>
      {admin && problem.adminNote && <p className="tv-note">{problem.adminNote}</p>}
      <div className="tv-actions lp-problem__actions">{problem.actions.map((a, i) => <Button key={a.id} id={`problem/${a.id}`} variant={i === 0 ? 'primary' : 'ghost'} autoFocus={i === 0} onPress={() => onAction(a.id)}>{a.label}</Button>)}</div>
    </section>
    {others.length > 0 && <div className="lp-problem__others">
      <Row id="others" title="Other channels on now" variant="live" items={others} itemKey={(c) => c.channel.id}
        renderItem={(c, focusId) => <OnNowCardButton card={c} focusId={focusId} onPress={() => onWatch(c.channel)}/>}/>
    </div>}
  </div>
}

/**
 * A recording will take this tuner soon. It never takes focus: it says
 * when, and that OK has choices (the controls hold Don't record it, for
 * whoever set it and admins); Return dismisses it.
 */
export function TakeoverBanner({ at, now, onDismiss }: { at?: string; now: number; onDismiss(): void }) {
  useKeys((i) => {
    if (i.action === 'back') { onDismiss(); return true }
    return false
  }, true, 'overlay')
  const line = takeoverLine(at, now)
  return <div className="lp-takeover" role="status">
    <span className="lp-dot" aria-hidden="true"/>
    <span><b>{line.strong}</b> {line.soft}</span>
    <span className="lp-hints"><span><span className="tv-kbd">OK</span>Choices</span></span>
  </div>
}

/** "Tuning 7.1…" while the channel starts. */
export function Tuning({ number }: { number?: string }) {
  const [shown, setShown] = useState(false)
  // A quick tune passes without the spinner flashing.
  useEffect(() => { const t = setTimeout(() => setShown(true), 250); return () => clearTimeout(t) }, [])
  if (!shown) return null
  return <div className="lp-tuning" role="status"><span className="tv-spinner tv-spinner--big lp-tuning__spin" aria-hidden="true"/>Tuning {number ?? ''}…</div>
}
