import { useEffect, useState } from 'react'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { plexHistoryPagesQuery, plexHistoryQuery, resetPlexHistoryPages, useClearPlexHistory, useDeletePlexHistory } from '../../api/plex'
import { usersQuery } from '../../api/queries'
import { useAuth } from '../../authContext'
import { PlexConfirmation } from '../../components/PlexConfirmation'
import { secondaryButton, textInput } from '../../components/SettingsPanel'
import { plexExampleTitle, plexReason } from '../../lib/plex'
import { plexHistoryCursor, plexHistoryRows, retainPlexHistoryHead } from '../../lib/plexHistory'
import { authScope } from '../../lib/authScope'
import { adminAwayWording } from '../../lib/adminAway'
import { useSettingsAccess } from '../settings/shared'
import { when } from './shared'

/** Imported events never share playback rows, invented durations or WatchMyMedia totals. */
export function PlexHistory() {
  const auth = useAuth()
  const { admin, adminLimit } = useSettingsAccess()
  const users = useQuery({ ...usersQuery, enabled: admin })
  const [selectedUser, setSelectedUser] = useState<number | undefined>()
  // A role/network change must not leave another person's selection active.
  const userId = admin ? selectedUser : undefined
  const userName = userId ? (users.data?.items.find((user) => user.id === userId)?.name ?? 'this person') : (auth.user?.name ?? 'you')
  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-xl font-semibold">Earlier viewings from Plex</h2>
        <p className="mt-1 max-w-2xl text-sm text-neutral-400">
          Viewings Plex still has, brought across for your account. They are separate from plays in WatchMyMedia and do not change its playback totals. Some
          dates or viewing details may be missing from Plex.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        {admin && (
          <label className="flex flex-wrap items-center gap-2 text-sm">
            Person
            <select value={userId ?? ''} onChange={(e) => setSelectedUser(e.target.value ? Number(e.target.value) : undefined)} className={textInput}>
              <option value="">Your history</option>
              {users.data?.items
                .filter((user) => user.id !== auth.user?.id)
                .map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.name}
                  </option>
                ))}
            </select>
          </label>
        )}
        {admin && (
          <Link to="/settings/plex" className="text-sm text-neutral-200 underline">
            Manage Plex imports
          </Link>
        )}
      </div>
      {adminLimit && <p className="text-sm text-neutral-400">{adminAwayWording(adminLimit).long}</p>}
      <PlexHistoryList key={`${auth.user?.id}:${userId ?? 'me'}`} userId={userId} userName={userName} />
    </div>
  )
}

function PlexHistoryList({ userId, userName }: { userId?: number; userName: string }) {
  const scope = authScope()
  const history = useQuery(plexHistoryQuery(userId))
  const retainedOptions = plexHistoryPagesQuery(userId, history.data)
  const retained = useInfiniteQuery(retainedOptions)
  const qc = useQueryClient()
  const remove = useDeletePlexHistory(userId)
  const clear = useClearPlexHistory(userId)
  const [deleting, setDeleting] = useState<{ id: number; title: string } | 'all' | null>(null)
  const [notice, setNotice] = useState('')
  useEffect(() => {
    // A complete head is authoritative for the entire list, including a clear
    // from another device. Do not let its old snapshots return on a later import.
    if (history.data && history.data.next === undefined && (retained.data?.pages.length || retained.isFetching)) {
      void resetPlexHistoryPages(qc, userId, scope)
    }
  }, [history.data, retained.data, retained.isFetching, qc, userId, scope])
  const rows = plexHistoryRows(history.data, retained.data)
  const earlier = async () => {
    if (!retained.data?.pages.length && history.data) qc.setQueryData(retainedOptions.queryKey, retainPlexHistoryHead(history.data))
    await retained.fetchNextPage()
  }
  const error = history.error ?? retained.error
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="text-sm text-neutral-400">
          <p>For {userName}. Viewing dates below are the dates Plex reported.</p>
          <p>New viewings appear automatically. Refresh to check earlier pages.</p>
        </div>
        <button type="button" className={secondaryButton} disabled={history.isFetching || retained.isFetching} onClick={() => {
          void resetPlexHistoryPages(qc, userId, scope).then(() => history.refetch())
        }}>
          Refresh viewings
        </button>
        <button type="button" className={secondaryButton} disabled={!rows.length || history.isFetching || retained.isFetching} onClick={() => setDeleting('all')}>
          Delete all imported history
        </button>
      </div>
      {notice && (
        <p role="status" className="rounded-lg border border-line-2 p-3 text-sm text-neutral-200">
          {notice}
        </p>
      )}
      {history.isPending && (
        <p role="status" className="text-neutral-400">
          Reading imported viewings…
        </p>
      )}
      {error && (
        <div role="alert" className="rounded-lg border border-orange-900 bg-orange-950/25 p-4 text-sm text-orange-200">
          <p>
            {error.message === 'history_not_found'
              ? plexReason(error.message)
              : 'Couldn’t read imported viewings. Check your connection and try again.'}
          </p>
          <button type="button" className={`${secondaryButton} mt-3`} onClick={() => { if (retained.isError) void earlier(); else void history.refetch() }}>
            Try again
          </button>
        </div>
      )}
      {!history.isPending && !history.isError && !rows.length && (
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="font-medium">No imported viewings yet</h3>
          <p className="mt-1 text-sm text-neutral-400">
            An admin can connect Plex under Settings › Plex and choose your account. If your watched choices came across but no viewings appear, Plex may not
            supply earlier history or history recording may be off here.
          </p>
          <p className="mt-3 text-sm text-neutral-400">Your watched choices and plays in WatchMyMedia stay as they are.</p>
        </div>
      )}
      {!!rows.length && (
        <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
          {rows.map((item) => (
            <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 p-4 sm:px-5">
              <div className="min-w-0 flex-1 basis-64">
                <strong className="break-words text-sm font-medium">{plexExampleTitle(item)}</strong>
                <p className="mt-1 text-xs text-neutral-400">{when(item.viewedAt)} · Plex</p>
                <p className="mt-0.5 text-xs text-neutral-500">Brought across {when(item.importedAt)}</p>
              </div>
              <button type="button" className={secondaryButton} onClick={() => setDeleting({ id: item.id, title: plexExampleTitle(item) })}>
                Delete
              </button>
            </li>
          ))}
        </ul>
      )}
      {plexHistoryCursor(history.data, retained.data) !== undefined && (
        <button type="button" className={secondaryButton} disabled={retained.isFetchingNextPage} onClick={() => void earlier()}>
          {retained.isFetchingNextPage ? 'Loading more…' : 'Show more viewings'}
        </button>
      )}
      {deleting && (
        <PlexConfirmation
          title={deleting === 'all' ? 'Delete all imported Plex history?' : 'Delete this imported viewing?'}
          description={
            deleting === 'all'
              ? `Delete earlier viewings brought across for ${userName} and prevent those older events returning on the next check. Watched choices, WatchMyMedia plays and Plex stay as they are. Later Plex viewings can still be imported.`
              : `Delete “${deleting.title}” from ${userName}’s imported history and prevent that viewing returning on the next check. Watched choices, WatchMyMedia plays and Plex stay as they are.`
          }
          confirm={deleting === 'all' ? 'Delete imported history' : 'Delete this viewing'}
          action={() => (deleting === 'all' ? clear.mutateAsync() : remove.mutateAsync(deleting.id))}
          onDone={() => {
            setDeleting(null)
            setNotice('Imported history deleted. Watched choices and WatchMyMedia plays were kept.')
          }}
          onClose={() => setDeleting(null)}
        />
      )}
    </>
  )
}
