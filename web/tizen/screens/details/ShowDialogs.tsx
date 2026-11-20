import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { deletePreviewQuery, useDeleteItem, useReplaceFile, type DeleteItem, type DeleteResult, type ReplaceResult } from '../../../src/api/delete'
import type { components } from '../../../src/api/schema'
import { deleteBody, deleteTitle, initialChoices, otherEpisodes, reachable, whatGoes } from '../../../src/lib/delete'
import { formatBytes } from '../../../src/lib/format'
import { Dialog, type DialogAction } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { Glyph } from './Glyph'
import { fileName } from './movieLabels'
import { deleteWhere, replaceBody, unansweredLines } from './showLabels'

type Episode = components['schemas']['Episode']
type MediaFile = components['schemas']['MediaFile']

/** The file the dialog is about: its name in a monospace line, and its size. */
function FileLine({ file, size }: { file: MediaFile; size?: number }) {
  return <p className="dt-file"><Glyph name="file"/><span className="dt-file__name">{fileName(file.relativePath)}</span><span className="dt-file__size">{formatBytes(size || file.size)}</span></p>
}

/** WatchMyMedia can't delete a file no Radarr or Sonarr manages. */
const CANT_DELETE = 'WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk.'

/**
 * Delete episode… (ShowPage's confirmDelete): what Sonarr will do, as the
 * preview says, with focus on Cancel, which Return also is; or, for a file
 * no Radarr or Sonarr manages, that WatchMyMedia can't, with OK.
 */
export function DeleteEpisodeDialog({ showId, kicker, e, onDeleted, onClose }: { showId: number; kicker: string; e: Episode; onDeleted(result: DeleteResult): void; onClose(): void }) {
  const item: DeleteItem = { type: 'episode', id: e.id, showId }
  const preview = useQuery(deletePreviewQuery(item))
  const del = useDeleteItem(item)
  const [recordAgain, setRecordAgain] = useState(false)
  const file = e.files[0]
  const p = preview.data
  const apps = p ? reachable(p) : []
  const cant = !!p && !p.managed && !p.recording
  const ready = !!p && !cant && (p.recording || apps.length > 0)
  const close = () => { if (!del.isPending) onClose() }
  const actions: DialogAction[] = [{ id: 'delete/cancel', label: cant || preview.isError ? 'OK' : 'Cancel', safe: true, onPress: close }]
  if (!cant && !preview.isError) {
    actions.push({ id: 'delete/confirm', label: 'Delete episode', variant: 'danger', busy: !ready || del.isPending, onPress: () => {
      if (!p || !ready) return
      del.mutate({ ...deleteBody('episode', initialChoices(p)), recordAgain }, { onSuccess: onDeleted })
    } })
  }
  return <Dialog title={deleteTitle('episode', '', { episode: e.title || `Episode ${e.number}` })} onCancel={close} actions={actions}>
    <p className="dt-dialog-kicker">{kicker}</p>
    {file && <FileLine file={file} size={p?.size}/>}
    {preview.isPending && <span className="tv-spinner" aria-label="Asking Sonarr"/>}
    {preview.isError && <p className="dt-dialog-body dt-dialog-body--warn">{problemWords(preview.error).title}. {problemWords(preview.error).body}</p>}
    {cant && <p className="dt-dialog-body">{CANT_DELETE}</p>}
    {p && !cant && <>
      {p.recording
        ? <>
          <p className="dt-dialog-body">WatchMyMedia will delete the files it recorded. This can’t be undone.</p>
          <span className="dt-small"><button type="button" role="switch" aria-checked={recordAgain} data-focus="delete/record" className="dt-switch" onClick={() => setRecordAgain(!recordAgain)}>
            <span className="dt-switch__knob" aria-hidden="true"/>Record it again if it airs again
          </button></span>
        </>
        : <p className="dt-dialog-body">{[whatGoes('episode', apps, false).filter((x): x is string => typeof x === 'string').join(''), deleteWhere(apps)].filter(Boolean).join(' ')}</p>}
      {otherEpisodes(apps) && <p className="dt-dialog-body">{otherEpisodes(apps)}</p>}
      {unansweredLines(p.apps).map((l) => <p key={l} className="dt-dialog-body dt-dialog-body--warn">{l}</p>)}
      <p className="dt-dialog-body dt-dialog-body--soft">Your watch history stays.</p>
    </>}
    {del.isError && <p className="dt-dialog-body dt-dialog-body--warn">Couldn’t delete it. {problemWords(del.error).title}.</p>}
  </Dialog>
}

/**
 * Replace file (admins, with Sonarr): it blocks the release, deletes the
 * file and searches for another. Focus starts on Cancel.
 */
export function ReplaceDialog({ app, kicker, file, onReplaced, onClose }: { app: string; kicker: string; file: MediaFile; onReplaced(r: ReplaceResult): void; onClose(): void }) {
  const replace = useReplaceFile()
  const close = () => { if (!replace.isPending) onClose() }
  return <Dialog title="Replace this file?" onCancel={close} actions={[
    { id: 'replace/cancel', label: 'Cancel', safe: true, onPress: close },
    { id: 'replace/confirm', label: 'Replace file', variant: 'danger', busy: replace.isPending, onPress: () => replace.mutate(file.id, { onSuccess: onReplaced }) },
  ]}>
    <p className="dt-dialog-kicker">{kicker}</p>
    <FileLine file={file}/>
    <p className="dt-dialog-body">{replaceBody(app)}</p>
    {replace.isError && <p className="dt-dialog-body dt-dialog-body--warn">Couldn’t replace it. {problemWords(replace.error).title}.</p>}
  </Dialog>
}

