import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { castFile, castSound, castSubtitleTiming, playOrPause, seekCast, send, stopCasting, takeHandover, useCast, whenCast } from '../cast/sender'
import type { StateMessage } from '../cast/protocol'
import { CastIcon } from '../components/Cast'
import { StillWatchingCard } from '../components/StillWatchingCard'
import { Choice, PlayerSheet, SoundSection, TimingSection } from '../components/PlayerPanels'
import { capClass, moveInGroup } from '../components/playerStyles'
import { useSubtitleTiming } from '../components/usePlayerCues'
import { useSoundChange } from '../components/useSoundChange'
import { SubtitlesIcon } from '../components/icons'
import { formatDuration } from '../lib/format'
import { SKIP_LABELS } from '../lib/play/rules'
import { audioWords, subtitleWords } from '../lib/play/words'
import { soundAndSubtitlesOn } from '../lib/sound'
import { subtitleSizeOf } from '../lib/subtitleSize'
import { subtitleTimingSaver } from '../api/subtitleTiming'
import { preferencesQuery, serverQuery } from '../api/queries'
import { useAuth } from '../authContext'
import { AUTO, qualitiesFor, qualityById, saveQuality, savedQuality, stepName } from '../lib/quality'

type Props = { fileId: number; start?: number; title?: string; back?: string; onLocal: () => void }

/**
 * The player as a remote while this browser casts. The
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
  const newer = soundAndSubtitlesOn(apiLevel)
  const prefs = useQuery(preferencesQuery)
  const prefsKnown = prefs.data !== undefined || prefs.failureCount > 0
  const [panel, setPanel] = useState(false)

  // Start this file on the TV unless it is on already; once, so a TV that
  // refuses it is not asked again and again.
  const tried = useRef(false)
  useEffect(() => {
    if (!connected || playingHere || asked || tried.current || !levelKnown || !prefsKnown) return
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
      // The TV draws its subtitles at the person's size.
      subtitleSize: newer ? subtitleSizeOf(prefs.data) : undefined,
    }).catch(() => undefined)
  }, [connected, playingHere, asked, fileId, start, title, levelKnown, apiLevel, prefsKnown, newer, prefs.data])

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
      if (e.target instanceof HTMLSelectElement || (e.target instanceof Element && e.target.closest('[data-player-keys="off"]'))) return
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
    <div className={`fixed inset-0 z-50 flex flex-col bg-ground text-white ${panel && state ? 'md:pr-[400px]' : ''}`}>
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
              {((state.audio?.length ?? 0) > 1 || (state.subtitles?.length ?? 0) > 0 || newer) && (
                <button
                  type="button"
                  aria-expanded={panel}
                  onClick={() => setPanel(!panel)}
                  className={`inline-flex min-h-[36px] items-center gap-2 rounded-md border border-white/20 px-3 text-sm hover:bg-white/10 ${panel ? 'bg-white/12' : ''}`}
                >
                  <SubtitlesIcon size={18} />
                  Audio &amp; subtitles
                </button>
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

      {panel && state && (
        <PlayerSheet title="Audio & subtitles" onClose={() => setPanel(false)}>
          <RemoteTracks state={state} fileId={fileId} device={device} newer={newer} />
        </PlayerSheet>
      )}
    </div>
  )
}

/**
 * The TV's Audio & subtitles, from this browser: its audio and subtitle
 * tracks, and on a server at API level 35 the person's sound settings and
 * the subtitle timing. A sound change saves the setting and has the TV play
 * on with it from where it is; a timing moves the TV's cues at once and is
 * saved for the person, as in the player.
 */
function RemoteTracks({ state, fileId, device, newer }: { state: StateMessage; fileId: number; device: string; newer: boolean }) {
  const auth = useAuth()
  const audio = state.audio ?? []
  const subtitles = state.subtitles ?? []
  const subtitle = subtitles.find((s) => s.id === state.selectedSubtitle)
  const burned = !!subtitle && (subtitle.format === 'image' || !!subtitle.burnInOnly)
  const [saver] = useState(() => subtitleTimingSaver())
  // A burned-in track's timing needs the TV to decide again, as a sound change does.
  const replay = useCallback(() => castSound(), [])
  const timing = useSubtitleTiming(saver, !!auth.user?.isAdmin && auth.adminAllowed, replay)
  useEffect(() => {
    timing.control.track(newer && subtitle ? { fileId, trackId: subtitle.id } : undefined, subtitle, burned)
  }, [timing.control, newer, subtitle, fileId, burned])
  // Each change of the timing here goes to the TV at once.
  const sent = useRef<number | undefined>(undefined)
  const ms = timing.view.ms
  useEffect(() => {
    if (!subtitle || burned) return
    if (sent.current !== undefined && ms !== sent.current) castSubtitleTiming(ms)
    sent.current = ms
  }, [ms, subtitle, burned])
  const sound = useSoundChange(async () => {
    castSound()
    // The TV decides again: it reports loading, then playing from the same moment.
    await whenCast((c) => c.receiver?.phase === 'loading', 3000)
    await whenCast((c) => c.receiver?.phase === 'playing', 20_000)
  })
  return (
    <>
      <p className="-mt-1.5 text-[12.5px] text-neutral-400">Changes apply on {device}.</p>
      {audio.length > 0 && (
        <>
          <h3 id="remote-audio" className={capClass}>Audio</h3>
          <div role="radiogroup" aria-labelledby="remote-audio" onKeyDown={moveInGroup} className="flex flex-col gap-1">
            {audio.map((a) => {
              const w = audioWords(a)
              const on = a.index === (state.selectedAudio ?? audio[0].index)
              return <Choice key={a.index} on={on} title={w.title} detail={w.detail} onPick={() => !on && void send({ type: 'audio', index: a.index })} />
            })}
          </div>
        </>
      )}
      {newer && <SoundSection sound={sound.sound} change={sound.change} onPick={sound.pick} onRetry={sound.retry} />}
      <h3 id="remote-subs" className={capClass}>Subtitles</h3>
      <div role="radiogroup" aria-labelledby="remote-subs" onKeyDown={moveInGroup} className="flex flex-col gap-1">
        <Choice on={!state.selectedSubtitle} title="Off" onPick={() => void send({ type: 'subtitle', id: null })} />
        {subtitles.map((s) => {
          const w = subtitleWords(s)
          return <Choice key={s.id} on={s.id === state.selectedSubtitle} title={w.title} detail={w.detail} onPick={() => void send({ type: 'subtitle', id: s.id })} />
        })}
      </div>
      {newer && subtitle && (
        <TimingSection view={timing.view} onStep={(by) => timing.control.step(by)} onReset={() => timing.control.reset()} onShare={() => timing.control.share()} onRetry={() => timing.control.retry()} />
      )}
    </>
  )
}
