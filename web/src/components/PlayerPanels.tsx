import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import type { OnlineSubtitle, SubtitleSearch, SubtitleTrack } from '../api/playback'
import type { components } from '../api/schema'
import { audioWords, onlineWords, playbackRows, streamStory, subtitleWords } from '../lib/play/words'
import { CheckIcon, CloseIcon, MoreIcon, SearchIcon } from './icons'
import { playerButton } from './playerStyles'

type Decision = components['schemas']['Decision']
type MediaInfo = components['schemas']['MediaInfo']

/**
 * The player's side sheet: Audio & subtitles, or Playback info. It sits at
 * the right of the picture from md up and along the foot at phone width.
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
      className="absolute inset-x-0 bottom-0 z-30 flex max-h-[78dvh] flex-col gap-3.5 overflow-y-auto rounded-t-2xl border-t border-line-2 bg-surface px-5 pb-6 pt-5 text-neutral-100 md:inset-x-auto md:right-0 md:top-0 md:max-h-none md:w-[400px] md:rounded-none md:border-l md:border-t-0 md:px-[22px] md:pt-[22px]"
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

const capClass = 'mt-1.5 text-[11.5px] font-bold uppercase tracking-[.08em] text-dim'
const optClass = (on: boolean) =>
  `flex min-h-[46px] w-full items-center gap-2.5 rounded-lg border px-3 py-1 text-left text-sm disabled:cursor-default disabled:opacity-50 ${on ? 'border-line-3 bg-raise font-semibold' : 'border-transparent hover:bg-surface-2'}`

/** Up and Down move along a group of choices, as in a radio group. */
function moveInGroup(e: KeyboardEvent<HTMLDivElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return
  const all = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]:not(:disabled)'))
  const at = all.indexOf(document.activeElement as HTMLButtonElement)
  const to = e.key === 'Home' ? 0 : e.key === 'End' ? all.length - 1 : e.key === 'ArrowDown' ? at + 1 : at - 1
  e.preventDefault()
  all[(to + all.length) % all.length]?.focus()
}

function Choice({ on, title, detail, disabled, onPick }: { on: boolean; title: string; detail?: string; disabled?: boolean; onPick: () => void }) {
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
      {remembered && (
        <p className="mt-auto border-t border-line-2 pt-3.5 text-[13px] text-neutral-400">
          <b className="font-semibold text-neutral-100">Remembered for {remembered}.</b> The next episode starts with the same choices.
        </p>
      )}
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
