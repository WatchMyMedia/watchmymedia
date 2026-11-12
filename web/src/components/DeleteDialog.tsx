import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { deletePreviewQuery, useDeleteItem, useReplaceFile, type DeleteItem, type DeleteResult, type ReplaceResult } from '../api/delete'
import {
  appName,
  chosenApps,
  deleteBody,
  deleteTitle,
  extraWarning,
  failures,
  fileSummary,
  filesLine,
  goingFiles,
  initialChoices,
  keptLine,
  otherEpisodes,
  recordingFilesLine,
  seerrLine,
  whatGoes,
  whereTheyGo,
  type Choices,
  type Piece,
} from '../lib/delete'
import { formatBytes } from '../lib/format'

const btn = 'inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md px-[18px] text-sm'
const cancelBtn = `${btn} border border-line-2 bg-surface font-medium hover:bg-surface-2`
const dangerBtn = `${btn} border border-red-500 bg-red-700 font-semibold text-white hover:bg-red-600 disabled:cursor-default disabled:opacity-50 disabled:hover:bg-red-700`
const legend = 'mb-2 p-0 text-xs font-semibold uppercase tracking-[.06em] text-neutral-400'
const group = 'overflow-hidden rounded-[10px] border border-line-2 bg-surface'
const option = 'flex cursor-pointer items-start gap-3 border-t border-line-2 px-3.5 py-3 first:border-t-0 has-[:disabled]:cursor-default has-[:disabled]:opacity-50'
const box = 'mt-px h-[18px] w-[18px] shrink-0 accent-accent'

/** A modal over the page, as the delete and replace boards draw it. */
function Modal({ titleId, onClose, children }: { titleId: string; onClose: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLElement>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    ref.current?.focus()
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 overflow-y-auto bg-black/65 px-4 pb-10 pt-20" onClick={onClose}>
      <section
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
        className="mx-auto flex w-full max-w-[36rem] flex-col gap-4 rounded-[14px] border border-line-2 bg-surface-2 p-6 shadow-[0_30px_80px_rgba(0,0,0,.6)] focus:outline-none"
      >
        {children}
      </section>
    </div>
  )
}

function Text({ pieces }: { pieces: Piece[] }) {
  return (
    <>
      {pieces.map((p, i) =>
        typeof p === 'string' ? (
          p
        ) : (
          <code key={i} className="rounded bg-ground px-1.5 py-px font-mono text-[13px] text-neutral-200 [overflow-wrap:anywhere]">
            {p.path}
          </code>
        ),
      )}
    </>
  )
}

function Warning({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-start gap-2.5 rounded-[10px] border border-orange-900 bg-orange-950/50 px-3.5 py-3 text-sm leading-normal text-orange-300">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-px shrink-0">
        <path d="M12 3 2 20h20L12 3z" />
        <path d="M12 10v4M12 17h.01" />
      </svg>
      <span>{children}</span>
    </p>
  )
}

const bodyText = 'text-[15px] leading-relaxed text-neutral-200'

/**
 * Delete a movie, show, season or episode (PLAN-ARR.md §8.1, design/mockups/web/DeleteDialog).
 * The server says what will go; the Radarr or Sonarr that manages it deletes it.
 */
export function DeleteDialog({
  item,
  name,
  season,
  episode,
  onClose,
  onDeleted,
}: {
  item: DeleteItem
  /** The movie's or show's title. */
  name: string
  season?: number
  /** An episode: its title, "Harbor Lights · S03 E07" for under it, and its files, to name the one that goes. */
  episode?: { title: string; line: string; files: { path: string; relativePath: string; size: number }[] }
  onClose: () => void
  /** After a delete in which every app succeeded. */
  onDeleted?: (r: DeleteResult) => void
}) {
  const preview = useQuery(deletePreviewQuery(item))
  const [dontRecord, setDontRecord] = useState(true)
  const [choices, setChoices] = useState<Choices | null>(null)
  const [result, setResult] = useState<DeleteResult | null>(null)
  const del = useDeleteItem(item)
  const title = deleteTitle(item.type, name, { season, episode: episode?.title })
  const titleId = 'delete-title'

  const p = preview.data
  const c = choices ?? (p ? initialChoices(p) : null)
  const set = (patch: Partial<Choices>) => c && setChoices({ ...c, ...patch })

  const heading = (sub?: string) => (
    <div className="flex flex-col gap-1">
      <h2 id={titleId} className="text-2xl font-extrabold leading-tight">
        {title}
      </h2>
      {sub && <p className="text-sm text-neutral-400">{sub}</p>}
    </div>
  )
  const closeOnly = (label = 'Close') => (
    <div className="mt-1 flex justify-end">
      <button type="button" className={cancelBtn} onClick={onClose}>
        {label}
      </button>
    </div>
  )

  if (!p || !c) {
    return (
      <Modal titleId={titleId} onClose={onClose}>
        {heading(episode?.line)}
        <p className={bodyText}>{preview.isError ? preview.error.message : 'Checking what this deletes…'}</p>
        {preview.isError && closeOnly()}
      </Modal>
    )
  }
  if (!p.managed) {
    return (
      <Modal titleId={titleId} onClose={onClose}>
        {heading(episode?.line)}
        <p className={bodyText}>WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk.</p>
        {closeOnly('OK')}
      </Modal>
    )
  }
  if (result) {
    return (
      <Modal titleId={titleId} onClose={onClose}>
        {heading(episode?.line)}
        {failures(result).map((f) => (
          <Warning key={f}>{f}</Warning>
        ))}
        {result.apps
          .filter((a) => !a.error)
          .map((a) => (
            <p key={a.connectionId} className={bodyText}>
              {a.connection} deleted {a.files === 1 ? '1 file' : `${a.files} files`} ({formatBytes(a.size)}).
            </p>
          ))}
        {result.seerr && !result.seerr.error && <p className={bodyText}>{result.seerr.connection} was told.</p>}
        {closeOnly()}
      </Modal>
    )
  }

  if (p.recording && p.apps.length === 0) return <Modal titleId={titleId} onClose={onClose}>
    {heading(episode?.line)}
    <p className={bodyText}>Permanently delete {p.files} recording {p.files === 1 ? 'file' : 'files'} ({formatBytes(p.size)}) from the server?</p>
    <label className={option}><input type="checkbox" checked={dontRecord} onChange={(e) => setDontRecord(e.target.checked)} className={box} /><span>Don’t record it again</span></label>
    {del.isError && <Warning>{del.error.message}</Warning>}
    <div className="flex justify-end gap-3"><button type="button" onClick={onClose} className={cancelBtn}>Cancel</button><button type="button" disabled={del.isPending} className={dangerBtn} onClick={() => del.mutate({ connections: [], recordAgain: !dontRecord }, { onSuccess: (r) => (onDeleted ?? onClose)(r) })}>{del.isPending ? 'Deleting…' : 'Delete recording'}</button></div>
  </Modal>

  const apps = chosenApps(p, c)
  const kind = p.apps[0].kind
  const app = appName(kind)
  const whole = item.type === 'movie' || item.type === 'show'
  const warnings = [
    extraWarning(item.type, apps, c.keep),
    item.type === 'episode' ? otherEpisodes(apps) : null,
    keptLine(item.type, p.kept),
    ...p.apps.filter((a) => a.error).map((a) => `Couldn’t reach ${a.connection}: ${a.error}. It can’t delete until it answers.`),
  ].filter((w): w is string => !!w)
  const submit = () =>
    del.mutate({ ...deleteBody(item.type, c), ...(p.recording ? { recordAgain: !dontRecord } : {}) }, {
      onSuccess: (r) => {
        if (failures(r).length > 0) setResult(r)
        else (onDeleted ?? onClose)(r)
      },
    })

  return (
    <Modal titleId={titleId} onClose={onClose}>
      {heading(episode ? episode.line : filesLine(apps))}
      {episode && (
        <p className="rounded-lg border border-line-2 bg-surface px-3 py-2.5 font-mono text-xs text-neutral-300 [overflow-wrap:anywhere]">
          {goingFiles(apps, episode.files).map((f, i) => (
            <span key={i} className="block">
              {f}
            </span>
          ))}
        </p>
      )}
      {p.recording && <label className={option}><input type="checkbox" checked={dontRecord} onChange={(e) => setDontRecord(e.target.checked)} className={box} /><span>Don’t record it again</span></label>}
      {p.apps.length > 1 && (
        <fieldset className="m-0 min-w-0 border-0 p-0">
          <legend className={legend}>Delete from</legend>
          <div className={group}>
            {p.apps.map((a) => (
              <label key={a.connectionId} className={option}>
                <input
                  type="checkbox"
                  className={box}
                  disabled={!!a.error}
                  checked={c.picked.includes(a.connectionId)}
                  onChange={(e) => set({ picked: e.target.checked ? [...c.picked, a.connectionId] : c.picked.filter((id) => id !== a.connectionId) })}
                />
                <span className="flex flex-1 flex-wrap justify-between gap-x-3 gap-y-1">
                  <span className="text-[15px] font-semibold">{a.connection}</span>
                  <span className="text-sm text-neutral-400">{a.error ? 'Not reachable' : [a.quality, formatBytes(a.size)].filter(Boolean).join(' · ')}</span>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {apps.length > 0 && (
        <p className={bodyText}>
          <Text pieces={whatGoes(item.type, apps, c.keep)} /> <Text pieces={whereTheyGo(item.type, apps, c.keep)} />
        </p>
      )}
      {p.recording && <p className={bodyText}>{recordingFilesLine(p)}</p>}
      {apps.length > 0 && item.type === 'episode' && <p className="text-sm leading-relaxed text-neutral-400">Your watch history stays.</p>}
      {apps.length > 0 && seerrLine(item.type, p.seerr) && <p className="text-sm leading-relaxed text-neutral-400">{seerrLine(item.type, p.seerr)}</p>}
      {warnings.map((w) => (
        <Warning key={w}>{w}</Warning>
      ))}
      {whole && (
        <>
          <fieldset className="m-0 min-w-0 border-0 p-0">
            <legend className={legend}>In {app}</legend>
            <div className={group}>
              <label className={option}>
                <input type="radio" name="delete-keep" className={box} checked={!c.keep} onChange={() => set({ keep: false })} />
                <span className="text-[15px]">Remove from {app}</span>
              </label>
              <label className={option}>
                <input type="radio" name="delete-keep" className={box} checked={c.keep} onChange={() => set({ keep: true })} />
                <span className="text-[15px]">Keep in {app}, unmonitored</span>
              </label>
            </div>
          </fieldset>
          <div className={group}>
            <label className={option}>
              <input type="checkbox" className={box} disabled={c.keep} checked={c.exclude && !c.keep} onChange={(e) => set({ exclude: e.target.checked })} />
              <span className="text-[15px]">Don’t add it again from import lists</span>
            </label>
          </div>
        </>
      )}
      {item.type === 'season' && (
        <div className={group}>
          <label className={option}>
            <input type="checkbox" className={box} checked={c.keepMonitored} onChange={(e) => set({ keepMonitored: e.target.checked })} />
            <span className="flex flex-col gap-0.5">
              <span className="text-[15px]">Keep getting new episodes of this season</span>
              {p.choices.airing && <span className="text-[13px] text-neutral-400">{season === 0 ? 'Specials are' : `Season ${season} is`} still airing.</span>}
            </span>
          </label>
        </div>
      )}
      {del.isError && <Warning>{del.error.message}</Warning>}
      <div className="mt-1 flex flex-wrap justify-end gap-2">
        <button type="button" className={cancelBtn} onClick={onClose}>
          Cancel
        </button>
        <button type="button" className={dangerBtn} disabled={apps.length === 0 || del.isPending} onClick={submit}>
          {del.isPending ? 'Deleting…' : `Delete ${item.type}`}
        </button>
      </div>
    </Modal>
  )
}

/** Replace a file the app manages (PLAN-ARR.md §9, design/mockups/web/ReportProblem, replace). */
export function ReplaceFileDialog({
  file,
  app,
  what,
  onClose,
}: {
  file: { id: number; relativePath: string; videoCodec?: string; audioCodec?: string; height?: number }
  /** The connection that manages it: Radarr, Sonarr 4K. */
  app: string
  what: 'movie' | 'episode'
  onClose: () => void
}) {
  const replace = useReplaceFile()
  const [done, setDone] = useState<ReplaceResult | null>(null)
  const titleId = 'replace-title'
  return (
    <Modal titleId={titleId} onClose={onClose}>
      <h2 id={titleId} className="text-2xl font-extrabold leading-tight">
        {done ? 'Replacing this file' : 'Replace this file?'}
      </h2>
      {done ? (
        <p className={bodyText}>
          {done.blocklisted ? `${done.connection} blocked this release and is searching for another.` : `${done.connection} is searching for another copy.`}
        </p>
      ) : (
        <p className={bodyText}>
          {app} blocks this release and searches for another. The {what} is gone until the new copy arrives.
        </p>
      )}
      <p className="rounded-lg border border-line-2 bg-surface px-3 py-2.5 font-mono text-xs text-neutral-300 [overflow-wrap:anywhere]">{fileSummary(file)}</p>
      {done?.note && <Warning>{done.note}</Warning>}
      {replace.isError && <Warning>{replace.error.message}</Warning>}
      <div className="mt-1 flex flex-wrap justify-end gap-2">
        {done ? (
          <button type="button" className={cancelBtn} onClick={onClose}>
            Close
          </button>
        ) : (
          <>
            <button type="button" className={cancelBtn} onClick={onClose}>
              Cancel
            </button>
            <button type="button" className={dangerBtn} disabled={replace.isPending} onClick={() => replace.mutate(file.id, { onSuccess: setDone })}>
              {replace.isPending ? 'Replacing…' : 'Replace file'}
            </button>
          </>
        )}
      </div>
    </Modal>
  )
}
