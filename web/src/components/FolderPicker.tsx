import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { browseQuery } from '../api/settings'

/** Modal that walks the server's folders and returns the chosen path. */
export function FolderPicker({ initial, onPick, onClose }: { initial?: string; onPick: (path: string) => void; onClose: () => void }) {
  const [path, setPath] = useState(initial ?? '')
  const list = useQuery(browseQuery(path))
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/70 p-4 sm:p-10" onClick={onClose}>
      <div className="w-full max-w-xl rounded-lg border border-line-2 bg-surface p-4 shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-base font-semibold">Choose a folder on the server</h2>
          <button type="button" onClick={onClose} className="text-neutral-400 hover:text-neutral-100">
            ✕
          </button>
        </div>
        <div className="mt-3 flex items-center gap-2 text-sm">
          <button type="button" disabled={!list.data?.parent && !path} onClick={() => setPath(list.data?.parent ?? '')} className="rounded-md border border-line-2 px-2 py-1 disabled:opacity-40">
            ↑
          </button>
          <code className="min-w-0 flex-1 truncate rounded bg-ground px-2 py-1">{path || 'Roots'}</code>
        </div>
        <ul className="mt-3 max-h-[50vh] divide-y divide-line overflow-y-auto rounded-md border border-line">
          {list.data?.entries.map((e) => (
            <li key={e.path}>
              <button type="button" onClick={() => setPath(e.path)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-surface-2">
                <span className="text-neutral-500">📁</span>
                <span className="truncate">{e.name}</span>
              </button>
            </li>
          ))}
          {list.data && list.data.entries.length === 0 && <li className="px-3 py-2 text-sm text-neutral-500">No subfolders.</li>}
        </ul>
        <div className="mt-3 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="min-h-[36px] rounded-md border border-line-2 px-3 text-sm">
            Cancel
          </button>
          <button type="button" disabled={!path} onClick={() => onPick(path)} className="min-h-[36px] rounded-md btn-primary px-3 text-sm font-semibold disabled:opacity-50">
            Use this folder
          </button>
        </div>
      </div>
    </div>
  )
}
