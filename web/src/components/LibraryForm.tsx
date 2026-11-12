import { useId, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { serverQuery } from '../api/queries'
import { libraryPathExample } from '../lib/transcoder'
import { checkPath } from '../api/settings'
import { FolderPicker } from './FolderPicker'
import { folderCheckCount, folderLayoutWarning, libraryLabel } from '../lib/setupRules'
import type { components } from '../api/schema'

type PathCheck = components['schemas']['PathCheck']

type Fields = { name: string; path: string }

/**
 * Name + folder fields with Browse and Check, shared by the wizard and settings.
 * The wizard passes `value` and `onChange`, so what is typed outlives the form.
 */
export function LibraryForm({
  type,
  initialName,
  initialPath,
  value,
  onChange,
  note,
  submitLabel,
  onSubmit,
  onCancel,
  busy,
  error,
}: {
  type: 'movie' | 'tv'
  initialName?: string
  initialPath?: string
  value?: Fields
  onChange?: (v: Fields) => void
  /** A line under the folder check, such as the wizard's "Not added yet". */
  note?: string | null
  submitLabel: string
  onSubmit: (v: Fields) => void
  onCancel?: () => void
  busy?: boolean
  error?: string | null
}) {
  const platform = useQuery(serverQuery).data?.platform
  const folderId = useId()
  const [own, setOwn] = useState<Fields>({ name: initialName ?? libraryLabel(type), path: initialPath ?? '' })
  const { name, path } = value ?? own
  const set = (v: Fields) => (onChange ? onChange(v) : setOwn(v))
  const setName = (n: string) => set({ name: n, path })
  const checkVersion = useRef(0)
  const setPath = (p: string) => {
    checkVersion.current++
    setCheck(null)
    setCheckError(null)
    setChecking(false)
    set({ name, path: p })
  }
  const [picking, setPicking] = useState(false)
  const [check, setCheck] = useState<PathCheck | null>(null)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<string | null>(null)

  const runCheck = async (p: string) => {
    if (!p) return
    const version = ++checkVersion.current
    setCheckError(null)
    setChecking(true)
    try {
      const result = await checkPath(p)
      if (version === checkVersion.current) setCheck(result)
    } catch {
      if (version === checkVersion.current) {
        setCheck(null)
        setCheckError('Couldn’t check this folder. Check your connection to the server, then choose Check again.')
      }
    } finally {
      if (version === checkVersion.current) setChecking(false)
    }
  }

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit({ name, path })
      }}
    >
      <label className="block text-sm">
        <span className="text-neutral-400">Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} disabled={busy} required className="mt-1 w-full rounded-md border border-line-2 bg-ground px-3 py-2" />
      </label>
      <div className="block text-sm">
        <label htmlFor={folderId} className="text-neutral-400">Folder on the server</label>
        <div className="mt-1 flex gap-2">
          <input
            id={folderId}
            aria-describedby={`${folderId}-hint`}
            value={path}
            onChange={(e) => {
              setPath(e.target.value)
              setCheck(null)
            }}
            onBlur={() => void runCheck(path)}
            placeholder={libraryPathExample(type, platform)}
            disabled={busy}
            required
            className="min-w-0 flex-1 rounded-md border border-line-2 bg-ground px-3 py-2 font-mono text-sm disabled:opacity-60"
          />
          <button type="button" disabled={busy} onClick={() => setPicking(true)} className="min-h-[40px] rounded-md border border-line-2 px-3 text-sm disabled:opacity-50">
            Browse…
          </button>
          <button type="button" disabled={!path || checking} onClick={() => void runCheck(path)} className="min-h-[40px] rounded-md border border-line-2 px-3 text-sm disabled:opacity-50">
            Check
          </button>
        </div>
        <p id={`${folderId}-hint`} className="mt-2 text-sm text-neutral-400">
          {platform === 'windows' ? <>Use a drive path such as <code>{libraryPathExample(type, platform)}</code> or a network path such as <code>{String.raw`\\nas\media`}</code>. The Windows account running WatchMyMedia must have access; mapped drives must be connected in that account.</> : 'Choose a folder on the server, not on the device running this browser.'}
        </p>
      </div>
      {check && (
        <p role="status" className={`text-sm ${check.exists && check.readable ? 'text-emerald-400' : 'text-warn'}`}>
          {!check.exists
            ? 'Folder not found on the server.'
            : !check.readable
              ? platform === 'windows' ? 'Folder exists but cannot be read. Check the Windows account’s folder and network-share permissions.' : 'Folder exists but cannot be read; check permissions (PUID/PGID in Docker).'
              : folderCheckCount(check.videoFiles, check.truncated)}
        </p>
      )}
      {check?.readable && folderLayoutWarning(type, check.sample) && <p className="text-sm text-warn">{folderLayoutWarning(type, check.sample)}</p>}
      {check?.readable && check.videoFiles === 0 && <p className="text-sm text-neutral-400">No video files found yet. You can save this folder now, then add files and scan it again.</p>}
      {checkError && <p role="alert" className="text-sm text-warn">{checkError}</p>}
      {error && <p role="alert" className="text-sm text-red-400">{error}</p>}
      {note && (
        <p className="flex items-start gap-2 text-sm text-neutral-300">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0">
            <circle cx="12" cy="12" r="9" />
            <path d="M12 8v5M12 16.5v.01" />
          </svg>
          <span>{note}</span>
        </p>
      )}
      <div className="flex gap-2">
        <button type="submit" disabled={busy || !path} className="min-h-[40px] rounded-md btn-primary px-4 text-sm font-semibold disabled:opacity-50">
          {submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} className="min-h-[40px] rounded-md border border-line-2 px-4 text-sm">
            Cancel
          </button>
        )}
      </div>
      {picking && (
        <FolderPicker
          initial={path || undefined}
          onPick={(p) => {
            setPath(p)
            setPicking(false)
            void runCheck(p)
          }}
          onClose={() => setPicking(false)}
        />
      )}
    </form>
  )
}
