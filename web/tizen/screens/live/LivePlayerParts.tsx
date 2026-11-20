import { useEffect, useState } from 'react'
import type { LiveChannel, LiveQuality } from '../../../src/api/liveTV'
import { episodeLine, nowAndNext, progress, timeOfDay, timeRange } from '../../../src/lib/liveGuide'
import type { LivePlaybackState } from '../../player'
import { useKeys } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Row } from '../../ui/Row'
import { VScroll } from '../../ui/VScroll'
import { ChannelLogo, ProgrammeArt } from './ChannelArt'
import { useNow } from './clock'
import { LiveIcon } from './LiveIcon'
import type { LiveProblem, ProblemAction } from './liveProblems'
import { stillWatchingWords } from './liveProblems'
import { PROMPT_GRACE_S } from './liveRemote'
import { channelLabel, clockWords, leftWords, onNowCards, qualityOptions } from './liveWords'
import { OnNowCardButton } from './OnNowRow'

const ms = (iso: string) => Date.parse(iso)

/** The top of the picture: the keys that work now, and the stream and the time. */
export function TopBar({ controls, stream, now }: { controls: boolean; stream: string; now: number }) {
  return <>
    <p className="live-hints" aria-hidden="true">
      {controls ? <>
        <span><b>◀ ▶</b> Move</span><span><b>OK</b> Choose</span><span><b>CH ▲ ▼</b> Change channel</span><span><b>Return</b> Hide controls</span>
      </> : <>
        <span><b>▲ ▼</b> or <b>CH ▲ ▼</b> Change channel</span><span><b>0–9</b> Go to channel</span><span><b>◀</b> On now</span><span><b>OK</b> Controls</span>
      </>}
    </p>
    <div className="live-topright"><span className="live-pill">{stream}</span><b>{clockWords(now)}</b></div>
  </>
}

/** The channel banner: logo, number and name, the programme's picture, title and tags, the episode line, times with progress, and Next. */
export function ChannelBanner({ channel, now, listings, badge, raised }: { channel: LiveChannel; now: number; listings: boolean; badge: 'live' | 'behind' | 'tuning'; raised: boolean }) {
  const { now: on, next } = nowAndNext(channel, now)
  const badgeEl = <span className={`live-badge live-badge--big${badge === 'live' ? '' : ' live-badge--grey'}`}><span className="live-dot"/>{badge === 'live' ? 'LIVE' : badge === 'tuning' ? 'TUNING' : 'BEHIND LIVE'}</span>
  return <section className={`live-banner${raised ? ' live-banner--raised' : ''}`} role="status" aria-label="Channel">
    <div className="live-banner__chan">
      <ChannelLogo channel={channel} className="live-banner__logo"/>
      <span className="live-banner__num">{channel.number}</span>
      <span className="live-banner__name">{channel.name}</span>
    </div>
    {listings && on && <div className="live-banner__pic"><ProgrammeArt channel={channel} programme={on} size="banner" overlay={false}/></div>}
    <div className="live-banner__body">
      {listings && on ? <>
        <div className="live-banner__titleline">
          <span className="live-banner__title">{on.title}</span>
          {on.new && <span className="live-tag">New</span>}
          {on.live && <span className="live-tag live-tag--live">Live</span>}
          {on.hd && <span className="live-tag live-tag--hd">HD</span>}
          {badgeEl}
        </div>
        {episodeLine(on) && <span className="live-banner__ep">{episodeLine(on)}</span>}
        <div className="live-banner__time">
          <span>{timeRange(ms(on.start), ms(on.end))}</span>
          <span className="live-thin" role="img" aria-label={`${Math.round(progress(on, now))}% through`}><span style={{ width: `${progress(on, now)}%` }}/></span>
          <span>{leftWords(ms(on.end), now)}</span>
        </div>
        {next && <span className="live-banner__next">Next: {timeOfDay(ms(next.start))} {next.title}</span>}
      </> : <>
        <div className="live-banner__titleline"><span className="live-banner__title">{channel.name}</span>{badgeEl}</div>
        <span className="live-banner__next">No listings for this channel</span>
      </>}
    </div>
  </section>
}

export type ControlsProps = {
  state: LivePlaybackState
  quality: string
  onToggle(): void
  onLive(): void
  onDown(): void
  onUp(): void
  onOnNow(): void
  onQuality(): void
  onGuide(): void
}

/** The controls: where the picture is against live, then play or pause, Go live, CH ▼ ▲, On now, Quality and Full guide. */
export function Controls({ state, quality, onToggle, onLive, onDown, onUp, onOnNow, onQuality, onGuide }: ControlsProps) {
  const atLive = !state.behind && !state.paused
  const channels = state.channels.filter((c) => c.available && c.enabled)
  const i = channels.findIndex((c) => c.id === state.channel?.id)
  const prev = channels[(i - 1 + channels.length) % channels.length]
  const next = channels[(i + 1) % channels.length]
  return <>
    <div className="live-livebar">
      {atLive ? <span className="live-livebar__at"><span className="live-dot live-dot--red"/>LIVE</span> : <span className="live-livebar__behind">{state.paused ? 'Paused' : 'Behind live'}</span>}
      <span className="live-livebar__track" role="img" aria-label={atLive ? 'At live' : 'Behind live, within what the server keeps'}><span style={{ width: atLive ? '100%' : '85%' }}/></span>
      <span className="live-livebar__end">Live</span>
    </div>
    <div className="live-controls" data-row>
      <button type="button" className="live-cbtn live-cbtn--big" data-focus="live/play" data-autofocus aria-label={state.paused ? 'Play' : 'Pause'} onClick={onToggle}>
        <LiveIcon name={state.paused ? 'play' : 'pauseBars'}/>
      </button>
      {!atLive && <button type="button" className="live-cbtn" data-focus="live/golive" onClick={onLive}><span className="live-dot live-dot--red"/>Go live</button>}
      <span className="live-controls__gap"/>
      {prev && <button type="button" className="live-cbtn" data-focus="live/down" aria-label={`Channel down, ${channelLabel(prev)}`} onClick={onDown}><LiveIcon name="down"/>Ch <small>{prev.number}</small></button>}
      {next && <button type="button" className="live-cbtn" data-focus="live/up" aria-label={`Channel up, ${channelLabel(next)}`} onClick={onUp}><LiveIcon name="up"/>Ch <small>{next.number}</small></button>}
      <button type="button" className="live-cbtn" data-focus="live/onnow" onClick={onOnNow}><LiveIcon name="guide"/>On now</button>
      <button type="button" className="live-cbtn" data-focus="live/quality" onClick={onQuality}><LiveIcon name="quality"/>Quality <small>{quality}</small></button>
      <span className="live-controls__flex"/>
      <button type="button" className="live-cbtn" data-focus="live/guide" onClick={onGuide}><LiveIcon name="chevronLeft"/>Full guide</button>
    </div>
  </>
}

/** On now, from the left: every channel with what is on; OK watches, ▶ or Return closes. */
export function OnNowPanel({ channels, current, listings, now, onWatch }: { channels: readonly LiveChannel[]; current?: string; listings: boolean; now: number; onWatch(c: LiveChannel): void }) {
  const cards = onNowCards(channels, now)
  return <aside className="live-onnow-panel" aria-label="On now">
    <div className="live-onnow-panel__head"><h2>{listings ? `On now · ${clockWords(now)}` : 'Channels'}</h2></div>
    <VScroll className="live-onnow-panel__list" pad={16}>
      {cards.map((c) => {
        const on = c.channel.id === current
        return <button key={c.channel.id} type="button" className={`live-crow${on ? ' live-crow--on' : ''}`} data-focus={`onnow/${c.channel.id}`} aria-current={on || undefined}
          aria-label={on ? `Watching ${channelLabel(c.channel)}` : c.label} onClick={() => { if (!on) onWatch(c.channel) }}>
          {listings ? <ProgrammeArt channel={c.channel} programme={c.programme} size="thumb"/> : <b className="live-crow__num">{c.channel.number}</b>}
          <span className="live-crow__text">
            <span className="live-crow__title">{listings && c.programme ? c.title : c.channel.name}</span>
            {listings && c.programme && <span className="live-thin"><span style={{ width: `${Math.round(c.progress * 100)}%` }}/></span>}
            <span className="live-crow__sub">{!listings || !c.programme ? 'No listings' : `${c.channel.name} · ${c.line}`}</span>
          </span>
          {on && <span className="live-crow__watching">Watching</span>}
        </button>
      })}
    </VScroll>
    <p className="live-onnow-panel__foot">OK watches the channel. ▶ or Return closes this list.</p>
  </aside>
}

/** Quality, from the right: Automatic and the fixed steps; a fixed one tunes again at live. */
export function QualityPanel({ state, onPick }: { state: LivePlaybackState; onPick(q: LiveQuality): void }) {
  return <aside className="live-quality" aria-label="Quality">
    <h2>Quality</h2>
    <p className="live-quality__lede">Automatic follows your connection and changes without stopping the picture. Choosing a fixed quality tunes {state.channel?.number ?? 'the channel'} again, at live.</p>
    {qualityOptions(state.qualities, state.autoStep).map((q) => <button key={q.id} type="button" role="radio" aria-checked={q.id === state.quality} className="live-qrow" data-focus={`quality/${q.id}`}
      data-autofocus={q.id === state.quality || undefined} onClick={() => onPick(q.id)}>
      <span className={`live-radio${q.id === state.quality ? ' live-radio--on' : ''}`} aria-hidden="true"><span/></span>
      <span className="live-qrow__text"><span className="live-qrow__label">{q.label}</span>{q.detail && <span className="live-qrow__detail">{q.detail}</span>}</span>
    </button>)}
    <p className="live-quality__foot">Return closes this panel.</p>
  </aside>
}

/** Still watching?, with the minute before the tuner is given back. */
export function StillWatching({ channel, onKeep, onStop }: { channel?: LiveChannel; onKeep(): void; onStop(): void }) {
  const [asked] = useState(() => Date.now())
  const now = useNow(1000)
  return <div className="live-prompt-host">
    <section role="alertdialog" aria-label="Still watching?" className="live-prompt">
      {channel && <span className="live-prompt__kicker">{channelLabel(channel)} · on for 4 hours</span>}
      <h2>Still watching?</h2>
      <p>Nothing has been pressed for 4 hours. Live TV stops in <b>{stillWatchingWords(PROMPT_GRACE_S - (now - asked) / 1000)}</b> so the tuner is free for someone else. Any press counts as an answer.</p>
      <div className="tv-actions"><Button id="prompt/keep" variant="primary" autoFocus onPress={onKeep}>Keep watching</Button><Button id="prompt/stop" onPress={onStop}>Stop</Button></div>
    </section>
  </div>
}

/** A problem as a dialog over the picture: the tuner was given back after a pause or four quiet hours. */
export function ProblemDialog({ problem, onAction }: { problem: LiveProblem; onAction(a: ProblemAction): void }) {
  return <div className="live-prompt-host">
    <section role="alertdialog" aria-label={problem.title} className="live-prompt">
      <h2>{problem.title}</h2>
      <p>{problem.body}</p>
      <div className="tv-actions">{problem.actions.map((a, i) => <Button key={a.id} id={`problem/${a.id}`} variant={i === 0 ? 'primary' : 'ghost'} autoFocus={i === 0} onPress={() => onAction(a.id)}>{a.label}</Button>)}</div>
    </section>
  </div>
}

/** LiveProblems: why the channel can't play, what to do, and other channels on now where another may work. */
export function ProblemPage({ problem, channel, channels, current, admin, now, onAction, onWatch }: {
  problem: LiveProblem; channel?: LiveChannel; channels: readonly LiveChannel[]; current?: string; admin: boolean; now: number; onAction(a: ProblemAction): void; onWatch(c: LiveChannel): void
}) {
  const others = problem.others ? onNowCards(channels, now).filter((c) => c.channel.id !== current) : []
  return <div className={`live-problem${others.length ? ' live-problem--others' : ''}`}>
    {channel && <div className="live-problem__chan"><b>{channel.number}</b><span>{channel.name}</span></div>}
    <section role="alert" aria-label={problem.title} className="live-problem__body">
      <span className="live-problem__icon"><LiveIcon name={problem.icon}/></span>
      <h1>{problem.title}</h1>
      <p>{problem.body}</p>
      {admin && problem.adminNote && <p className="live-problem__note">{problem.adminNote}</p>}
      <div className="tv-actions">{problem.actions.map((a, i) => <Button key={a.id} id={`problem/${a.id}`} variant={i === 0 ? 'primary' : 'ghost'} autoFocus={i === 0} onPress={() => onAction(a.id)}>{a.label}</Button>)}</div>
    </section>
    {others.length > 0 && <div className="live-problem__others">
      <Row id="others" title="Other channels on now" variant="live" items={others} itemKey={(c) => c.channel.id}
        renderItem={(c, focusId) => <OnNowCardButton card={c} focusId={focusId} onPress={() => onWatch(c.channel)}/>}/>
    </div>}
  </div>
}

/**
 * A recording will take this tuner soon (DVR's takeover). It never takes
 * focus: Hold OK is Don't record it (for whoever set it and admins) and
 * Return dismisses it.
 */
export function TakeoverBanner({ words, canCancel, onCancel, onDismiss }: { words: string; canCancel: boolean; onCancel(): void; onDismiss(): void }) {
  useKeys((i) => {
    if (i.action === 'back') { onDismiss(); return true }
    if (i.action === 'hold' && canCancel) { onCancel(); return true }
    return false
  }, true, 'overlay')
  return <div className="live-takeover" role="status">
    <p>{words}</p>
    <div className="live-takeover__hints">
      {canCancel && <span className="live-hint"><span className="tv-kbd">Hold OK</span>Don’t record it</span>}
      <span className="live-hint"><span className="tv-kbd">Return</span>Dismiss</span>
    </div>
  </div>
}

/** "Tuning 7.1…" while the channel starts. */
export function Tuning({ number }: { number?: string }) {
  const [shown, setShown] = useState(false)
  // A quick tune passes without the spinner flashing.
  useEffect(() => { const t = setTimeout(() => setShown(true), 250); return () => clearTimeout(t) }, [])
  if (!shown) return null
  return <div className="live-tuning" role="status"><span className="tv-spinner live-tuning__spin" aria-hidden="true"/>Tuning {number ?? ''}…</div>
}
