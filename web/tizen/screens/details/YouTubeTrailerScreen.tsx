import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { movieQuery } from '../../../src/api/queries'
import { commandMessage, listeningMessage, youTubeTrailers, type PlayableTrailer } from '../../../src/lib/trailers'
import { useFocus, useKeys, useNav } from '../../app/hooks'
import { holdScreenSaver } from '../../app/platform'
import type { PlayRequest } from '../../app/routes'
import { ProblemScreen } from '../../ui/States'
import { Backdrop } from './DetailParts'
import { Glyph } from './Glyph'
import {
  embedUrl, initialTrailer, pausedLine, playerShown, readTrailerMessage, seekTarget, trailerEmbed, trailerKey, trailerStep, READY_TIMEOUT_MS, SEEK_MS, type TrailerEvent,
} from './trailerPlayer'
import './details.css'

type YouTubeTrailer = Extract<PlayableTrailer, { kind: 'youtube' }>

// The id this screen gives the player in its messages; there is only ever one.
const PLAYER_ID = 1

/**
 * A movie's YouTube trailer inside the app (TrailerPlayer on the Samsung TV
 * canvas; the Android TV app's TvTrailerScreen). The player route hands it
 * trailer requests with a YouTube key and no file. It tries the movie's
 * YouTube trailers from the one asked for, as the movie lists them.
 */
export function YouTubeTrailerScreen({ play }: { play: Extract<PlayRequest, { kind: 'trailer' }> }) {
  const movie = useQuery(movieQuery(play.movieId))
  const title = movie.data?.title ?? play.title ?? ''
  if (movie.isPending) return <TrailerScreen><Waiting name="Trailer" movie={title}/></TrailerScreen>
  const listed = youTubeTrailers(movie.data?.trailers)
  // Without the movie's list (it couldn't be had), the one asked for.
  const trailers: YouTubeTrailer[] = listed.length ? listed : play.youtubeKey ? [{ kind: 'youtube', key: play.youtubeKey, name: 'Trailer' }] : []
  const start = Math.max(0, trailers.findIndex((t) => t.key === play.youtubeKey))
  return <TrailerPlayer trailers={trailers} start={start} movie={title} backdropPath={movie.data?.backdropPath}/>
}

function TrailerScreen({ children }: { children: ReactNode }) {
  return <div className="dt-trailer">{children}</div>
}

/** While YouTube's player loads out of sight: a spinner, the trailer, the movie and "Plays from YouTube", over the movie's backdrop. */
function Waiting({ name, movie, backdropPath }: { name: string; movie: string; backdropPath?: string }) {
  return <section className="dt-trailer__screen" role="status" aria-label="Loading the trailer">
    <Backdrop path={backdropPath} seed={movie} kind="movie"/>
    <span className="tv-spinner dt-trailer__spin" aria-hidden="true"/>
    <h1 className="dt-trailer__name">{name}</h1>
    <p className="dt-trailer__movie">{movie}</p>
    <p className="dt-trailer__from">Plays from YouTube</p>
  </section>
}

function TrailerPlayer({ trailers, start, movie, backdropPath }: { trailers: YouTubeTrailer[]; start: number; movie: string; backdropPath?: string }) {
  const nav = useNav()
  const focus = useFocus()
  const [s, setS] = useState(() => initialTrailer(start, trailers.length))
  const frame = useRef<HTMLIFrameElement>(null)
  const root = useRef<HTMLDivElement>(null)
  const embed = trailerEmbed()
  const count = trailers.length
  const trailer = trailers[s.index]
  const mounted = s.phase === 'loading' || s.phase === 'playing' || s.phase === 'paused'
  const shown = playerShown(s)
  const dispatch = useCallback((e: TrailerEvent) => setS((prev) => trailerStep(prev, e, count)), [setS, count])
  const post = useCallback((func: string, args: unknown[] = []) => frame.current?.contentWindow?.postMessage(commandMessage(PLAYER_ID, func, args), embed.origin), [embed.origin])

  // Each load: say "listening" until the player answers, then hear its events; no answer in time is unreachable.
  const { index, attempt } = s
  useEffect(() => {
    if (!mounted) return
    let heard = false
    let tries = 0
    const hello = setInterval(() => {
      if (heard || ++tries > 80) clearInterval(hello)
      else frame.current?.contentWindow?.postMessage(listeningMessage(PLAYER_ID), embed.origin)
    }, 250)
    const timeout = setTimeout(() => dispatch({ type: 'timeout', attempt }), READY_TIMEOUT_MS)
    const onMessage = (ev: MessageEvent) => {
      if (!frame.current || ev.source !== frame.current.contentWindow) return
      const read = readTrailerMessage(embed.origin, ev.origin, ev.data)
      if (!read) return
      if (!heard) {
        heard = true
        clearInterval(hello)
        post('addEventListener', ['onError'])
        post('addEventListener', ['onStateChange'])
      }
      if (read.message && read.message.type !== 'other') dispatch({ type: 'message', message: read.message })
      if (read.timeMs !== undefined) dispatch({ type: 'time', timeMs: read.timeMs, durationMs: read.durationMs })
    }
    window.addEventListener('message', onMessage)
    return () => { clearInterval(hello); clearTimeout(timeout); window.removeEventListener('message', onMessage) }
  }, [mounted, index, attempt, embed.origin, dispatch, post])

  // Shown first, then asked to play: at the start and after the pause screen, so nothing plays unseen.
  useEffect(() => { if (s.phase === 'playing' && s.ready) post('playVideo') }, [s.phase, s.ready, post])
  // A trailer that played and ended goes back to the movie, as Return does.
  useEffect(() => { if (s.leave) nav.back() }, [s.leave, nav])
  // The TV stays awake while it plays; nothing plays while the app can't be seen (YouTube's rules).
  useEffect(() => (shown ? holdScreenSaver() : undefined), [shown])
  useEffect(() => {
    const onHide = () => { if (document.hidden) post('pauseVideo') }
    document.addEventListener('visibilitychange', onHide)
    return () => document.removeEventListener('visibilitychange', onHide)
  }, [post])
  // The player never keeps focus: the remote's keys are the screen's, through YouTube's API.
  useEffect(() => {
    const onBlur = () => setTimeout(() => {
      if (document.activeElement !== frame.current) return
      frame.current?.blur()
      root.current?.focus({ preventScroll: true })
    }, 0)
    window.addEventListener('blur', onBlur)
    root.current?.focus({ preventScroll: true })
    return () => window.removeEventListener('blur', onBlur)
  }, [])
  // The problem screens' buttons take focus as they come.
  useEffect(() => {
    if (s.phase === 'refused') focus.restore(['trailer/back'])
    if (s.phase === 'unreachable') focus.restore(['trailer/retry'])
  }, [s.phase, focus])

  useKeys((i) => {
    const key = trailerKey(i.action, s.phase)
    if (key === 'pass') return false
    if (key === 'swallow') return true
    const jump = key === 'back10' || key === 'ahead10'
    // A held play or pause key acts once; held jumps repeat.
    if (i.repeat && !jump) return true
    if (jump) {
      const t = seekTarget(s.timeMs, s.durationMs, key === 'back10' ? -SEEK_MS : SEEK_MS)
      post('seekTo', [t / 1000, true])
      dispatch({ type: 'time', timeMs: t })
    } else if (s.phase === 'paused' && (key === 'toggle' || key === 'play')) {
      dispatch({ type: 'resume' })
    } else if (s.phase === 'playing' && (key === 'toggle' || key === 'pause')) {
      post('pauseVideo')
    }
    return true
  })

  const name = (s.phase === 'refused' ? trailers[0] : trailer)?.name || 'Trailer'
  const src = trailer && (embed.srcdoc ? { srcDoc: embed.srcdoc(trailer.key) } : { src: embedUrl(trailer.key, location.origin) })
  return <div ref={root} className="dt-trailer" tabIndex={-1}>
    {mounted && trailer && <iframe key={`${trailer.key}/${s.attempt}`} ref={frame} {...src} title={`${name}: YouTube’s player`} tabIndex={-1}
      className={`dt-trailer__frame${shown ? ' dt-trailer__frame--shown' : ''}`} allow="autoplay; encrypted-media"
      // A page that sends no Referer is refused by YouTube (error 153); the packaged app's file:// page has none to send, which only the TV can tell.
      referrerPolicy="strict-origin-when-cross-origin"/>}
    {s.phase === 'loading' && <Waiting name={name} movie={movie} backdropPath={backdropPath}/>}
    {s.phase === 'paused' && <section className="dt-trailer__screen" role="status" aria-label="Trailer paused">
      <span className="dt-trailer__ring" aria-hidden="true"><Glyph name="pause"/></span>
      <h1 className="dt-trailer__name">{name}</h1>
      <p className="dt-trailer__movie">{movie}</p>
      <p className="dt-trailer__from">{pausedLine(s.timeMs, s.durationMs)}</p>
      <p className="dt-trailer__keys"><b>OK</b> or <b>Play/Pause</b> to play · <b>◀ ▶</b> or <b>⏪ ⏩</b> 10 s · <b>Return</b> to the movie</p>
    </section>}
    {(s.phase === 'refused' || s.phase === 'unreachable') && <>
      <p className="dt-trailer__head"><b>{name}</b>{movie}</p>
      {s.phase === 'refused'
        ? <ProblemScreen icon="alert" title="This trailer can’t play here" actions={[{ id: 'trailer/back', label: 'Back to the movie', onPress: () => nav.back() }]}>
          Its owner only lets it play on YouTube.
        </ProblemScreen>
        : <ProblemScreen icon="offline" title="Couldn’t reach YouTube" actions={[
          { id: 'trailer/retry', label: 'Try again', onPress: () => dispatch({ type: 'retry' }) },
          { id: 'trailer/back', label: 'Back to the movie', onPress: () => nav.back() },
        ]}>Check the connection and try again.</ProblemScreen>}
    </>}
  </div>
}
