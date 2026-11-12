import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { posterOptionsQuery, useChangePoster, type PosterChange, type PosterChoice, type PosterKind } from '../api/posters'
import { img } from '../lib/images'
import { cropNote, firstPicture, isPictureLink, pictureProblem, saveProblem } from '../lib/posters'

/** What a poster can be changed for: a movie, a show, or one of the show's seasons. */
export type PosterTarget = { kind: PosterKind; id: number; label: string }

type Pick =
  | { by: 'tmdb'; path: string; fromShow: boolean }
  | { by: 'upload'; file: File; preview: string; width: number; height: number }
  | { by: 'link'; url: string }

/**
 * Change poster (design/mockups/web/PosterDialog, PLAN-POSTERS.md): an admin
 * picks one of TMDB's posters, uploads a picture, or gives a link the server
 * copies once. With more than one target (a show and its open season) a
 * switch at the top says which poster is being changed.
 */
export function ChangePosterDialog({ title, targets, onClose }: { title: string; targets: PosterTarget[]; onClose: () => void }) {
  const [targetIndex, setTargetIndex] = useState(0)
  const target = targets[targetIndex] ?? targets[0]
  const options = useQuery(posterOptionsQuery(target.kind, target.id))
  const save = useChangePoster()
  const [pick, setPick] = useState<Pick | null>(null)
  const [link, setLink] = useState('')
  const [problem, setProblem] = useState<string>()
  const [dragging, setDragging] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // The browser keeps an uploaded picture's preview until it is let go.
  const preview = pick?.by === 'upload' ? pick.preview : undefined
  useEffect(() => {
    if (!preview) return
    return () => URL.revokeObjectURL(preview)
  }, [preview])

  const choose = (next: Pick | null) => {
    setProblem(undefined)
    save.reset()
    setPick(next)
  }

  const takeFile = (file: File | undefined) => {
    if (!file) return
    const refused = pictureProblem(file)
    if (refused) {
      choose(null)
      setProblem(refused)
      return
    }
    const url = URL.createObjectURL(file)
    const probe = new Image()
    probe.onload = () => choose({ by: 'upload', file, preview: url, width: probe.naturalWidth, height: probe.naturalHeight })
    probe.onerror = () => {
      URL.revokeObjectURL(url)
      choose(null)
      setProblem(pictureProblem({ type: 'not/a-picture', size: 0 }))
    }
    probe.src = url
  }

  // A picture pasted anywhere in the dialog is an upload; pasted text in
  // the link field stays text.
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const file = firstPicture(e.clipboardData?.files)
      if (!file) return
      e.preventDefault()
      takeFile(file)
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
    // takeFile only sets state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const o = options.data
  const current = o?.posterPath
  const custom = !!o?.posterSource
  const busy = save.isPending

  // What Save would send, when the pick differs from the poster now.
  let change: PosterChange | undefined
  if (pick?.by === 'upload') change = { file: pick.file }
  else if (pick?.by === 'link') change = { url: pick.url }
  else if (pick?.by === 'tmdb' && pick.path !== current) change = pick.path === o?.tmdbPath ? { reset: true } : { tmdbPath: pick.path }

  const apply = (c: PosterChange) =>
    save.mutate(
      { kind: target.kind, id: target.id, change: c },
      {
        onSuccess: onClose,
        onError: (err) => setProblem(saveProblem(err instanceof ApiError ? err.status : 0, err.message)),
      },
    )

  const selectedPath = pick?.by === 'tmdb' ? pick.path : pick ? undefined : current
  const note =
    pick?.by === 'upload'
      ? `${pick.file.name} · ${pick.width} × ${pick.height}`
      : pick?.by === 'link'
        ? 'The server gets the picture from the link when you save.'
        : pick?.by === 'tmdb' && pick.path !== current
          ? pick.fromShow
            ? 'One of the show’s posters on TMDB'
            : 'From TMDB'
          : custom
            ? o?.posterSource === 'tmdb'
              ? 'The poster now · one you chose from TMDB'
              : 'The poster now · a picture you added'
            : 'The poster now · from TMDB'
  const crop = pick?.by === 'upload' ? cropNote(pick.width, pick.height) : undefined
  const previewSrc = pick?.by === 'upload' ? pick.preview : pick?.by === 'link' ? undefined : img(selectedPath, 'w342')

  const grid = (heading: string, list: PosterChoice[], fromShow: boolean) =>
    list.length > 0 && (
      <section className="space-y-2.5">
        <h3 className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">{heading}</h3>
        <ul className="grid grid-cols-3 gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(6.5rem,1fr))] sm:gap-3">
          {list.map((p) => {
            const on = selectedPath === p.path
            const isCurrent = p.path === current
            const isOwn = p.path === o?.tmdbPath
            return (
              <li key={p.path}>
                <button
                  type="button"
                  aria-pressed={on}
                  aria-label={`Poster, ${p.width} by ${p.height}${isCurrent ? ', the poster now' : ''}`}
                  onClick={() => choose({ by: 'tmdb', path: p.path, fromShow })}
                  className={`relative block aspect-[2/3] w-full overflow-hidden rounded-md bg-neutral-800 ${on ? 'ring-[3px] ring-accent' : 'ring-1 ring-line-2 hover:ring-neutral-500'}`}
                >
                  <img src={img(p.path, 'w185')} alt="" loading="lazy" className="h-full w-full object-cover" />
                  {(isCurrent || (isOwn && custom)) && (
                    <span className="absolute top-1.5 left-1.5 rounded bg-ground/85 px-1.5 py-0.5 text-[11px] font-semibold text-neutral-100">{isCurrent ? 'Current' : 'TMDB’s'}</span>
                  )}
                  {on && (
                    <span className="absolute top-1.5 right-1.5 grid h-6 w-6 place-items-center rounded-full bg-accent text-ground" aria-hidden="true">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                        <path d="m5 12 5 5 9-10" />
                      </svg>
                    </span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      </section>
    )

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-3 sm:p-10" onClick={onClose}>
      <div
        className="w-full max-w-[60rem] rounded-xl border border-line-2 bg-surface shadow-xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="poster-title"
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragging(false)
          takeFile(firstPicture(e.dataTransfer.files))
        }}
      >
        <div className="flex items-start justify-between gap-4 px-4 pt-4 sm:px-6 sm:pt-5">
          <div className="min-w-0">
            <h2 id="poster-title" className="text-xl font-bold">
              Change poster
            </h2>
            <p className="mt-1 text-sm text-neutral-400">{title} · Everyone on this server sees the poster you choose.</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            ✕
          </button>
        </div>

        {targets.length > 1 && (
          <div className="flex flex-wrap items-center gap-2 px-4 pt-4 sm:px-6" role="radiogroup" aria-label="Poster for">
            <span className="text-sm text-neutral-400">Poster for</span>
            {targets.map((t, i) => (
              <button
                key={`${t.kind}-${t.id}`}
                type="button"
                role="radio"
                aria-checked={i === targetIndex}
                disabled={busy}
                onClick={() => {
                  choose(null)
                  setTargetIndex(i)
                }}
                className={`min-h-[44px] rounded-md border px-4 text-sm font-medium ${i === targetIndex ? 'border-accent bg-surface-2 text-neutral-100' : 'border-line-2 text-neutral-300 hover:bg-surface-2'}`}
              >
                {t.label}
              </button>
            ))}
          </div>
        )}

        <div className="flex flex-wrap items-start gap-6 px-4 pt-5 pb-6 sm:px-6">
          <div className="flex w-full gap-4 sm:w-44 sm:flex-col sm:gap-2">
            <h3 className="hidden text-xs font-semibold tracking-wider text-neutral-400 uppercase sm:block">Preview</h3>
            <div className="aspect-[2/3] w-24 shrink-0 overflow-hidden rounded-md bg-neutral-800 ring-1 ring-line-2 sm:w-44">
              {previewSrc ? (
                <img src={previewSrc} alt="How the poster will look" className="h-full w-full object-cover" />
              ) : (
                <div className="flex h-full items-center justify-center p-2 text-center text-xs text-neutral-400">{pick?.by === 'link' ? 'Shown after saving' : 'No poster'}</div>
              )}
            </div>
            <div className="min-w-0 space-y-1.5 text-[13px] leading-snug">
              <h3 className="text-xs font-semibold tracking-wider text-neutral-400 uppercase sm:hidden">Preview</h3>
              <p className="break-words text-neutral-300">{note}</p>
              {crop && <p className="text-warn">{crop}</p>}
            </div>
          </div>

          <div className="min-w-0 flex-[999_1_26rem] space-y-5">
            <section className="space-y-2.5">
              <h3 className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">Your own picture</h3>
              <div className="flex flex-wrap items-stretch gap-3">
                <button
                  type="button"
                  onClick={() => fileInput.current?.click()}
                  className={`flex min-h-[76px] flex-[1_1_16rem] items-center gap-3 rounded-lg border bg-ground px-4 py-3 text-left ${
                    pick?.by === 'upload' || dragging ? 'border-accent' : 'border-dashed border-neutral-600 hover:border-neutral-400'
                  }`}
                >
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
                    <path d="M12 16V4M7 9l5-5 5 5M4 20h16" />
                  </svg>
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-semibold">{pick?.by === 'upload' ? pick.file.name : 'Upload a picture'}</span>
                    <span className="mt-0.5 block text-[13px] leading-snug text-neutral-400">
                      {pick?.by === 'upload' ? 'Choose another, or drop or paste one here' : 'Choose, drop or paste one here. JPEG, PNG or WebP, up to 20 MB'}
                    </span>
                  </span>
                </button>
                <input
                  ref={fileInput}
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  className="sr-only"
                  tabIndex={-1}
                  aria-hidden="true"
                  onChange={(e) => {
                    takeFile(e.target.files?.[0])
                    e.target.value = ''
                  }}
                />
                <label className="flex min-w-0 flex-[1_1_18rem] flex-col gap-1.5 text-sm text-neutral-300">
                  Or a link to a picture
                  <input
                    type="url"
                    inputMode="url"
                    placeholder="https://"
                    value={link}
                    aria-invalid={pick?.by === 'link' && !!problem}
                    onChange={(e) => {
                      const text = e.target.value
                      setLink(text)
                      if (isPictureLink(text)) choose({ by: 'link', url: text.trim() })
                      else if (pick?.by === 'link') choose(null)
                    }}
                    className="min-h-[44px] rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 aria-[invalid=true]:border-red-400"
                  />
                </label>
              </div>
              {problem && (
                <p role="alert" className="text-sm text-red-400">
                  {problem}
                </p>
              )}
              <p className="text-[13px] leading-snug text-neutral-400">The picture is copied to this server once. Posters are drawn 2:3, so other shapes are cropped to fit.</p>
            </section>

            {options.isPending && <p className="text-sm text-neutral-400">Loading TMDB’s posters…</p>}
            {options.isError && <p className="text-sm text-red-400">{options.error.message}</p>}
            {o && (
              <>
                {grid(target.kind === 'season' ? 'This season’s posters, from TMDB' : 'From TMDB', o.posters, false)}
                {grid('The show’s posters, from TMDB', o.showPosters, true)}
                {o.posters.length === 0 && o.showPosters.length === 0 && (
                  <section className="space-y-1.5">
                    <h3 className="text-xs font-semibold tracking-wider text-neutral-400 uppercase">From TMDB</h3>
                    <p className="text-sm text-neutral-400">
                      {o.tmdbAvailable
                        ? 'TMDB has no posters for this.'
                        : 'TMDB’s posters aren’t available right now. Check the TMDB key in Settings › General. Uploading and links work without it.'}
                    </p>
                  </section>
                )}
              </>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-3 border-t border-line px-4 py-4 sm:px-6">
          {custom && (
            <button
              type="button"
              disabled={busy}
              onClick={() => apply({ reset: true })}
              className="min-h-[44px] rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50"
            >
              Use TMDB’s poster
            </button>
          )}
          <div className="ml-auto flex flex-wrap gap-2">
            <button type="button" onClick={onClose} className="min-h-[44px] rounded-md px-4 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
              Cancel
            </button>
            <button type="button" disabled={!change || busy} onClick={() => change && apply(change)} className="btn-primary min-h-[44px] rounded-md px-5 text-sm font-semibold disabled:opacity-50">
              {busy ? (pick?.by === 'link' ? 'Getting the picture…' : 'Saving…') : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
