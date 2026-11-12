import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { healthQuery, librariesQuery } from '../../api/queries'
import { useCreateLibrary, useDeleteLibrary, useUpdateLibrary } from '../../api/settings'
import { relativeTime } from '../../lib/format'
import { ActivityBar } from '../../components/ActivityBar'
import { LibraryActions } from '../../components/LibraryActions'
import { LibraryForm } from '../../components/LibraryForm'
import { PageHeader, Panel, primaryButton, secondaryButton } from '../../components/SettingsPanel'
import { ScanIntervalSection } from '../../components/SettingsSections'
import type { components } from '../../api/schema'
import { ServerOnly } from './SettingsLayout'
import { useSettingsAccess } from './shared'

type Library = components['schemas']['Library']
type LibraryType = 'movie' | 'tv'

/** Settings › Libraries: the folders this server scans, with their counts, scans and matches. */
export function LibrariesSettings() {
  const { admin } = useSettingsAccess()
  const [adding, setAdding] = useState<LibraryType | null>(null)
  return (
    <>
      <PageHeader
        title="Libraries"
        actions={
          admin && (
            <>
              <Link to="/setup" className={`${secondaryButton} inline-flex items-center`}>
                Setup wizard
              </Link>
              <button type="button" onClick={() => setAdding(adding ? null : 'movie')} aria-expanded={adding !== null} className={primaryButton}>
                Add library
              </button>
            </>
          )
        }
      >
        Folders this server scans for movies and TV. Removing a library leaves its files on disk untouched.
      </PageHeader>
      <ServerOnly>
        <div className="space-y-6">
          <LibraryList adding={adding} setAdding={setAdding} />
          <ScanIntervalSection />
        </div>
      </ServerOnly>
    </>
  )
}

function LibraryList({ adding, setAdding }: { adding: LibraryType | null; setAdding: (t: LibraryType | null) => void }) {
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? false
  const libraries = useQuery(librariesQuery)
  const create = useCreateLibrary()
  return (
    <div className="space-y-4">
      {adding && (
        <Panel title="New library" actions={<TypeSwitch value={adding} onChange={setAdding} />}>
          <div className="px-5 pb-5">
            <LibraryForm
              key={adding}
              type={adding}
              submitLabel="Add"
              busy={create.isPending}
              error={create.isError ? create.error.message : null}
              onCancel={() => setAdding(null)}
              onSubmit={(v) => create.mutate({ type: adding, ...v }, { onSuccess: () => setAdding(null) })}
            />
          </div>
        </Panel>
      )}
      {libraries.isError && <p className="text-sm text-red-400">{libraries.error.message}</p>}
      {libraries.data && libraries.data.items.length === 0 && (
        <p className="text-neutral-400">
          No libraries yet.{' '}
          <Link to="/setup" className="underline">
            Run the setup wizard.
          </Link>
        </p>
      )}
      <ul className="grid gap-4 lg:grid-cols-2">
        {libraries.data?.items.map((lib) => (
          <LibraryCard key={lib.id} lib={lib} tmdb={tmdb} />
        ))}
      </ul>
    </div>
  )
}

/** Movies or TV for a new library; the form starts over with that type's name. */
function TypeSwitch({ value, onChange }: { value: LibraryType; onChange: (t: LibraryType) => void }) {
  return (
    <div role="radiogroup" aria-label="Library type" className="flex rounded-md border border-line-2 p-0.5">
      {(['movie', 'tv'] as const).map((t) => (
        <label
          key={t}
          className={`cursor-pointer rounded px-3 py-1.5 text-sm has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-accent ${value === t ? 'bg-neutral-100 text-black' : 'text-neutral-300 hover:text-neutral-100'}`}
        >
          <input type="radio" name="library-type" value={t} checked={value === t} onChange={() => onChange(t)} className="sr-only" />
          {t === 'tv' ? 'TV shows' : 'Movies'}
        </label>
      ))}
    </div>
  )
}

function LibraryCard({ lib, tmdb }: { lib: Library; tmdb: boolean }) {
  const [editing, setEditing] = useState(false)
  const update = useUpdateLibrary()
  const remove = useDeleteLibrary()
  if (editing) {
    return (
      <li className="rounded-xl border border-line bg-surface p-5">
        <LibraryForm
          type={lib.type}
          initialName={lib.name}
          initialPath={lib.path}
          submitLabel="Save"
          busy={update.isPending}
          error={update.isError ? update.error.message : null}
          onCancel={() => setEditing(false)}
          onSubmit={(v) => update.mutate({ id: lib.id, ...v }, { onSuccess: () => setEditing(false) })}
        />
      </li>
    )
  }
  const unmatched = lib.unmatchedCount.toLocaleString()
  return (
    <li className="flex flex-col rounded-xl border border-line bg-surface">
      <div className="px-5 pt-4">
        <div className="flex items-center justify-between gap-3">
          <Link to="/libraries/$libraryId/files" params={{ libraryId: String(lib.id) }} className="text-[17px] font-semibold hover:underline">
            {lib.name}
          </Link>
          <span className="rounded-full border border-line-2 px-2 text-xs text-neutral-400">{lib.type === 'tv' ? 'TV' : 'Movies'}</span>
        </div>
        <p className="mt-1 break-all font-mono text-xs text-neutral-400">{lib.path}</p>
      </div>
      <dl className="mx-5 mt-4 grid grid-cols-3 gap-3 border-t border-line pt-3.5">
        <Stat label="Files">{lib.fileCount.toLocaleString()}</Stat>
        <Stat label="Unmatched" warn={lib.unmatchedCount > 0}>
          {unmatched}
        </Stat>
        <div>
          <dt className="text-xs text-neutral-400">Last scan</dt>
          <dd className="mt-1.5 text-sm font-medium">{relativeTime(lib.lastScannedAt)}</dd>
        </div>
      </dl>
      {(lib.missingCount > 0 || lib.pendingCount > 0) && (
        <p className="mx-5 mt-2 text-xs text-neutral-400">
          {lib.missingCount > 0 && <span className="text-warn">{lib.missingCount.toLocaleString()} missing</span>}
          {lib.missingCount > 0 && lib.pendingCount > 0 && ' · '}
          {lib.pendingCount > 0 && `${lib.pendingCount.toLocaleString()} awaiting match`}
        </p>
      )}
      {lib.watch && lib.watch.state !== 'watching' && <p className="mx-5 mt-2 text-xs text-neutral-400">{watchNote(lib.watch)}</p>}
      <div className="px-5">
        <ActivityBar library={lib} />
      </div>
      <div className="mt-auto flex flex-wrap items-center gap-2 px-5 pt-4">
        <LibraryActions library={lib} tmdbConfigured={tmdb} />
        {lib.unmatchedCount > 0 && (
          <Link to={lib.type === 'tv' ? '/tv' : '/movies'} search={{ view: 'unmatched' }} className="ml-auto text-sm text-warn hover:underline">
            Review {unmatched} unmatched
          </Link>
        )}
      </div>
      <div className="flex gap-1 px-3 pb-3 pt-1">
        <button type="button" onClick={() => setEditing(true)} className="min-h-[32px] px-2 text-sm text-neutral-400 hover:text-neutral-100">
          Edit
        </button>
        <button
          type="button"
          onClick={() => {
            if (window.confirm(`Remove "${lib.name}"? Files on disk are untouched; indexed items and watch history for them are deleted.`)) remove.mutate(lib.id)
          }}
          className="min-h-[32px] px-2 text-sm text-neutral-400 hover:text-red-400"
        >
          Remove
        </button>
      </div>
    </li>
  )
}

/** Why changes in this library may wait for the scheduled scan. */
function watchNote(w: NonNullable<Library['watch']>): string {
  if (w.state === 'partial') {
    const all = w.folders + w.unwatched
    const rest = w.unwatched === 1 ? 'one' : w.unwatched.toLocaleString()
    return `Watching ${w.folders.toLocaleString()} of ${all.toLocaleString()} folders, the most this system allows. Changes in the other ${rest} show up at the next scheduled scan.`
  }
  return 'Not watching this folder, so changes show up at the next scheduled scan.'
}

function Stat({ label, warn, children }: { label: string; warn?: boolean; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-neutral-400">{label}</dt>
      <dd className={`mt-0.5 text-xl font-semibold ${warn ? 'text-warn' : ''}`}>{children}</dd>
    </div>
  )
}
