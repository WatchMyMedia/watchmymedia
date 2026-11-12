import { useState } from 'react'
import { episodeCode, formatBytes, formatDate, formatDuration, resolutionLabel } from '../lib/format'
import { FixMatchDialog } from './FixMatchDialog'
import { SegmentEditorDialog } from './SegmentEditorDialog'
import { useAuth } from '../authContext'
import type { components } from '../api/schema'

type MediaFile = components['schemas']['MediaFile']

const matchStyles: Record<MediaFile['matchStatus'], string> = {
  matched: 'bg-accent/15 text-accent',
  pending: 'bg-neutral-800 text-neutral-400',
  unmatched: 'bg-amber-900/50 text-amber-300',
  failed: 'bg-red-900/50 text-red-300',
}

export function FilesTable({ files, type, tmdbConfigured }: { files: MediaFile[]; type: 'movie' | 'tv'; tmdbConfigured: boolean }) {
  const [fixing, setFixing] = useState<MediaFile | null>(null)
  const [editing, setEditing] = useState<MediaFile | null>(null)
  const auth = useAuth()
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  return (
    <>
      <div className="overflow-x-auto rounded-lg border border-neutral-800">
        <table className="w-full text-sm">
          <thead className="bg-neutral-900 text-left text-xs uppercase tracking-wide text-neutral-500">
            <tr>
              <th className="px-3 py-2">Title</th>
              <th className="px-3 py-2">Video</th>
              <th className="px-3 py-2">Audio</th>
              <th className="px-3 py-2 text-right">Length</th>
              <th className="px-3 py-2 text-right">Size</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Match</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-800">
            {files.map((f) => (
              <FileRow key={f.id} file={f} type={type} onFix={tmdbConfigured ? () => setFixing(f) : undefined} onSegments={admin ? () => setEditing(f) : undefined} />
            ))}
          </tbody>
        </table>
      </div>
      {fixing && <FixMatchDialog file={fixing} type={type} onClose={() => setFixing(null)} />}
      {editing && <SegmentEditorDialog fileId={editing.id} title={editing.relativePath} onClose={() => setEditing(null)} />}
    </>
  )
}

function FileRow({ file, type, onFix, onSegments }: { file: MediaFile; type: 'movie' | 'tv'; onFix?: () => void; onSegments?: () => void }) {
  const g = file.parsed
  let title: string
  let subtitle = ''
  if (type === 'tv' && g.kind === 'episode') {
    // Daily shows are named by air date; matching places them in TMDB's seasons.
    title = `${g.title} · ${episodeCode(g.season, g.episodes) || (g.airDate ? `aired ${formatDate(g.airDate)}` : '?')}`
    subtitle = g.episodeTitle ?? ''
  } else {
    title = g.year ? `${g.title} (${g.year})` : g.title
  }
  const res = resolutionLabel(file.width, file.height)
  return (
    <tr className={file.status === 'missing' ? 'opacity-60' : ''}>
      <td className="max-w-xl px-3 py-2">
        <div className="truncate font-medium">
          {title}
          {subtitle && <span className="font-normal text-neutral-400"> — {subtitle}</span>}
          {g.kind === 'unknown' && (
            <span className="ml-2 rounded bg-amber-900/50 px-1.5 py-0.5 text-xs text-amber-300">unrecognised name</span>
          )}
        </div>
        <div className="truncate text-xs text-neutral-500">{file.relativePath}</div>
      </td>
      <td className="whitespace-nowrap px-3 py-2 text-neutral-300">
        {file.probeError ? (
          <span className="text-red-400" title={file.probeError}>
            unreadable
          </span>
        ) : (
          [res, file.videoCodec].filter(Boolean).join(' · ')
        )}
      </td>
      <td className="whitespace-nowrap px-3 py-2 text-neutral-300">{file.audioCodec}</td>
      <td className="whitespace-nowrap px-3 py-2 text-right text-neutral-300">{formatDuration(file.durationMs ?? 0)}</td>
      <td className="whitespace-nowrap px-3 py-2 text-right text-neutral-300">{formatBytes(file.size)}</td>
      <td className="whitespace-nowrap px-3 py-2">
        <span
          className={`rounded px-1.5 py-0.5 text-xs ${
            file.status === 'present' ? 'bg-emerald-900/50 text-emerald-300' : 'bg-neutral-800 text-neutral-400'
          }`}
        >
          {file.status}
        </span>
      </td>
      <td className="whitespace-nowrap px-3 py-2">
        <span className={`rounded px-1.5 py-0.5 text-xs ${matchStyles[file.matchStatus]}`} title={file.matchError}>
          {file.matchStatus}
          {file.matchLocked ? ' 🔒' : ''}
        </span>
        {onFix && file.status === 'present' && (
          <button type="button" onClick={onFix} className="ml-2 text-xs text-neutral-400 underline-offset-2 hover:text-neutral-100 hover:underline">
            {file.matchStatus === 'matched' ? 'change' : 'fix'}
          </button>
        )}
        {onSegments && file.status === 'present' && (
          <button type="button" onClick={onSegments} className="ml-2 text-xs text-neutral-400 underline-offset-2 hover:text-neutral-100 hover:underline">
            skips
          </button>
        )}
      </td>
    </tr>
  )
}
