import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { movieQuery } from '../api/queries'
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
 * A movie's YouTube trailer, full screen (PLAN-TRAILERS.md §6.1): our bar on
 * top, YouTube's own player in a plain iframe below it, and nothing of ours
 * drawn over the video. When the owner keeps a video on YouTube, the next
 * trailer is tried, then the page says it can't play here.
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
      Plays from YouTube ·{' '}
      <a href={YOUTUBE_TERMS_URL} target="_blank" rel="noreferrer" className="whitespace-nowrap text-white/85 underline hover:text-white">
        YouTube Terms of Service ↗<span className="sr-only"> (opens in a new tab)</span>
      </a>
    </>
  )

  return (
    <div className="fixed inset-0 z-50 flex flex-col overflow-hidden bg-black text-white">
      <div className="flex flex-none items-center gap-3 border-b border-surface-2 px-4 py-3 md:gap-4 md:p-4">
        <Link {...backLink} className="inline-flex min-h-11 shrink-0 items-center rounded-md border border-white/20 px-3 text-sm text-white hover:bg-white/10 md:min-h-9">
          ‹ Back
        </Link>
        <h1 className="min-w-0 flex-1 text-sm font-normal leading-snug text-white md:truncate">
          {shown ? `${shown.name} · ${label}` : label}
        </h1>
        {playing && !blocked && <p className="hidden shrink-0 whitespace-nowrap text-[13px] text-white/65 md:block">{terms}</p>}
      </div>

      {/* The player is the largest 16:9 box that fits under the bar; on phones it spans the width with the note under it. */}
      <div className="flex min-h-0 flex-1 flex-col justify-center gap-4 md:items-center md:[container-type:size]">
        {movie.isPending ? (
          <p className="text-center text-sm text-white/65">Loading…</p>
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
          <>
            <div className="relative aspect-video w-full md:w-[min(100cqw,calc(100cqh*16/9))]">
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
            <p className="px-4 text-[13px] leading-normal text-white/65 md:hidden">{terms}</p>
          </>
        ) : (
          <Notice title="This trailer can’t play here">
            <p className="text-sm text-neutral-400 md:text-[15px]">Its owner only lets it play on YouTube.</p>
            <a href={watchUrl(shown.key)} target="_blank" rel="noreferrer" className={secondary}>
              Watch on YouTube ↗<span className="sr-only"> (opens in a new tab)</span>
            </a>
          </Notice>
        )}
      </div>
    </div>
  )
}

const secondary =
  'mt-1.5 inline-flex min-h-11 items-center justify-center gap-2 rounded-md border border-line-2 bg-surface px-4 text-sm font-medium text-neutral-100 hover:bg-surface-2 md:mt-2 md:px-[18px]'

/** What shows in the player's place when it can't play: the same 16:9 box, our words in it. */
function Notice({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex aspect-video w-full flex-col items-center justify-center gap-2 bg-ground p-4 text-center md:w-[min(100cqw,calc(100cqh*16/9))] md:gap-3 md:p-8">
      <h2 className="text-[17px] font-semibold md:text-[22px]">{title}</h2>
      {children}
    </div>
  )
}
