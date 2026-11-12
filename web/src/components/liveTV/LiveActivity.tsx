import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { liveTVActivityQuery } from '../../api/queries'
import { stopLiveAsAdmin } from '../../api/liveTV'
import { formatBytes } from '../../lib/format'
import { LiveBadge } from './LiveBadge'

/** Live TV viewers; canStop (an admin on an admin network) adds Stop. */
export function LiveActivity({ canStop }: { canStop: boolean }) {
  const activity = useQuery(liveTVActivityQuery)
  const queryClient = useQueryClient()
  const [stopping, setStopping] = useState('')
  const [error, setError] = useState('')
  const [confirm, setConfirm] = useState('')
  if (activity.isPending || !activity.data?.items.length) return activity.isError ? <p role="alert" className="text-warn">Live TV activity: {activity.error.message}</p> : null
  async function stop(id: string) {
    setStopping(id); setError('')
    try { await stopLiveAsAdmin(id); setConfirm(''); await queryClient.invalidateQueries({ queryKey: liveTVActivityQuery.queryKey }) }
    catch (error) { setError(error instanceof Error ? error.message : 'The play could not be stopped.') }
    finally { setStopping('') }
  }
  return <section className="space-y-3" aria-label="Live TV viewers">
    <h2 className="text-lg font-semibold">Live TV <span className="ml-2 text-sm font-normal text-neutral-400">{activity.data.items.length} watching</span></h2>
    {error && <p role="alert" className="text-warn">{error}</p>}
    <div className="grid gap-4 xl:grid-cols-2">{activity.data.items.map((session) => <article key={session.id} className="rounded-xl border border-line bg-surface p-5">
      <div className="flex items-start justify-between gap-3"><div><LiveBadge /><h3 className="mt-2 font-semibold"><span className="mr-2 tabular-nums text-accent">{session.channel.number}</span>{session.channel.name}</h3><p className="mt-1 text-sm text-neutral-300">{session.userName} · {session.clientName || 'Player'}</p></div>{canStop && <button type="button" onClick={() => setConfirm(confirm === session.id ? '' : session.id)} className="min-h-11 shrink-0 rounded-md border border-line-2 px-3 text-sm">Stop…</button>}</div>
      <p className="mt-3 text-xs text-neutral-400">{session.paused ? 'Paused' : session.state === 'starting' ? 'Tuning' : 'Watching live'} · {session.automatic ? `Auto · ${session.actualQuality === 'original' ? 'Original' : session.actualQuality ? `${session.actualQuality}p` : 'Starting'}` : `Up to ${session.quality}p`}{session.mode ? ` · ${session.mode === 'original' ? 'Direct stream' : session.mode === 'remux' ? 'Remux' : session.mode === 'audio' ? 'Audio conversion' : 'Video conversion'}` : ''} · {formatBytes(session.bufferBytes)} buffered</p>
      {confirm === session.id && <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-3"><p className="flex-1 text-sm">Stop this play and release its tuner?</p><button type="button" disabled={stopping === session.id} onClick={() => void stop(session.id)} className="min-h-11 rounded-md btn-primary px-4 text-sm disabled:opacity-50">{stopping === session.id ? 'Stopping…' : 'Stop this play'}</button><button type="button" onClick={() => setConfirm('')} className="min-h-11 px-2 text-sm text-neutral-300">Cancel</button></div>}
    </article>)}</div>
  </section>
}
