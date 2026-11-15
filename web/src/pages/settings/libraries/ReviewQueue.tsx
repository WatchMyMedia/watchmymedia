import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { healthQuery, librariesQuery, unmatchedQuery, useMatchFiles } from '../../../api/queries'
import { FlowFrame, type FooterAction } from '../../../components/settings/Flow'
import { HrefLink, LoadingRows, Message, Outcome, Pill } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { UnmatchedGroupCard, type GroupOutcome } from '../../../components/UnmatchedView'
import { entryWord, isSorted, nextOpen, queueEntries, queuePlace, queueSummary, type QueueEntry, type QueuePlace, type Recorded } from '../../../lib/librariesSettings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { MatchingOff } from './parts'
import type { components } from '../../../api/schema'

type Library = components['schemas']['Library']
type Group = components['schemas']['UnmatchedGroup']

/**
 * Review unmatched (boards lib-review, lib-review-done): the library's
 * unmatched files as a queue under Settings, one group at a time, with a
 * count, the list beside it on wide screens, Previous, Skip for now and the
 * action. Each card is the Unmatched view's own, with its dialogs. Nothing
 * here asks for the password.
 */
export function ReviewUnmatched({ id, step }: { id: number; step?: string }) {
  const libraries = useQuery(librariesQuery)
  const lib = libraries.data?.items.find((l) => l.id === id)
  const crumbs = settingsCrumbs('libraries', { label: lib?.name ?? 'Library', href: settingsHref('libraries', id) }, { label: 'Review unmatched' })
  if (!lib) {
    return (
      <FlowFrame tab="libraries" crumbs={crumbs} title="Review unmatched files" back={{ label: 'Libraries', href: settingsHref('libraries') }}>
        {libraries.isPending ? <LoadingRows rows={3} /> : <Message tone="info" title="This library isn’t here">It may have been removed. The other libraries are on the Libraries page.</Message>}
      </FlowFrame>
    )
  }
  return <Queue lib={lib} step={step} crumbs={crumbs} />
}

function Queue({ lib, step, crumbs }: { lib: Library; step?: string; crumbs: ReturnType<typeof settingsCrumbs> }) {
  const go = useGo()
  const topMatch = useMatchFiles()
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? true
  const groups = useQuery(unmatchedQuery(lib.id))
  // The queue keeps the order it started with, so files sorted here stay in
  // the list with what happened to them.
  const [snapshot, setSnapshot] = useState<Group[] | null>(null)
  if (snapshot === null && groups.data) setSnapshot(groups.data.groups.filter((g) => g.kind !== 'ignored'))
  const [skipped, setSkipped] = useState<ReadonlySet<string>>(new Set())
  const [recorded, setRecorded] = useState<ReadonlyMap<string, Recorded>>(new Map())
  const [said, setSaid] = useState<{ key: string; outcome: GroupOutcome } | null>(null)

  const entries = snapshot && groups.data ? queueEntries(snapshot, groups.data.groups, groups.dataUpdatedAt, skipped, recorded) : []
  const place: QueuePlace | null = snapshot && groups.data ? queuePlace(entries, step) : null
  const wanted = place ? ('done' in place ? 'done' : String(place.at)) : undefined
  const pos = (p: QueuePlace) => settingsHref('libraries', lib.id, 'unmatched', 'done' in p ? 'done' : p.at)
  useEffect(() => {
    if (wanted && wanted !== step) go(settingsHref('libraries', lib.id, 'unmatched', wanted), { replace: true })
  }, [go, lib.id, step, wanted])

  if (groups.isError) {
    return (
      <FlowFrame tab="libraries" crumbs={crumbs} title="Review unmatched files" back={{ label: lib.name, href: settingsHref('libraries', lib.id) }}>
        <Message tone="err" title="Couldn’t load the unmatched files">The server didn’t answer. Reload the page to try again.</Message>
      </FlowFrame>
    )
  }
  if (!place || !snapshot) {
    return (
      <FlowFrame tab="libraries" crumbs={crumbs} title="Review unmatched files" wide>
        <LoadingRows rows={3} />
      </FlowFrame>
    )
  }
  if (snapshot.length === 0) {
    return (
      <FlowFrame tab="libraries" crumbs={crumbs} finished="ok" title="Every file is matched" lede={`Nothing in ${lib.name} is waiting for you.`} next={{ label: `Back to ${lib.name}`, href: settingsHref('libraries', lib.id) }}>
        <Outcome changed={['Nothing: there was nothing to review.']} next={['New files that can’t be matched join the queue, and the Libraries tab shows how many.']} />
      </FlowFrame>
    )
  }
  if ('done' in place) {
    const firstSkipped = entries.findIndex((e) => e.state === 'skipped')
    return <QueueDone lib={lib} crumbs={crumbs} entries={entries} recorded={recorded} onSeeSkipped={firstSkipped < 0 ? undefined : () => { setSkipped(new Set()); go(pos({ at: firstSkipped + 1 })) }} />
  }

  const at = place.at
  const entry = entries[at - 1]
  const group = entry.live ?? snapshot[at - 1]
  const sorted = isSorted(entry.state)
  const after = (state: QueueEntry['state']) => nextOpen(entries.map((e, i) => (i === at - 1 ? { ...e, state } : e)), at)
  const sortedHere = (o: GroupOutcome) => {
    topMatch.reset()
    setSaid({ key: entry.key, outcome: o })
    // Episodes still to pick keep the files here, under the group the server now holds them in.
    if (o.needEpisodes) return
    const state = o.how === 'seasons' ? 'seasons' : o.how === 'ignored' ? 'ignored' : 'matched'
    setRecorded((r) => new Map(r).set(entry.key, { state, at: Date.now(), folder: o.folder }))
    setSkipped((s) => { const n = new Set(s); n.delete(entry.key); return n })
    go(pos(after(state)))
  }
  const skip = () => {
    topMatch.reset()
    setSkipped((s) => new Set(s).add(entry.key))
    go(pos(after('skipped')))
  }
  const summary = queueSummary(entries, recorded)
  const left = summary.open + summary.skipped

  // The footer's action is the card's own best guess, the same match as its
  // first Match button, so the review can go on from the footer.
  const top = group.kind === 'show' && !sorted ? group.candidates[0] : undefined
  const matchTop = () => {
    if (!top) return
    const name = `${top.title}${top.year ? ` (${top.year})` : ''}`
    topMatch.mutate(
      { libraryId: lib.id, fileIds: group.files.map((f) => f.id), tmdbId: top.tmdbId, remember: true },
      {
        onSuccess: (r) => {
          let text = `Matched ${r.matched} file${r.matched === 1 ? '' : 's'} to ${name}.`
          if (r.needEpisodes) text += ` ${r.needEpisodes} need${r.needEpisodes === 1 ? 's' : ''} lining up next.`
          if (r.folder) text += ` New files in ${r.folder} will use it.`
          sortedHere({ how: 'matched', text, showId: r.showId, movieId: r.movieId, folder: r.folder, needEpisodes: r.needEpisodes })
        },
      },
    )
  }
  const n = group.files.length
  const next: FooterAction = sorted
    ? { label: 'done' in nextOpen(entries, at) ? 'See what happened' : 'Next', href: pos(nextOpen(entries, at)) }
    : top
      ? { label: `Match ${n === 1 ? 'it' : `${n} files`} to ${top.title} and go on`, onClick: matchTop, busy: topMatch.isPending, busyLabel: 'Matching…', disabled: !tmdb }
      : { label: 'Next', disabled: true, reason: 'Say what these files are above, or skip them for now.' }

  return (
    <FlowFrame
      tab="libraries"
      crumbs={crumbs}
      wide
      title="Review unmatched files"
      back={at > 1 ? { label: 'Previous', href: pos({ at: at - 1 }) } : undefined}
      skip={sorted ? undefined : { label: 'Skip for now', onClick: skip }}
      next={next}
    >
      <div className="flex flex-wrap items-center gap-3">
        <Pill tone="now">{at} of {entries.length} · {left} left</Pill>
        <div className="h-1.5 min-w-[8rem] flex-1 overflow-hidden rounded-full bg-line" role="progressbar" aria-label="Sorted" aria-valuemin={0} aria-valuemax={entries.length} aria-valuenow={summary.sorted}>
          <div className="h-full rounded-full bg-emerald-400 transition-[width] motion-reduce:transition-none" style={{ width: `${(summary.sorted / entries.length) * 100}%` }} />
        </div>
      </div>
      {!tmdb && <div className="mt-4"><MatchingOff /></div>}
      {said && said.key !== entry.key && (
        <Message tone="ok" className="mt-4">✓ {said.outcome.text}</Message>
      )}
      <div className="mt-5 grid items-start gap-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
        <QueueList entries={entries} at={at} type={lib.type} href={(i) => pos({ at: i + 1 })} />
        <div className="min-w-0">
          {sorted ? (
            <SortedCard entry={entry} outcome={said?.key === entry.key ? said.outcome : undefined} />
          ) : (
            <>
              {said?.key === entry.key && <Message tone="ok" className="mb-4">✓ {said.outcome.text}</Message>}
              <UnmatchedGroupCard key={group.key} g={group} libraryId={lib.id} type={lib.type} onSorted={sortedHere} />
            </>
          )}
          {topMatch.isError && (
            <Message tone="err" className="mt-4" focus title="Couldn’t match these files">
              TMDB didn’t answer, so nothing was changed. Try again, or skip and come back.
            </Message>
          )}
        </div>
      </div>
    </FlowFrame>
  )
}

function QueueList({ entries, at, type, href }: { entries: QueueEntry[]; at: number; type: 'movie' | 'tv'; href: (i: number) => string }) {
  return (
    <nav aria-label="Unmatched files" className="hidden lg:block">
      <ol className="max-h-[60vh] overflow-y-auto rounded-xl border border-line bg-surface py-1 text-sm">
        {entries.map((e, i) => {
          const current = i === at - 1
          return (
            <li key={e.key}>
              <HrefLink
                href={href(i)}
                aria-current={current ? 'step' : undefined}
                className={`flex min-h-[40px] items-center gap-2 px-3 py-1.5 ${current ? 'bg-accent/[0.08] font-semibold text-neutral-100' : 'text-neutral-300 hover:bg-surface-2'}`}
              >
                <span aria-hidden="true" className={`w-3 shrink-0 ${isSorted(e.state) ? 'text-emerald-300' : 'text-neutral-600'}`}>{isSorted(e.state) ? '✓' : ''}</span>
                <span className="min-w-0 flex-1 truncate">{e.title}</span>
                <span className={`shrink-0 text-xs ${isSorted(e.state) ? 'text-emerald-300' : e.state === 'skipped' ? 'text-neutral-500' : 'text-warn'}`}>{entryWord(e, type)}</span>
              </HrefLink>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

function SortedCard({ entry, outcome }: { entry: QueueEntry; outcome?: GroupOutcome }) {
  const how = entry.state === 'ignored' ? 'Ignored' : entry.state === 'seasons' ? 'Seasons lined up' : 'Matched'
  return (
    <Message
      tone="ok"
      title={`✓ ${entry.title} · ${how}`}
      actions={outcome?.showId != null
        ? <Link to="/shows/$showId" params={{ showId: String(outcome.showId) }} className="text-sm font-medium text-neutral-100 underline">Open show</Link>
        : outcome?.movieId != null && <Link to="/movies/$movieId" params={{ movieId: String(outcome.movieId) }} className="text-sm font-medium text-neutral-100 underline">Open movie</Link>}
    >
      {outcome?.text ?? (entry.state === 'ignored'
        ? `${entry.files === 1 ? 'The file stays' : `The ${entry.files} files stay`} playable from the folder view and aren’t asked about again.`
        : `${entry.files === 1 ? 'The file has' : `The ${entry.files} files have`} posters and details now.`)}
    </Message>
  )
}

function QueueDone({ lib, crumbs, entries, recorded, onSeeSkipped }: { lib: Library; crumbs: ReturnType<typeof settingsCrumbs>; entries: QueueEntry[]; recorded: ReadonlyMap<string, Recorded>; onSeeSkipped?: () => void }) {
  const s = queueSummary(entries, recorded)
  const all = s.sorted === s.total
  return (
    <FlowFrame
      tab="libraries"
      crumbs={crumbs}
      finished={all ? 'ok' : 'partial'}
      title={all ? `All ${s.total.toLocaleString()} sorted` : `${s.sorted.toLocaleString()} of ${s.total.toLocaleString()} sorted`}
      lede={s.skipped ? `${s.skipped.toLocaleString()} skipped for now.` : all ? 'Nothing is waiting.' : undefined}
      skip={onSeeSkipped && { label: 'See skipped files', onClick: onSeeSkipped }}
      next={{ label: `Back to ${lib.name}`, href: settingsHref('libraries', lib.id) }}
    >
      <dl className="grid grid-cols-3 gap-3">
        <Metric value={s.matched} label={lib.type === 'tv' ? 'Matched to a show' : 'Matched to a movie'} />
        <Metric value={s.seasons} label="Seasons lined up" />
        <Metric value={s.ignored} label="Ignored" />
      </dl>
      <div className="mt-5">
        <Outcome
          changed={[
            s.matchedFiles > 0 ? `${s.matchedFiles.toLocaleString()} file${s.matchedFiles === 1 ? '' : 's'} now ${s.matchedFiles === 1 ? 'has' : 'have'} posters and details.` : s.sorted === 0 ? 'Nothing was matched.' : null,
            s.folders > 0 ? `${s.folders.toLocaleString()} folder${s.folders === 1 ? '' : 's'} remember${s.folders === 1 ? 's' : ''} ${s.folders === 1 ? 'its' : 'their'} match for new files.` : null,
            s.ignored > 0 ? 'Ignored files stay playable from the folder view and aren’t asked about again.' : null,
          ]}
          next={[
            s.waitingFiles > 0 ? `${s.waitingFiles.toLocaleString()} file${s.waitingFiles === 1 ? '' : 's'} still wait${s.waitingFiles === 1 ? 's' : ''} under Review unmatched, and the Libraries badge counts ${s.waitingFiles === 1 ? 'it' : 'them'}.` : null,
            'New files that can’t be matched join the queue.',
          ]}
        />
      </div>
    </FlowFrame>
  )
}

function Metric({ value, label }: { value: number; label: string }) {
  return (
    <div className="flex flex-col-reverse rounded-xl border border-line bg-surface p-4">
      <dt className="mt-0.5 text-[13px] text-neutral-400">{label}</dt>
      <dd className="text-2xl font-semibold">{value.toLocaleString()}</dd>
    </div>
  )
}
