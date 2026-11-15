import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { healthQuery, librariesQuery, unmatchedQuery, useMatchLibrary, useScanLibrary } from '../../../api/queries'
import { useDeleteLibrary } from '../../../api/settings'
import { libraryRemovalQuery } from '../../../api/libraryAdmin'
import { useEventState } from '../../../api/events'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message, NextStep, RemoveSection, Spinner } from '../../../components/settings/Frame'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { useGo } from '../../../components/settings/hooks'
import { fileCount, libraryState, removalWords, removeRefusal, startRefusal, unmatchedBreakdown, type Refusal } from '../../../lib/librariesSettings'
import { relativeTime } from '../../../lib/format'
import { savedScanError } from '../../../lib/setupRules'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { leaveFlash, useFlash } from './flash'
import { LinkButton, MatchingOff, RefusalMessage } from './parts'
import type { components } from '../../../api/schema'

type Library = components['schemas']['Library']

/** One library's page (board lib-one): Scan and Match report beside their
 * buttons, working and failing; Name and folder; Remove, apart. */
export function LibraryPage({ id }: { id: number }) {
  const libraries = useQuery(librariesQuery)
  const lib = libraries.data?.items.find((l) => l.id === id)
  if (libraries.isPending) {
    return (
      <HubPage crumbs={settingsCrumbs('libraries', { label: 'Library' })} title="Library">
        <LoadingRows rows={3} />
      </HubPage>
    )
  }
  if (!lib) {
    return (
      <HubPage crumbs={settingsCrumbs('libraries', { label: 'Not found' })} title="This library isn’t here">
        <Message tone="info" title={libraries.isError ? 'Couldn’t load the libraries' : 'It may have been removed'} actions={<LinkButton small href={settingsHref('libraries')}>Back to Libraries</LinkButton>}>
          {libraries.isError ? 'The server didn’t answer. Reload the page to try again.' : 'Nothing is lost by opening this address. The other libraries are on the Libraries page.'}
        </Message>
      </HubPage>
    )
  }
  return <LibraryLoaded lib={lib} />
}

function LibraryLoaded({ lib }: { lib: Library }) {
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? true
  const live = useEventState()
  const page = settingsHref('libraries', lib.id)
  const [flash, dismiss] = useFlash(page)
  const removal = useQuery(libraryRemovalQuery(lib.id))
  const groups = useQuery({ ...unmatchedQuery(lib.id), enabled: lib.unmatchedCount > 0 })
  const [removing, setRemoving] = useState(false)
  const state = libraryState(lib, { scan: live.scans[lib.id], match: live.matches[lib.id], lastScan: live.lastScan[lib.id] })
  const r = removal.data
  const counts = r ? (lib.type === 'tv' ? `${r.shows.toLocaleString()} show${r.shows === 1 ? '' : 's'}, ${r.episodes.toLocaleString()} episode${r.episodes === 1 ? '' : 's'}` : `${r.movies.toLocaleString()} movie${r.movies === 1 ? '' : 's'}`) : fileCount(lib.fileCount)
  const unmatched = lib.unmatchedCount
  const breakdown = groups.data ? unmatchedBreakdown(lib.type, groups.data.groups.filter((g) => g.kind !== 'ignored')) : ''

  return (
    <HubPage
      crumbs={settingsCrumbs('libraries', { label: lib.name })}
      title={lib.name}
      lede={[lib.type === 'tv' ? 'TV' : 'Movies', lib.path, counts, lib.missingCount > 0 ? `${lib.missingCount.toLocaleString()} missing` : ''].filter(Boolean).join(' · ')}
      pill={{ tone: state.tone, label: state.label }}
      actions={<Link to="/libraries/$libraryId/files" params={{ libraryId: String(lib.id) }} className="inline-flex min-h-[36px] items-center rounded-md px-3 text-sm text-neutral-300 hover:bg-surface-2 hover:text-neutral-100">Files</Link>}
      banner={flash ? <Message tone={flash.tone} title={flash.title} actions={<button type="button" onClick={dismiss} className="min-h-[32px] text-sm text-neutral-400 underline hover:text-neutral-100">Dismiss</button>}>{flash.text}</Message> : undefined}
    >
      {unmatched > 0 && (
        <NextStep
          title={`${unmatched.toLocaleString()} file${unmatched === 1 ? '' : 's'} couldn’t be matched`}
          action={<LinkButton variant="primary" href={settingsHref('libraries', lib.id, 'unmatched')}>Review {unmatched.toLocaleString()} unmatched</LinkButton>}
        >
          {breakdown || 'They play, but have no poster or details until you say what they are.'}
        </NextStep>
      )}
      <Card>
        <ScanRow lib={lib} />
        <MatchRow lib={lib} tmdb={tmdb} />
        <ItemRow
          title="Name and folder"
          detail={<>{lib.name} · <span className="font-mono">{lib.path}</span></>}
          control={<LinkButton small href={settingsHref('libraries', lib.id, 'edit')}>Change<span className="sr-only"> name and folder</span></LinkButton>}
        />
        {lib.watch && lib.watch.state !== 'watching' && <ItemRow title="Watching for changes" detail={watchNote(lib.watch)} />}
      </Card>
      {!tmdb && <MatchingOff short />}
      <RemoveSection>
        <ItemRow
          title="Remove this library"
          detail="Files on disk stay. Everyone’s watched marks and resume points on its titles are removed. Plays stay in Activity history."
          control={<Button variant="danger" small guarded onClick={() => setRemoving(true)}>Remove…</Button>}
        />
      </RemoveSection>
      {removing && <RemoveDialog lib={lib} removal={r} counting={removal.isPending} onClose={() => setRemoving(false)} />}
    </HubPage>
  )
}

function ScanRow({ lib }: { lib: Library }) {
  const live = useEventState()
  const scanNow = useScanLibrary()
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const scan = live.scans[lib.id] ?? lib.scan
  const lastScan = live.lastScan[lib.id]
  const failed = lastScan?.failed?.error ?? savedScanError(lib, false, lastScan)
  const start = () => {
    setRefusal(null)
    scanNow.mutate(lib.id, { onError: (e) => setRefusal(startRefusal(e, 'scan', lib.name)) })
  }
  let detail: ReactNode
  if (scan) detail = <Working>{scan.total ? `Scanning · ${scan.scanned.toLocaleString()} of ${scan.total.toLocaleString()} files checked` : 'Scanning · collecting files…'}</Working>
  else if (scanNow.isPending) detail = <Working>Starting the scan…</Working>
  else if (lastScan?.finished) {
    const f = lastScan.finished
    detail = `Last scan just now: ${f.added.toLocaleString()} added, ${f.updated.toLocaleString()} updated, ${f.missing.toLocaleString()} missing${f.failed ? `, ${f.failed.toLocaleString()} unreadable` : ''}${f.skipped ? `, ${f.skipped.toLocaleString()} still copying` : ''}.`
  } else detail = lib.lastScannedAt ? `Reads the folder again for new, changed and missing files. Last scanned ${relativeTime(lib.lastScannedAt)}.` : 'Reads the folder for new, changed and missing files. Not scanned yet.'
  return (
    <ItemRow
      title="Scan now"
      detail={detail}
      control={<Button small disabled={!!scan || scanNow.isPending} onClick={start}>Scan<span className="sr-only"> {lib.name}</span></Button>}
    >
      {!scan && failed && (
        <div className="mt-3">
          <Message tone="err" title={`Couldn’t scan ${lib.name}`} actions={<Button small onClick={start} busy={scanNow.isPending}>Scan again</Button>}>
            {lib.path} can’t be read right now. Check the drive is connected, then scan again.
          </Message>
        </div>
      )}
      {refusal && <div className="mt-3"><RefusalMessage refusal={refusal} focus /></div>}
    </ItemRow>
  )
}

function MatchRow({ lib, tmdb }: { lib: Library; tmdb: boolean }) {
  const live = useEventState()
  const matchNow = useMatchLibrary()
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const match = live.matches[lib.id] ?? lib.match
  const last = live.lastMatch[lib.id]?.finished
  const start = () => {
    setRefusal(null)
    matchNow.mutate({ id: lib.id, rematch: true }, { onError: (e) => setRefusal(startRefusal(e, 'match', lib.name)) })
  }
  let detail: ReactNode
  if (match) detail = <Working>{`Matching · ${match.done.toLocaleString()} of ${match.total.toLocaleString()} files looked up`}</Working>
  else if (matchNow.isPending) detail = <Working>Starting…</Working>
  else if (!tmdb) detail = 'Off: there’s no TMDB key.'
  else if (lib.fileCount === 0) detail = 'Nothing to match yet: the folder has no video files.'
  else if (last) detail = `Looks up unmatched files on TMDB once more. Last run found ${last.matched.toLocaleString()}${last.unmatched ? `, and ${last.unmatched.toLocaleString()} still couldn’t be matched` : ''}.`
  else detail = 'Looks up unmatched files on TMDB once more.'
  return (
    <ItemRow
      title="Match again"
      detail={detail}
      control={<Button small disabled={!tmdb || !!match || matchNow.isPending || lib.fileCount === 0} onClick={start}>Match<span className="sr-only"> {lib.name}</span></Button>}
    >
      {!match && last && last.failed > 0 && (
        <div className="mt-3">
          <Message tone="err" title="Couldn’t match: TMDB didn’t answer" actions={<Button small onClick={start}>Match again</Button>}>
            {last.failed.toLocaleString()} lookup{last.failed === 1 ? '' : 's'} failed, and nothing was changed for {last.failed === 1 ? 'it' : 'them'}. Try again in a few minutes.
          </Message>
        </div>
      )}
      {refusal && <div className="mt-3"><RefusalMessage refusal={refusal} focus /></div>}
    </ItemRow>
  )
}

function Working({ children }: { children: ReactNode }) {
  return <span role="status" className="inline-flex items-center gap-2"><Spinner className="text-accent" />{children}</span>
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

/**
 * Remove (board lib-remove): type the library's name; away from home the
 * server asks for the password. Removing deletes no plays: Activity keeps
 * them. What goes is the titles and everyone's watched marks and resume
 * points on them.
 */
function RemoveDialog({ lib, removal, counting, onClose }: { lib: Library; removal?: components['schemas']['LibraryRemoval']; counting: boolean; onClose: () => void }) {
  const go = useGo()
  const remove = useDeleteLibrary()
  const [refusal, setRefusal] = useState<Refusal | null>(null)
  const words = removalWords(lib, removal)
  return (
    <ConfirmDialog
      title={`Remove ${lib.name}?`}
      lede={counting ? 'Counting what this library holds…' : 'Adding the folder again later matches everything afresh, but watched marks and resume points don’t come back.'}
      stays={words.stays}
      deleted={words.deleted}
      typeToConfirm={lib.name}
      confirmLabel="Remove library"
      guarded
      busy={remove.isPending}
      error={refusal && <><b className="block font-semibold text-red-200">{refusal.title}</b>{refusal.body}</>}
      onClose={onClose}
      onConfirm={() => {
        setRefusal(null)
        remove.mutate(lib.id, {
          onSuccess: () => {
            leaveFlash(settingsHref('libraries'), { tone: 'ok', title: `${lib.name} is removed`, text: `Its files are still in ${lib.path}. Its plays stay in Activity history.` })
            go(settingsHref('libraries'), { replace: true })
          },
          onError: (e) => {
            const said = removeRefusal(e, lib.name)
            if (said?.gone) {
              go(settingsHref('libraries'), { replace: true })
              return
            }
            setRefusal(said)
          },
        })
      }}
    />
  )
}
