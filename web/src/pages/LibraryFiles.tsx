import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { filesQuery, healthQuery, libraryQuery, type MatchStatus } from '../api/queries'
import { BrowseChips } from '../components/BrowseControls'
import { FilesTable } from '../components/FilesTable'
import { LibraryHeader } from '../components/LibraryHeader'
import { Pager } from '../components/Pager'
import { filesChips, filesQueryOf, type FilesFilter } from '../lib/libraryFiles'

const PAGE = 100

/** An admin's view of one library's files: the one page with file names, codecs and sizes up front. */
export function LibraryFiles({ libraryId }: { libraryId: number }) {
  const library = useQuery(libraryQuery(libraryId))
  if (library.isPending) return <p className="text-neutral-400">Loading…</p>
  if (library.isError) return <p className="text-red-400">{library.error.message}</p>
  const lib = library.data
  return (
    <div className="pt-0.5">
      <LibraryHeader library={lib} />
      <div className="mt-6">
        <FilesView libraryId={lib.id} type={lib.type} />
      </div>
      <p className="mt-3.5 text-[13px] text-neutral-400">This page is for admins. Everywhere else, file names, codecs and sizes stay behind “File details”.</p>
    </div>
  )
}

/** The library's files, one filter at a time, a page at a time. */
export function FilesView({ libraryId, type, initialMatch }: { libraryId: number; type: 'movie' | 'tv'; initialMatch?: MatchStatus }) {
  const [filter, setFilter] = useState<FilesFilter>(initialMatch ?? 'all')
  const [offset, setOffset] = useState(0)
  const library = useQuery(libraryQuery(libraryId))
  const health = useQuery(healthQuery)
  const { status, match } = filesQueryOf(filter)
  const files = useQuery(filesQuery(libraryId, status, match, PAGE, offset))
  const lib = library.data
  const total = files.data?.total ?? 0
  return (
    <div className="space-y-3.5">
      {lib && (
        <BrowseChips
          label="Show"
          options={filesChips(lib)}
          value={filter}
          onChange={(v) => {
            setFilter(v)
            setOffset(0)
          }}
        />
      )}
      {files.isError && <p className="text-red-400">{files.error.message}</p>}
      {files.data && files.data.items.length === 0 && (
        <p className="text-neutral-400">
          {total === 0 && filter === 'all' ? 'No video files found yet. Run a scan, or check that the folder is mounted and readable.' : 'No files here.'}
        </p>
      )}
      {files.data && files.data.items.length > 0 && <FilesTable files={files.data.items} type={type} tmdbConfigured={health.data?.tmdbConfigured ?? false} />}
      <Pager offset={offset} limit={PAGE} total={total} onChange={setOffset} />
    </div>
  )
}
