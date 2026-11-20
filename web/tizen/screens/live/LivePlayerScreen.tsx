import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState, useSyncExternalStore, type CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { LiveChannel } from '../../../src/api/liveTV'
import { liveTVCapabilitiesQuery } from '../../../src/api/queries'
import { cancelRecording } from '../../../src/api/recordings'
import { useFocus, useKeys, useNav, useSession } from '../../app/hooks'
import { holdScreenSaver } from '../../app/platform'
import { useVideoSurface } from '../../app/surface'
import { recordPlaybackError } from '../../app/playbackErrors'
import type { PlayRequest } from '../../app/routes'
import type { LivePlayback } from '../../player'
import { ProblemScreen } from '../../ui/States'
import { placeholderTone } from '../../ui/tones'
import { useNow } from './clock'
import { GoToChannelPanel } from './GoToChannel'
import { useRecordingAction } from './liveData'
import { liveProblem, loggedKind, lowerQuality, type ProblemAction } from './liveProblems'
import { registerTuner, rememberChannel, useLivePlayback } from './livePlayback'
import { ChannelBanner, Controls, OnNowPanel, ProblemDialog, ProblemPage, QualityPanel, StillWatching, TakeoverBanner, TopBar, Tuning } from './LivePlayerParts'
import { BANNER_MS, CONTROLS_MS, liveBackAction, liveKeyAction, numberKey, type LivePanel } from './liveRemote'
import { matchWords, qualityShort, streamLine } from './liveWords'
import './live.css'

/**
 * The Live TV player, which the player route
 * draws for a channel: one LivePlayback for the session, the channel
 * banner, the controls, On now, Quality, Go to channel, Still watching and
 * the problems.
 */
export function LivePlayerScreen({ play }: { play: Extract<PlayRequest, { kind: 'live' }> }) {
  const nav = useNav()
  const engine = useLivePlayback()
  if (!engine) return <div className="tv-page"><ProblemScreen icon="alert" title="This can’t play Live TV" actions={[{ id: 'live/back', label: 'Back', onPress: () => nav.back() }]}>
    Live TV plays in the WatchMyMedia app on a Samsung TV.
  </ProblemScreen></div>
  return <LivePlayer channelId={play.channelId} live={engine.live} preview={engine.preview}/>
}

const SWITCH_WORDS: Record<string, string> = {
  preparing: 'Preparing another quality while this channel keeps playing.',
  waiting_capacity: 'Keeping this quality until the server has room to improve it.',
  source_wait: 'Waiting for new broadcast segments.',
  failed: 'Keeping the current quality after a switch could not finish.',
}

function LivePlayer({ channelId, live, preview }: { channelId: string; live: LivePlayback; preview: boolean }) {
  const nav = useNav()
  const focus = useFocus()
  const session = useSession()
  const state = useSyncExternalStore(live.subscribe, live.snapshot)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const listings = !!caps.data?.guide
  const now = useNow()
  const [shownControls, setControls] = useState(false)
  const [panel, setPanel] = useState<LivePanel>()
  // The channel whose banner has had its time.
  const [bannerDone, setBannerDone] = useState<string>()
  const [lastKey, setLastKey] = useState(() => Date.now())
  const want = useRef<string | undefined>(undefined)
  const cancel = useRecordingAction(cancelRecording)

  // Tune on open; give the tuner back on leaving.
  useEffect(() => {
    void live.open(channelId)
    return () => { void live.stop() }
  }, [live, channelId])
  // A reminder's Watch changes channel here rather than opening another player.
  useEffect(() => registerTuner((id) => { setPanel(undefined); void live.open(id) }), [live])
  // The tuner is given back while the app is in the background, and the channel tuned again on return.
  useEffect(() => {
    const change = () => { if (document.hidden) live.suspend(); else live.restore() }
    document.addEventListener('visibilitychange', change)
    return () => document.removeEventListener('visibilitychange', change)
  }, [live])

  const channel = state.channel
  const terminal = state.phase === 'error' || state.phase === 'ended'
  const playing = state.phase === 'playing'
  const tuning = state.phase === 'loading' || state.phase === 'tuning'
  const problem = terminal && state.failure ? liveProblem(state.failure, {
    number: channel?.number, admin: session.isAdmin, quality: state.quality, pauseMinutes: caps.data?.pauseSeconds ? Math.max(1, Math.round(caps.data.pauseSeconds / 60)) : undefined,
  }) : undefined
  const prompt = state.askStillWatching
  // Each failure goes in Settings › About's log of the last playback errors, as a code.
  // The engine words a failure afresh on each tick, so it is logged by what it is, once until it clears.
  const failed = state.failure ? `${state.failure.reason ?? ''}:${state.failure.title}` : ''
  const logFailure = useEffectEvent(() => {
    const kind = state.failure && loggedKind(state.failure.reason)
    if (kind) recordPlaybackError({ media: 'live', kind, channel: channel?.number })
  })
  useEffect(() => { if (failed) logFailure() }, [failed])
  const controls = shownControls
  const banner = !!channel && channel.id !== bannerDone

  // The banner shows for a while on each channel; the controls hide after a quiet spell while it plays.
  useEffect(() => { if (channel?.id) rememberChannel(channel.id) }, [channel?.id])
  // The banner stays while the channel tunes, and goes a few seconds after it plays.
  useEffect(() => {
    const id = channel?.id
    if (!id || !playing) return
    const timer = setTimeout(() => setBannerDone(id), BANNER_MS)
    return () => clearTimeout(timer)
  }, [channel?.id, playing])
  useEffect(() => {
    if (!controls || panel || !playing || state.paused || prompt) return
    const timer = setTimeout(() => { setControls(false); (document.activeElement as HTMLElement | null)?.blur() }, Math.max(0, lastKey + CONTROLS_MS - Date.now()))
    return () => clearTimeout(timer)
  }, [controls, panel, playing, state.paused, prompt, lastKey])

  useVideoSurface(!preview && playing)
  useEffect(() => (tuning || (playing && !state.paused) ? holdScreenSaver() : undefined), [tuning, playing, state.paused])

  const show = (id: string) => { want.current = id }
  const showControls = () => { setControls(true); show('live/play') }
  const hideControls = () => { setControls(false); (document.activeElement as HTMLElement | null)?.blur() }
  const openPanel = (p: LivePanel) => {
    setPanel(p)
    show(p === 'onNow' ? `onnow/${channel?.id}` : p === 'quality' ? `quality/${state.quality}` : 'goto/watch')
  }
  const closePanel = () => {
    const was = panel
    setPanel(undefined)
    if (was === 'number') live.clearNumber()
    if (controls) show(was === 'onNow' ? 'live/onnow' : was === 'quality' ? 'live/quality' : 'live/play')
    else (document.activeElement as HTMLElement | null)?.blur()
  }
  const watch = (c: LiveChannel) => { setPanel(undefined); setControls(false); void live.open(c.id) }
  const toGuide = () => nav.replace({ name: 'liveGuide', channelId: channel?.id })
  const leave = () => nav.back()

  // A problem or Still watching takes focus on its first button.
  const asking = problem ? `problem/${problem.actions[0]?.id}` : prompt ? 'prompt/keep' : undefined
  useLayoutEffect(() => { if (asking) focus.focusId(asking) }, [focus, asking])
  // Focus goes where the last change asked, once it is drawn.
  useLayoutEffect(() => {
    const id = want.current
    if (id && focus.focusId(id)) want.current = undefined
  })

  useKeys((i) => {
    setLastKey(Date.now())
    live.noteInput()
    const s = { panel, controls, terminal: !!problem, prompt }
    if (i.action === 'back') {
      switch (liveBackAction(s)) {
        case 'leave': leave(); break
        case 'keepWatching': live.keepWatching(); showControls(); break
        case 'closePanel': closePanel(); break
        case 'hideControls': hideControls(); break
      }
      return true
    }
    const action = liveKeyAction(i.action, s)
    switch (action) {
      case 'pass': {
        const k = panel === 'number' ? numberKey(i.action, i.digit) : undefined
        if (k) { live.typeDigit(k); return true }
        return false
      }
      case 'swallow': return true
      case 'controls': showControls(); return true
      case 'onNow': if (panel === 'onNow') closePanel(); else openPanel('onNow'); return true
      case 'quality': openPanel('quality'); return true
      case 'number': {
        const k = numberKey(i.action, i.digit)
        if (!k) return true
        live.clearNumber()
        live.typeDigit(k)
        openPanel('number')
        return true
      }
      // A new channel shows its banner over the picture, without the controls.
      case 'channelUp': if (!i.repeat) { setPanel(undefined); hideControls(); live.channelUp() } return true
      case 'channelDown': if (!i.repeat) { setPanel(undefined); hideControls(); live.channelDown() } return true
      case 'previousChannel': setPanel(undefined); hideControls(); live.previousChannel(); return true
      // Pausing brings the controls up, with Go live.
      case 'togglePause': if (!state.paused && !controls) showControls(); live.togglePause(); return true
      case 'play': live.resume(); return true
      case 'pause': if (!controls) showControls(); live.pause(); return true
      case 'leave': leave(); return true
      case 'guide': toGuide(); return true
      case 'keepWatching': live.keepWatching(); showControls(); return true
    }
  })

  const act = (a: ProblemAction) => {
    switch (a) {
      case 'retry': case 'tuneAgain': return live.retry()
      case 'guide': case 'another': return toGuide()
      case 'lower': { const q = lowerQuality(state.quality); if (q) live.setQuality(q); return }
      case 'home': return nav.openSection({ name: 'home' })
      case 'watchRecording': {
        const id = state.failure?.recordingId
        if (id) nav.replace({ name: 'player', play: { kind: 'recording', id, startMs: 0, title: state.failure?.recordingTitle } })
        return
      }
      case 'keepWatching': return live.keepWatching()
      case 'stop': return leave()
    }
  }

  const tone = placeholderTone(channel?.now?.title ?? channel?.name ?? '')
  // The preview has no picture to show: the board's stand-in. On the TV the page is clear over AVPlay's plane.
  const backdrop: CSSProperties | undefined = preview || !playing ? { '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties : undefined
  const showBanner = !!channel && !problem && !prompt && (banner || controls) && panel !== 'onNow'
  const controlsShown = playing && controls && !panel && !problem && !prompt
  const number = state.number
  const typed = number ? matchWords(state.channels, number.digits, now) : undefined
  const note = state.paused && state.pauseLeftSeconds !== undefined ? `Paused. The tuner is given back in ${Math.floor(state.pauseLeftSeconds / 60)}:${String(state.pauseLeftSeconds % 60).padStart(2, '0')}.`
    : state.note ?? (state.session?.automatic && state.session.switchStatus && state.session.switchStatus !== 'ready' ? SWITCH_WORDS[state.session.switchStatus] : undefined)

  if (problem && !problem.dialog) return <div className="live-player live-player--problem">
    <ProblemPage problem={problem} channel={channel} channels={state.channels} current={channel?.id} admin={session.isAdmin} now={now} onAction={act} onWatch={watch}/>
  </div>
  return <div className={`live-player${backdrop ? ' live-player--backdrop' : ''}${tuning ? ' live-player--tuning' : ''}`} style={backdrop}>
    {(showBanner || controlsShown) && <div className="live-player__shade live-player__shade--top" aria-hidden="true"/>}
    {(showBanner || controlsShown || panel === 'number') && <div className="live-player__shade live-player__shade--bottom" aria-hidden="true"/>}
    {tuning && <Tuning number={channel?.number}/>}
    {(showBanner || controlsShown) && <TopBar controls={controlsShown} now={now} stream={streamLine({ tuning, quality: state.quality, autoStep: state.autoStep, mode: state.session?.mode })}/>}
    {note && !problem && <div className="live-note live-note--player" role="status">{note}</div>}
    {showBanner && channel && <ChannelBanner channel={channel} now={now} listings={listings} raised={controlsShown} badge={tuning ? 'tuning' : state.behind || state.paused ? 'behind' : 'live'}/>}
    {controlsShown && <Controls state={state} quality={qualityShort(state.quality, state.autoStep)} onToggle={() => live.togglePause()} onLive={() => live.goLive()}
      onDown={() => live.channelDown()} onUp={() => live.channelUp()} onOnNow={() => openPanel('onNow')} onQuality={() => openPanel('quality')} onGuide={toGuide}/>}
    {panel === 'onNow' && !problem && <OnNowPanel channels={state.channels} current={channel?.id} listings={listings} now={now} onWatch={watch}/>}
    {panel === 'quality' && !problem && <QualityPanel state={state} onPick={(q) => { live.setQuality(q); closePanel() }}/>}
    {panel === 'number' && !problem && typed && number && <GoToChannelPanel digits={number.digits} text={typed.text} match={typed.match}
      onKey={(k) => live.typeDigit(k)} onCancel={closePanel} onWatch={() => { if (live.commitNumber()) { setPanel(undefined); setControls(false) } }}/>}
    {state.takeover && !problem && <TakeoverBanner words={state.takeover.words} canCancel={state.takeover.canCancel}
      onDismiss={() => live.dismissTakeover()} onCancel={() => { const id = state.takeover?.recordingId; live.dismissTakeover(); if (id) cancel.mutate(id) }}/>}
    {prompt && !problem && <StillWatching channel={channel} onKeep={() => { live.keepWatching(); showControls() }} onStop={leave}/>}
    {problem?.dialog && <ProblemDialog problem={problem} onAction={act}/>}
  </div>
}
