import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { inboxQuery, useDecideRequest, useResolveProblem, type MediaRequest, type Problem } from '../../api/requests'
import { PageHeader, Panel, primaryButton, secondaryButton, textInput } from '../../components/SettingsPanel'
import { relativeTime } from '../../lib/format'
import { whatLabel } from '../../lib/requests'
import { RequestRow } from './MyRequests'
import { ServerOnly } from './SettingsLayout'

const kindLabel: Record<Problem['kind'], string> = { video: 'Video', audio: 'Audio', subtitles: 'Subtitles', other: 'Other' }

/** Settings › Inbox (admins): requests waiting for approval and reported problems (PLAN-ARR.md §7.2, §9). */
export function InboxSettings() {
  const [tab, setTab] = useState<'requests' | 'problems'>('requests')
  const inbox = useQuery(inboxQuery)
  const waiting = inbox.data?.requests.filter((r) => r.status === 'pending' || r.status === 'failed').length ?? 0
  return (
    <>
      <PageHeader title="Inbox">Requests that wait for an admin, and problems people reported with what they watched.</PageHeader>
      <ServerOnly>
        <div role="tablist" aria-label="Inbox" className="mb-4 flex gap-1 border-b border-line">
          {(['requests', 'problems'] as const).map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={tab === t}
              onClick={() => setTab(t)}
              className={`min-h-[44px] border-b-2 px-3 text-sm ${tab === t ? 'border-accent text-neutral-100' : 'border-transparent text-neutral-400 hover:text-neutral-100'}`}
            >
              {t === 'requests' ? 'Requests' : 'Problems'}
              <span className="ml-1.5 text-xs text-neutral-500">{t === 'requests' ? waiting : (inbox.data?.problems.length ?? 0)}</span>
            </button>
          ))}
        </div>
        {inbox.isPending && <p className="text-neutral-400">Loading…</p>}
        {inbox.isError && <p className="text-red-400">{inbox.error.message}</p>}
        {inbox.data && tab === 'requests' && inbox.data.seerr && (
          <div className="space-y-6">
            <Panel>
              <div className="flex flex-wrap items-center justify-between gap-4 px-5 py-5">
                <h3 className="text-[15px] font-semibold">Requests are approved in Seerr</h3>
                <a href={inbox.data.seerr.requestsUrl} target="_blank" rel="noreferrer" className={`${secondaryButton} inline-flex items-center gap-2`}>
                  Open Seerr
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M14 4h6v6M20 4l-9 9M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5" />
                  </svg>
                </a>
              </div>
            </Panel>
            {inbox.data.requests.length > 0 && (
              <Panel title="Asked for before Seerr was connected" description="These still go to Radarr or Sonarr when approved.">
                {inbox.data.requests.map((r) => (
                  <InboxRequest key={r.id} r={r} />
                ))}
              </Panel>
            )}
          </div>
        )}
        {inbox.data && tab === 'requests' && !inbox.data.seerr && (
          <Panel>
            {inbox.data.requests.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No requests are waiting.</p>}
            {inbox.data.requests.map((r) => (
              <InboxRequest key={r.id} r={r} />
            ))}
          </Panel>
        )}
        {inbox.data && tab === 'problems' && (
          <Panel>
            {inbox.data.problems.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">No problems reported.</p>}
            {inbox.data.problems.map((p) => (
              <InboxProblem key={p.id} p={p} />
            ))}
          </Panel>
        )}
      </ServerOnly>
    </>
  )
}

function InboxRequest({ r }: { r: MediaRequest }) {
  const decide = useDecideRequest()
  const [declining, setDeclining] = useState(false)
  const [note, setNote] = useState('')
  const waiting = r.status === 'pending' || r.status === 'failed'
  return (
    <div className="border-t border-line first:border-t-0">
      <p className="px-5 pt-3 text-sm">
        <strong>{r.requestedBy}</strong> asked for <strong>{whatLabel(r) === 'Movie' ? 'the movie' : whatLabel(r)}</strong>
        {r.mediaType === 'tv' && (
          <>
            {' '}
            of <em>{r.title}</em>
          </>
        )}{' '}
        · {relativeTime(r.createdAt)}
      </p>
      <RequestRow r={{ ...r, mine: false }}>
        {waiting && !declining && (
          <>
            <button type="button" className={primaryButton} disabled={decide.isPending} onClick={() => decide.mutate({ id: r.id, approve: true })}>
              {r.status === 'failed' ? 'Retry' : 'Approve'}
            </button>
            <button type="button" className={secondaryButton} onClick={() => setDeclining(true)}>
              Decline
            </button>
          </>
        )}
      </RequestRow>
      {declining && (
        <form
          className="flex flex-wrap items-end gap-2 px-5 pb-4"
          onSubmit={(e) => {
            e.preventDefault()
            decide.mutate({ id: r.id, approve: false, note }, { onSuccess: () => setDeclining(false) })
          }}
        >
          <label className="flex min-w-0 flex-1 basis-72 flex-col gap-1 text-sm">
            Tell {r.requestedBy} why (optional)
            <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} className={textInput} />
          </label>
          <button type="submit" className={secondaryButton} disabled={decide.isPending}>
            Decline
          </button>
          <button type="button" className={secondaryButton} onClick={() => setDeclining(false)}>
            Cancel
          </button>
        </form>
      )}
      {decide.isError && <p className="px-5 pb-3 text-sm text-warn">{decide.error.message}</p>}
    </div>
  )
}

function InboxProblem({ p }: { p: Problem }) {
  const resolve = useResolveProblem()
  return (
    <div className="flex flex-wrap items-start gap-4 border-t border-line px-5 py-4 first:border-t-0">
      <div className="min-w-0 flex-[999_1_20rem] space-y-1">
        <p className="text-sm">
          <strong>{p.reportedBy}</strong> reported <strong>{kindLabel[p.kind]}</strong> on{' '}
          {p.itemType === 'movie' ? (
            <Link to="/movies/$movieId" params={{ movieId: String(p.itemId) }} className="italic hover:underline">
              {p.title}
            </Link>
          ) : p.showId ? (
            <Link
              to="/shows/$showId/seasons/$number/episodes/$episodeId"
              params={{ showId: String(p.showId), number: String(p.season ?? 1), episodeId: String(p.itemId) }}
              className="italic hover:underline"
            >
              {p.title}
            </Link>
          ) : (
            <em>{p.title}</em>
          )}{' '}
          · {relativeTime(p.createdAt)}
        </p>
        {p.note && <p className="text-sm text-neutral-300">&ldquo;{p.note}&rdquo;</p>}
        {p.details && <p className="text-xs text-neutral-400">{p.details}</p>}
      </div>
      <button type="button" className={secondaryButton} disabled={resolve.isPending} onClick={() => resolve.mutate(p.id)}>
        Mark resolved
      </button>
    </div>
  )
}
