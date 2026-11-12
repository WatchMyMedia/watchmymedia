import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { castFile, playOrPause, seekCast, send, stopCasting, takeHandover, useCast } from '../cast/sender'
import { CastIcon } from '../components/Cast'
import { StillWatchingCard } from '../components/StillWatchingCard'
import { formatDuration } from '../lib/format'
import { SKIP_LABELS } from '../lib/play/rules'
import { serverQuery } from '../api/queries'
import { AUTO, qualitiesFor, qualityById, saveQuality, savedQuality, stepName } from '../lib/quality'

type Props = { fileId: number; start?: number; title?: string; back?: string; onLocal: () => void }

/**
 * The player as a remote while this browser casts (PLAN-CAST.md §5.1). The
 * TV runs the play; this page shows its state and sends commands. Opening
 * it for a file the TV is not playing casts that file; when the TV moves on
 * to the next episode by itself, the page follows.
 */
export function RemotePlayer({ fileId, start, title, back, onLocal }: Props) {
  const cast = useCast()
  const navigate = useNavigate()
  const r = cast.receiver
  const connected = cast.status === 'connected'
  const playingHere = r?.fileId === fileId
  const asked = cast.castingFileId === fileId
  // Automatic needs the server at API level 3 (the TV checks too).
  const server = useQuery(serverQuery)
  const apiLevel = server.data?.apiLevel
  const levelKnown = server.data !== undefined || server.failureCount > 0

  // Start this file on the TV unless it is on already; once, so a TV that
  // refuses it is not asked again and again.
  const tried = useRef(false)
  useEffect(() => {
    if (!connected || playingHere || asked || tried.current || !levelKnown) return
    tried.current = true
    const h = takeHandover(fileId)
    void castFile({
      fileId,
      startMs: h ? h.positionMs : start !== undefined ? start * 1000 : null,
      audioIndex: h?.audioIndex,
      subtitleId: h?.subtitleId,
      subtitleMode: h?.subtitleMode,
      quality: h?.quality ?? savedQuality(apiLevel).id,
      title: h?.title ?? title,
    }).catch(() => undefined)
  }, [connected, playingHere, asked, fileId, start, title, levelKnown, apiLevel])

  // The TV moved on to another file (the next episode): follow it.
  const moved = connected && asked && !!r?.fileId && r.fileId !== fileId && r.phase === 'playing'
  useEffect(() => {
    if (!moved || !r?.fileId) return
    void navigate({ to: '/play/$fileId', params: { fileId: String(r.fileId) }, search: { title: r.subtitle ? `${r.title} · ${r.subtitle}` : r.title, back }, replace: true })
  }, [moved, r?.fileId, r?.title, r?.subtitle, back, navigate])

  const state = playingHere ? r : undefined
  const offset = (state?.offsetMs ?? 0) / 1000
  const time = state ? offset + cast.currentTime : 0
  const duration = (state?.durationMs ?? 0) / 1000
  const pct = duration ? Math.min(100, (time / duration) * 100) : 0
  const seekBy = (d: number) => seekCast((time + d) * 1000)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLSelectElement) return
      if (e.key === ' ' || e.key === 'k') {
        e.preventDefault()
        playOrPause()
      } else if (e.key === 'ArrowRight') seekBy(10)
      else if (e.key === 'ArrowLeft') seekBy(-10)
      else if (e.key === 'Escape' && back) void navigate({ href: back })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const heading = state?.title ?? title ?? ''
  const device = cast.deviceName ?? 'your TV'

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-ground text-white">
      <div className="flex items-center gap-4 p-4">
        <Link to="/" href={back} className="rounded-md border border-white/20 px-3 py-1.5 text-sm hover:bg-white/10">
          ‹ Back
        </Link>
        <div className="min-w-0 flex-1 truncate text-sm text-white/70">{heading}</div>
        {connected && (
          <button type="button" onClick={stopCasting} className="rounded-md border border-white/20 px-3 py-1.5 text-sm hover:bg-white/10">
            Stop casting
          </button>
        )}
      </div>

      <div className="grid flex-1 place-items-center px-6 text-center">
        <div className="max-w-xl">
          <div className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-surface text-accent">
            <CastIcon connected={connected} size={30} />
          </div>
          {!connected ? (
            <>
              <h1 className="text-2xl font-semibold">Casting ended</h1>
              <p className="mt-2 text-white/60">{heading}</p>
              <div className="mt-6 flex justify-center gap-3">
                <button type="button" onClick={onLocal} className="btn-primary min-h-[44px] rounded-md px-4 text-sm font-semibold">
                  Continue in this browser
                </button>
                <Link to="/" href={back} className="min-h-[44px] rounded-md border border-white/20 px-4 py-3 text-sm hover:bg-white/10">
                  Back
                </Link>
              </div>
            </>
          ) : state?.phase === 'stopped' ? (
            <>
              <h1 className="text-2xl font-semibold">Playback stopped</h1>
              <p className="mt-2 text-white/60">{state.stopMessage ? <>The server&rsquo;s admin stopped this play: &ldquo;{state.stopMessage}&rdquo;</> : <>The server&rsquo;s admin stopped this play.</>}</p>
            </>
          ) : (
            <>
              <p className="text-sm text-white/60">Playing on {device}</p>
              <h1 className="mt-1 text-2xl font-semibold">{heading}</h1>
              {state?.subtitle && <p className="mt-1 text-white/70">{state.subtitle}</p>}
              {!state && !cast.error && <p className="mt-3 text-sm text-white/50">Starting on the TV…</p>}
            </>
          )}
          {(cast.error || (playingHere && r?.error)) && (
            <p className="mx-auto mt-4 max-w-md rounded-md border border-red-900 bg-red-950/90 px-4 py-3 text-sm text-red-200">{cast.error ?? r?.error}</p>
          )}
        </div>
      </div>

      {state?.skip && !state.skip.auto && !state.upNext && (
        <button
          type="button"
          onClick={() => void send({ type: 'skip' })}
          className="absolute bottom-36 right-6 min-h-[44px] rounded-md border border-white/30 bg-black/70 px-5 text-sm font-semibold hover:bg-white/10"
        >
          {SKIP_LABELS[state.skip.kind] ?? 'Skip'}
        </button>
      )}

      {state?.upNext && (
        <div className="absolute bottom-36 right-6 w-80 rounded-lg border border-white/15 bg-neutral-950/95 p-4 text-left shadow-2xl">
          <p className="text-xs text-white/60">Up next{state.upNext.secondsLeft !== null ? ` · in ${state.upNext.secondsLeft} s` : ''}</p>
          <p className="mt-1 truncate font-semibold">{state.upNext.showTitle}</p>
          <p className="truncate text-sm text-white/70">
            S{state.upNext.seasonNumber} · E{state.upNext.episodeNumber} · {state.upNext.title}
          </p>
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={() => void send({ type: 'playNext' })} className="btn-primary min-h-[40px] flex-1 rounded-md px-3 text-sm font-semibold">
              Play now
            </button>
            <button type="button" onClick={() => void send({ type: 'watchCredits' })} className="min-h-[40px] rounded-md border border-white/20 px-3 text-sm hover:bg-white/10">
              Watch credits
            </button>
          </div>
        </div>
      )}

      {state?.stillWatching && (
        <StillWatchingCard
          showTitle={state.nextEpisode?.showTitle ?? heading}
          onContinue={() => void send({ type: 'stillWatching', keep: true })}
          onBack={() => void send({ type: 'stillWatching', keep: false })}
        />
      )}

      {state && state.phase === 'playing' && (
        <div className="p-4">
          <div
            className="relative h-2 w-full cursor-pointer rounded bg-white/20"
            onClick={(e) => {
              const box = (e.currentTarget as HTMLDivElement).getBoundingClientRect()
              seekCast(((e.clientX - box.left) / box.width) * duration * 1000)
            }}
          >
            {duration > 0 &&
              state.segments?.map((s, i) => (
                <div
                  key={i}
                  className="absolute inset-y-0 rounded bg-white/25"
                  style={{ left: `${(s.startMs / 1000 / duration) * 100}%`, width: `${(((s.endMs ? s.endMs / 1000 : duration) - s.startMs / 1000) / duration) * 100}%` }}
                />
              ))}
            <div className="absolute inset-y-0 left-0 rounded bg-accent" style={{ width: `${pct}%` }} />
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
            <button type="button" onClick={() => seekBy(-10)} className="min-h-[40px] min-w-[44px] rounded-md bg-white/10 px-3 hover:bg-white/20" aria-label="Back 10 seconds">
              ‹ 10
            </button>
            <button type="button" onClick={playOrPause} className="min-h-[40px] min-w-[44px] rounded-md bg-white/10 px-3 hover:bg-white/20" aria-label={cast.paused ? 'Play' : 'Pause'}>
              {cast.paused ? '▶' : '❚❚'}
            </button>
            <button type="button" onClick={() => seekBy(10)} className="min-h-[40px] min-w-[44px] rounded-md bg-white/10 px-3 hover:bg-white/20" aria-label="Forward 10 seconds">
              10 ›
            </button>
            <span className="tabular-nums text-white/80">
              {formatDuration(time * 1000)} / {formatDuration(duration * 1000)}
            </span>
            <div className="ml-auto flex flex-wrap items-center gap-2">
              {state.audio && state.audio.length > 1 && (
                <select
                  value={state.selectedAudio ?? ''}
                  aria-label="Audio"
                  onChange={(e) => void send({ type: 'audio', index: Number(e.target.value) })}
                  className="min-h-[36px] rounded-md border border-white/20 bg-black/60 px-2 text-sm"
                >
                  {state.audio.map((a) => (
                    <option key={a.index} value={a.index}>
                      {[a.language?.toUpperCase(), a.codec.toUpperCase(), a.channels ? `${a.channels}ch` : '', a.title].filter(Boolean).join(' · ')}
                    </option>
                  ))}
                </select>
              )}
              {state.subtitles && state.subtitles.length > 0 && (
                <select
                  value={state.selectedSubtitle ?? ''}
                  aria-label="Subtitles"
                  onChange={(e) => void send({ type: 'subtitle', id: e.target.value || null })}
                  className="min-h-[36px] rounded-md border border-white/20 bg-black/60 px-2 text-sm"
                >
                  <option value="">Subtitles off</option>
                  {state.subtitles.map((s) => (
                    <option key={s.id} value={s.id}>
                      {[s.language?.toUpperCase() || 'und', s.title, s.forced ? 'forced' : '', s.burnInOnly ? 'burned in, restarts playback' : ''].filter(Boolean).join(' · ')}
                    </option>
                  ))}
                </select>
              )}
              <select
                value={state.quality ?? 'original'}
                aria-label="Quality"
                onChange={(e) => {
                  const q = qualityById(e.target.value, apiLevel)
                  saveQuality(q)
                  void send({ type: 'quality', id: q.id })
                }}
                className="min-h-[36px] rounded-md border border-white/20 bg-black/60 px-2 text-sm"
              >
                {qualitiesFor(apiLevel).map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.id === AUTO && state.quality === AUTO && state.autoStep ? `${q.label} · now ${stepName(state.autoStep)}` : q.label}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
