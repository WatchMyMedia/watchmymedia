import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState, type FocusEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { liveTVRecordingQuery, preferencesQuery } from '../../../src/api/queries'
import { fileOn, movieOn, seasonOn } from '../../session/sourceQueries'
import type { Source } from '../../session/peerRegistry'
import { AUTO } from '../../../src/lib/quality'
import type { components } from '../../../src/api/schema'
import { useFocus, useKeys, useNav, useSession } from '../../app/hooks'
import type { KeyInput } from '../../app/keys'
import { useKeyUp } from '../../app/keyUp'
import { recordPlaybackError } from '../../app/playbackErrors'
import { holdScreenSaver } from '../../app/platform'
import type { PlayRequest } from '../../app/routes'
import { useVideoSurface } from '../../app/surface'
import type { FilePlaybackState, FileRequest, Player } from '../../player'
import { PlayerChrome, TitleBlock, type ChromeActions, type ChromeModel, type Opener } from './Chrome'
import { EpisodesPanel } from './Episodes'
import { episodeContext, episodeFile, playableEpisodes, playingEpisode, previousEpisode } from './episodeList'
import { areaOf, backAction, HIDE_CONTROLS_MS, overlayOf, PLAY_PAUSE_ID, RELEASE_AFTER_MS, routeKey, SCRUBBER_ID, SeekHold, seekKey, SKIP_ID, type Overlay } from './keys'
import { adBreaksWords, episodeLine, otherVersion, qualityDetail, recordingLine, shortCode, tracksDetail, type PlayerKind } from './labels'
import { PickerPanel, type Picker } from './Pickers'
import { AdminStop, NextCard, Note, PlayError, PROMPT_FOCUS, Spinner, Starting, StillWatching } from './PlayerOverlays'
import { playbackMedia, PlayerSession, type Heading } from './playerSession'
import { atRecordingEdge } from './scrubber'
import { useSelect } from './select'
import { Cues, SkipButton } from './Ticking'
import './player.css'

type Episode = components['schemas']['Episode']

/** What the screen draws from, without the position and the countdowns, which only the small parts read. */
function modelOf(s: FilePlaybackState) {
  return {
    phase: s.phase, request: s.request, title: s.title, stream: s.stream, mode: s.mode, video: s.video,
    audio: s.audio, quality: s.quality, note: s.note, trickplay: s.trickplay, recording: s.recording,
    subtitles: { options: s.subtitles.options, selected: s.subtitles.selected, online: s.subtitles.online },
    skipId: s.skip?.segmentId,
    next: s.next && { episode: s.next.episode, heading: s.next.heading },
    askStillWatching: s.askStillWatching, adminStop: s.adminStop, error: s.error, failure: s.failure, exited: s.exited,
    segments: s.segments, upNext: s.upNext,
    scrubbing: !!s.scrub, holding: !!s.scrub?.holding,
    writtenMinutes: s.recording?.now ? Math.floor(s.durationMs / 60_000) : undefined,
    durationMs: Math.floor(s.durationMs / 60_000) * 60_000,
  }
}

type Panel = Picker | 'episodes'

/** The focused element's id, read when a key comes (focus events can lag a hidden page). */
const focusedId = () => (document.activeElement as HTMLElement | null)?.dataset?.focus

const waiting = (phase: FilePlaybackState['phase']) => phase === 'loading' || phase === 'buffering'

/** The time a key came, for telling a held seek from a quick press (read in the key handlers, never while drawing). */
const keyTime = () => performance.now()

/**
 * Files, recordings and trailer files over the engine's
 * FilePlayback. One play per screen: it starts when the
 * screen opens and stops when it goes, and the page is see-through over the
 * video while it shows.
 */
export function FilePlayer({ play, request, player, source }: { play: PlayRequest; request: FileRequest; player: Player; source: Source }) {
  const session = useSession()
  const nav = useNav()
  const focus = useFocus()
  // Another server's copy plays from that server, through its own client: its progress is kept there.
  const [ps] = useState(() => new PlayerSession(session, player, source))
  const [hold] = useState(() => new SeekHold())
  const playback = ps.controller
  const m = useSelect(playback, modelOf)

  const [controls, setControls] = useState(true)
  const [panel, setPanel] = useState<Panel>()
  const [serial, setSerial] = useState(0)
  const [focusId, setFocusId] = useState<string>()
  const [shown, setShown] = useState(m.request)
  // Whether this play has got going: until then the screen says where it starts, with Cancel.
  const [started, setStarted] = useState<FileRequest>()
  const leaving = useRef(false)
  const wanted = useRef<string>(undefined)
  const quietTimer = useRef<ReturnType<typeof setTimeout>>(undefined)

  // The person's skip, autoplay and subtitle settings; the play starts without waiting for them.
  const preferences = useQuery(preferencesQuery)
  useEffect(() => { ps.setPreferences(preferences.data) }, [ps, preferences.data])

  useEffect(() => {
    ps.start(play, request)
    return () => { clearTimeout(quietTimer.current); ps.release() }
  }, [ps, play, request])

  // Hidden (Home, another app): report it closed and let the decoder go; back, paused where it was.
  useEffect(() => {
    const onVisibility = () => { if (document.hidden) playback.suspend(); else playback.restore() }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [playback])

  useVideoSurface()
  const busy = m.phase === 'playing' || waiting(m.phase)
  useEffect(() => busy ? holdScreenSaver() : undefined, [busy])

  // The play moved on (the next episode, or one from the Episodes panel): the controls show, and a panel closes.
  if (m.request !== shown) {
    setShown(m.request)
    setControls(true)
    setPanel(undefined)
  }
  if (m.request && started !== m.request && (m.phase === 'playing' || m.phase === 'paused' || m.phase === 'ended' || m.phase === 'error')) setStarted(m.request)
  const starting = !!m.request && started !== m.request && waiting(m.phase)

  // What the file is: an episode's show and season (the Episodes panel, Previous), a trailer's movie, a recording's channel.
  const fileId = m.request?.kind === 'file' ? m.request.fileId : undefined
  const file = useQuery({ ...fileOn(source, fileId ?? 0), enabled: fileId !== undefined })
  const context = fileId !== undefined ? episodeContext(file.data) : undefined
  const season = useQuery({ ...seasonOn(source, context?.showId ?? 0, context?.season ?? 0), enabled: !!context })
  const episodes = playableEpisodes(season.data?.episodes)
  const previous = previousEpisode(episodes, fileId)
  const movie = useQuery({ ...movieOn(source, play.kind === 'trailer' ? play.movieId : 0), enabled: play.kind === 'trailer' })
  // When a file can't play: its movie's other versions, to offer one that may.
  const failedMovie = useQuery({ ...movieOn(source, file.data?.movieId ?? 0), enabled: !!file.data?.movieId && m.phase === 'error' })
  const recording = useQuery({ ...liveTVRecordingQuery(play.kind === 'recording' ? play.id : ''), enabled: play.kind === 'recording' })

  const kind: PlayerKind = m.request?.kind === 'recording' ? 'recording' : m.request?.kind === 'trailer' ? 'trailer' : context ? 'episode' : 'file'
  const heading = headingFor(ps.heading(m), kind, {
    episode: context && playingEpisode(episodes, fileId), context,
    movieTitle: movie.data?.title, recording: recording.data,
  })

  const panelOpen = panel === 'episodes' ? !!context && fileId !== undefined : !!panel
  // Each failure the player gives up on goes in the TV's list (Settings › About), as facts only.
  const recordFailure = useEffectEvent((failure: NonNullable<FilePlaybackState['failure']>) => recordPlaybackError({ media: playbackMedia(kind), ...failure }))
  useEffect(() => { if (m.failure) recordFailure(m.failure) }, [m.failure])

  const overlay: Overlay = overlayOf(m, panelOpen)
  const chromeVisible = controls && overlay === 'none' && !starting
  const skipShown = !!m.skipId && overlay === 'none' && !(m.scrubbing && m.trickplay)

  const leave = () => {
    if (leaving.current) return
    leaving.current = true
    void ps.stop()
    nav.back()
  }
  // The play ended by itself (the end, a trailer's end, a skip to the end): back to where it was opened.
  const ended = useEffectEvent(leave)
  useEffect(() => { if (m.exited && !m.adminStop) ended() }, [m.exited, m.adminStop])

  const showControls = (to = PLAY_PAUSE_ID) => {
    if (!controls) wanted.current = focusedId() === SKIP_ID && skipShown ? SKIP_ID : to
    setControls(true)
  }
  const hideControls = () => {
    setControls(false)
    if (skipShown) wanted.current = SKIP_ID
  }
  const hideLater = useEffectEvent(hideControls)
  const autoHide = chromeVisible && m.phase === 'playing' && !m.holding
  useEffect(() => {
    if (!autoHide) return
    const timer = setTimeout(hideLater, HIDE_CONTROLS_MS)
    return () => clearTimeout(timer)
  }, [autoHide, serial])

  // Focus: what a key asked for, once drawn; an overlay's first button when it comes; Skip when it appears.
  // Never while the screen first draws: the shell gives it its focus memory after its children's effects,
  // so a focus now would be written into the screen below's memory. The shell's own restore focuses Play/Pause.
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true }, [])
  useLayoutEffect(() => {
    if (!mounted.current) return
    const id = wanted.current
    if (!id) return
    wanted.current = undefined
    focus.focusId(id)
  })
  useLayoutEffect(() => {
    if (!mounted.current) return
    if (overlay === 'next') focus.focusId('next/play')
    else if (overlay === 'still' || overlay === 'message') focus.focusId(PROMPT_FOCUS)
  }, [overlay, focus])
  // While it starts, Cancel holds focus; once it plays, Play/Pause.
  // (Not as the screen first draws: the shell's own restore finds Cancel then, by its autofocus.)
  useLayoutEffect(() => {
    if (!mounted.current) return
    if (starting) focus.focusId(STARTING_ID)
    else if (chromeVisible && focusedId() === undefined) focus.focusId(PLAY_PAUSE_ID)
  }, [starting, focus]) // eslint-disable-line react-hooks/exhaustive-deps
  // Skip takes focus as a segment's button comes, not each time it shows again after a seek.
  useLayoutEffect(() => {
    if (mounted.current && m.skipId !== undefined && skipShown) focus.focusId(SKIP_ID)
  }, [m.skipId, focus]) // eslint-disable-line react-hooks/exhaustive-deps
  // Focus never rests on nothing while there is something to hold it: Skip, else Play/Pause.
  useLayoutEffect(() => {
    if (!mounted.current || (document.activeElement !== document.body && document.activeElement !== null)) return
    if (skipShown) focus.focusId(SKIP_ID)
    else if (chromeVisible) focus.focusId(PLAY_PAUSE_ID)
  }, [skipShown, chromeVisible, focus])
  useLayoutEffect(() => { if (mounted.current && m.request) focus.focusId(PLAY_PAUSE_ID) }, [m.request, focus])

  const commit = () => { clearTimeout(quietTimer.current); playback.commitScrub() }
  useKeyUp((action) => { if (hold.up(action)) commit() })

  /** Return closed a panel: focus goes back to the button that opened it. */
  const closePanel = () => {
    if (panel && controls) wanted.current = panel
    setPanel(undefined)
  }

  const playEpisode = (e: Episode, showTitle: string, number: number) => {
    const id = episodeFile(e)
    if (id === undefined) return
    setPanel(undefined)
    wanted.current = PLAY_PAUSE_ID
    ps.playEpisode(id, { title: showTitle || heading.title, line: episodeLine(number, e.number, e.title) })
  }

  const onKey = (input: KeyInput): boolean => {
    playback.noteInput()
    setSerial((n) => n + 1)
    const s = playback.snapshot()
    const over = overlayOf(s, panelOpen)
    // Starting: Return or Stop cancels and leaves; OK presses Cancel; nothing else does anything yet.
    if (starting && over === 'none') {
      if (input.action === 'back' || input.action === 'stop') { leave(); return true }
      return input.action !== 'ok'
    }
    if (input.action === 'back') {
      switch (backAction(over, controls, hold.holding || !!s.scrub?.holding)) {
        case 'leave': leave(); break
        case 'keepWatching': playback.keepWatching(); break
        case 'watchCredits': playback.watchCredits(); setControls(false); break
        case 'cancelScrub': hold.cancel(); playback.cancelScrub(); break
        case 'hideControls': hideControls(); break
        case 'closePanel': closePanel(); break
      }
      return true
    }
    const area = areaOf(focusedId())
    const action = routeKey(input.action, { overlay: over, controls, area, hasNext: !!s.upNext, hasPrevious: !!previous })
    switch (action.kind) {
      case 'pass': return false
      case 'swallow': return true
      case 'show': showControls(); return true
      case 'toControls': focus.focusId(PLAY_PAUSE_ID); return true
      case 'seek': {
        const key = seekKey(input.action)
        if (!key) return true
        if (hold.down(key, action.direction, keyTime()) === 'begin') playback.beginScrub(action.direction)
        else playback.scrubStep(action.direction)
        clearTimeout(quietTimer.current)
        quietTimer.current = setTimeout(() => { if (hold.quiet(keyTime())) commit() }, RELEASE_AFTER_MS + 50)
        if (over === 'none' && area !== 'scrubber') wanted.current = SCRUBBER_ID
        setControls(true)
        return true
      }
      case 'toggle':
        // While seeking, OK plays from the target.
        if (s.scrub) { hold.cancel(); commit(); return true }
        playback.togglePause()
        if (over === 'none') showControls()
        return true
      case 'play': playback.resume(); return true
      case 'pause':
        playback.pause()
        if (over === 'none') showControls()
        return true
      case 'next': playback.playNext(); return true
      case 'previous': if (previous) playEpisode(previous, season.data?.show.title ?? '', context?.season ?? 0); return true
      case 'keepWatching': playback.keepWatching(); return true
      case 'leave': leave(); return true
    }
  }
  useKeys(onKey, overlay !== 'panel')

  const actions: ChromeActions = {
    back: leave,
    // A press and release, as ◀ ▶ are: quick presses add up to one seek.
    step: (direction) => { playback.beginScrub(direction); playback.commitScrub() },
    togglePause: () => playback.togglePause(),
    goToNow: () => { playback.goToNow(); wanted.current = SCRUBBER_ID },
    open: (opener: Opener) => setPanel(opener),
    playNext: () => playback.playNext(),
  }
  const model: ChromeModel = {
    kind, heading,
    playing: m.phase === 'playing' || waiting(m.phase),
    recordingMs: m.writtenMinutes === undefined ? undefined : m.writtenMinutes * 60_000,
    goToNow: !!m.recording?.goToNow, atEdge: atRecordingEdge(m.recording),
    episodes: !!context && !!season.data,
    tracks: m.audio.options.length || m.subtitles.options.length || m.subtitles.online?.available ? tracksDetail(m.audio, m.subtitles) : undefined,
    quality: qualityDetail(m.quality),
    next: !!m.upNext,
    foot: kind === 'recording' ? adBreaksWords(m.segments, !!m.recording?.now) : undefined,
    seeking: m.scrubbing, scrubberFocused: focusId === SCRUBBER_ID,
  }
  const onFocus = (e: FocusEvent) => setFocusId((e.target as HTMLElement).dataset.focus)
  const onBlur = () => setFocusId(undefined)
  const retry = () => { if (m.stream) playback.resume(); else if (m.request) void playback.play(m.request) }
  const backTo = kind === 'trailer' ? 'Back to the movie' : 'Go back'
  // Another version of what failed: the movie's other files, or the episode's.
  const playingEp = context ? playingEpisode(episodes, fileId) : undefined
  const other = fileId === undefined || kind === 'trailer' ? undefined
    : otherVersion(kind === 'episode' ? playingEp?.files : failedMovie.data?.files, fileId)
  const playOther = () => { if (other) ps.playVersion(other.fileId, heading, playback.snapshot().positionMs) }
  // The line at the foot while the credits roll: the show and the episode, short.
  const creditsWhat = context ? [shortCode(context.season, context.episode), playingEp?.title].filter(Boolean).join(' · ') : heading.line

  /** Keys while a panel is open: the viewer is there, and Play/Pause works under it. */
  const onPanelKey = (input: KeyInput): boolean => {
    playback.noteInput()
    const action = routeKey(input.action, { overlay: 'panel', controls, area: 'root', hasNext: false, hasPrevious: false })
    if (action.kind === 'toggle') { playback.togglePause(); return true }
    if (action.kind === 'play') { playback.resume(); return true }
    if (action.kind === 'pause') { playback.pause(); return true }
    if (action.kind === 'leave') { leave(); return true }
    return action.kind === 'swallow'
  }

  return <div className="pl" onFocus={onFocus} onBlur={onBlur}>
    <Cues playback={playback} raised={chromeVisible || overlay === 'next'}/>
    {chromeVisible && <PlayerChrome playback={playback} model={model} actions={actions}/>}
    {(overlay === 'panel' || overlay === 'still' || overlay === 'message' || starting) && <>
      <div className="pl-shade pl-shade--top"/>
      <TitleBlock heading={heading} dim={overlay === 'panel'} paused={overlay === 'panel' && m.phase === 'paused'}/>
    </>}
    {skipShown && <SkipButton playback={playback}/>}
    {chromeVisible && kind === 'recording' && model.atEdge && !skipShown && <div className="pl-atnow" role="status"><span className="pl-dot pl-dot--live"/>At now. More plays as it records.</div>}
    {overlay === 'next' && m.next && <NextCard playback={playback} episode={m.next.episode} title={heading.title} line={creditsWhat}/>}
    {starting && overlay === 'none' && <Starting startMs={m.request && 'startMs' in m.request ? m.request.startMs : undefined} durationMs={m.durationMs} recording={kind === 'recording' && !!m.recording?.starting} onCancel={leave}/>}
    {!starting && waiting(m.phase) && overlay !== 'message' && overlay !== 'still' && <Spinner automatic={m.quality.id === AUTO}/>}
    {m.note && (overlay === 'none' || overlay === 'next') && <Note text={m.note}/>}
    {overlay === 'still' && <StillWatching title={m.upNext?.showTitle ?? heading.title} onKeep={() => playback.keepWatching()} onStop={leave}/>}
    {overlay === 'message' && (m.adminStop
      ? <AdminStop message={m.adminStop.message} onOk={leave}/>
      : <PlayError message={m.error ?? 'Playback couldn’t start. Try again.'} other={other?.label} backLabel={backTo} onOther={playOther} onRetry={retry} onBack={leave}/>)}
    {overlay === 'panel' && panel === 'episodes' && context && fileId !== undefined &&
      <EpisodesPanel playback={playback} context={context} fileId={fileId} onPlay={playEpisode} onClose={closePanel} onKey={onPanelKey}/>}
    {overlay === 'panel' && panel && panel !== 'episodes' &&
      <PickerPanel picker={panel} state={m} playback={playback} onClose={closePanel} onKey={onPanelKey}/>}
  </div>
}

/** The id of Cancel while a play starts. */
const STARTING_ID = 'starting/cancel'

/** The heading, with what the route didn't say filled in from the library: an episode's line in words, a trailer's movie, a recording's channel and times. */
function headingFor(h: Heading, kind: PlayerKind, o: {
  episode?: Episode; context?: { season: number; episode: number }; movieTitle?: string
  recording?: components['schemas']['LiveTVRecording']
}): Heading {
  if (kind === 'trailer') return o.movieTitle && o.movieTitle !== h.title ? { title: h.title, line: o.movieTitle } : h
  if (kind === 'recording') return o.recording ? { title: h.title, line: recordingLine(o.recording) } : h
  if (kind === 'episode' && o.context) return { title: h.title, line: episodeLine(o.context.season, o.context.episode, o.episode?.title ?? '') }
  return h
}
