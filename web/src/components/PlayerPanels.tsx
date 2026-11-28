import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'
import type { OnlineSubtitle, SubtitleSearch, SubtitleTrack } from '../api/playback'
import type { components } from '../api/schema'
import { audioWords, onlineWords, playbackRows, streamStory, subtitleWords } from '../lib/play/words'
import { SOUND_CHANGING, SOUND_LEVELS, SOUND_NOTE, SOUND_ROWS, SOUND_SAVED, soundFailed, type Sound, type SoundChangeState, type SoundLevel, type SoundSetting } from '../lib/sound'
import { SHARE_DONE, SHARE_HELP, SHARE_LABEL, TIMING_BURNED, TIMING_FAILED, TIMING_HELP, TIMING_SHARED, type TimingView } from '../lib/play/subtitleTiming'
import { BurnedSubtitlesIcon, CheckIcon, ChevronLeftIcon, ChevronRightIcon, CloseIcon, MoreIcon, PeopleIcon, SearchIcon } from './icons'
import { PHONE_SHEET_TOP, capClass, moveInGroup, playerButton } from './playerStyles'

type Decision = components['schemas']['Decision']
type MediaInfo = components['schemas']['MediaInfo']


/**
 * The player's side sheet: Audio & subtitles, or Playback info. It sits at
 * the right of the picture from md up and under it at phone width.
 * Focus goes to it on opening and back to the button that opened it on
 * closing; Escape closes. The player's own keys leave it alone.
 */
export function PlayerSheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const box = useRef<HTMLElement>(null)
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const first = box.current?.querySelector<HTMLElement>('[aria-checked="true"]') ?? box.current?.querySelector<HTMLElement>('[role="radio"], button')
    first?.focus()
    // Back to what opened it, or to the bar's More button when that was a menu item now gone.
    return () => (opener?.isConnected ? opener : document.querySelector<HTMLElement>('[aria-label^="More: playback info"]'))?.focus?.()
  }, [])
  return (
    <aside
      ref={box}
      aria-label={title}
      data-player-keys="off"
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault()
          onClose()
        }
      }}
      className={`absolute inset-x-0 bottom-0 z-30 flex flex-col gap-3.5 overflow-y-auto rounded-t-2xl border-t border-line-2 bg-surface px-5 pb-6 pt-5 text-neutral-100 md:inset-x-auto md:right-0 md:top-0 md:w-[400px] md:rounded-none md:border-l md:border-t-0 md:px-[22px] md:pt-[22px] ${PHONE_SHEET_TOP}`}
    >
      <div className="flex items-center gap-2.5">
        <h2 className="flex-1 text-lg font-[650]">{title}</h2>
        <button type="button" onClick={onClose} aria-label="Close" className="grid size-10 place-items-center rounded-full text-neutral-100 hover:bg-white/14">
          <CloseIcon size={20} />
        </button>
      </div>
      {children}
    </aside>
  )
}

const optClass = (on: boolean) =>
  `flex min-h-[46px] w-full items-center gap-2.5 rounded-lg border px-3 py-1 text-left text-sm disabled:cursor-default disabled:opacity-50 ${on ? 'border-line-3 bg-raise font-semibold' : 'border-transparent hover:bg-surface-2'}`

export function Choice({ on, title, detail, disabled, onPick }: { on: boolean; title: string; detail?: string; disabled?: boolean; onPick: () => void }) {
  return (
    <button type="button" role="radio" aria-checked={on} disabled={disabled} tabIndex={on ? 0 : -1} onClick={onPick} className={optClass(on)}>
      <span className="min-w-0 flex-1">
        {title}
        {detail && <small className="block truncate text-[12.5px] font-normal text-neutral-400">{detail}</small>}
      </span>
      {on && <CheckIcon size={18} className="shrink-0 text-accent" />}
    </button>
  )
}

/**
 * Audio & subtitles: the sound tracks, then the subtitles in the file before
 * those from OpenSubtitles, as one panel. A choice applies at once.
 */
export function TracksPanel({
  decision,
  audioIndex,
  onAudio,
  subtitleId,
  automatic,
  onSubtitle,
  online,
  fetching,
  note,
  remembered,
  sound,
  timing,
}: {
  decision: Decision
  audioIndex: number
  onAudio: (index: number) => void
  subtitleId: string
  /** Following the account's subtitle settings: nothing chosen in this player yet. */
  automatic: boolean
  onSubtitle: (value: string) => void
  online?: SubtitleSearch
  fetching: string | null
  note: string | null
  /** The show an episode's picks are kept for, which the panel's foot names. */
  remembered?: string
  /** Boost dialogue and Reduce loud sounds, under the audio (API level 35). */
  sound?: ReactNode
  /** The timing of the subtitles on now, under the subtitles (API level 35). */
  timing?: ReactNode
}) {
  const own = decision.subtitles.filter((s) => s.source !== 'opensubtitles')
  const downloaded = decision.subtitles.filter((s) => s.source === 'opensubtitles')
  const toDownload = (online?.results ?? []).filter((r) => !r.trackId || !downloaded.some((s) => s.id === r.trackId))
  const showOnline = !!online?.available
  const [more, setMore] = useState(downloaded.length > 0)
  const canGet = (r: OnlineSubtitle) => !!online?.canDownload && online.quota.remaining > 0 && fetching === null && !!r
  return (
    <>
      {decision.audioTracks.length > 0 && (
        <>
          <h3 id="player-audio" className={capClass}>
            Audio
          </h3>
          <div role="radiogroup" aria-labelledby="player-audio" onKeyDown={moveInGroup} className="flex flex-col gap-1">
            {decision.audioTracks.map((a) => {
              const w = audioWords(a)
              return <Choice key={a.index} on={a.index === audioIndex} title={w.title} detail={w.detail} onPick={() => a.index !== audioIndex && onAudio(a.index)} />
            })}
          </div>
        </>
      )}
      {sound}
      <h3 id="player-subs" className={capClass}>
        Subtitles
      </h3>
      {automatic && !subtitleId && <p className="-mt-1.5 text-[13px] text-neutral-400">Following your subtitle settings until you choose here.</p>}
      <div role="radiogroup" aria-labelledby="player-subs" onKeyDown={moveInGroup} className="flex flex-col gap-1">
        <Choice on={!subtitleId && !automatic} title="Off" onPick={() => onSubtitle('')} />
        {own.map((s) => (
          <SubtitleChoice key={s.id} track={s} on={s.id === subtitleId} onPick={() => onSubtitle(s.id)} />
        ))}
        {showOnline && more && (
          <>
            <p className={`${capClass} px-1`}>From OpenSubtitles</p>
            {downloaded.map((s) => (
              <SubtitleChoice key={s.id} track={s} on={s.id === subtitleId} onPick={() => onSubtitle(s.id)} />
            ))}
            {toDownload.map((r) => {
              const w = onlineWords(r)
              return <Choice key={r.id} on={false} title={fetching === r.id ? `Downloading ${w.title}…` : w.title} detail={w.detail} disabled={!canGet(r)} onPick={() => onSubtitle(`online:${r.id}`)} />
            })}
          </>
        )}
      </div>
      {showOnline && !more && (
        <button type="button" onClick={() => setMore(true)} className={`${optClass(false)} text-neutral-300`}>
          <SearchIcon size={16} />
          Find more subtitles…
        </button>
      )}
      {showOnline && more && online && (
        <div className="space-y-1 px-1 text-[12.5px] text-neutral-400">
          {online.error && <p>The search failed. Try again later.</p>}
          {!online.error && downloaded.length === 0 && toDownload.length === 0 && <p>None found in {online.languages.join(', ')}.</p>}
          {!online.canDownload && toDownload.length > 0 && <p>Only admins can download subtitles.</p>}
          {online.canDownload && online.quota.remaining === 0 && <p>No downloads left today.</p>}
          <a href="https://opensubtitles.com" target="_blank" rel="noreferrer" className="inline-block text-neutral-400 underline decoration-neutral-600 underline-offset-2 hover:text-neutral-100">
            {online.attribution}
          </a>
        </div>
      )}
      {note && (
        <p role="status" className="text-[13px] text-neutral-300">
          {note}
        </p>
      )}
      {timing}
      {remembered && (
        <p className="mt-auto border-t border-line-2 pt-3.5 text-[13px] text-neutral-400">
          <b className="font-semibold text-neutral-100">Remembered for {remembered}.</b> The next episode starts with the same choices.
        </p>
      )}
    </>
  )
}

/** A sound setting being changed in the player: busy while it saves and the play reloads, then saved, or failed with the value it stayed at. */
export type SoundChange = SoundChangeState | null

/**
 * Sound, under the audio: Boost dialogue and Reduce loud sounds, each Off,
 * Medium or High. A change saves the person's setting and plays on from the
 * same moment with it; the panel stays open.
 */
export function SoundSection({ sound, change, onPick, onRetry }: { sound: Sound; change: SoundChange; onPick: (setting: SoundSetting, level: SoundLevel) => void; onRetry: () => void }) {
  const busy = change?.phase === 'busy'
  return (
    <>
      <h3 className={capClass}>Sound</h3>
      <div className="flex flex-col gap-4">
        {SOUND_ROWS.map((row) => {
          const mine = change?.setting === row.id ? change : null
          return (
            <div key={row.id} className="flex flex-col gap-[7px]">
              <span id={`player-${row.id}`} className="text-sm font-medium">{row.label}</span>
              <p id={`player-${row.id}-help`} className="text-[12.5px] leading-[1.45] text-neutral-400">{row.help}</p>
              <div role="radiogroup" aria-labelledby={`player-${row.id}`} aria-describedby={`player-${row.id}-help`} onKeyDown={moveInGroup} className="flex w-full rounded-[9px] border border-line-2 bg-ground p-[3px]">
                {SOUND_LEVELS.map((level) => {
                  const on = sound[row.id] === level.id
                  return (
                    <button key={level.id} type="button" role="radio" aria-checked={on} tabIndex={on ? 0 : -1} disabled={busy}
                      onClick={() => !on && onPick(row.id, level.id)}
                      className={`inline-flex min-h-[34px] flex-1 basis-0 items-center justify-center gap-[7px] rounded-md px-2 text-[13px] disabled:cursor-default ${on ? 'bg-raise font-semibold text-neutral-100 ring-1 ring-inset ring-line-3' : `text-neutral-400 hover:text-neutral-100 ${busy ? 'opacity-45' : ''}`}`}>
                      {on && mine?.phase === 'busy' && <Spin />}
                      {level.label}
                    </button>
                  )
                })}
              </div>
              {mine?.phase === 'busy' && <p role="status" className="flex items-center gap-1.5 text-[12.5px] text-neutral-300"><Spin />{SOUND_CHANGING}</p>}
              {mine?.phase === 'ok' && <p role="status" className="flex items-center gap-1.5 text-[12.5px] text-emerald-300"><CheckIcon size={14} />{SOUND_SAVED}</p>}
              {mine?.phase === 'err' && (
                <p role="alert" className="text-[12.5px] text-red-300">
                  {soundFailed(mine.stayed ?? 'off')}{' '}
                  <button type="button" onClick={onRetry} className="min-h-7 px-0.5 font-semibold text-neutral-100 underline underline-offset-2">Try again</button>
                </p>
              )}
            </div>
          )
        })}
        <p className="rounded-lg border border-line-2 p-3 text-[12.5px] leading-[1.45] text-neutral-400">{SOUND_NOTE}</p>
      </div>
    </>
  )
}

function Spin() {
  return <span aria-hidden="true" className="inline-block size-3 shrink-0 animate-spin rounded-full border-2 border-current border-r-transparent motion-reduce:animate-none" />
}

/** Holding Earlier or Later repeats it: once at the press, then every 0.1 s after 0.4 s. */
function useHeldRepeat(act: () => void) {
  const timer = useRef<number | undefined>(undefined)
  const pressed = useRef(false)
  const stop = () => {
    window.clearTimeout(timer.current)
    window.clearInterval(timer.current)
    timer.current = undefined
  }
  useEffect(() => stop, [])
  return {
    onPointerDown: (e: ReactPointerEvent<HTMLButtonElement>) => {
      if (e.button !== 0) return
      pressed.current = true
      act()
      stop()
      timer.current = window.setTimeout(() => { timer.current = window.setInterval(act, 100) }, 400)
    },
    onPointerUp: stop,
    onPointerLeave: stop,
    onPointerCancel: stop,
    // The keyboard's Enter and Space click without a pointer press.
    onClick: () => {
      if (pressed.current) pressed.current = false
      else act()
    },
  }
}

/**
 * Subtitle timing, under the subtitles while a track is on: Earlier and
 * Later by 0.1 s (held, they repeat; the arrow keys do the same), Reset
 * when it would change something, and Save for everyone for admins. The
 * cues move as it changes.
 */
export function TimingSection({ view, onStep, onReset, onShare, onRetry }: { view: TimingView; onStep: (by: 1 | -1) => void; onReset: () => void; onShare: () => void; onRetry: () => void }) {
  const earlier = useHeldRepeat(() => onStep(-1))
  const later = useHeldRepeat(() => onStep(1))
  const button = 'btn min-h-10 shrink-0 gap-1.5 px-3'
  return (
    <>
      <h3 id="player-timing" className={capClass}>Subtitle timing</h3>
      <div className="flex flex-col gap-2.5">
        <div
          role="group"
          aria-labelledby="player-timing"
          aria-describedby="player-timing-help"
          className="flex items-center gap-1.5"
          onKeyDown={(e) => {
            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
              e.preventDefault()
              onStep(e.key === 'ArrowLeft' ? -1 : 1)
            }
          }}
        >
          <button type="button" className={button} {...earlier}><ChevronLeftIcon size={16} />Earlier</button>
          <output aria-live="polite" className="flex min-w-0 flex-auto flex-col items-center text-center text-[14.5px] font-semibold whitespace-nowrap tabular-nums">
            {view.words}
            {view.setForEveryone && <small className="text-[11.5px] font-medium text-neutral-400">{TIMING_SHARED}</small>}
          </output>
          <button type="button" className={button} {...later}>Later<ChevronRightIcon size={16} /></button>
          {view.canReset ? (
            <button type="button" onClick={onReset} className="btn btn-ghost min-h-10 w-[66px] shrink-0 px-0">Reset</button>
          ) : (
            <span aria-hidden="true" className="w-[66px] shrink-0" />
          )}
        </div>
        <p id="player-timing-help" className="text-[12.5px] leading-[1.45] text-neutral-400">{TIMING_HELP}</p>
        {view.burned && (
          <p className="flex gap-[9px] rounded-lg border border-line-2 p-3 text-[12.5px] leading-[1.45] text-neutral-300">
            <BurnedSubtitlesIcon size={16} className="mt-px shrink-0" />
            <span>{TIMING_BURNED}</span>
          </p>
        )}
        {view.status === 'failed' && (
          <p role="alert" className="text-[12.5px] text-red-300">
            {TIMING_FAILED}{' '}
            <button type="button" onClick={onRetry} className="min-h-7 px-0.5 font-semibold text-neutral-100 underline underline-offset-2">Try again</button>
          </p>
        )}
        {view.canShare && (
          <div className="flex flex-col items-start gap-[7px] border-t border-line-2 pt-3">
            <button type="button" onClick={onShare} aria-describedby="player-share-help" className="btn btn-sm gap-1.5"><PeopleIcon size={16} />{SHARE_LABEL}</button>
            <p id="player-share-help" className="text-[12.5px] leading-[1.45] text-neutral-400">{SHARE_HELP}</p>
          </div>
        )}
        {view.status === 'shared' && (
          <p role="status" className="flex items-center gap-1.5 border-t border-line-2 pt-3 text-[12.5px] text-emerald-300"><CheckIcon size={14} />{SHARE_DONE}</p>
        )}
      </div>
    </>
  )
}

function SubtitleChoice({ track, on, onPick }: { track: SubtitleTrack; on: boolean; onPick: () => void }) {
  const w = subtitleWords(track)
  return <Choice on={on} title={w.title} detail={w.detail} onPick={onPick} />
}

/** Playback info: what is happening to the stream in a sentence, then the numbers, and a way to copy them. */
export function InfoPanel({ decision, source, subtitleId, bufferedSeconds, observedHeight, onReport }: { decision: Decision; source?: MediaInfo; subtitleId: string; bufferedSeconds: number; observedHeight?: number; onReport?: () => void }) {
  const story = streamStory(decision)
  const rows = playbackRows({ decision, source, subtitleId, bufferedSeconds, observedHeight })
  const [copied, setCopied] = useState(false)
  const copy = () => {
    const text = [story, ...rows.map((r) => `${r.label}: ${r.value}`), ...(decision.reasons.length ? [`Reasons: ${decision.reasons.join('; ')}`] : [])].join('\n')
    void navigator.clipboard?.writeText(text).then(() => setCopied(true), () => setCopied(false))
  }
  return (
    <>
      <p className="text-[15px] leading-normal">{story}</p>
      <dl className="dl border-t border-line-2 pt-3.5">
        {rows.map((r) => (
          <div key={r.label} className="contents">
            <dt>{r.label}</dt>
            <dd>{r.value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-auto flex flex-wrap gap-2 pt-2">
        {'clipboard' in navigator && (
          <button type="button" onClick={copy} className="btn btn-sm">
            {copied ? 'Copied' : 'Copy these details'}
          </button>
        )}
        {onReport && (
          <button type="button" onClick={onReport} className="btn btn-ghost btn-sm">
            Report a problem
          </button>
        )}
      </div>
    </>
  )
}

/** The ⋯ menu in the player's bar: Playback info and Report a problem. */
export function PlayerMoreMenu({ items, onOpenChange }: { items: { label: string; onPick: () => void }[]; onOpenChange: (open: boolean) => void }) {
  const [open, setOpenState] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const setOpen = (o: boolean) => {
    setOpenState(o)
    onOpenChange(o)
  }
  useEffect(() => {
    if (!open) return
    root.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    const away = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) {
        setOpenState(false)
        onOpenChange(false)
      }
    }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
    // Only on opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  if (!items.length) return null
  return (
    <div ref={root} className="relative" data-player-keys="off" onClick={(e) => e.stopPropagation()}>
      <button ref={button} type="button" aria-haspopup="menu" aria-expanded={open} aria-label="More: playback info, report a problem" onClick={() => setOpen(!open)} className={playerButton}>
        <MoreIcon size={20} />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="More"
          onKeyDown={(e) => {
            const all = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('[role="menuitem"]'))
            const at = all.indexOf(document.activeElement as HTMLElement)
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault()
              all[(at + (e.key === 'ArrowDown' ? 1 : -1) + all.length) % all.length]?.focus()
            } else if (e.key === 'Escape') {
              e.preventDefault()
              setOpen(false)
              button.current?.focus()
            } else if (e.key === 'Tab') setOpen(false)
          }}
          className="menu absolute right-0 top-[calc(100%+6px)] w-56"
        >
          {items.map((it) => (
            <button
              key={it.label}
              type="button"
              role="menuitem"
              tabIndex={-1}
              onClick={() => {
                setOpen(false)
                it.onPick()
              }}
              className="menu-item"
            >
              {it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
