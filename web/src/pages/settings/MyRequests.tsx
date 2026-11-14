import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { myRequestsQuery, requestOptionsQuery, useCancelRequest, type MediaRequest } from '../../api/requests'
import { useAuth } from '../../authContext'
import { Poster } from '../../components/Poster'
import { secondaryButton } from '../../components/SettingsPanel'
import { Button, Card, HubPage, ItemRow, LoadingRows, Message } from '../../components/settings/Frame'
import { ConfirmDialog } from '../../components/settings/Dialogs'
import { useGo } from '../../components/settings/hooks'
import { relativeTime } from '../../lib/format'
import { canCancel, requestStateLabel, toneDot, toneText, whatLabel } from '../../lib/requests'
import { settingsCrumbs, settingsHref } from '../../lib/settingsRoutes'
import { cancelError, cancelLine, requestTitle, requestsOff, requestsSummary } from '../../lib/youSettings'
import { without } from './you/hooks'

/**
 * Settings › You › Your requests (PLAN-ARR.md §7.1, §7.3,
 * PLAN-ADMIN-SETTINGS.md §5.10): what the signed-in person asked for and
 * where each stands, in Seerr when it carries requests out. Cancel asks
 * first. Opened by its address when requests are off, it says why and who
 * can change it (loose end 10).
 */
export function MyRequestsSettings() {
  const auth = useAuth()
  const qc = useQueryClient()
  const go = useGo()
  const options = useQuery(requestOptionsQuery)
  const off = options.data ? requestsOff(options.data, auth.user?.isAdmin ?? false) : null
  const list = useQuery({ ...myRequestsQuery, enabled: !!options.data && !off })
  const cancel = useCancelRequest()
  const backend = options.data?.backend
  const [asking, setAsking] = useState<MediaRequest | null>(null)
  const [failed, setFailed] = useState<Record<number, unknown>>({})
  const [gone, setGone] = useState<{ id: number; title: string; text: string }[]>([])
  const items = list.data?.items
  const crumbs = settingsCrumbs('requests')
  const lede = ['Movies and seasons you asked for, newest first.', items ? requestsSummary(items) : ''].filter(Boolean).join(' ')

  if (off) {
    return (
      <HubPage crumbs={crumbs} title="Your requests">
        <Card padded>
          <h3 className="font-semibold">{off.title}</h3>
          <p className="mt-1 text-sm text-neutral-400">{off.body}</p>
          {off.setUp && <div className="mt-3"><Button variant="primary" onClick={() => go(settingsHref('integrations'))}>Set up requests</Button></div>}
        </Card>
      </HubPage>
    )
  }

  const confirm = (r: MediaRequest) => {
    setFailed((f) => without(f, r.id))
    cancel.mutate(r.id, {
      onSuccess: () => setAsking(null),
      onError: (e) => {
        setAsking(null)
        void qc.invalidateQueries({ queryKey: ['requests'] })
        const result = cancelError(e, backend)
        if (result.gone) setGone((g) => [{ id: r.id, title: requestTitle(r), text: result.text }, ...g.filter((x) => x.id !== r.id)])
        else setFailed((f) => ({ ...f, [r.id]: e }))
      },
    })
  }
  const ghosts = gone.filter((g) => !items?.some((r) => r.id === g.id))

  return (
    <HubPage
      crumbs={crumbs}
      title="Your requests"
      lede={lede}
      banner={backend === 'seerr' && <Message title="Requests go to Seerr">Approval, limits and notifications happen there.</Message>}
    >
      {items ? (
        items.length === 0 && ghosts.length === 0 ? (
          <Card padded>
            <h3 className="font-semibold">No requests yet</h3>
            <p className="mt-1 text-sm text-neutral-400">Search for a movie or show that isn’t here, or open a show with missing seasons, and ask for it.</p>
            <div className="mt-3"><Button variant="primary" onClick={() => go('/search')}>Search</Button></div>
          </Card>
        ) : (
          <Card>
            {ghosts.map((g) => (
              <ItemRow key={`gone-${g.id}`} title={g.title} detail={<span role="status">{g.text}</span>} />
            ))}
            {items.map((r) => (
              <MyRequestRow key={r.id} r={r} error={r.id in failed ? cancelError(failed[r.id], backend, r.status).text : undefined}
                onCancel={canCancel(r, backend) ? () => setAsking(r) : undefined} />
            ))}
          </Card>
        )
      ) : list.isError ? (
        <Message tone="err" title="Couldn’t load your requests" actions={<Button small onClick={() => void list.refetch()}>Try again</Button>}>
          The server didn’t answer. Your requests are as they were.
        </Message>
      ) : options.isError ? (
        <Message tone="err" title="Couldn’t load your requests" actions={<Button small onClick={() => void options.refetch()}>Try again</Button>}>
          The server didn’t answer. Your requests are as they were.
        </Message>
      ) : <LoadingRows rows={3} />}
      {items && items.length > 0 && <p className="text-sm text-neutral-400">To ask for something, search for it, or open a show with missing seasons.</p>}
      {asking && (
        <ConfirmDialog
          title={`Cancel your request for ${requestTitle(asking)}?`}
          lede={cancelLine(backend)}
          confirmLabel="Cancel request"
          cancelLabel="Keep request"
          busy={cancel.isPending}
          onClose={() => setAsking(null)}
          onConfirm={() => confirm(asking)}
        />
      )}
    </HubPage>
  )
}

/** A request with its state in words, progress while it downloads, and why a cancel failed. */
function MyRequestRow({ r, error, onCancel }: { r: MediaRequest; error?: string; onCancel?: () => void }) {
  const st = requestStateLabel(r)
  const title = requestTitle(r)
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
      <div className="w-12 shrink-0">
        <Poster path={r.posterPath} alt={r.title} size="w92" />
      </div>
      <div className="min-w-0 flex-[999_1_14rem]">
        <h4 className="font-medium break-words">
          {r.movieId ? (
            <Link to="/movies/$movieId" params={{ movieId: String(r.movieId) }} className="hover:underline">{title}</Link>
          ) : r.showId ? (
            <Link to="/shows/$showId" params={{ showId: String(r.showId) }} className="hover:underline">{title}</Link>
          ) : title}
        </h4>
        <div className="text-sm text-neutral-400">{whatLabel(r)} · {relativeTime(r.createdAt)}</div>
        <span className={`mt-1 inline-flex items-center gap-2 text-[13px] font-semibold ${toneText[st.tone]}`}>
          <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${toneDot[st.tone]}`} />
          {st.label}
        </span>
        {r.download?.state === 'downloading' && (
          <span aria-hidden="true" className="mt-1.5 block h-1 w-48 max-w-full rounded-full bg-line-2">
            <span className="block h-full rounded-full bg-accent" style={{ width: `${Math.round(r.download.progress * 100)}%` }} />
          </span>
        )}
        {error && <p role="alert" className="mt-1 text-sm text-red-300">{error}</p>}
      </div>
      {onCancel && <Button small onClick={onCancel}>Cancel request…</Button>}
    </div>
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
