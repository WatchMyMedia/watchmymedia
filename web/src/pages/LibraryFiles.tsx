import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { filesQuery, healthQuery, libraryQuery, type FileStatus, type MatchStatus } from '../api/queries'
import { FilesTable } from '../components/FilesTable'
import { LibraryHeader } from '../components/LibraryHeader'
import { Pager } from '../components/Pager'

const PAGE = 100

/** Admin view of one library's files, with scan/match controls. */
export function LibraryFiles({ libraryId }: { libraryId: number }) {
  const library = useQuery(libraryQuery(libraryId))
  if (library.isPending) return <p className="text-neutral-400">Loading…</p>
  if (library.isError) return <p className="text-red-400">{library.error.message}</p>
  const lib = library.data
  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <LibraryHeader library={lib} />
      <FilesView libraryId={lib.id} type={lib.type} />
    </div>
  )
}

/** Filterable file table for the admin file list. */
export function FilesView({ libraryId, type, initialMatch }: { libraryId: number; type: 'movie' | 'tv'; initialMatch?: MatchStatus }) {
  const [status, setStatus] = useState<FileStatus>(initialMatch ? 'present' : undefined)
  const [match, setMatch] = useState<MatchStatus>(initialMatch)
  const [offset, setOffset] = useState(0)
  const library = useQuery(libraryQuery(libraryId))
  const health = useQuery(healthQuery)
  const files = useQuery(filesQuery(libraryId, status, match, PAGE, offset))
  const lib = library.data
  const total = files.data?.total ?? 0
  const chip = (active: boolean) =>
    `min-h-[36px] rounded-full border px-3 py-1 ${active ? 'border-accent text-neutral-100' : 'border-line-2 text-neutral-400 hover:text-neutral-200'}`

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        {(
          [
            ['All', undefined, lib ? lib.fileCount + lib.missingCount : undefined],
            ['Present', 'present', lib?.fileCount],
            ['Missing', 'missing', lib?.missingCount],
          ] as const
        ).map(([label, value, count]) => (
          <button
            key={label}
            type="button"
            onClick={() => {
              setStatus(value)
              setOffset(0)
            }}
            className={chip(status === value)}
          >
            {label} {count != null && <span className="text-neutral-500">{count}</span>}
          </button>
        ))}
        <span className="mx-1 text-neutral-700">|</span>
        {(
          [
            ['Any match', undefined],
            ['Matched', 'matched'],
            ['Unmatched', 'unmatched'],
            ['Pending', 'pending'],
            ['Failed', 'failed'],
          ] as const
        ).map(([label, value]) => (
          <button
            key={label}
            type="button"
            onClick={() => {
              setMatch(value)
              setOffset(0)
            }}
            className={chip(match === value)}
          >
            {label}
          </button>
        ))}
      </div>

      {files.isError && <p className="text-red-400">{files.error.message}</p>}
      {files.data && files.data.items.length === 0 && (
        <p className="text-neutral-400">
          {total === 0 && status === undefined && match === undefined
            ? 'No video files found yet. Run a scan, or check that the folder is mounted and readable.'
            : 'Nothing matches this filter.'}
        </p>
      )}
      {files.data && files.data.items.length > 0 && (
        <FilesTable files={files.data.items} type={type} tmdbConfigured={health.data?.tmdbConfigured ?? false} />
      )}
      <Pager offset={offset} limit={PAGE} total={total} onChange={setOffset} />
    </div>
  )
}
