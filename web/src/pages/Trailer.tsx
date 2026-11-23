import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { movieQuery } from '../api/queries'
import { Poster } from '../components/Poster'
import { ArrowLeftIcon, PlayIcon } from '../components/icons'
import { formatRuntime } from '../lib/format'
import { clock } from '../lib/clock'
import { otherTrailers, trailerLine } from '../lib/trailerList'
import {
  PLAYER_ENDED,
  YOUTUBE_EMBED_ORIGIN,
  YOUTUBE_TERMS_URL,
  afterPlayerError,
  commandMessage,
  embedUrl,
  listeningMessage,
  readPlayerMessage,
  watchUrl,
  youTubeTrailers,
} from '../lib/trailers'

// The id this page gives the player in its messages; there is only ever one.
const PLAYER_ID = 1

/**
 * A movie's YouTube trailer, full screen: our bar on
 * top, YouTube's own player in a plain iframe below it, and nothing of ours
 * drawn over the video. Beside it, the movie's other trailers to choose
 * from and the movie itself, to play. When the owner keeps a video on
 * YouTube, the next trailer is tried, then the page says it can't play here.
 */
export function TrailerPage({ movieId }: { movieId: number }) {
  const movie = useQuery(movieQuery(movieId))
  const navigate = useNavigate()
  const frame = useRef<HTMLIFrameElement>(null)
  const [index, setIndex] = useState(0)
  const [refused, setRefused] = useState(false)
  const [blocked, setBlocked] = useState(false)
  const trailers = youTubeTrailers(movie.data?.trailers)
  const playing = refused ? undefined : trailers[index]
  // After every trailer was refused, the page offers the first, the one Trailer meant.
  const shown = refused ? trailers[0] : playing
  const m = movie.data
  const label = m ? `${m.title}${m.year ? ` (${m.year})` : ''}` : ''
  const backLink = { to: '/movies/$movieId', params: { movieId: String(movieId) } } as const

  // Esc goes back to the movie page, as in the player. While the iframe has
  // focus its keys stay inside it, and Esc there leaves YouTube's full screen.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') void navigate(backLink)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navigate, movieId])

  // The page's Content-Security-Policy is the one it loaded with: turning
  // YouTube trailers on in this tab doesn't let it frame YouTube until a reload.
  useEffect(() => {
    const onViolation = (e: SecurityPolicyViolationEvent) => {
      if (e.blockedURI.startsWith(YOUTUBE_EMBED_ORIGIN)) setBlocked(true)
    }
    document.addEventListener('securitypolicyviolation', onViolation)
    return () => document.removeEventListener('securitypolicyviolation', onViolation)
  }, [])

  // The player's events. Like YouTube's IFrame API script, the page says
  // "listening" until the player answers, then subscribes to its errors and
  // state. Only messages from this iframe and YouTube's origin count.
  const key = playing?.key
  const count = trailers.length
  useEffect(() => {
    if (!key) return
    const post = (msg: string) => frame.current?.contentWindow?.postMessage(msg, YOUTUBE_EMBED_ORIGIN)
    let heard = false
    let started = false
    let tries = 0
    const hello = window.setInterval(() => {
      if (heard || ++tries > 120) window.clearInterval(hello)
      else post(listeningMessage(PLAYER_ID))
    }, 250)
    const onMessage = (e: MessageEvent) => {
      if (!frame.current || e.source !== frame.current.contentWindow) return
      const msg = readPlayerMessage(e.origin, e.data)
      if (!msg) return
      if (!heard) {
        heard = true
        window.clearInterval(hello)
        post(commandMessage(PLAYER_ID, 'addEventListener', ['onError']))
        post(commandMessage(PLAYER_ID, 'addEventListener', ['onStateChange']))
      }
      if (msg.type === 'error') {
        const step = afterPlayerError(msg.code, index, count)
        if (step === 'refused') setRefused(true)
        else if (step !== 'stay') setIndex(step.next)
      } else if (msg.type === 'state') {
        // Back to the movie when the trailer ends, as a trailer file does in the player.
        if (msg.state === 1) started = true
        else if (msg.state === PLAYER_ENDED && started) void navigate(backLink)
      }
    }
    // YouTube's rules: nothing plays while it can't be seen.
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') post(commandMessage(PLAYER_ID, 'pauseVideo'))
    }
    window.addEventListener('message', onMessage)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearInterval(hello)
      window.removeEventListener('message', onMessage)
      document.removeEventListener('visibilitychange', onVisibility)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, index, count, navigate, movieId])

  const terms = (
    <>
      Trailers play in YouTube’s own player.{' '}
      <a href={YOUTUBE_TERMS_URL} target="_blank" rel="noreferrer" className="whitespace-nowrap text-neutral-400 underline hover:text-white">
        YouTube’s terms<span className="sr-only"> (opens in a new tab)</span>
      </a>{' '}
      apply.
    </>
  )
  const list = otherTrailers(movie.data?.trailers, shown?.key)
  const file = m?.files?.[0]
  const facts = m ? [m.year ? String(m.year) : '', m.runtimeMin ? formatRuntime(m.runtimeMin) : '', m.contentRating ?? ''].filter(Boolean).join(' · ') : ''

  return (
    <div className="fixed inset-0 z-50 flex flex-col overflow-y-auto bg-black text-white md:overflow-hidden">
      <header className="flex h-16 flex-none items-center gap-3.5 px-3 md:h-[76px] md:px-7">
        <Link {...backLink} aria-label={m ? `Back to ${m.title}` : 'Back'} className="grid size-12 shrink-0 place-items-center rounded-full text-neutral-100 hover:bg-white/15">
          <ArrowLeftIcon size={22} />
        </Link>
        <div className="flex min-w-0 flex-col leading-tight">
          {label && <span className="truncate text-[13.5px] text-neutral-300">{label}</span>}
          <h1 className="truncate text-lg font-[650] md:text-xl">{shown ? shown.name : 'Trailer'}</h1>
        </div>
        {list.position && <span className="ml-auto hidden shrink-0 text-[13px] text-neutral-400 sm:block">{list.position}</span>}
      </header>

      <div className="flex min-h-0 flex-1 flex-col gap-7 px-0 pb-10 md:flex-row md:px-12">
        {/* The player is the largest 16:9 box that fits beside the list; on phones it spans the width. */}
        <div className="flex min-h-0 min-w-0 flex-col justify-start md:flex-1 md:items-center md:[container-type:size]">
          {movie.isPending ? (
            <p className="px-4 text-center text-sm text-white/65">Loading…</p>
          ) : movie.isError ? (
            <p className="px-4 text-center text-sm text-red-400">{movie.error.message}</p>
          ) : !shown ? (
            <p className="px-4 text-center text-sm text-white/65">This movie has no trailer to play.</p>
          ) : blocked ? (
            <Notice title="Reload the page to play this trailer">
              <p className="text-sm text-neutral-400 md:text-[15px]">Trailers from YouTube were turned on after this page opened.</p>
              <button type="button" onClick={() => window.location.reload()} className={secondary}>
                Reload
              </button>
            </Notice>
          ) : playing ? (
            <div className="relative aspect-video w-full overflow-hidden ring-1 ring-inset ring-white/10 md:w-[min(100cqw,calc(100cqh*16/9))] md:rounded-xl">
              <iframe
                key={playing.key}
                ref={frame}
                src={embedUrl(playing.key, window.location.origin)}
                title={`${playing.name}: YouTube player`}
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                allowFullScreen
                // The app's pages send no Referer to other sites; without one YouTube refuses to play (error 153).
                referrerPolicy="strict-origin-when-cross-origin"
                className="absolute inset-0 h-full w-full border-0"
              />
            </div>
          ) : (
            <Notice title="This trailer can’t play here">
              <p className="text-sm text-neutral-400 md:text-[15px]">Its owner only lets it play on YouTube.</p>
              <a href={watchUrl(shown.key)} target="_blank" rel="noreferrer" className={secondary}>
                Watch on YouTube ↗<span className="sr-only"> (opens in a new tab)</span>
              </a>
            </Notice>
          )}
        </div>

        <aside aria-label="This movie’s trailers" className="flex min-w-0 flex-col gap-3.5 px-4 md:w-80 md:flex-none md:overflow-y-auto md:px-0">
          {list.items.length > 1 && (
            <>
              <h2 className="text-xs font-bold uppercase tracking-[.08em] text-dim">More trailers</h2>
              <ul className="-mx-2 flex flex-col gap-1.5">
                {list.items.map((t) => (
                  <li key={t.id}>
                    {t.kind === 'youtube' ? (
                      <button
                        type="button"
                        aria-pressed={t.current}
                        onClick={() => {
                          setRefused(false)
                          setIndex(t.index)
                        }}
                        className={`flex w-full items-center gap-3 rounded-[10px] border p-2 text-left ${t.current ? 'border-accent bg-raise' : 'border-transparent hover:bg-surface-2'}`}
                      >
                        <TrailerTile />
                        <span className="flex min-w-0 flex-col leading-snug">
                          <span className="truncate text-sm font-semibold">{t.name}</span>
                          <span className="text-[12.5px] text-neutral-400">{trailerLine(t)}</span>
                        </span>
                      </button>
                    ) : (
                      <Link
                        to="/play/$fileId"
                        params={{ fileId: String(t.fileId) }}
                        search={{ title: m ? `${m.title} · ${t.name}` : t.name, back: `/movies/${movieId}/trailer`, trailer: true }}
                        className="flex w-full items-center gap-3 rounded-[10px] border border-transparent p-2 text-left hover:bg-surface-2"
                      >
                        <TrailerTile length={t.durationMs ? clock(t.durationMs) : undefined} />
                        <span className="flex min-w-0 flex-col leading-snug">
                          <span className="truncate text-sm font-semibold">{t.name}</span>
                          <span className="text-[12.5px] text-neutral-400">{trailerLine(t)}</span>
                        </span>
                      </Link>
                    )}
                  </li>
                ))}
              </ul>
            </>
          )}
          {shown && (
            <a href={watchUrl(shown.key)} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 self-start text-[13.5px] text-neutral-300 hover:text-accent">
              Watch on YouTube ↗<span className="sr-only"> (opens in a new tab)</span>
            </a>
          )}
          {m && (
            <div className="mt-2 flex flex-col gap-3 md:mt-auto">
              <div className="panel flex items-center gap-3 p-3">
                <span className="w-[54px] shrink-0">
                  <Poster path={m.posterPath} size="w154" alt="" className="rounded-md" />
                </span>
                <span className="flex min-w-0 flex-col leading-snug">
                  <b className="truncate font-semibold">{m.title}</b>
                  {facts && <span className="text-[12.5px] text-neutral-400">{facts}</span>}
                  <span className="text-[12.5px] text-neutral-400">In your library</span>
                </span>
              </div>
              {file && (
                <Link to="/play/$fileId" params={{ fileId: String(file.id) }} search={{ title: m.title, back: `/movies/${movieId}` }} className="btn btn-primary btn-lg">
                  <PlayIcon />
                  Play the movie
                </Link>
              )}
              {playing && !blocked && <p className="text-xs leading-normal text-dim">{terms}</p>}
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

/** A trailer's tile in the list: no picture of YouTube's is fetched, so a quiet tile with its length. */
function TrailerTile({ length }: { length?: string }) {
  return (
    <span className="relative grid aspect-video w-28 shrink-0 place-items-center rounded-md bg-surface-2 text-neutral-400 ring-1 ring-inset ring-white/[.07]">
      <PlayIcon size={18} />
      {length && <span className="absolute bottom-1.5 right-1.5 rounded-[5px] bg-ground/80 px-[5px] py-px text-[11px] font-bold text-neutral-100">{length}</span>}
    </span>
  )
}

const secondary = 'btn mt-1.5 md:mt-2'

/** What shows in the player's place when it can't play: the same 16:9 box, our words in it. */
function Notice({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex aspect-video w-full flex-col items-center justify-center gap-2 bg-ground p-4 text-center md:w-[min(100cqw,calc(100cqh*16/9))] md:gap-3 md:rounded-xl md:p-8">
      <h2 className="text-[17px] font-semibold md:text-[22px]">{title}</h2>
      {children}
    </div>
  )
}
