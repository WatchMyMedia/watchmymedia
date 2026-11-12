import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { myRequestsQuery, requestOptionsQuery, useCancelRequest, type MediaRequest } from '../../api/requests'
import { Poster } from '../../components/Poster'
import { PageHeader, Panel, secondaryButton } from '../../components/SettingsPanel'
import { relativeTime } from '../../lib/format'
import { canCancel, requestStateLabel, toneDot, toneText, whatLabel } from '../../lib/requests'

/** Settings › Your requests: what the signed-in user asked for and where each stands, in Seerr when it carries requests out (PLAN-ARR.md §7.1, §7.3). */
export function MyRequestsSettings() {
  const list = useQuery(myRequestsQuery)
  const options = useQuery(requestOptionsQuery)
  return (
    <>
      <PageHeader title="Your requests">Movies and seasons you asked for, newest first. One that is waiting can be cancelled.</PageHeader>
      {options.data?.backend === 'seerr' && (
        <div className="mb-4">
          <Panel>
            <SeerrNote>Requests go to Seerr. Approval, quotas and notifications happen there.</SeerrNote>
          </Panel>
        </div>
      )}
      {list.isPending && <p className="text-neutral-400">Loading…</p>}
      {list.isError && <p className="text-red-400">{list.error.message}</p>}
      {list.data && (
        <Panel>
          {list.data.items.length === 0 && <p className="px-5 py-4 text-sm text-neutral-400">Nothing yet. Search for a title, or open a show with missing seasons, to ask for it.</p>}
          {list.data.items.map((r) => (
            <RequestRow key={r.id} r={r} />
          ))}
        </Panel>
      )}
    </>
  )
}

/** A line saying requests are Seerr's, with an info mark. */
export function SeerrNote({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-center gap-2.5 px-5 py-3.5 text-sm text-neutral-300">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-neutral-400">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v5M12 8h.01" />
      </svg>
      {children}
    </p>
  )
}

export function RequestRow({ r, children }: { r: MediaRequest; children?: React.ReactNode }) {
  const cancel = useCancelRequest()
  const options = useQuery(requestOptionsQuery)
  const st = requestStateLabel(r)
  const title = `${r.title}${r.year ? ` (${r.year})` : ''}`
  return (
    <div className="flex flex-wrap items-center gap-4 border-t border-line px-5 py-3 first:border-t-0">
      <div className="w-12 shrink-0">
        <Poster path={r.posterPath} alt={r.title} size="w92" />
      </div>
      <div className="min-w-0 flex-[999_1_16rem]">
        {r.movieId ? (
          <Link to="/movies/$movieId" params={{ movieId: String(r.movieId) }} className="font-medium hover:underline">
            {title}
          </Link>
        ) : r.showId ? (
          <Link to="/shows/$showId" params={{ showId: String(r.showId) }} className="font-medium hover:underline">
            {title}
          </Link>
        ) : (
          <span className="font-medium">{title}</span>
        )}
        <div className="text-sm text-neutral-400">
          {whatLabel(r)} · {r.mine ? '' : `${r.requestedBy} · `}
          {relativeTime(r.createdAt)}
        </div>
        <span className={`mt-1 inline-flex items-center gap-2 text-[13px] font-semibold ${toneText[st.tone]}`}>
          <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${toneDot[st.tone]}`} />
          {st.label}
        </span>
        {r.download?.state === 'downloading' && (
          <span aria-hidden="true" className="mt-1.5 block h-1 w-48 max-w-full rounded-full bg-line-2">
            <span className="block h-full rounded-full bg-accent" style={{ width: `${Math.round(r.download.progress * 100)}%` }} />
          </span>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        {children}
        {canCancel(r, options.data?.backend) && (
          <button type="button" className={secondaryButton} disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)}>
            Cancel request
          </button>
        )}
      </div>
      {cancel.isError && <p className="basis-full text-sm text-warn">{cancel.error.message}</p>}
    </div>
  )
}
